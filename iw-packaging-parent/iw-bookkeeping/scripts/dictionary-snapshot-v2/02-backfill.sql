-- Incremental migration; explicit execution authorization required. MySQL 8.0.21+, InnoDB, utf8mb4.
-- Select the single iw-core database explicitly. Stop old writers and scheduled jobs first.
SET NAMES utf8mb4;

-- Preconditions: 00 reviewed without blockers, 01 complete, all writers still stopped.
-- Baseline names can only be the surviving source names; overwritten past names cannot be recovered.
-- Reject partial success before COMMIT. Use a batch client that stops on any SQL error.
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_dict_v2_assert_backfill$$
CREATE PROCEDURE iw_dict_v2_assert_backfill()
BEGIN
 IF EXISTS(SELECT 1 FROM dict_v2_issues) THEN
  SELECT * FROM dict_v2_issues ORDER BY kind,entity_id,reason;
  ROLLBACK;
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: dictionary backfill has issues; all DML in this run rolled back';
 END IF;
END$$
DELIMITER ;
DROP TEMPORARY TABLE IF EXISTS dict_v2_issues;
CREATE TEMPORARY TABLE dict_v2_issues(kind VARCHAR(20),entity_id INT UNSIGNED,reason VARCHAR(255));
DROP TEMPORARY TABLE IF EXISTS dict_v2_categories;
CREATE TEMPORARY TABLE dict_v2_categories AS
 SELECT user_id,dict_code,COUNT(*) candidate_count,MIN(id) source_id,MIN(dict_name) source_name
 FROM base_dict WHERE dict_type=4002 AND scope_type=0 AND family_group_id=0 GROUP BY user_id,dict_code;
-- MIN values are ONLY consumed when candidate_count=1; no ambiguous source is chosen.
START TRANSACTION;
INSERT INTO dict_v2_issues SELECT 'record',id,'invalid bill category' FROM bookkeeping_records WHERE dictionary_snapshot_status=0 AND record_category NOT IN (1,2);
INSERT INTO dict_v2_issues SELECT 'action',id,'invalid quick action category' FROM bookkeeping_actions WHERE record_category NOT IN (1,2);
INSERT INTO dict_v2_issues
 SELECT 'record',r.id,'category source missing/ambiguous' FROM bookkeeping_records r
 LEFT JOIN dict_v2_categories c ON c.user_id=r.user_id AND c.dict_code=r.record_type
 WHERE r.dictionary_snapshot_status=0 AND NOT
 (COALESCE(c.candidate_count,0)=1 OR (COALESCE(r.record_type,0)=0 AND COALESCE(c.candidate_count,0)=0));
INSERT INTO dict_v2_issues
 SELECT DISTINCT 'record',r.id,'tag source missing/wrong owner/wrong type' FROM bookkeeping_records r
 JOIN base_dict_business_relation rel ON rel.business_type=4001 AND rel.business_id=r.id
 LEFT JOIN base_dict d ON d.id=rel.dict_id WHERE r.dictionary_snapshot_status=0 AND
 (d.id IS NULL OR d.dict_type<>IF(r.record_category=1,4001,4011) OR d.scope_type<>0 OR d.family_group_id<>0 OR d.user_id<>r.user_id);
INSERT INTO dict_v2_issues
 SELECT DISTINCT 'record',r.id,'existing partial snapshot conflicts with migration baseline' FROM bookkeeping_records r
 JOIN bookkeeping_record_tag_snapshot s ON s.record_id=r.id
 LEFT JOIN base_dict_business_relation rel ON rel.business_type=4001 AND rel.business_id=r.id AND rel.dict_id=s.source_dict_id
 LEFT JOIN base_dict d ON d.id=s.source_dict_id WHERE r.dictionary_snapshot_status=0 AND
 (rel.id IS NULL OR d.id IS NULL OR s.dict_type<>d.dict_type OR BINARY s.tag_name<>BINARY d.dict_name);
INSERT INTO dict_v2_issues
 SELECT 'record',r.id,'existing partial category snapshot requires reviewed repair' FROM bookkeeping_records r
 WHERE r.dictionary_snapshot_status=0 AND (r.record_type_id IS NOT NULL OR r.record_type_name IS NOT NULL);
DROP TEMPORARY TABLE IF EXISTS dict_v2_records;
CREATE TEMPORARY TABLE dict_v2_records AS
 SELECT r.id,c.source_id,c.source_name FROM bookkeeping_records r
 LEFT JOIN dict_v2_categories c ON c.user_id=r.user_id AND c.dict_code=r.record_type AND COALESCE(c.candidate_count,0)=1
 WHERE r.dictionary_snapshot_status=0 AND NOT EXISTS(SELECT 1 FROM dict_v2_issues e WHERE e.kind='record' AND e.entity_id=r.id);
INSERT INTO bookkeeping_record_tag_snapshot(record_id,dict_type,source_dict_id,tag_name)
 SELECT DISTINCT r.id,d.dict_type,d.id,d.dict_name FROM dict_v2_records r
 JOIN base_dict_business_relation rel ON rel.business_type=4001 AND rel.business_id=r.id
 JOIN base_dict d ON d.id=rel.dict_id
 WHERE NOT EXISTS(SELECT 1 FROM bookkeeping_record_tag_snapshot s WHERE s.record_id=r.id AND s.dict_type=d.dict_type AND s.source_dict_id=d.id);
-- Mark complete only after the category AND every tag succeeded in this transaction.
UPDATE bookkeeping_records r JOIN dict_v2_records c ON c.id=r.id
 SET r.record_type_id=c.source_id,r.record_type_name=c.source_name,r.dictionary_snapshot_status=1
 WHERE r.dictionary_snapshot_status=0;

DROP TEMPORARY TABLE IF EXISTS dict_v2_budgets;
CREATE TEMPORARY TABLE dict_v2_budgets AS
 SELECT b.id,b.user_id,b.budget_type,b.budget_month,b.budget_year,b.deleted,c.candidate_count,
 CASE WHEN b.budget_type IN (1,2) THEN NULL ELSE c.source_id END source_id,
 CASE WHEN b.budget_type IN (1,2) THEN NULL ELSE c.source_name END source_name
 FROM bookkeeping_budget b LEFT JOIN dict_v2_categories c ON c.user_id=b.user_id AND c.dict_code=b.record_type AND COALESCE(c.candidate_count,0)=1;
INSERT INTO dict_v2_issues SELECT 'budget',id,'category source missing/ambiguous'
 FROM dict_v2_budgets WHERE budget_type IN (11,21) AND COALESCE(candidate_count,0)<>1;
DROP TEMPORARY TABLE IF EXISTS dict_v2_budget_duplicates;
CREATE TEMPORARY TABLE dict_v2_budget_duplicates AS
 SELECT user_id,budget_type,budget_month,budget_year,BINARY source_name name_key,COUNT(*) n
 FROM dict_v2_budgets WHERE deleted=0 GROUP BY user_id,budget_type,budget_month,budget_year,BINARY source_name HAVING COUNT(*)>1;
INSERT INTO dict_v2_issues SELECT 'budget',b.id,'duplicate same-name budget; do not combine amounts' FROM dict_v2_budgets b
 JOIN dict_v2_budget_duplicates x ON x.user_id=b.user_id AND x.budget_type=b.budget_type
 AND x.budget_month<=>b.budget_month AND x.budget_year<=>b.budget_year AND x.name_key<=>BINARY b.source_name WHERE b.deleted=0;
UPDATE bookkeeping_budget b JOIN dict_v2_budgets m ON m.id=b.id
 SET b.record_type_id=m.source_id,b.record_type_name=m.source_name
 WHERE b.record_type_id IS NULL AND b.record_type_name IS NULL AND b.budget_type IN (11,21)
 AND NOT EXISTS(SELECT 1 FROM dict_v2_issues e WHERE e.kind='budget' AND e.entity_id=b.id);

-- Quick actions are future configurations. Disabled/deleted references are reported, never substituted.
INSERT INTO dict_v2_issues SELECT 'action',a.id,'category source missing/ambiguous'
 FROM bookkeeping_actions a LEFT JOIN dict_v2_categories c ON c.user_id=a.user_id AND c.dict_code=a.record_type
 WHERE a.record_type_id IS NULL AND NOT(COALESCE(c.candidate_count,0)=1 OR (a.record_type=0 AND COALESCE(c.candidate_count,0)=0));
INSERT INTO dict_v2_issues SELECT 'action',id,'invalid tag ID list'
 FROM bookkeeping_actions WHERE NOT JSON_VALID(CONCAT('[',record_tags,']'));
INSERT INTO dict_v2_issues SELECT DISTINCT 'action',a.id,'tag source missing/wrong owner/wrong type'
 FROM bookkeeping_actions a JOIN JSON_TABLE(IF(JSON_VALID(CONCAT('[',a.record_tags,']')),CONCAT('[',a.record_tags,']'),'[]'),'$[*]'
 COLUMNS(ordinal_num FOR ORDINALITY, source_id BIGINT PATH '$' NULL ON ERROR)) j LEFT JOIN base_dict d ON d.id=j.source_id
 WHERE COALESCE(JSON_TYPE(JSON_EXTRACT(CONCAT('[',a.record_tags,']'),CONCAT('$[',j.ordinal_num-1,']'))),'NULL') NOT IN ('INTEGER','UNSIGNED INTEGER') OR j.source_id<=0 OR d.deleted<>0 OR d.dict_status<>1 OR d.id IS NULL OR d.user_id<>a.user_id OR d.scope_type<>0 OR d.family_group_id<>0 OR d.dict_type<>IF(a.record_category=1,4001,4011);
INSERT INTO dict_v2_issues SELECT 'action',a.id,'future category is disabled/deleted; reconfigure'
 FROM bookkeeping_actions a JOIN dict_v2_categories c ON c.user_id=a.user_id AND c.dict_code=a.record_type AND COALESCE(c.candidate_count,0)=1
 JOIN base_dict d ON d.id=c.source_id WHERE a.deleted=0 AND (d.deleted<>0 OR d.dict_status<>1);
UPDATE bookkeeping_actions a JOIN dict_v2_categories c ON c.user_id=a.user_id AND c.dict_code=a.record_type AND COALESCE(c.candidate_count,0)=1
 SET a.record_type_id=c.source_id WHERE a.record_type_id IS NULL
 AND NOT EXISTS(SELECT 1 FROM dict_v2_issues e WHERE e.kind='action' AND e.entity_id=a.id);

-- JSON_SET preserves unknown/amount/source/statistics fields. Known schema1 ONLY, never downgrade schema2+.
DROP TEMPORARY TABLE IF EXISTS dict_v2_templates;
CREATE TEMPORARY TABLE dict_v2_templates(kind VARCHAR(20),id INT UNSIGNED,user_id INT UNSIGNED,body JSON,
 record_category INT,old_code INT,source_id INT UNSIGNED,source_name VARCHAR(32) CHARACTER SET utf8mb4,
 upgraded JSON,PRIMARY KEY(kind,id));
INSERT INTO dict_v2_templates(kind,id,user_id,body,record_category,old_code)
 SELECT 'plan',id,user_id,bookkeeping_template,
 CASE WHEN JSON_TYPE(JSON_EXTRACT(bookkeeping_template,'$.recordCategory')) IN ('INTEGER','UNSIGNED INTEGER') THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(bookkeeping_template,'$.recordCategory')) AS UNSIGNED) ELSE NULL END,
 CASE WHEN JSON_TYPE(JSON_EXTRACT(bookkeeping_template,'$.recordType')) IN ('INTEGER','UNSIGNED INTEGER') THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(bookkeeping_template,'$.recordType')) AS UNSIGNED) ELSE NULL END
 FROM points_task_plan WHERE JSON_TYPE(JSON_EXTRACT(bookkeeping_template,'$.schemaVersion')) IN ('INTEGER','UNSIGNED INTEGER') AND JSON_EXTRACT(bookkeeping_template,'$.schemaVersion')=1;
INSERT INTO dict_v2_templates(kind,id,user_id,body,record_category,old_code)
 SELECT 'task',id,user_id,template_snapshot,
 CASE WHEN JSON_TYPE(JSON_EXTRACT(template_snapshot,'$.recordCategory')) IN ('INTEGER','UNSIGNED INTEGER') THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(template_snapshot,'$.recordCategory')) AS UNSIGNED) ELSE NULL END,
 CASE WHEN JSON_TYPE(JSON_EXTRACT(template_snapshot,'$.recordType')) IN ('INTEGER','UNSIGNED INTEGER') THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(template_snapshot,'$.recordType')) AS UNSIGNED) ELSE NULL END
 FROM points_task_bookkeeping WHERE JSON_TYPE(JSON_EXTRACT(template_snapshot,'$.schemaVersion')) IN ('INTEGER','UNSIGNED INTEGER') AND JSON_EXTRACT(template_snapshot,'$.schemaVersion')=1;
INSERT INTO dict_v2_issues SELECT 'plan',id,'unknown template schema; re-save template'
 FROM points_task_plan WHERE bookkeeping_template IS NOT NULL
 AND (COALESCE(JSON_TYPE(JSON_EXTRACT(bookkeeping_template,'$.schemaVersion')),'NULL') NOT IN ('INTEGER','UNSIGNED INTEGER') OR JSON_EXTRACT(bookkeeping_template,'$.schemaVersion') NOT IN (1,2));
INSERT INTO dict_v2_issues SELECT 'task',id,'unknown template schema; re-save template'
 FROM points_task_bookkeeping WHERE COALESCE(JSON_TYPE(JSON_EXTRACT(template_snapshot,'$.schemaVersion')),'NULL') NOT IN ('INTEGER','UNSIGNED INTEGER') OR JSON_EXTRACT(template_snapshot,'$.schemaVersion') NOT IN (1,2);
INSERT INTO dict_v2_issues SELECT t.kind,t.id,'template category missing/ambiguous/invalid'
 FROM dict_v2_templates t LEFT JOIN dict_v2_categories c ON c.user_id=t.user_id AND c.dict_code=t.old_code
 WHERE t.record_category NOT IN (1,2) OR t.record_category IS NULL
 OR COALESCE(JSON_TYPE(JSON_EXTRACT(t.body,'$.recordCategory')),'NULL') NOT IN ('INTEGER','UNSIGNED INTEGER')
 OR COALESCE(JSON_TYPE(JSON_EXTRACT(t.body,'$.recordType')),'NULL') NOT IN ('INTEGER','UNSIGNED INTEGER','NULL')
 OR NOT(COALESCE(c.candidate_count,0)=1 OR (COALESCE(t.old_code,0)=0 AND COALESCE(c.candidate_count,0)=0));
INSERT INTO dict_v2_issues SELECT t.kind,t.id,'future category is disabled/deleted; re-save template'
 FROM dict_v2_templates t JOIN dict_v2_categories c ON c.user_id=t.user_id AND c.dict_code=t.old_code AND COALESCE(c.candidate_count,0)=1
 JOIN base_dict d ON d.id=c.source_id WHERE d.deleted<>0 OR d.dict_status<>1;
INSERT INTO dict_v2_issues SELECT kind,id,'template tags must be array of source IDs' FROM dict_v2_templates
 WHERE COALESCE(JSON_TYPE(JSON_EXTRACT(body,'$.recordTags')),'NULL') NOT IN ('ARRAY','NULL');
INSERT INTO dict_v2_issues SELECT DISTINCT t.kind,t.id,'template tag source missing/wrong owner/wrong type'
 FROM dict_v2_templates t JOIN JSON_TABLE(IF(JSON_TYPE(JSON_EXTRACT(t.body,'$.recordTags'))='ARRAY',JSON_EXTRACT(t.body,'$.recordTags'),JSON_ARRAY()),'$[*]'
 COLUMNS(ordinal_num FOR ORDINALITY, source_id BIGINT PATH '$' NULL ON ERROR)) j LEFT JOIN base_dict d ON d.id=j.source_id
 WHERE COALESCE(JSON_TYPE(JSON_EXTRACT(t.body,CONCAT('$.recordTags[',j.ordinal_num-1,']'))),'NULL') NOT IN ('INTEGER','UNSIGNED INTEGER') OR j.source_id<=0 OR d.deleted<>0 OR d.dict_status<>1 OR d.id IS NULL OR d.user_id<>t.user_id OR d.scope_type<>0 OR d.family_group_id<>0 OR d.dict_type<>IF(t.record_category=1,4001,4011);
UPDATE dict_v2_templates t LEFT JOIN dict_v2_categories c ON c.user_id=t.user_id AND c.dict_code=t.old_code AND COALESCE(c.candidate_count,0)=1
 SET t.source_id=c.source_id,t.source_name=c.source_name;
UPDATE dict_v2_templates t SET upgraded=JSON_SET(JSON_REMOVE(t.body,'$.recordType'),
 '$.schemaVersion',2,'$.recordTypeId',t.source_id,'$.recordTypeName',t.source_name,
 '$.recordTagSnapshots',COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('id',d.id,'type',d.dict_type,'name',d.dict_name))
 FROM JSON_TABLE(IF(JSON_TYPE(JSON_EXTRACT(t.body,'$.recordTags'))='ARRAY',JSON_EXTRACT(t.body,'$.recordTags'),JSON_ARRAY()),'$[*]'
 COLUMNS(ordinal_num FOR ORDINALITY, source_id BIGINT PATH '$' NULL ON ERROR)) j JOIN base_dict d ON d.id=j.source_id),JSON_ARRAY()))
 WHERE NOT EXISTS(SELECT 1 FROM dict_v2_issues e WHERE e.kind=t.kind AND e.entity_id=t.id);
UPDATE points_task_plan p JOIN dict_v2_templates t ON t.kind='plan' AND t.id=p.id
 SET p.bookkeeping_template=t.upgraded WHERE t.upgraded IS NOT NULL AND JSON_EXTRACT(p.bookkeeping_template,'$.schemaVersion')=1;
UPDATE points_task_bookkeeping p JOIN dict_v2_templates t ON t.kind='task' AND t.id=p.id
 SET p.template_snapshot=t.upgraded WHERE t.upgraded IS NOT NULL AND JSON_EXTRACT(p.template_snapshot,'$.schemaVersion')=1;
CALL iw_dict_v2_assert_backfill();
COMMIT;
DROP PROCEDURE iw_dict_v2_assert_backfill;
SELECT * FROM dict_v2_issues ORDER BY kind,entity_id,reason;
SELECT (SELECT COUNT(*) FROM dict_v2_records) records_migrated_this_run,
 (SELECT COUNT(*) FROM bookkeeping_records WHERE dictionary_snapshot_status=1) complete_records,
 (SELECT COUNT(*) FROM bookkeeping_records WHERE dictionary_snapshot_status=0) unresolved_records,
 (SELECT COUNT(*) FROM dict_v2_templates WHERE upgraded IS NOT NULL) templates_upgraded_this_run,
 (SELECT COUNT(*) FROM dict_v2_issues) issue_count;
-- On any SQL error: ROLLBACK and stop. Do not run mysql --force. Retain full output.
-- An issue-free SQL exit is not sufficient: every issue result above MUST be empty.

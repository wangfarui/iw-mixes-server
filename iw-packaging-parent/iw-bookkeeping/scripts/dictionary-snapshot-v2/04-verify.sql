-- Incremental migration; explicit execution authorization required. MySQL 8.0.21+, InnoDB, utf8mb4.
-- Select the single iw-core database explicitly. Stop old writers and scheduled jobs first.
SET NAMES utf8mb4;
SET @dict_trim_pattern = CONCAT('^[', CONVERT(0x090a0b0c0d1c1d1e1f20c285c2a0e19a80e28080e28081e28082e28083e28084e28085e28086e28087e28088e28089e2808ae280a8e280a9e280afe2819fe38080 USING utf8mb4), ']+|[', CONVERT(0x090a0b0c0d1c1d1e1f20c285c2a0e19a80e28080e28081e28082e28083e28084e28085e28086e28087e28088e28089e2808ae280a8e280a9e280afe2819fe38080 USING utf8mb4), ']+$');

-- Read-only. Run before new application writes. Every exception result must be empty.
SELECT dictionary_snapshot_status,COUNT(*) rows_by_status FROM bookkeeping_records GROUP BY dictionary_snapshot_status;
SELECT id,'invalid bill category' issue FROM bookkeeping_records WHERE record_category NOT IN (1,2);
SELECT id,user_id,record_type,'unresolved bill snapshot' issue FROM bookkeeping_records WHERE dictionary_snapshot_status<>1;
SELECT id,'inconsistent category snapshot' issue FROM bookkeeping_records
 WHERE (record_type_id IS NULL)<>(record_type_name IS NULL);
SELECT s.id,s.record_id,'invalid tag snapshot/type' issue FROM bookkeeping_record_tag_snapshot s
 LEFT JOIN bookkeeping_records r ON r.id=s.record_id WHERE r.id IS NULL OR s.dict_type<>IF(r.record_category=1,4001,4011)
 OR CHAR_LENGTH(s.tag_name) NOT BETWEEN 1 AND 32 OR BINARY s.tag_name<>BINARY REGEXP_REPLACE(s.tag_name,@dict_trim_pattern,'');
SELECT scope_type,family_group_id,user_id,dict_type,HEX(dict_name) name_bytes,COUNT(*) duplicate_count FROM base_dict
 WHERE deleted=0 AND dict_type IN (4001,4002,4011)
 GROUP BY scope_type,family_group_id,user_id,dict_type,HEX(dict_name) HAVING COUNT(*)>1;
SELECT id,'noncanonical name/ownership' issue FROM base_dict WHERE dict_type IN (4001,4002,4011) AND
 (BINARY dict_name<>BINARY REGEXP_REPLACE(dict_name,@dict_trim_pattern,'') OR CHAR_LENGTH(dict_name) NOT BETWEEN 1 AND 32
 OR scope_type NOT IN (0,1) OR (scope_type=0 AND family_group_id<>0) OR (scope_type=1 AND (family_group_id=0 OR user_id<>0)));
SELECT previous_id,COUNT(*) successors FROM base_dict WHERE previous_id IS NOT NULL GROUP BY previous_id HAVING COUNT(*)>1;
SELECT n.id,'invalid rename lineage' issue FROM base_dict n LEFT JOIN base_dict o ON o.id=n.previous_id
 WHERE n.previous_id IS NOT NULL AND (o.id IS NULL OR o.deleted<>1 OR o.dict_type<>n.dict_type OR o.user_id<>n.user_id
 OR o.scope_type<>n.scope_type OR o.family_group_id<>n.family_group_id OR n.id<=o.id);
SELECT id,'missing category budget snapshot' issue FROM bookkeeping_budget
 WHERE budget_type IN (11,21) AND (record_type_id IS NULL OR record_type_name IS NULL);
SELECT id,'total budget must not have category' issue FROM bookkeeping_budget
 WHERE budget_type IN (1,2) AND (record_type_id IS NOT NULL OR record_type_name IS NOT NULL);
SELECT user_id,budget_type,budget_month,budget_year,HEX(record_type_name) name_bytes,COUNT(*) duplicate_count
 FROM bookkeeping_budget WHERE deleted=0 GROUP BY user_id,budget_type,budget_month,budget_year,HEX(record_type_name) HAVING COUNT(*)>1;
SELECT id,'missing quick action category reference' issue FROM bookkeeping_actions WHERE record_type<>0 AND record_type_id IS NULL;
SELECT DISTINCT a.id,'invalid quick action tag reference' issue
 FROM bookkeeping_actions a JOIN JSON_TABLE(IF(JSON_VALID(CONCAT('[',a.record_tags,']')),CONCAT('[',a.record_tags,']'),'[]'),'$[*]'
 COLUMNS(ordinal_num FOR ORDINALITY, source_id BIGINT PATH '$' NULL ON ERROR)) j LEFT JOIN base_dict d ON d.id=j.source_id
 WHERE COALESCE(JSON_TYPE(JSON_EXTRACT(CONCAT('[',a.record_tags,']'),CONCAT('$[',j.ordinal_num-1,']'))),'NULL') NOT IN ('INTEGER','UNSIGNED INTEGER') OR j.source_id<=0 OR d.deleted<>0 OR d.dict_status<>1 OR d.id IS NULL OR d.user_id<>a.user_id OR d.scope_type<>0 OR d.family_group_id<>0 OR d.dict_type<>IF(a.record_category=1,4001,4011);
SELECT a.id,'invalid quick action category' issue FROM bookkeeping_actions a LEFT JOIN base_dict d ON d.id=a.record_type_id
 WHERE a.deleted=0 AND a.record_type_id IS NOT NULL AND (d.id IS NULL OR d.user_id<>a.user_id OR d.scope_type<>0 OR d.family_group_id<>0 OR d.dict_type<>4002 OR d.deleted<>0 OR d.dict_status<>1);
SELECT id,'invalid quick action tag list' issue FROM bookkeeping_actions WHERE NOT JSON_VALID(CONCAT('[',record_tags,']'));
SELECT 'plan' kind,id,'template requires re-save/upgrade' issue FROM points_task_plan WHERE bookkeeping_template IS NOT NULL
 AND (COALESCE(JSON_TYPE(JSON_EXTRACT(bookkeeping_template,'$.schemaVersion')),'NULL') NOT IN ('INTEGER','UNSIGNED INTEGER') OR JSON_EXTRACT(bookkeeping_template,'$.schemaVersion')<>2);
SELECT 'task' kind,id,'template requires re-save/upgrade' issue FROM points_task_bookkeeping
 WHERE COALESCE(JSON_TYPE(JSON_EXTRACT(template_snapshot,'$.schemaVersion')),'NULL') NOT IN ('INTEGER','UNSIGNED INTEGER') OR JSON_EXTRACT(template_snapshot,'$.schemaVersion')<>2;
WITH templates AS (
 SELECT 'plan' kind,id,user_id,bookkeeping_template body FROM points_task_plan WHERE bookkeeping_template IS NOT NULL
 UNION ALL SELECT 'task',id,user_id,template_snapshot FROM points_task_bookkeeping
)
SELECT t.kind,t.id,'invalid schema2 category reference' issue FROM templates t
 LEFT JOIN base_dict d ON d.id=CASE WHEN JSON_TYPE(JSON_EXTRACT(t.body,'$.recordTypeId')) IN ('INTEGER','UNSIGNED INTEGER') THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(t.body,'$.recordTypeId')) AS UNSIGNED) ELSE NULL END
 WHERE JSON_EXTRACT(t.body,'$.schemaVersion')=2 AND
 (COALESCE(JSON_TYPE(JSON_EXTRACT(t.body,'$.recordCategory')),'NULL') NOT IN ('INTEGER','UNSIGNED INTEGER')
 OR JSON_EXTRACT(t.body,'$.recordCategory') NOT IN (1,2)
 OR COALESCE(JSON_TYPE(JSON_EXTRACT(t.body,'$.recordTags')),'NULL') NOT IN ('ARRAY','NULL')
 OR JSON_TYPE(JSON_EXTRACT(t.body,'$.recordTypeId')) NOT IN ('INTEGER','UNSIGNED INTEGER','NULL') OR
 (JSON_TYPE(JSON_EXTRACT(t.body,'$.recordTypeId')) IN ('INTEGER','UNSIGNED INTEGER') AND
 (d.id IS NULL OR d.dict_type<>4002 OR d.user_id<>t.user_id OR d.scope_type<>0 OR d.family_group_id<>0 OR d.deleted<>0 OR d.dict_status<>1)));
WITH templates AS (
 SELECT 'plan' kind,id,user_id,bookkeeping_template body FROM points_task_plan WHERE bookkeeping_template IS NOT NULL
 UNION ALL SELECT 'task',id,user_id,template_snapshot FROM points_task_bookkeeping
)
SELECT DISTINCT t.kind,t.id,'invalid schema2 tag reference' issue FROM templates t
 JOIN JSON_TABLE(IF(JSON_TYPE(JSON_EXTRACT(t.body,'$.recordTags'))='ARRAY',JSON_EXTRACT(t.body,'$.recordTags'),JSON_ARRAY()),'$[*]'
 COLUMNS(ordinal_num FOR ORDINALITY,source_id BIGINT PATH '$' NULL ON ERROR)) j LEFT JOIN base_dict d ON d.id=j.source_id
 WHERE JSON_EXTRACT(t.body,'$.schemaVersion')=2 AND
 (COALESCE(JSON_TYPE(JSON_EXTRACT(t.body,CONCAT('$.recordTags[',j.ordinal_num-1,']'))),'NULL') NOT IN ('INTEGER','UNSIGNED INTEGER')
 OR j.source_id<=0 OR d.id IS NULL OR d.dict_type<>IF(JSON_EXTRACT(t.body,'$.recordCategory')=1,4001,4011)
 OR d.user_id<>t.user_id OR d.scope_type<>0 OR d.family_group_id<>0 OR d.deleted<>0 OR d.dict_status<>1);
SELECT g.id,'active family without initialization marker' issue FROM auth_family_group g
 LEFT JOIN bookkeeping_family_dict_state s ON s.family_group_id=g.id WHERE g.deleted=0 AND g.status=1 AND s.family_group_id IS NULL;
-- Historical generic relations are evidence only, never deleted/rewritten by this migration.
SELECT b.metric,b.row_count expected_rows,v.row_count actual_rows,b.amount expected_amount,v.amount actual_amount,
 b.fingerprint expected_fingerprint,v.fingerprint actual_fingerprint,
 (b.row_count=v.row_count AND (b.amount<=>v.amount) AND b.fingerprint=v.fingerprint) unchanged
 FROM bookkeeping_dictionary_migration_baseline b JOIN (
 SELECT 'records' metric,COUNT(*) row_count,COALESCE(SUM(amount),0) amount,
 BIT_XOR(CRC32(CONCAT_WS('|',id,user_id,COALESCE(expense_user_id,'NULL'),record_category,amount,record_date,group_id,share_state,deleted))) fingerprint FROM bookkeeping_records
 UNION ALL SELECT 'wallet',COUNT(*),COALESCE(SUM(wallet_balance),0),BIT_XOR(CRC32(CONCAT_WS('|',id,user_id,wallet_balance,wallet_assets,deleted))) FROM bookkeeping_wallet
 UNION ALL SELECT 'relations',COUNT(*),NULL,BIT_XOR(CRC32(CONCAT_WS('|',id,business_type,business_id,dict_id))) FROM base_dict_business_relation
 UNION ALL SELECT 'other_dict',COUNT(*),NULL,BIT_XOR(CRC32(CONCAT_WS('|',id,user_id,dict_type,dict_code,dict_name,dict_status,sort,deleted))) FROM base_dict WHERE dict_type NOT IN (4001,4002,4011)
 ) v ON b.metric=v.metric ORDER BY b.metric;
SELECT table_name,column_name,column_type,is_nullable,column_default,extra,generation_expression
 FROM information_schema.columns WHERE table_schema=DATABASE() AND
 ((table_name='base_dict' AND column_name IN ('scope_type','family_group_id','previous_id','revision','active_name_key')) OR
 (table_name IN ('bookkeeping_records','bookkeeping_budget','bookkeeping_actions') AND column_name IN ('record_type_id','record_type_name','dictionary_snapshot_status')));
SELECT table_name,index_name,non_unique,GROUP_CONCAT(column_name ORDER BY seq_in_index) columns_in_order FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name IN ('base_dict','bookkeeping_record_tag_snapshot','bookkeeping_family_dict_state') GROUP BY table_name,index_name,non_unique;
-- CRC fingerprints are a change detector, not cryptographic proof; retain the database backup and 00 output.
-- Re-run 02 then 03 then 04 during stop-write window: migrated/initialized counts must be zero,
-- snapshots and schema2 JSON unchanged, no new exceptions, all baseline unchanged flags=1.

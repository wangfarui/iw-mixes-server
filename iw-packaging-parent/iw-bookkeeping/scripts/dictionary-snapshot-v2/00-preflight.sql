-- Incremental migration; explicit execution authorization required. MySQL 8.0.21+, InnoDB, utf8mb4.
-- Select the single iw-core database explicitly. Stop old writers and scheduled jobs first.
SET NAMES utf8mb4;
SET @dict_trim_pattern = CONCAT('^[', CONVERT(0x090a0b0c0d1c1d1e1f20c285c2a0e19a80e28080e28081e28082e28083e28084e28085e28086e28087e28088e28089e2808ae280a8e280a9e280afe2819fe38080 USING utf8mb4), ']+|[', CONVERT(0x090a0b0c0d1c1d1e1f20c285c2a0e19a80e28080e28081e28082e28083e28084e28085e28086e28087e28088e28089e2808ae280a8e280a9e280afe2819fe38080 USING utf8mb4), ']+$');

SELECT VERSION() AS mysql_version, DATABASE() AS selected_database, @@sql_mode AS sql_mode;
-- Every returned row below is a STOP condition. Existing migrations are separate operations.
SELECT required.table_name, required.column_name AS missing_prerequisite
FROM (
 SELECT 'bookkeeping_records' table_name, 'expense_user_id' column_name UNION ALL
 SELECT 'bookkeeping_records','group_id' UNION ALL SELECT 'bookkeeping_records','share_state' UNION ALL
 SELECT 'points_task_plan','bookkeeping_template' UNION ALL
 SELECT 'points_task_basics','completion_reward_points' UNION ALL
 SELECT 'points_task_basics','source_request_id' UNION ALL
 SELECT 'points_task_basics','scheduled_plan_date' UNION ALL
 SELECT 'auth_user','family_group_id' UNION ALL
 SELECT 'points_task_bookkeeping','template_snapshot' UNION ALL
 SELECT 'auth_family_member','role'
) required LEFT JOIN information_schema.columns c
 ON c.table_schema=DATABASE() AND c.table_name=required.table_name AND c.column_name=required.column_name
WHERE c.column_name IS NULL;
SELECT table_name, engine FROM information_schema.tables WHERE table_schema=DATABASE()
 AND table_name IN ('base_dict','bookkeeping_records','bookkeeping_budget','bookkeeping_actions',
 'auth_user','auth_family_group','auth_family_member','points_task_plan','points_task_bookkeeping') AND engine<>'InnoDB';
SELECT table_name,column_name,column_type,character_set_name,collation_name
 FROM information_schema.columns WHERE table_schema=DATABASE() AND
 ((table_name='base_dict' AND column_name IN ('dict_name','dict_code','user_id')) OR
 (table_name IN ('bookkeeping_records','bookkeeping_actions','bookkeeping_budget') AND column_name='record_type'));
SELECT table_name,column_name AS unsupported_charset FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='base_dict' AND column_name='dict_name' AND character_set_name<>'utf8mb4';
SELECT id,user_id,dict_type,dict_name,HEX(dict_name) AS name_bytes,
 REGEXP_REPLACE(dict_name,@dict_trim_pattern,SPACE(0)) AS proposed_normalized_name
 FROM base_dict WHERE dict_type IN (4001,4002,4011) AND
 (CHAR_LENGTH(REGEXP_REPLACE(dict_name,@dict_trim_pattern,SPACE(0))) NOT BETWEEN 1 AND 32
 OR BINARY dict_name <> BINARY REGEXP_REPLACE(dict_name,@dict_trim_pattern,SPACE(0)));
-- Active names, including disabled items, must be unique. On reruns include scope columns.
SET @has_scope=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='base_dict' AND column_name='scope_type');
SET @query=IF(@has_scope=0,
 'SELECT user_id,dict_type,HEX(REGEXP_REPLACE(dict_name,@dict_trim_pattern,SPACE(0))) name_key,COUNT(*) duplicate_count,GROUP_CONCAT(id ORDER BY id) ids FROM base_dict WHERE dict_type IN (4001,4002,4011) AND deleted=0 GROUP BY user_id,dict_type,HEX(REGEXP_REPLACE(dict_name,@dict_trim_pattern,SPACE(0))) HAVING COUNT(*)>1',
 'SELECT scope_type,family_group_id,user_id,dict_type,HEX(REGEXP_REPLACE(dict_name,@dict_trim_pattern,SPACE(0))) name_key,COUNT(*) duplicate_count,GROUP_CONCAT(id ORDER BY id) ids FROM base_dict WHERE dict_type IN (4001,4002,4011) AND deleted=0 GROUP BY scope_type,family_group_id,user_id,dict_type,HEX(REGEXP_REPLACE(dict_name,@dict_trim_pattern,SPACE(0))) HAVING COUNT(*)>1');
PREPARE dict_preflight FROM @query; EXECUTE dict_preflight; DEALLOCATE PREPARE dict_preflight;
-- All versions count: deleted sources remain possible evidence, never choose the first.
SET @personal=IF(@has_scope=0,'',' AND scope_type=0 AND family_group_id=0');
SET @query=CONCAT('SELECT user_id,dict_code,COUNT(*) candidate_count,GROUP_CONCAT(id ORDER BY id) ids FROM base_dict WHERE dict_type=4002',@personal,' GROUP BY user_id,dict_code HAVING COUNT(*)>1');
PREPARE dict_preflight FROM @query; EXECUTE dict_preflight; DEALLOCATE PREPARE dict_preflight;
SELECT id,record_category,'invalid bill category' issue FROM bookkeeping_records WHERE record_category NOT IN (1,2);
SELECT r.id,r.user_id,r.record_type,COUNT(d.id) source_count FROM bookkeeping_records r
 LEFT JOIN base_dict d ON d.user_id=r.user_id AND d.dict_type=4002 AND d.dict_code=r.record_type
 WHERE r.record_type<>0 GROUP BY r.id,r.user_id,r.record_type HAVING COUNT(d.id)<>1;
SELECT rel.id relation_id,r.id record_id,rel.dict_id,d.dict_type,r.record_category
 FROM base_dict_business_relation rel LEFT JOIN bookkeeping_records r ON r.id=rel.business_id
 LEFT JOIN base_dict d ON d.id=rel.dict_id WHERE rel.business_type=4001 AND
 (r.id IS NULL OR d.id IS NULL OR d.dict_type<>IF(r.record_category=1,4001,4011) OR d.user_id<>r.user_id);
SELECT b.user_id,b.budget_type,b.budget_month,b.budget_year,b.record_type,
 COUNT(*) duplicate_count,GROUP_CONCAT(b.id ORDER BY b.id) ids FROM bookkeeping_budget b WHERE b.deleted=0
 GROUP BY b.user_id,b.budget_type,b.budget_month,b.budget_year,b.record_type HAVING COUNT(*)>1;
SELECT 'plan' source,id,user_id,JSON_EXTRACT(bookkeeping_template,'$.schemaVersion') schema_version,
 JSON_EXTRACT(bookkeeping_template,'$.recordType') old_code FROM points_task_plan WHERE bookkeeping_template IS NOT NULL;
SELECT 'task' source,id,user_id,JSON_EXTRACT(template_snapshot,'$.schemaVersion') schema_version,
 JSON_EXTRACT(template_snapshot,'$.recordType') old_code FROM points_task_bookkeeping;
SELECT COUNT(*) record_count,COALESCE(SUM(amount),0) record_amount FROM bookkeeping_records;
SELECT COUNT(*) wallet_count,COALESCE(SUM(wallet_balance),0) wallet_balance,COALESCE(SUM(wallet_assets),0) wallet_assets FROM bookkeeping_wallet;
SELECT COUNT(*) historical_relation_count FROM base_dict_business_relation;
-- Save complete output. No data is changed. Do not continue on errors, missing prerequisites,
-- ambiguous codes, noncanonical names, invalid relations or duplicate budgets.

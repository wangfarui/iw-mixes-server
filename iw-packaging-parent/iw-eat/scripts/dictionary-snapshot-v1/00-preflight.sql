-- Read-only diagnostics for kitchen dictionary snapshot v1.
-- Review every result before 01/02. Do not run with --force.
SET SESSION TRANSACTION READ ONLY;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;

SELECT 'P10_SESSION' AS check_id,DATABASE() AS target_database,VERSION() AS mysql_version,NOW() AS query_time,@@transaction_read_only AS transaction_read_only;
SELECT 'P11_MYSQL8' AS check_id,IF(VERSION() LIKE '8.%','READY_MYSQL8','STOP_NOT_MYSQL8') AS result;

SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P20_FOOD_ROWS' check_id,COUNT(*) total_rows,
 COALESCE(SUM(deleted=0),0) current_rows,COALESCE(SUM(deleted=1),0) deleted_rows,COUNT(DISTINCT user_id) owner_count
FROM eat_fridge_food;

SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P30_DICTIONARY_SCOPES' check_id,dict_type,scope_type,
 (user_id=0 AND scope_type=0 AND family_group_id=0) is_template,
 COUNT(*) total_rows,SUM(deleted=0) current_rows,SUM(deleted=0 AND dict_status=1) enabled_rows,
 SUM(deleted=1) deleted_rows,COUNT(DISTINCT user_id) owner_count
FROM base_dict WHERE dict_type IN (3010,3011)
GROUP BY dict_type,scope_type,is_template ORDER BY dict_type,scope_type,is_template;

WITH types AS (SELECT 3010 dict_type UNION ALL SELECT 3011)
SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P31_TEMPLATE_TYPES' check_id,t.dict_type,
 COALESCE(SUM(d.deleted=0 AND d.dict_status=1),0) enabled_template_rows
FROM types t LEFT JOIN base_dict d ON d.user_id=0 AND d.scope_type=0 AND d.family_group_id=0 AND d.dict_type=t.dict_type
GROUP BY t.dict_type ORDER BY t.dict_type;

SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P40_DUPLICATE_CODES' check_id,COUNT(*) duplicate_groups,COALESCE(SUM(n),0) affected_dict_rows FROM (
 SELECT scope_type,family_group_id,user_id,dict_type,dict_code,COUNT(*) n FROM base_dict
 WHERE dict_type IN (3010,3011) AND deleted=0 AND dict_code IS NOT NULL
 GROUP BY scope_type,family_group_id,user_id,dict_type,dict_code HAVING COUNT(*)>1
) d;

SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P41_DUPLICATE_NAMES' check_id,COUNT(*) duplicate_groups,COALESCE(SUM(n),0) affected_dict_rows FROM (
 SELECT scope_type,family_group_id,user_id,dict_type,CONVERT(dict_name USING binary) name_key,COUNT(*) n
 FROM base_dict WHERE dict_type IN (3010,3011) AND deleted=0
 GROUP BY scope_type,family_group_id,user_id,dict_type,name_key HAVING COUNT(*)>1
) d;

SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P42_NAME_SHAPE' check_id,COUNT(*) total_rows,
 COALESCE(SUM(CHAR_LENGTH(dict_name) NOT BETWEEN 1 AND 32),0) invalid_length,
 COALESCE(SUM(CONVERT(dict_name USING binary)<>CONVERT(REGEXP_REPLACE(dict_name,'^[[:space:]]+|[[:space:]]+$','') USING binary)),0) edge_whitespace
FROM base_dict WHERE dict_type IN (3010,3011) AND deleted=0;

WITH dict_keys AS (
 SELECT user_id,dict_type,dict_code,COUNT(*) all_versions,SUM(deleted=0) current_rows,
 SUM(deleted=0 AND dict_status=1) enabled_rows,COUNT(DISTINCT CONVERT(dict_name USING binary)) historical_name_count,
 MIN(IF(deleted=0,id,NULL)) only_current_id
 FROM base_dict WHERE scope_type=0 AND family_group_id=0 AND dict_type IN (3010,3011)
 GROUP BY user_id,dict_type,dict_code
),
refs AS (
 SELECT id,user_id,deleted,3010 dict_type,category code_value FROM eat_fridge_food WHERE COALESCE(category,0)<>0
 UNION ALL SELECT id,user_id,deleted,3011,section FROM eat_fridge_food WHERE COALESCE(section,0)<>0
)
SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P50_FOOD_CODE_SOURCES' check_id,r.dict_type,r.deleted,COUNT(*) reference_rows,
 SUM(COALESCE(p.current_rows,0)=1) personal_current_unique,
 SUM(COALESCE(p.current_rows,0)>1) personal_current_ambiguous,
 SUM(COALESCE(p.current_rows,0)=0 AND p.all_versions=1) personal_deleted_unique,
 SUM(p.user_id IS NULL AND t.current_rows=1) template_only,
 SUM(p.user_id IS NULL AND COALESCE(t.current_rows,0)=0) no_personal_or_template,
 SUM(p.all_versions>1) multiple_personal_versions,
 SUM(p.historical_name_count>1) multiple_personal_names
FROM refs r LEFT JOIN dict_keys p ON p.user_id=r.user_id AND p.dict_type=r.dict_type AND p.dict_code=r.code_value
LEFT JOIN dict_keys t ON t.user_id=0 AND t.dict_type=r.dict_type AND t.dict_code=r.code_value
GROUP BY r.dict_type,r.deleted ORDER BY r.dict_type,r.deleted;

SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P60_EXISTING_KITCHEN_TABLES' check_id,table_name,engine
FROM information_schema.tables
WHERE table_schema=DATABASE() AND table_name IN ('kitchen_dictionary_scope_state','kitchen_dictionary_migration_state',
'kitchen_dictionary_migration_baseline','kitchen_dictionary_migration_resolution');

ROLLBACK;
-- Read-only diagnostic result sets. Review every check before 01/02.
-- MySQL 8 only. No data/DDL changes. On any error stop and ROLLBACK.
SET SESSION TRANSACTION READ ONLY;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
SELECT 'K10_SESSION' AS check_id,DATABASE() AS target_database,VERSION() AS mysql_version,NOW() AS query_time,@@transaction_read_only AS transaction_read_only;

SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'K10_BUSINESS_ROWS' check_id,COUNT(*) total_rows,
 COALESCE(SUM(deleted=0),0) current_rows,COALESCE(SUM(deleted=1),0) deleted_rows,COUNT(DISTINCT user_id) owner_count
FROM eat_fridge_food;

SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'K11_DICTIONARY_SCOPES' check_id,dict_type,scope_type,
 (user_id=0 AND scope_type=0 AND family_group_id=0) is_template,
 COUNT(*) total_rows,SUM(deleted=0) current_rows,SUM(deleted=0 AND dict_status=1) enabled_rows,SUM(deleted=1) deleted_rows
FROM base_dict WHERE dict_type IN (3010,3011)
GROUP BY dict_type,scope_type,is_template ORDER BY dict_type,scope_type,is_template;

SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'K13_DUPLICATE_CODES' check_id,COUNT(*) duplicate_groups,COALESCE(SUM(n),0) affected_dict_rows FROM (
 SELECT scope_type,family_group_id,user_id,dict_type,dict_code,COUNT(*) n FROM base_dict
 WHERE dict_type IN (3010,3011) AND deleted=0
 GROUP BY scope_type,family_group_id,user_id,dict_type,dict_code HAVING COUNT(*)>1
) d;
SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'K13_DUPLICATE_NAMES' check_id,COUNT(*) duplicate_groups,COALESCE(SUM(n),0) affected_dict_rows FROM (
 SELECT scope_type,family_group_id,user_id,dict_type,
 CONVERT(REGEXP_REPLACE(dict_name,'^[[:space:]]+|[[:space:]]+$','') USING binary) name_key,COUNT(*) n
 FROM base_dict WHERE dict_type IN (3010,3011) AND deleted=0
 GROUP BY scope_type,family_group_id,user_id,dict_type,name_key HAVING COUNT(*)>1
) d;

WITH refs AS (
 SELECT id row_id,user_id,deleted,3010 dict_type,category code_value FROM eat_fridge_food
 UNION ALL SELECT id,user_id,deleted,3011,section FROM eat_fridge_food
),
dict_keys AS (
 SELECT user_id,dict_type,dict_code,COUNT(*) all_versions,
 SUM(deleted=0) current_rows,SUM(deleted=0 AND dict_status=1) enabled_rows,
 COUNT(DISTINCT CONVERT(dict_name USING binary)) historical_name_count,
 MIN(IF(deleted=0,id,NULL)) only_current_id
 FROM base_dict WHERE scope_type=0 AND family_group_id=0 AND dict_type IN (3010,3011)
 GROUP BY user_id,dict_type,dict_code
)
SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'K15_SCALAR_CODE_SOURCES' check_id,r.dict_type,r.deleted,COUNT(*) reference_rows,
 SUM(r.code_value IS NULL OR r.code_value=0) unset_rows,
 SUM(r.code_value IS NOT NULL AND r.code_value<>0 AND COALESCE(p.current_rows,0)=1) personal_current_unique,
 SUM(r.code_value IS NOT NULL AND r.code_value<>0 AND COALESCE(p.current_rows,0)>1) personal_current_ambiguous,
 SUM(r.code_value IS NOT NULL AND r.code_value<>0 AND COALESCE(p.current_rows,0)=0) personal_current_missing
FROM refs r LEFT JOIN dict_keys p ON p.user_id=r.user_id AND p.dict_type=r.dict_type AND p.dict_code=r.code_value
GROUP BY r.dict_type,r.deleted ORDER BY r.dict_type,r.deleted;

SELECT 'K99_PREFLIGHT_COMPLETE' AS check_id, 'Review ambiguous/missing sources before schema/backfill' AS note;
COMMIT;

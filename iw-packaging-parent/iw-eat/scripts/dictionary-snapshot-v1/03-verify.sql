-- Run immediately after 02 and before resuming writers. Read-only assertions, no cleanup.
SET SESSION TRANSACTION READ ONLY;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;

SELECT 'FOOD_SNAPSHOT_READY' metric,(SELECT COUNT(*) FROM eat_fridge_food WHERE dictionary_snapshot_status<>1) failures
UNION ALL
SELECT 'FOOD_BASELINE_MEMBERSHIP' metric,(SELECT COUNT(*) FROM kitchen_dictionary_migration_baseline b LEFT JOIN eat_fridge_food r ON r.id=b.business_id WHERE b.business_type='FOOD' AND (r.id IS NULL OR r.user_id<>b.owner_id OR r.deleted<>b.deleted)) failures
UNION ALL
SELECT 'FOOD_CATEGORY_SCALARS' metric,(SELECT COUNT(*) FROM eat_fridge_food WHERE COALESCE(category,0)<>0 AND (category_source_id IS NULL OR category_name_snapshot IS NULL)) failures
UNION ALL
SELECT 'FOOD_SECTION_SCALARS' metric,(SELECT COUNT(*) FROM eat_fridge_food WHERE COALESCE(section,0)<>0 AND (section_source_id IS NULL OR section_name_snapshot IS NULL)) failures
UNION ALL
SELECT 'FOOD_EMPTY_CATEGORY_OK' metric,(SELECT COUNT(*) FROM eat_fridge_food WHERE COALESCE(category,0)=0 AND (category_source_id IS NOT NULL OR category_name_snapshot IS NOT NULL)) failures
UNION ALL
SELECT 'FOOD_EMPTY_SECTION_OK' metric,(SELECT COUNT(*) FROM eat_fridge_food WHERE COALESCE(section,0)=0 AND (section_source_id IS NOT NULL OR section_name_snapshot IS NOT NULL)) failures
UNION ALL
SELECT 'FOOD_RESOLUTION_AUDIT' metric,(SELECT COUNT(*) FROM kitchen_dictionary_migration_resolution r LEFT JOIN eat_fridge_food f ON f.id=r.business_id WHERE r.business_type='FOOD' AND ((r.dict_type=3010 AND (NOT(r.source_id<=>f.category_source_id) OR NOT(r.name_snapshot<=>f.category_name_snapshot))) OR (r.dict_type=3011 AND (NOT(r.source_id<=>f.section_source_id) OR NOT(r.name_snapshot<=>f.section_name_snapshot))))) failures
UNION ALL
SELECT 'ORIGINAL_DICTIONARIES' metric,(SELECT COUNT(*) FROM kitchen_dictionary_migration_baseline b LEFT JOIN base_dict d ON d.id=b.business_id WHERE b.business_type='DICT' AND (d.id IS NULL OR d.user_id<>b.owner_id OR d.deleted<>b.deleted OR NOT(JSON_EXTRACT(b.raw_snapshot,'$.dict_type')<=>d.dict_type) OR NOT(JSON_EXTRACT(b.raw_snapshot,'$.dict_code')<=>d.dict_code) OR BINARY JSON_UNQUOTE(JSON_EXTRACT(b.raw_snapshot,'$.dict_name'))<>BINARY d.dict_name OR NOT(JSON_EXTRACT(b.raw_snapshot,'$.parent_id')<=>d.parent_id))) failures
UNION ALL
SELECT 'FOOD_ORIGINAL_FIELDS' metric,(SELECT COUNT(*) FROM kitchen_dictionary_migration_baseline b JOIN eat_fridge_food r ON r.id=b.business_id WHERE b.business_type='FOOD' AND BINARY CAST(b.raw_snapshot AS CHAR)<>BINARY CAST(JSON_OBJECT('category',r.category,'section',r.section) AS CHAR)) failures
UNION ALL
SELECT 'GENERATED_KEYS' metric,(SELECT COUNT(*) FROM base_dict WHERE NOT(kitchen_scope_owner<=>CASE WHEN dict_type IN (3010,3011) AND deleted=0 THEN CASE WHEN scope_type=0 THEN user_id ELSE family_group_id END ELSE NULL END) OR NOT(kitchen_name_key<=>CASE WHEN dict_type IN (3010,3011) AND deleted=0 THEN CONVERT(dict_name USING binary) ELSE NULL END)) failures
UNION ALL
SELECT 'UNIQUE_NAME_DUPLICATES' metric,(SELECT COUNT(*) FROM (SELECT dict_type,scope_type,kitchen_scope_owner,kitchen_name_key,COUNT(*) n FROM base_dict WHERE dict_type IN (3010,3011) AND deleted=0 GROUP BY dict_type,scope_type,kitchen_scope_owner,kitchen_name_key HAVING COUNT(*)>1) d) failures
UNION ALL
SELECT 'READY_PHASE' metric,(SELECT 1-COUNT(*) FROM kitchen_dictionary_migration_state WHERE id=1 AND phase='READY') failures;

SELECT IF(SUM(failures)=0,'PASS','STOP') AS OVERALL,SUM(failures) failures FROM (
SELECT (SELECT COUNT(*) FROM eat_fridge_food WHERE dictionary_snapshot_status<>1) failures
UNION ALL SELECT (SELECT COUNT(*) FROM kitchen_dictionary_migration_baseline b LEFT JOIN eat_fridge_food r ON r.id=b.business_id WHERE b.business_type='FOOD' AND (r.id IS NULL OR r.user_id<>b.owner_id OR r.deleted<>b.deleted))
UNION ALL SELECT (SELECT COUNT(*) FROM eat_fridge_food WHERE COALESCE(category,0)<>0 AND (category_source_id IS NULL OR category_name_snapshot IS NULL))
UNION ALL SELECT (SELECT COUNT(*) FROM eat_fridge_food WHERE COALESCE(section,0)<>0 AND (section_source_id IS NULL OR section_name_snapshot IS NULL))
UNION ALL SELECT (SELECT COUNT(*) FROM eat_fridge_food WHERE COALESCE(category,0)=0 AND (category_source_id IS NOT NULL OR category_name_snapshot IS NOT NULL))
UNION ALL SELECT (SELECT COUNT(*) FROM eat_fridge_food WHERE COALESCE(section,0)=0 AND (section_source_id IS NOT NULL OR section_name_snapshot IS NOT NULL))
UNION ALL SELECT (SELECT COUNT(*) FROM kitchen_dictionary_migration_resolution r LEFT JOIN eat_fridge_food f ON f.id=r.business_id WHERE r.business_type='FOOD' AND ((r.dict_type=3010 AND (NOT(r.source_id<=>f.category_source_id) OR NOT(r.name_snapshot<=>f.category_name_snapshot))) OR (r.dict_type=3011 AND (NOT(r.source_id<=>f.section_source_id) OR NOT(r.name_snapshot<=>f.section_name_snapshot)))))
UNION ALL SELECT (SELECT COUNT(*) FROM kitchen_dictionary_migration_baseline b LEFT JOIN base_dict d ON d.id=b.business_id WHERE b.business_type='DICT' AND (d.id IS NULL OR d.user_id<>b.owner_id OR d.deleted<>b.deleted OR NOT(JSON_EXTRACT(b.raw_snapshot,'$.dict_type')<=>d.dict_type) OR NOT(JSON_EXTRACT(b.raw_snapshot,'$.dict_code')<=>d.dict_code) OR BINARY JSON_UNQUOTE(JSON_EXTRACT(b.raw_snapshot,'$.dict_name'))<>BINARY d.dict_name OR NOT(JSON_EXTRACT(b.raw_snapshot,'$.parent_id')<=>d.parent_id)))
UNION ALL SELECT (SELECT COUNT(*) FROM kitchen_dictionary_migration_baseline b JOIN eat_fridge_food r ON r.id=b.business_id WHERE b.business_type='FOOD' AND BINARY CAST(b.raw_snapshot AS CHAR)<>BINARY CAST(JSON_OBJECT('category',r.category,'section',r.section) AS CHAR))
UNION ALL SELECT (SELECT COUNT(*) FROM base_dict WHERE NOT(kitchen_scope_owner<=>CASE WHEN dict_type IN (3010,3011) AND deleted=0 THEN CASE WHEN scope_type=0 THEN user_id ELSE family_group_id END ELSE NULL END) OR NOT(kitchen_name_key<=>CASE WHEN dict_type IN (3010,3011) AND deleted=0 THEN CONVERT(dict_name USING binary) ELSE NULL END))
UNION ALL SELECT (SELECT COUNT(*) FROM (SELECT dict_type,scope_type,kitchen_scope_owner,kitchen_name_key,COUNT(*) n FROM base_dict WHERE dict_type IN (3010,3011) AND deleted=0 GROUP BY dict_type,scope_type,kitchen_scope_owner,kitchen_name_key HAVING COUNT(*)>1) d)
UNION ALL SELECT (SELECT 1-COUNT(*) FROM kitchen_dictionary_migration_state WHERE id=1 AND phase='READY')
) checks;

ROLLBACK;
-- Assertions after backfill. Do not set READY here; 02 already does after success.
SELECT 'V10_PHASE' check_id,phase FROM kitchen_dictionary_migration_state WHERE id=1;
SELECT IF((SELECT phase FROM kitchen_dictionary_migration_state WHERE id=1)='READY','PASS','FAIL') AS phase_ready;

SELECT 'V11_ROW_COUNTS' check_id,COUNT(*) foods,
 SUM(category IS NOT NULL AND category<>0 AND category_source_id IS NULL) missing_category_snapshot,
 SUM(section IS NOT NULL AND section<>0 AND section_source_id IS NULL) missing_section_snapshot,
 SUM(dictionary_snapshot_status<>1 AND ((category IS NOT NULL AND category<>0) OR (section IS NOT NULL AND section<>0))) unready_nonempty
FROM eat_fridge_food;

SELECT IF((SELECT SUM(category IS NOT NULL AND category<>0 AND category_source_id IS NULL)+SUM(section IS NOT NULL AND section<>0 AND section_source_id IS NULL) FROM eat_fridge_food)=0,'PASS','FAIL') AS snapshot_coverage;

SELECT 'V12_UNIQUE_KEY' check_id,
 (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='base_dict' AND index_name='uk_kitchen_dict_name' AND non_unique=0) kitchen_unique_present;

SELECT 'V13_SCOPE_STATE' check_id,scope_type,COUNT(*) owners FROM kitchen_dictionary_scope_state GROUP BY scope_type;

SELECT 'V99_VERIFY_COMPLETE' check_id,NOW() AS verified_at;

-- Local test migration only. Uses documented null-source baselines.
-- Run immediately after 02 and before resuming writers. Read-only assertions, no cleanup.
SET SESSION TRANSACTION READ ONLY;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
SELECT 'ITEM_SNAPSHOT_READY' metric,(SELECT COUNT(*) FROM wardrobe_item WHERE dictionary_snapshot_status<>1) failures
UNION ALL
SELECT 'ITEM_BASELINE_MEMBERSHIP' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b LEFT JOIN wardrobe_item r ON r.id=b.business_id WHERE b.business_type='ITEM' AND (r.id IS NULL OR r.user_id<>b.owner_id OR r.deleted<>b.deleted)) failures
UNION ALL
SELECT 'ITEM_SCALARS' metric,(SELECT COUNT(*) FROM wardrobe_item WHERE category_name_snapshot IS NULL OR (category_match_kind_snapshot IS NULL OR category_match_kind_snapshot NOT BETWEEN 1 AND 11) OR (item_style<>0 AND (item_style_name_snapshot IS NULL))) failures
UNION ALL
SELECT 'OUTFIT_SNAPSHOT_READY' metric,(SELECT COUNT(*) FROM wardrobe_outfit WHERE dictionary_snapshot_status<>1) failures
UNION ALL
SELECT 'OUTFIT_BASELINE_MEMBERSHIP' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b LEFT JOIN wardrobe_outfit r ON r.id=b.business_id WHERE b.business_type='OUTFIT' AND (r.id IS NULL OR r.user_id<>b.owner_id OR r.deleted<>b.deleted)) failures
UNION ALL
SELECT 'OUTFIT_ITEM_SNAPSHOT_READY' metric,(SELECT COUNT(*) FROM wardrobe_outfit_item WHERE dictionary_snapshot_status<>1) failures
UNION ALL
SELECT 'OUTFIT_ITEM_BASELINE_MEMBERSHIP' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b LEFT JOIN wardrobe_outfit_item r ON r.id=b.business_id WHERE b.business_type='OUTFIT_ITEM' AND (r.id IS NULL OR r.user_id<>b.owner_id OR r.deleted<>b.deleted)) failures
UNION ALL
SELECT 'OUTFIT_ITEM_SCALARS' metric,(SELECT COUNT(*) FROM wardrobe_outfit_item WHERE category_name_snapshot IS NULL OR (category_match_kind_snapshot IS NULL OR category_match_kind_snapshot NOT BETWEEN 1 AND 11) OR (item_style<>0 AND (item_style_name_snapshot IS NULL))) failures
UNION ALL
SELECT 'WEAR_RECORD_SNAPSHOT_READY' metric,(SELECT COUNT(*) FROM wardrobe_wear_record WHERE dictionary_snapshot_status<>1) failures
UNION ALL
SELECT 'WEAR_RECORD_BASELINE_MEMBERSHIP' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b LEFT JOIN wardrobe_wear_record r ON r.id=b.business_id WHERE b.business_type='WEAR_RECORD' AND (r.id IS NULL OR r.user_id<>b.owner_id OR r.deleted<>b.deleted)) failures
UNION ALL
SELECT 'WEAR_RECORD_ITEM_SNAPSHOT_READY' metric,(SELECT COUNT(*) FROM wardrobe_wear_record_item WHERE dictionary_snapshot_status<>1) failures
UNION ALL
SELECT 'WEAR_RECORD_ITEM_BASELINE_MEMBERSHIP' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b LEFT JOIN wardrobe_wear_record_item r ON r.id=b.business_id WHERE b.business_type='WEAR_RECORD_ITEM' AND (r.id IS NULL OR r.user_id<>b.owner_id OR r.deleted<>b.deleted)) failures
UNION ALL
SELECT 'WEAR_RECORD_ITEM_SCALARS' metric,(SELECT COUNT(*) FROM wardrobe_wear_record_item WHERE category_name_snapshot IS NULL OR (category_match_kind_snapshot IS NULL OR category_match_kind_snapshot NOT BETWEEN 1 AND 11) OR (item_style<>0 AND (item_style_name_snapshot IS NULL))) failures
UNION ALL
SELECT 'DICT_TREE' metric,(SELECT COUNT(*) FROM base_dict s LEFT JOIN base_dict p ON p.id=s.parent_id WHERE s.dict_type=5006 AND s.deleted=0 AND (p.id IS NULL OR p.deleted<>0 OR p.dict_type<>5002 OR s.wardrobe_category_lineage_id<>p.wardrobe_category_lineage_id OR s.wardrobe_category_lineage_id IS NULL OR s.user_id<>p.user_id OR s.scope_type<>p.scope_type OR s.family_group_id<>p.family_group_id)) failures
UNION ALL
SELECT 'DICT_CATEGORIES' metric,(SELECT COUNT(*) FROM base_dict WHERE dict_type=5002 AND (wardrobe_category_lineage_id IS NULL OR wardrobe_category_lineage_id=0 OR wardrobe_match_kind NOT BETWEEN 1 AND 11 OR wardrobe_match_kind IS NULL)) failures
UNION ALL
SELECT 'TAGS_AUDIT' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_tag_snapshot t LEFT JOIN wardrobe_dictionary_migration_resolution r ON r.business_type=t.business_type AND r.business_id=t.business_id AND r.dict_type=t.dict_type AND r.ordinal=t.ordinal WHERE r.business_id IS NULL OR NOT(r.source_id<=>t.source_id) OR BINARY r.name_snapshot<>BINARY t.name_snapshot) failures
UNION ALL
SELECT 'ORIGINAL_DICTIONARIES' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b LEFT JOIN base_dict d ON d.id=b.business_id WHERE b.business_type='DICT' AND (d.id IS NULL OR d.user_id<>b.owner_id OR d.deleted<>b.deleted OR NOT (JSON_EXTRACT(b.raw_snapshot,'$.dict_type')<=>d.dict_type) OR NOT (JSON_EXTRACT(b.raw_snapshot,'$.dict_code')<=>d.dict_code) OR BINARY JSON_UNQUOTE(JSON_EXTRACT(b.raw_snapshot,'$.dict_name'))<>BINARY d.dict_name OR NOT(JSON_EXTRACT(b.raw_snapshot,'$.parent_id')<=>d.parent_id))) failures
UNION ALL
SELECT 'ITEM_ORIGINAL_FIELDS' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b JOIN wardrobe_item r ON r.id=b.business_id WHERE b.business_type='ITEM' AND BINARY CAST(b.raw_snapshot AS CHAR)<>BINARY CAST(JSON_OBJECT('category',r.category,'item_style',r.item_style,'color_name',r.color_name,'scene_tags',r.scene_tags,'style_tags',r.style_tags) AS CHAR)) failures
UNION ALL
SELECT 'OUTFIT_ITEM_ORIGINAL_FIELDS' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b JOIN wardrobe_outfit_item r ON r.id=b.business_id WHERE b.business_type='OUTFIT_ITEM' AND BINARY CAST(b.raw_snapshot AS CHAR)<>BINARY CAST(JSON_OBJECT('category',r.category,'item_style',r.item_style) AS CHAR)) failures
UNION ALL
SELECT 'WEAR_RECORD_ITEM_ORIGINAL_FIELDS' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b JOIN wardrobe_wear_record_item r ON r.id=b.business_id WHERE b.business_type='WEAR_RECORD_ITEM' AND BINARY CAST(b.raw_snapshot AS CHAR)<>BINARY CAST(JSON_OBJECT('category',r.category,'item_style',r.item_style) AS CHAR)) failures
UNION ALL
SELECT 'OUTFIT_ORIGINAL_FIELDS' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b JOIN wardrobe_outfit r ON r.id=b.business_id WHERE b.business_type='OUTFIT' AND BINARY CAST(b.raw_snapshot AS CHAR)<>BINARY CAST(JSON_OBJECT('scene_tags',r.scene_tags,'style_tags',r.style_tags) AS CHAR)) failures
UNION ALL
SELECT 'WEAR_RECORD_ORIGINAL_FIELDS' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b JOIN wardrobe_wear_record r ON r.id=b.business_id WHERE b.business_type='WEAR_RECORD' AND BINARY CAST(b.raw_snapshot AS CHAR)<>BINARY CAST(JSON_OBJECT('scene_tags',r.scene_tags) AS CHAR)) failures
UNION ALL
SELECT 'MISSING_TAGS' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_resolution r LEFT JOIN wardrobe_dictionary_tag_snapshot t ON t.business_type=r.business_type AND t.business_id=r.business_id AND t.dict_type=r.dict_type AND t.ordinal=r.ordinal WHERE r.dict_type IN (5004,5005) AND t.id IS NULL) failures
UNION ALL
SELECT 'GENERATED_KEYS' metric,(SELECT COUNT(*) FROM base_dict WHERE NOT(wardrobe_parent_key<=>IF(dict_type=5006,COALESCE(wardrobe_category_lineage_id,0),0)) OR NOT(wardrobe_name_key<=>CASE WHEN dict_type BETWEEN 5002 AND 5006 AND deleted=0 AND (dict_type<>5006 OR wardrobe_category_lineage_id>0) THEN CONVERT(dict_name USING binary) ELSE NULL END)) failures
UNION ALL
SELECT 'LOCAL_NULL_SOURCE_AUDIT' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_resolution WHERE source_id IS NULL AND resolution NOT IN ('UNSET','BUILTIN_BASELINE','LEGACY_LITERAL')) failures
UNION ALL
SELECT 'READY_PHASE' metric,(SELECT 1-COUNT(*) FROM wardrobe_dictionary_migration_state WHERE id=1 AND phase='READY') failures;
SELECT IF(SUM(failures)=0,'PASS','STOP') AS OVERALL,SUM(failures) failures FROM (
SELECT 'ITEM_SNAPSHOT_READY' metric,(SELECT COUNT(*) FROM wardrobe_item WHERE dictionary_snapshot_status<>1) failures
UNION ALL
SELECT 'ITEM_BASELINE_MEMBERSHIP' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b LEFT JOIN wardrobe_item r ON r.id=b.business_id WHERE b.business_type='ITEM' AND (r.id IS NULL OR r.user_id<>b.owner_id OR r.deleted<>b.deleted)) failures
UNION ALL
SELECT 'ITEM_SCALARS' metric,(SELECT COUNT(*) FROM wardrobe_item WHERE category_name_snapshot IS NULL OR (category_match_kind_snapshot IS NULL OR category_match_kind_snapshot NOT BETWEEN 1 AND 11) OR (item_style<>0 AND (item_style_name_snapshot IS NULL))) failures
UNION ALL
SELECT 'OUTFIT_SNAPSHOT_READY' metric,(SELECT COUNT(*) FROM wardrobe_outfit WHERE dictionary_snapshot_status<>1) failures
UNION ALL
SELECT 'OUTFIT_BASELINE_MEMBERSHIP' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b LEFT JOIN wardrobe_outfit r ON r.id=b.business_id WHERE b.business_type='OUTFIT' AND (r.id IS NULL OR r.user_id<>b.owner_id OR r.deleted<>b.deleted)) failures
UNION ALL
SELECT 'OUTFIT_ITEM_SNAPSHOT_READY' metric,(SELECT COUNT(*) FROM wardrobe_outfit_item WHERE dictionary_snapshot_status<>1) failures
UNION ALL
SELECT 'OUTFIT_ITEM_BASELINE_MEMBERSHIP' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b LEFT JOIN wardrobe_outfit_item r ON r.id=b.business_id WHERE b.business_type='OUTFIT_ITEM' AND (r.id IS NULL OR r.user_id<>b.owner_id OR r.deleted<>b.deleted)) failures
UNION ALL
SELECT 'OUTFIT_ITEM_SCALARS' metric,(SELECT COUNT(*) FROM wardrobe_outfit_item WHERE category_name_snapshot IS NULL OR (category_match_kind_snapshot IS NULL OR category_match_kind_snapshot NOT BETWEEN 1 AND 11) OR (item_style<>0 AND (item_style_name_snapshot IS NULL))) failures
UNION ALL
SELECT 'WEAR_RECORD_SNAPSHOT_READY' metric,(SELECT COUNT(*) FROM wardrobe_wear_record WHERE dictionary_snapshot_status<>1) failures
UNION ALL
SELECT 'WEAR_RECORD_BASELINE_MEMBERSHIP' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b LEFT JOIN wardrobe_wear_record r ON r.id=b.business_id WHERE b.business_type='WEAR_RECORD' AND (r.id IS NULL OR r.user_id<>b.owner_id OR r.deleted<>b.deleted)) failures
UNION ALL
SELECT 'WEAR_RECORD_ITEM_SNAPSHOT_READY' metric,(SELECT COUNT(*) FROM wardrobe_wear_record_item WHERE dictionary_snapshot_status<>1) failures
UNION ALL
SELECT 'WEAR_RECORD_ITEM_BASELINE_MEMBERSHIP' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b LEFT JOIN wardrobe_wear_record_item r ON r.id=b.business_id WHERE b.business_type='WEAR_RECORD_ITEM' AND (r.id IS NULL OR r.user_id<>b.owner_id OR r.deleted<>b.deleted)) failures
UNION ALL
SELECT 'WEAR_RECORD_ITEM_SCALARS' metric,(SELECT COUNT(*) FROM wardrobe_wear_record_item WHERE category_name_snapshot IS NULL OR (category_match_kind_snapshot IS NULL OR category_match_kind_snapshot NOT BETWEEN 1 AND 11) OR (item_style<>0 AND (item_style_name_snapshot IS NULL))) failures
UNION ALL
SELECT 'DICT_TREE' metric,(SELECT COUNT(*) FROM base_dict s LEFT JOIN base_dict p ON p.id=s.parent_id WHERE s.dict_type=5006 AND s.deleted=0 AND (p.id IS NULL OR p.deleted<>0 OR p.dict_type<>5002 OR s.wardrobe_category_lineage_id<>p.wardrobe_category_lineage_id OR s.wardrobe_category_lineage_id IS NULL OR s.user_id<>p.user_id OR s.scope_type<>p.scope_type OR s.family_group_id<>p.family_group_id)) failures
UNION ALL
SELECT 'DICT_CATEGORIES' metric,(SELECT COUNT(*) FROM base_dict WHERE dict_type=5002 AND (wardrobe_category_lineage_id IS NULL OR wardrobe_category_lineage_id=0 OR wardrobe_match_kind NOT BETWEEN 1 AND 11 OR wardrobe_match_kind IS NULL)) failures
UNION ALL
SELECT 'TAGS_AUDIT' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_tag_snapshot t LEFT JOIN wardrobe_dictionary_migration_resolution r ON r.business_type=t.business_type AND r.business_id=t.business_id AND r.dict_type=t.dict_type AND r.ordinal=t.ordinal WHERE r.business_id IS NULL OR NOT(r.source_id<=>t.source_id) OR BINARY r.name_snapshot<>BINARY t.name_snapshot) failures
UNION ALL
SELECT 'ORIGINAL_DICTIONARIES' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b LEFT JOIN base_dict d ON d.id=b.business_id WHERE b.business_type='DICT' AND (d.id IS NULL OR d.user_id<>b.owner_id OR d.deleted<>b.deleted OR NOT (JSON_EXTRACT(b.raw_snapshot,'$.dict_type')<=>d.dict_type) OR NOT (JSON_EXTRACT(b.raw_snapshot,'$.dict_code')<=>d.dict_code) OR BINARY JSON_UNQUOTE(JSON_EXTRACT(b.raw_snapshot,'$.dict_name'))<>BINARY d.dict_name OR NOT(JSON_EXTRACT(b.raw_snapshot,'$.parent_id')<=>d.parent_id))) failures
UNION ALL
SELECT 'ITEM_ORIGINAL_FIELDS' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b JOIN wardrobe_item r ON r.id=b.business_id WHERE b.business_type='ITEM' AND BINARY CAST(b.raw_snapshot AS CHAR)<>BINARY CAST(JSON_OBJECT('category',r.category,'item_style',r.item_style,'color_name',r.color_name,'scene_tags',r.scene_tags,'style_tags',r.style_tags) AS CHAR)) failures
UNION ALL
SELECT 'OUTFIT_ITEM_ORIGINAL_FIELDS' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b JOIN wardrobe_outfit_item r ON r.id=b.business_id WHERE b.business_type='OUTFIT_ITEM' AND BINARY CAST(b.raw_snapshot AS CHAR)<>BINARY CAST(JSON_OBJECT('category',r.category,'item_style',r.item_style) AS CHAR)) failures
UNION ALL
SELECT 'WEAR_RECORD_ITEM_ORIGINAL_FIELDS' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b JOIN wardrobe_wear_record_item r ON r.id=b.business_id WHERE b.business_type='WEAR_RECORD_ITEM' AND BINARY CAST(b.raw_snapshot AS CHAR)<>BINARY CAST(JSON_OBJECT('category',r.category,'item_style',r.item_style) AS CHAR)) failures
UNION ALL
SELECT 'OUTFIT_ORIGINAL_FIELDS' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b JOIN wardrobe_outfit r ON r.id=b.business_id WHERE b.business_type='OUTFIT' AND BINARY CAST(b.raw_snapshot AS CHAR)<>BINARY CAST(JSON_OBJECT('scene_tags',r.scene_tags,'style_tags',r.style_tags) AS CHAR)) failures
UNION ALL
SELECT 'WEAR_RECORD_ORIGINAL_FIELDS' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_baseline b JOIN wardrobe_wear_record r ON r.id=b.business_id WHERE b.business_type='WEAR_RECORD' AND BINARY CAST(b.raw_snapshot AS CHAR)<>BINARY CAST(JSON_OBJECT('scene_tags',r.scene_tags) AS CHAR)) failures
UNION ALL
SELECT 'MISSING_TAGS' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_resolution r LEFT JOIN wardrobe_dictionary_tag_snapshot t ON t.business_type=r.business_type AND t.business_id=r.business_id AND t.dict_type=r.dict_type AND t.ordinal=r.ordinal WHERE r.dict_type IN (5004,5005) AND t.id IS NULL) failures
UNION ALL
SELECT 'GENERATED_KEYS' metric,(SELECT COUNT(*) FROM base_dict WHERE NOT(wardrobe_parent_key<=>IF(dict_type=5006,COALESCE(wardrobe_category_lineage_id,0),0)) OR NOT(wardrobe_name_key<=>CASE WHEN dict_type BETWEEN 5002 AND 5006 AND deleted=0 AND (dict_type<>5006 OR wardrobe_category_lineage_id>0) THEN CONVERT(dict_name USING binary) ELSE NULL END)) failures
UNION ALL
SELECT 'LOCAL_NULL_SOURCE_AUDIT' metric,(SELECT COUNT(*) FROM wardrobe_dictionary_migration_resolution WHERE source_id IS NULL AND resolution NOT IN ('UNSET','BUILTIN_BASELINE','LEGACY_LITERAL')) failures
UNION ALL
SELECT 'READY_PHASE' metric,(SELECT 1-COUNT(*) FROM wardrobe_dictionary_migration_state WHERE id=1 AND phase='READY') failures
) checks;
ROLLBACK;

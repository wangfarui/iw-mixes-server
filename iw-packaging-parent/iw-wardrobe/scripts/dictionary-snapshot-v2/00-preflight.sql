-- Read-only diagnostic result sets. Review every check before 01/02; 02 repeats fail-closed source guards.
-- MySQL 8 only. Run only after P01_REQUIRED_STRUCTURE is READY_MYSQL8.
-- Open a fresh read-only query connection on the confirmed application database.
-- No data/DDL changes. On any error stop and end this transaction with ROLLBACK.
SET SESSION TRANSACTION READ ONLY;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
SELECT 'P10_SESSION' AS check_id,DATABASE() AS target_database,VERSION() AS mysql_version,NOW() AS query_time,@@transaction_read_only AS transaction_read_only;

SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P10_BUSINESS_ROWS' check_id,'ITEM' kind,COUNT(*) total_rows,COALESCE(SUM(deleted=0),0) current_rows,COALESCE(SUM(deleted=1),0) deleted_rows,COUNT(DISTINCT user_id) owner_count FROM wardrobe_item
UNION ALL SELECT 'P10_BUSINESS_ROWS','OUTFIT',COUNT(*),COALESCE(SUM(deleted=0),0),COALESCE(SUM(deleted=1),0),COUNT(DISTINCT user_id) FROM wardrobe_outfit
UNION ALL SELECT 'P10_BUSINESS_ROWS','OUTFIT_ITEM',COUNT(*),COALESCE(SUM(deleted=0),0),COALESCE(SUM(deleted=1),0),COUNT(DISTINCT user_id) FROM wardrobe_outfit_item
UNION ALL SELECT 'P10_BUSINESS_ROWS','RECORD',COUNT(*),COALESCE(SUM(deleted=0),0),COALESCE(SUM(deleted=1),0),COUNT(DISTINCT user_id) FROM wardrobe_wear_record
UNION ALL SELECT 'P10_BUSINESS_ROWS','RECORD_ITEM',COUNT(*),COALESCE(SUM(deleted=0),0),COALESCE(SUM(deleted=1),0),COUNT(DISTINCT user_id) FROM wardrobe_wear_record_item;

SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P11_DICTIONARY_SCOPES' check_id,dict_type,scope_type,
 (user_id=0 AND scope_type=0 AND family_group_id=0) is_template,
 COUNT(*) total_rows,SUM(deleted=0) current_rows,SUM(deleted=0 AND dict_status=1) enabled_rows,SUM(deleted=1) deleted_rows,COUNT(DISTINCT user_id) owner_count
FROM base_dict WHERE dict_type IN (5002,5003,5004,5005,5006)
GROUP BY dict_type,scope_type,is_template ORDER BY dict_type,scope_type,is_template;

WITH owners AS (
 SELECT user_id FROM wardrobe_item UNION SELECT user_id FROM wardrobe_outfit
 UNION SELECT user_id FROM wardrobe_outfit_item UNION SELECT user_id FROM wardrobe_wear_record
 UNION SELECT user_id FROM wardrobe_wear_record_item
), types AS (
 SELECT 5002 dict_type UNION ALL SELECT 5003 UNION ALL SELECT 5004 UNION ALL SELECT 5005 UNION ALL SELECT 5006
), history AS (
 SELECT user_id,dict_type,COUNT(*) all_rows,SUM(deleted=0) current_rows FROM base_dict
 WHERE scope_type=0 AND family_group_id=0 AND dict_type IN (5002,5003,5004,5005,5006)
 GROUP BY user_id,dict_type
)
SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P12_OWNER_DICTIONARY_COVERAGE' check_id,t.dict_type,COUNT(*) business_owner_count,
 SUM(h.user_id IS NULL) owners_without_any_history,SUM(COALESCE(h.current_rows,0)=0) owners_without_current_rows
FROM owners o CROSS JOIN types t LEFT JOIN history h ON h.user_id=o.user_id AND h.dict_type=t.dict_type
GROUP BY t.dict_type ORDER BY t.dict_type;

SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P13_DUPLICATE_CODES' check_id,COUNT(*) duplicate_groups,COALESCE(SUM(n),0) affected_dict_rows FROM (
 SELECT scope_type,family_group_id,user_id,dict_type,dict_code,COUNT(*) n FROM base_dict
 WHERE dict_type IN (5002,5003,5004,5005,5006) AND deleted=0
 GROUP BY scope_type,family_group_id,user_id,dict_type,dict_code HAVING COUNT(*)>1
) d;
SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P13_DUPLICATE_NAMES' check_id,COUNT(*) duplicate_groups,COALESCE(SUM(n),0) affected_dict_rows FROM (
 SELECT scope_type,family_group_id,user_id,dict_type,IF(dict_type=5006,parent_id,0) parent_key,
 CONVERT(REGEXP_REPLACE(dict_name,'^[[:space:]]+|[[:space:]]+$','') USING binary) name_key,COUNT(*) n
 FROM base_dict WHERE dict_type IN (5002,5003,5004,5005,5006) AND deleted=0
 GROUP BY scope_type,family_group_id,user_id,dict_type,parent_key,name_key HAVING COUNT(*)>1
) d;
SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P13_NAME_SHAPE' check_id,COUNT(*) total_rows,
 COALESCE(SUM(CHAR_LENGTH(dict_name) NOT BETWEEN 1 AND 32),0) invalid_length,
 COALESCE(SUM(CONVERT(dict_name USING binary)<>CONVERT(REGEXP_REPLACE(dict_name,'^[[:space:]]+|[[:space:]]+$','') USING binary)),0) edge_whitespace
FROM base_dict WHERE dict_type IN (5002,5003,5004,5005,5006) AND deleted=0;

SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P14_STYLE_PARENTS' check_id,COUNT(*) current_styles,
 COALESCE(SUM(p.id IS NULL),0) missing_parent,
 COALESCE(SUM(p.id IS NOT NULL AND p.dict_type<>5002),0) wrong_parent_type,
 COALESCE(SUM(p.id IS NOT NULL AND (p.user_id<>c.user_id OR p.scope_type<>c.scope_type OR p.family_group_id<>c.family_group_id)),0) wrong_parent_scope,
 COALESCE(SUM(p.deleted=1),0) deleted_parent,COALESCE(SUM(p.dict_status=0),0) disabled_parent
FROM base_dict c LEFT JOIN base_dict p ON p.id=c.parent_id WHERE c.dict_type=5006 AND c.deleted=0;

WITH refs AS (
 SELECT 'ITEM' kind,id row_id,user_id,deleted,category,item_style FROM wardrobe_item
 UNION ALL SELECT 'OUTFIT_ITEM',id,user_id,deleted,category,item_style FROM wardrobe_outfit_item
 UNION ALL SELECT 'RECORD_ITEM',id,user_id,deleted,category,item_style FROM wardrobe_wear_record_item
),
dict_keys AS (
 SELECT user_id,dict_type,dict_code,COUNT(*) all_versions,
 SUM(deleted=0) current_rows,SUM(deleted=0 AND dict_status=1) enabled_rows,
 COUNT(DISTINCT CONVERT(dict_name USING binary)) historical_name_count,
 MIN(IF(deleted=0,id,NULL)) only_current_id
 FROM base_dict WHERE scope_type=0 AND family_group_id=0 AND dict_type IN (5002,5003,5004,5005,5006)
 GROUP BY user_id,dict_type,dict_code
),
scalar_refs AS (
 SELECT kind,row_id,user_id,deleted,5002 dict_type,category code_value FROM refs
 UNION ALL SELECT kind,row_id,user_id,deleted,5006,item_style FROM refs
)
SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P15_SCALAR_CODE_SOURCES' check_id,r.kind,r.dict_type,r.deleted,COUNT(*) reference_rows,
 SUM(r.code_value=0) unset_rows,
 SUM(r.code_value<>0 AND COALESCE(p.current_rows,0)=1) personal_current_unique,
 SUM(r.code_value<>0 AND COALESCE(p.current_rows,0)>1) personal_current_ambiguous,
 SUM(r.code_value<>0 AND COALESCE(p.current_rows,0)=0 AND p.all_versions=1) personal_deleted_unique,
 SUM(r.code_value<>0 AND p.user_id IS NULL AND t.current_rows=1) template_only,
 SUM(r.code_value<>0 AND p.user_id IS NULL AND COALESCE(t.current_rows,0)=0) no_personal_or_template,
 SUM(r.code_value<>0 AND p.all_versions>1) multiple_personal_versions,
 SUM(r.code_value<>0 AND p.historical_name_count>1) multiple_personal_names
FROM scalar_refs r LEFT JOIN dict_keys p ON p.user_id=r.user_id AND p.dict_type=r.dict_type AND p.dict_code=r.code_value
LEFT JOIN dict_keys t ON t.user_id=0 AND t.dict_type=r.dict_type AND t.dict_code=r.code_value
GROUP BY r.kind,r.dict_type,r.deleted ORDER BY r.kind,r.dict_type,r.deleted;

WITH refs AS (
 SELECT 'ITEM' kind,id row_id,user_id,deleted,category,item_style FROM wardrobe_item
 UNION ALL SELECT 'OUTFIT_ITEM',id,user_id,deleted,category,item_style FROM wardrobe_outfit_item
 UNION ALL SELECT 'RECORD_ITEM',id,user_id,deleted,category,item_style FROM wardrobe_wear_record_item
),
dict_keys AS (
 SELECT user_id,dict_type,dict_code,COUNT(*) all_versions,
 SUM(deleted=0) current_rows,SUM(deleted=0 AND dict_status=1) enabled_rows,
 COUNT(DISTINCT CONVERT(dict_name USING binary)) historical_name_count,
 MIN(IF(deleted=0,id,NULL)) only_current_id
 FROM base_dict WHERE scope_type=0 AND family_group_id=0 AND dict_type IN (5002,5003,5004,5005,5006)
 GROUP BY user_id,dict_type,dict_code
),
resolved AS (
 SELECT r.*,CASE WHEN p.current_rows=1 THEN p.only_current_id
 WHEN p.user_id IS NULL AND t.current_rows=1 THEN t.only_current_id ELSE NULL END style_source
 FROM refs r LEFT JOIN dict_keys p ON p.user_id=r.user_id AND p.dict_type=5006 AND p.dict_code=r.item_style
 LEFT JOIN dict_keys t ON t.user_id=0 AND t.dict_type=5006 AND t.dict_code=r.item_style WHERE r.item_style<>0
)
SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P16_STYLE_CATEGORY_PAIRING' check_id,r.kind,r.deleted,COUNT(*) nonzero_style_rows,
 SUM(r.style_source IS NULL) unresolved_style_source,
 SUM(r.style_source IS NOT NULL AND (parent.id IS NULL OR parent.dict_type<>5002 OR parent.deleted=1)) invalid_style_parent,
 SUM(parent.dict_type=5002 AND parent.deleted=0 AND parent.dict_code<>r.category) category_code_mismatch
FROM resolved r LEFT JOIN base_dict style ON style.id=r.style_source
LEFT JOIN base_dict parent ON parent.id=style.parent_id
GROUP BY r.kind,r.deleted ORDER BY r.kind,r.deleted;

WITH fields AS (
 SELECT 'ITEM' kind,id row_id,user_id,deleted,5004 dict_type,scene_tags tags FROM wardrobe_item
 UNION ALL SELECT 'ITEM',id,user_id,deleted,5005,style_tags FROM wardrobe_item
 UNION ALL SELECT 'OUTFIT',id,user_id,deleted,5004,scene_tags FROM wardrobe_outfit
 UNION ALL SELECT 'OUTFIT',id,user_id,deleted,5005,style_tags FROM wardrobe_outfit
 UNION ALL SELECT 'RECORD',id,user_id,deleted,5004,scene_tags FROM wardrobe_wear_record
)
SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P17_TAG_FIELD_FORMAT' check_id,kind,dict_type,deleted,COUNT(*) field_count,
 SUM(COALESCE(tags,'')='') empty_fields,
 SUM(COALESCE(tags,'')<>'' AND TRIM(tags)='') whitespace_only_fields,
 SUM(REGEXP_LIKE(COALESCE(tags,''),'^[0-9]+(,[0-9]+)*$')) numeric_csv_fields,
 SUM(COALESCE(tags,'')<>'' AND NOT REGEXP_LIKE(tags,'^[0-9]+(,[0-9]+)*$')) other_format_fields
FROM fields GROUP BY kind,dict_type,deleted ORDER BY kind,dict_type,deleted;

WITH fields AS (
 SELECT 'ITEM' kind,id row_id,user_id,deleted,5004 dict_type,scene_tags tags FROM wardrobe_item
 UNION ALL SELECT 'ITEM',id,user_id,deleted,5005,style_tags FROM wardrobe_item
 UNION ALL SELECT 'OUTFIT',id,user_id,deleted,5004,scene_tags FROM wardrobe_outfit
 UNION ALL SELECT 'OUTFIT',id,user_id,deleted,5005,style_tags FROM wardrobe_outfit
 UNION ALL SELECT 'RECORD',id,user_id,deleted,5004,scene_tags FROM wardrobe_wear_record
),
tokens AS (
 SELECT f.*,TRIM(j.token) token
 FROM fields f JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(tags,'')),',','","'),']'),
 '$[*]' COLUMNS(token VARCHAR(128) PATH '$')) j
 WHERE TRIM(j.token)<>''
), classified AS (
 SELECT t.*,CASE WHEN REGEXP_LIKE(token,'^(0|[1-9][0-9]{0,4})$') AND CAST(token AS UNSIGNED)<=65535
 THEN CAST(token AS UNSIGNED) ELSE NULL END code_value FROM tokens t
),
dict_keys AS (
 SELECT user_id,dict_type,dict_code,COUNT(*) all_versions,
 SUM(deleted=0) current_rows,SUM(deleted=0 AND dict_status=1) enabled_rows,
 COUNT(DISTINCT CONVERT(dict_name USING binary)) historical_name_count,
 MIN(IF(deleted=0,id,NULL)) only_current_id
 FROM base_dict WHERE scope_type=0 AND family_group_id=0 AND dict_type IN (5002,5003,5004,5005,5006)
 GROUP BY user_id,dict_type,dict_code
)
SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P18_TAG_TOKEN_SOURCES' check_id,c.kind,c.dict_type,c.deleted,COUNT(*) token_count,
 SUM(c.code_value IS NOT NULL) canonical_code_tokens,
 SUM(c.code_value IS NULL AND REGEXP_LIKE(c.token,'^[0-9]+$')) noncanonical_numeric_tokens,
 SUM(NOT REGEXP_LIKE(c.token,'^[0-9]+$')) literal_tokens,
 SUM(c.code_value IS NOT NULL AND p.current_rows=1) personal_current_unique,
 SUM(c.code_value IS NOT NULL AND COALESCE(p.current_rows,0)>1) personal_current_ambiguous,
 SUM(c.code_value IS NOT NULL AND COALESCE(p.current_rows,0)=0 AND p.all_versions=1) personal_deleted_unique,
 SUM(c.code_value IS NOT NULL AND p.user_id IS NULL AND t.current_rows=1) template_only,
 SUM(c.code_value IS NOT NULL AND p.user_id IS NULL AND COALESCE(t.current_rows,0)=0) no_personal_or_template,
 SUM(c.code_value IS NOT NULL AND p.historical_name_count>1) multiple_personal_names
FROM classified c LEFT JOIN dict_keys p ON p.user_id=c.user_id AND p.dict_type=c.dict_type AND p.dict_code=c.code_value
LEFT JOIN dict_keys t ON t.user_id=0 AND t.dict_type=c.dict_type AND t.dict_code=c.code_value
GROUP BY c.kind,c.dict_type,c.deleted ORDER BY c.kind,c.dict_type,c.deleted;

WITH fields AS (
 SELECT 'ITEM' kind,id row_id,user_id,deleted,5004 dict_type,scene_tags tags FROM wardrobe_item
 UNION ALL SELECT 'ITEM',id,user_id,deleted,5005,style_tags FROM wardrobe_item
 UNION ALL SELECT 'OUTFIT',id,user_id,deleted,5004,scene_tags FROM wardrobe_outfit
 UNION ALL SELECT 'OUTFIT',id,user_id,deleted,5005,style_tags FROM wardrobe_outfit
 UNION ALL SELECT 'RECORD',id,user_id,deleted,5004,scene_tags FROM wardrobe_wear_record
),
tokens AS (
 SELECT f.*,TRIM(j.token) token
 FROM fields f JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(tags,'')),',','","'),']'),
 '$[*]' COLUMNS(token VARCHAR(128) PATH '$')) j
 WHERE TRIM(j.token)<>''
), classified AS (
 SELECT t.*,CASE WHEN REGEXP_LIKE(token,'^(0|[1-9][0-9]{0,4})$') AND CAST(token AS UNSIGNED)<=65535
 THEN CAST(token AS UNSIGNED) ELSE NULL END code_value FROM tokens t
)
SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P18_DUPLICATE_EXACT_TOKENS' check_id,COUNT(*) duplicate_groups,COALESCE(SUM(n-1),0) extra_token_count FROM (
 SELECT kind,row_id,dict_type,CONVERT(token USING binary) token_key,COUNT(*) n FROM tokens
 GROUP BY kind,row_id,dict_type,token_key HAVING COUNT(*)>1
) d;

WITH color_keys AS (
 SELECT user_id,CONVERT(dict_name USING binary) name_key,COUNT(*) all_versions,SUM(deleted=0) current_rows
 FROM base_dict WHERE scope_type=0 AND family_group_id=0 AND dict_type=5003
 GROUP BY user_id,name_key
)
SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P19_COLOR_TEXT_SOURCES' check_id,i.deleted,COUNT(*) row_count,
 SUM(COALESCE(i.color_name,'')='') empty_color,
 SUM(COALESCE(i.color_name,'')<>'' AND p.current_rows=1) personal_current_unique,
 SUM(COALESCE(i.color_name,'')<>'' AND COALESCE(p.current_rows,0)=0 AND p.all_versions=1) personal_deleted_unique,
 SUM(COALESCE(i.color_name,'')<>'' AND p.user_id IS NULL) no_personal_exact_name,
 SUM(COALESCE(i.color_name,'')<>'' AND COALESCE(p.current_rows,0)>1) personal_current_ambiguous
FROM wardrobe_item i LEFT JOIN color_keys p ON p.user_id=i.user_id AND p.name_key=CONVERT(i.color_name USING binary)
GROUP BY i.deleted ORDER BY i.deleted;

SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P20_HISTORY_LINKS' check_id,'OUTFIT_ITEM' kind,r.deleted,COUNT(*) row_count,
 SUM(head.id IS NULL) missing_head,SUM(head.deleted=1) deleted_head,
 SUM(i.id IS NULL) missing_item,SUM(i.deleted=1) deleted_item,SUM(i.user_id<>r.user_id) current_owner_differs,
 SUM(head.user_id<>r.user_id) head_owner_differs
FROM wardrobe_outfit_item r LEFT JOIN wardrobe_outfit head ON head.id=r.outfit_id LEFT JOIN wardrobe_item i ON i.id=r.item_id
GROUP BY r.deleted
UNION ALL
SELECT 'P20_HISTORY_LINKS','RECORD_ITEM',r.deleted,COUNT(*),SUM(head.id IS NULL),SUM(head.deleted=1),SUM(i.id IS NULL),SUM(i.deleted=1),SUM(i.user_id<>r.user_id),SUM(head.user_id<>r.user_id)
FROM wardrobe_wear_record_item r LEFT JOIN wardrobe_wear_record head ON head.id=r.record_id LEFT JOIN wardrobe_item i ON i.id=r.item_id
GROUP BY r.deleted;

SELECT /*+ MAX_EXECUTION_TIME(10000) */ 'P21_DICTIONARY_HISTORY_SIGNALS' check_id,dict_type,COUNT(*) total_rows,
 SUM(previous_id IS NOT NULL) predecessor_links,SUM(revision<>0) nonzero_revisions,
 SUM(deleted=1) deleted_rows,SUM(update_time>create_time) later_timestamp_rows
FROM base_dict WHERE dict_type IN (5002,5003,5004,5005,5006)
GROUP BY dict_type ORDER BY dict_type;
ROLLBACK;

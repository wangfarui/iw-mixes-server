-- MySQL 8; reviewed migration-time name baseline. Run only while affected writers are stopped.
-- Entire DML is atomic. Existing READY batches are not reparsed; execute 03-verify separately.
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_wardrobe_backfill$$
CREATE PROCEDURE iw_wardrobe_backfill()
main: BEGIN
 DECLARE migration_phase VARCHAR(24);
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
 SET @dict_trim_pattern = CONCAT('^[', CONVERT(0x090a0b0c0d1c1d1e1f20c285c2a0e19a80e28080e28081e28082e28083e28084e28085e28086e28087e28088e28089e2808ae280a8e280a9e280afe2819fe38080 USING utf8mb4), ']+|[', CONVERT(0x090a0b0c0d1c1d1e1f20c285c2a0e19a80e28080e28081e28082e28083e28084e28085e28086e28087e28088e28089e2808ae280a8e280a9e280afe2819fe38080 USING utf8mb4), ']+$');
 START TRANSACTION;
 SELECT phase INTO migration_phase FROM wardrobe_dictionary_migration_state WHERE id=1 FOR UPDATE;
 IF migration_phase='READY' THEN COMMIT; LEAVE main; END IF;
 IF migration_phase IS NULL OR migration_phase<>'SCHEMA' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: migration phase invalid'; END IF;
 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type BETWEEN 5002 AND 5006 AND (previous_id IS NOT NULL OR revision<>0)) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: existing wardrobe versions require a separately reviewed baseline'; END IF;
 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type BETWEEN 5002 AND 5006 AND
 (scope_type NOT IN (0,1) OR (scope_type=0 AND family_group_id<>0) OR (scope_type=1 AND (family_group_id=0 OR user_id<>0)))) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: invalid dictionary scope'; END IF;
 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type BETWEEN 5002 AND 5006 AND deleted=0
 GROUP BY scope_type,family_group_id,user_id,dict_type,dict_code HAVING COUNT(*)>1) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: duplicate legacy codes'; END IF;
 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type BETWEEN 5002 AND 5006 AND deleted=0
 GROUP BY scope_type,family_group_id,user_id,dict_type,IF(dict_type=5006,parent_id,0),CONVERT(dict_name USING binary) HAVING COUNT(*)>1) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: duplicate names'; END IF;
 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type=5002 AND (dict_code NOT BETWEEN 1 AND 11)) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: category semantic kind is unknown'; END IF;
 IF EXISTS(SELECT 1 FROM base_dict c LEFT JOIN base_dict p ON p.id=c.parent_id WHERE c.dict_type=5006 AND c.deleted=0 AND
 (p.id IS NULL OR p.dict_type<>5002 OR p.deleted<>0 OR p.scope_type<>c.scope_type OR p.family_group_id<>c.family_group_id OR p.user_id<>c.user_id)) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: invalid style parent'; END IF;
 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type BETWEEN 5002 AND 5006 AND
 (CHAR_LENGTH(dict_name) NOT BETWEEN 1 AND 32 OR BINARY dict_name<>BINARY REGEXP_REPLACE(dict_name,@dict_trim_pattern,''))) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: noncanonical Unicode names'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_item r LEFT JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id
 AND d.dict_type=5002 AND d.dict_code=r.category AND d.deleted=0 WHERE 1=1
 GROUP BY r.id HAVING COUNT(d.id)<>1) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: wardrobe_item.category source missing or ambiguous'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_item r LEFT JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id
 AND d.dict_type=5006 AND d.dict_code=r.item_style AND d.deleted=0 WHERE 1=1 AND r.item_style<>0
 GROUP BY r.id HAVING COUNT(d.id)<>1) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: wardrobe_item.item_style source missing or ambiguous'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_item r JOIN base_dict s ON s.scope_type=0 AND s.family_group_id=0 AND s.user_id=r.user_id AND s.dict_type=5006 AND s.dict_code=r.item_style AND s.deleted=0
 JOIN base_dict p ON p.id=s.parent_id WHERE r.item_style<>0 AND p.dict_code<>r.category) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: category/style code pairing mismatch'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_outfit_item r LEFT JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id
 AND d.dict_type=5002 AND d.dict_code=r.category AND d.deleted=0 WHERE 1=1
 GROUP BY r.id HAVING COUNT(d.id)<>1) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: wardrobe_outfit_item.category source missing or ambiguous'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_outfit_item r LEFT JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id
 AND d.dict_type=5006 AND d.dict_code=r.item_style AND d.deleted=0 WHERE 1=1 AND r.item_style<>0
 GROUP BY r.id HAVING COUNT(d.id)<>1) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: wardrobe_outfit_item.item_style source missing or ambiguous'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_outfit_item r JOIN base_dict s ON s.scope_type=0 AND s.family_group_id=0 AND s.user_id=r.user_id AND s.dict_type=5006 AND s.dict_code=r.item_style AND s.deleted=0
 JOIN base_dict p ON p.id=s.parent_id WHERE r.item_style<>0 AND p.dict_code<>r.category) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: category/style code pairing mismatch'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_wear_record_item r LEFT JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id
 AND d.dict_type=5002 AND d.dict_code=r.category AND d.deleted=0 WHERE 1=1
 GROUP BY r.id HAVING COUNT(d.id)<>1) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: wardrobe_wear_record_item.category source missing or ambiguous'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_wear_record_item r LEFT JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id
 AND d.dict_type=5006 AND d.dict_code=r.item_style AND d.deleted=0 WHERE 1=1 AND r.item_style<>0
 GROUP BY r.id HAVING COUNT(d.id)<>1) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: wardrobe_wear_record_item.item_style source missing or ambiguous'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_wear_record_item r JOIN base_dict s ON s.scope_type=0 AND s.family_group_id=0 AND s.user_id=r.user_id AND s.dict_type=5006 AND s.dict_code=r.item_style AND s.deleted=0
 JOIN base_dict p ON p.id=s.parent_id WHERE r.item_style<>0 AND p.dict_code<>r.category) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: category/style code pairing mismatch'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_item r LEFT JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id
 AND d.dict_type=5003 AND BINARY d.dict_name=BINARY r.color_name AND d.deleted=0 WHERE r.color_name<>'' GROUP BY r.id HAVING COUNT(d.id)<>1) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: color text source missing or ambiguous'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_item WHERE COALESCE(scene_tags,'')<>'' AND NOT REGEXP_LIKE(scene_tags,'^[0-9]+(,[0-9]+)*$')) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: noncanonical legacy tags require review'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_item r JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.scene_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 LEFT JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=5004 AND d.dict_code=CAST(tok.token AS UNSIGNED) AND d.deleted=0
 WHERE COALESCE(r.scene_tags,'')<>'' GROUP BY r.id,tok.ordinal,tok.token HAVING COUNT(d.id)<>1 OR NOT REGEXP_LIKE(tok.token,'^(0|[1-9][0-9]{0,4})$') OR CAST(tok.token AS UNSIGNED)>65535) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: tag source missing/ambiguous or noncanonical code'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_item r JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.scene_tags,'')),',','","'),']'),'$[*]' COLUMNS(token VARCHAR(128) PATH '$')) tok
 WHERE COALESCE(r.scene_tags,'')<>'' GROUP BY r.id,tok.token HAVING COUNT(*)>1) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: duplicate tag tokens'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_item WHERE COALESCE(style_tags,'')<>'' AND NOT REGEXP_LIKE(style_tags,'^[0-9]+(,[0-9]+)*$')) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: noncanonical legacy tags require review'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_item r JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.style_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 LEFT JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=5005 AND d.dict_code=CAST(tok.token AS UNSIGNED) AND d.deleted=0
 WHERE COALESCE(r.style_tags,'')<>'' GROUP BY r.id,tok.ordinal,tok.token HAVING COUNT(d.id)<>1 OR NOT REGEXP_LIKE(tok.token,'^(0|[1-9][0-9]{0,4})$') OR CAST(tok.token AS UNSIGNED)>65535) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: tag source missing/ambiguous or noncanonical code'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_item r JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.style_tags,'')),',','","'),']'),'$[*]' COLUMNS(token VARCHAR(128) PATH '$')) tok
 WHERE COALESCE(r.style_tags,'')<>'' GROUP BY r.id,tok.token HAVING COUNT(*)>1) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: duplicate tag tokens'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_outfit WHERE COALESCE(scene_tags,'')<>'' AND NOT REGEXP_LIKE(scene_tags,'^[0-9]+(,[0-9]+)*$')) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: noncanonical legacy tags require review'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_outfit r JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.scene_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 LEFT JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=5004 AND d.dict_code=CAST(tok.token AS UNSIGNED) AND d.deleted=0
 WHERE COALESCE(r.scene_tags,'')<>'' GROUP BY r.id,tok.ordinal,tok.token HAVING COUNT(d.id)<>1 OR NOT REGEXP_LIKE(tok.token,'^(0|[1-9][0-9]{0,4})$') OR CAST(tok.token AS UNSIGNED)>65535) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: tag source missing/ambiguous or noncanonical code'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_outfit r JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.scene_tags,'')),',','","'),']'),'$[*]' COLUMNS(token VARCHAR(128) PATH '$')) tok
 WHERE COALESCE(r.scene_tags,'')<>'' GROUP BY r.id,tok.token HAVING COUNT(*)>1) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: duplicate tag tokens'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_outfit WHERE COALESCE(style_tags,'')<>'' AND NOT REGEXP_LIKE(style_tags,'^[0-9]+(,[0-9]+)*$')) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: noncanonical legacy tags require review'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_outfit r JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.style_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 LEFT JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=5005 AND d.dict_code=CAST(tok.token AS UNSIGNED) AND d.deleted=0
 WHERE COALESCE(r.style_tags,'')<>'' GROUP BY r.id,tok.ordinal,tok.token HAVING COUNT(d.id)<>1 OR NOT REGEXP_LIKE(tok.token,'^(0|[1-9][0-9]{0,4})$') OR CAST(tok.token AS UNSIGNED)>65535) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: tag source missing/ambiguous or noncanonical code'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_outfit r JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.style_tags,'')),',','","'),']'),'$[*]' COLUMNS(token VARCHAR(128) PATH '$')) tok
 WHERE COALESCE(r.style_tags,'')<>'' GROUP BY r.id,tok.token HAVING COUNT(*)>1) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: duplicate tag tokens'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_wear_record WHERE COALESCE(scene_tags,'')<>'' AND NOT REGEXP_LIKE(scene_tags,'^[0-9]+(,[0-9]+)*$')) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: noncanonical legacy tags require review'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_wear_record r JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.scene_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 LEFT JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=5004 AND d.dict_code=CAST(tok.token AS UNSIGNED) AND d.deleted=0
 WHERE COALESCE(r.scene_tags,'')<>'' GROUP BY r.id,tok.ordinal,tok.token HAVING COUNT(d.id)<>1 OR NOT REGEXP_LIKE(tok.token,'^(0|[1-9][0-9]{0,4})$') OR CAST(tok.token AS UNSIGNED)>65535) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: tag source missing/ambiguous or noncanonical code'; END IF;
 IF EXISTS(SELECT 1 FROM wardrobe_wear_record r JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.scene_tags,'')),',','","'),']'),'$[*]' COLUMNS(token VARCHAR(128) PATH '$')) tok
 WHERE COALESCE(r.scene_tags,'')<>'' GROUP BY r.id,tok.token HAVING COUNT(*)>1) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: duplicate tag tokens'; END IF;
 INSERT INTO wardrobe_dictionary_migration_baseline(business_type,business_id,owner_id,deleted,raw_snapshot) SELECT 'ITEM',id,user_id,deleted,JSON_OBJECT('category',category,'item_style',item_style,'color_name',color_name,'scene_tags',scene_tags,'style_tags',style_tags) FROM wardrobe_item;
 INSERT INTO wardrobe_dictionary_migration_baseline(business_type,business_id,owner_id,deleted,raw_snapshot) SELECT 'OUTFIT_ITEM',id,user_id,deleted,JSON_OBJECT('category',category,'item_style',item_style) FROM wardrobe_outfit_item;
 INSERT INTO wardrobe_dictionary_migration_baseline(business_type,business_id,owner_id,deleted,raw_snapshot) SELECT 'WEAR_RECORD_ITEM',id,user_id,deleted,JSON_OBJECT('category',category,'item_style',item_style) FROM wardrobe_wear_record_item;
 INSERT INTO wardrobe_dictionary_migration_baseline(business_type,business_id,owner_id,deleted,raw_snapshot) SELECT 'OUTFIT',id,user_id,deleted,JSON_OBJECT('scene_tags',scene_tags,'style_tags',style_tags) FROM wardrobe_outfit;
 INSERT INTO wardrobe_dictionary_migration_baseline(business_type,business_id,owner_id,deleted,raw_snapshot) SELECT 'WEAR_RECORD',id,user_id,deleted,JSON_OBJECT('scene_tags',scene_tags) FROM wardrobe_wear_record;
 INSERT INTO wardrobe_dictionary_migration_baseline(business_type,business_id,owner_id,deleted,raw_snapshot) SELECT 'DICT',id,user_id,deleted,JSON_OBJECT('parent_id',parent_id,'dict_type',dict_type,'dict_code',dict_code,'dict_name',dict_name,'dict_status',dict_status,'scope_type',scope_type,'family_group_id',family_group_id) FROM base_dict;
 UPDATE base_dict SET wardrobe_category_lineage_id=id,wardrobe_match_kind=dict_code WHERE dict_type=5002;
 UPDATE base_dict s JOIN base_dict p ON p.id=s.parent_id SET s.wardrobe_category_lineage_id=p.wardrobe_category_lineage_id WHERE s.dict_type=5006;
 CREATE TEMPORARY TABLE IF NOT EXISTS iw_wardrobe_new_scopes(scope_type TINYINT NOT NULL,owner_id INT UNSIGNED NOT NULL,PRIMARY KEY(scope_type,owner_id));
 DELETE FROM iw_wardrobe_new_scopes;
 INSERT INTO iw_wardrobe_new_scopes SELECT 0,u.id FROM auth_user u WHERE u.deleted=0 AND u.id>0
 AND NOT EXISTS(SELECT 1 FROM base_dict d WHERE d.scope_type=0 AND d.family_group_id=0 AND d.user_id=u.id AND d.dict_type BETWEEN 5002 AND 5006);
 INSERT INTO iw_wardrobe_new_scopes SELECT 1,g.id FROM auth_family_group g WHERE g.deleted=0 AND g.status=1
 AND NOT EXISTS(SELECT 1 FROM wardrobe_dictionary_scope_state s WHERE s.scope_type=1 AND s.owner_id=g.id);
 IF EXISTS(SELECT 1 FROM iw_wardrobe_new_scopes s JOIN base_dict d ON s.scope_type=1 AND d.scope_type=1 AND d.family_group_id=s.owner_id AND d.dict_type BETWEEN 5002 AND 5006) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: family data exists without reviewed initialization state'; END IF;
 IF (SELECT COUNT(DISTINCT dict_type) FROM base_dict WHERE user_id=0 AND scope_type=0 AND family_group_id=0 AND dict_type BETWEEN 5002 AND 5006 AND deleted=0 AND dict_status=1)<>5 THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: system templates missing'; END IF;
 INSERT INTO base_dict(parent_id,dict_type,dict_code,dict_name,dict_status,sort,user_id,scope_type,family_group_id,revision,deleted,wardrobe_match_kind)
 SELECT 0,t.dict_type,t.dict_code,t.dict_name,1,t.sort,IF(s.scope_type=0,s.owner_id,0),s.scope_type,IF(s.scope_type=1,s.owner_id,0),0,0,t.wardrobe_match_kind
 FROM iw_wardrobe_new_scopes s CROSS JOIN base_dict t WHERE t.user_id=0 AND t.scope_type=0 AND t.family_group_id=0
 AND t.dict_type BETWEEN 5002 AND 5005 AND t.deleted=0 AND t.dict_status=1;
 UPDATE base_dict SET wardrobe_category_lineage_id=id WHERE dict_type=5002 AND wardrobe_category_lineage_id IS NULL;
 INSERT INTO base_dict(parent_id,dict_type,dict_code,dict_name,dict_status,sort,user_id,scope_type,family_group_id,revision,deleted,wardrobe_category_lineage_id)
 SELECT p.id,5006,t.dict_code,t.dict_name,1,t.sort,IF(s.scope_type=0,s.owner_id,0),s.scope_type,IF(s.scope_type=1,s.owner_id,0),0,0,p.wardrobe_category_lineage_id
 FROM iw_wardrobe_new_scopes s CROSS JOIN base_dict t JOIN base_dict tp ON tp.id=t.parent_id
 JOIN base_dict p ON p.dict_type=5002 AND p.dict_code=tp.dict_code AND p.deleted=0 AND p.scope_type=s.scope_type
 AND p.user_id=IF(s.scope_type=0,s.owner_id,0) AND p.family_group_id=IF(s.scope_type=1,s.owner_id,0)
 WHERE t.user_id=0 AND t.scope_type=0 AND t.family_group_id=0 AND t.dict_type=5006 AND t.deleted=0 AND t.dict_status=1;
 INSERT INTO wardrobe_dictionary_scope_state(scope_type,owner_id,scope_revision,initialized_at)
 SELECT 0,u.id,0,NOW() FROM auth_user u WHERE u.deleted=0 AND u.id>0
 AND NOT EXISTS(SELECT 1 FROM wardrobe_dictionary_scope_state s WHERE s.scope_type=0 AND s.owner_id=u.id);
 INSERT INTO wardrobe_dictionary_scope_state(scope_type,owner_id,scope_revision,initialized_at)
 SELECT 1,g.id,0,NOW() FROM auth_family_group g WHERE g.deleted=0 AND g.status=1
 AND NOT EXISTS(SELECT 1 FROM wardrobe_dictionary_scope_state s WHERE s.scope_type=1 AND s.owner_id=g.id);
 UPDATE wardrobe_item r
 JOIN base_dict c ON c.scope_type=0 AND c.family_group_id=0 AND c.user_id=r.user_id AND c.dict_type=5002 AND c.dict_code=r.category AND c.deleted=0
 LEFT JOIN base_dict s ON s.scope_type=0 AND s.family_group_id=0 AND s.user_id=r.user_id AND s.dict_type=5006 AND s.dict_code=r.item_style AND r.item_style<>0 AND s.deleted=0
 SET r.category_source_id=c.id,r.category_name_snapshot=c.dict_name,r.category_match_kind_snapshot=c.wardrobe_match_kind,
 r.item_style_source_id=s.id,r.item_style_name_snapshot=s.dict_name,r.dictionary_snapshot_status=1;
 INSERT INTO wardrobe_dictionary_migration_resolution(business_type,business_id,dict_type,ordinal,raw_value,owner_id,resolution,source_id,name_snapshot)
 SELECT 'ITEM',id,5002,0,CAST(category AS CHAR),user_id,'PERSONAL_CURRENT',category_source_id,category_name_snapshot FROM wardrobe_item
 UNION ALL SELECT 'ITEM',id,5006,0,CAST(item_style AS CHAR),user_id,IF(item_style=0,'UNSET','PERSONAL_CURRENT'),item_style_source_id,item_style_name_snapshot FROM wardrobe_item;
 UPDATE wardrobe_outfit_item r
 JOIN base_dict c ON c.scope_type=0 AND c.family_group_id=0 AND c.user_id=r.user_id AND c.dict_type=5002 AND c.dict_code=r.category AND c.deleted=0
 LEFT JOIN base_dict s ON s.scope_type=0 AND s.family_group_id=0 AND s.user_id=r.user_id AND s.dict_type=5006 AND s.dict_code=r.item_style AND r.item_style<>0 AND s.deleted=0
 SET r.category_source_id=c.id,r.category_name_snapshot=c.dict_name,r.category_match_kind_snapshot=c.wardrobe_match_kind,
 r.item_style_source_id=s.id,r.item_style_name_snapshot=s.dict_name,r.dictionary_snapshot_status=1;
 INSERT INTO wardrobe_dictionary_migration_resolution(business_type,business_id,dict_type,ordinal,raw_value,owner_id,resolution,source_id,name_snapshot)
 SELECT 'OUTFIT_ITEM',id,5002,0,CAST(category AS CHAR),user_id,'PERSONAL_CURRENT',category_source_id,category_name_snapshot FROM wardrobe_outfit_item
 UNION ALL SELECT 'OUTFIT_ITEM',id,5006,0,CAST(item_style AS CHAR),user_id,IF(item_style=0,'UNSET','PERSONAL_CURRENT'),item_style_source_id,item_style_name_snapshot FROM wardrobe_outfit_item;
 UPDATE wardrobe_wear_record_item r
 JOIN base_dict c ON c.scope_type=0 AND c.family_group_id=0 AND c.user_id=r.user_id AND c.dict_type=5002 AND c.dict_code=r.category AND c.deleted=0
 LEFT JOIN base_dict s ON s.scope_type=0 AND s.family_group_id=0 AND s.user_id=r.user_id AND s.dict_type=5006 AND s.dict_code=r.item_style AND r.item_style<>0 AND s.deleted=0
 SET r.category_source_id=c.id,r.category_name_snapshot=c.dict_name,r.category_match_kind_snapshot=c.wardrobe_match_kind,
 r.item_style_source_id=s.id,r.item_style_name_snapshot=s.dict_name,r.dictionary_snapshot_status=1;
 INSERT INTO wardrobe_dictionary_migration_resolution(business_type,business_id,dict_type,ordinal,raw_value,owner_id,resolution,source_id,name_snapshot)
 SELECT 'WEAR_RECORD_ITEM',id,5002,0,CAST(category AS CHAR),user_id,'PERSONAL_CURRENT',category_source_id,category_name_snapshot FROM wardrobe_wear_record_item
 UNION ALL SELECT 'WEAR_RECORD_ITEM',id,5006,0,CAST(item_style AS CHAR),user_id,IF(item_style=0,'UNSET','PERSONAL_CURRENT'),item_style_source_id,item_style_name_snapshot FROM wardrobe_wear_record_item;
 UPDATE wardrobe_item r JOIN base_dict c ON c.scope_type=0 AND c.family_group_id=0 AND c.user_id=r.user_id AND c.dict_type=5003 AND BINARY c.dict_name=BINARY r.color_name AND c.deleted=0 SET r.color_source_id=c.id WHERE r.color_name<>'';
 INSERT INTO wardrobe_dictionary_migration_resolution(business_type,business_id,dict_type,ordinal,raw_value,owner_id,resolution,source_id,name_snapshot)
 SELECT 'ITEM',id,5003,0,color_name,user_id,IF(color_name='','UNSET','PERSONAL_TEXT'),color_source_id,color_name FROM wardrobe_item;
 INSERT INTO wardrobe_dictionary_tag_snapshot(business_type,business_id,dict_type,ordinal,source_id,name_snapshot)
 SELECT 'ITEM',r.id,5004,tok.ordinal,d.id,d.dict_name FROM wardrobe_item r
 JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.scene_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=5004 AND d.dict_code=CAST(tok.token AS UNSIGNED) AND d.deleted=0
 WHERE COALESCE(r.scene_tags,'')<>'';
 INSERT INTO wardrobe_dictionary_migration_resolution(business_type,business_id,dict_type,ordinal,raw_value,owner_id,resolution,source_id,name_snapshot)
 SELECT 'ITEM',r.id,5004,tok.ordinal,tok.token,r.user_id,'PERSONAL_CURRENT',d.id,d.dict_name FROM wardrobe_item r
 JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.scene_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=5004 AND d.dict_code=CAST(tok.token AS UNSIGNED) AND d.deleted=0
 WHERE COALESCE(r.scene_tags,'')<>'';
 INSERT INTO wardrobe_dictionary_tag_snapshot(business_type,business_id,dict_type,ordinal,source_id,name_snapshot)
 SELECT 'ITEM',r.id,5005,tok.ordinal,d.id,d.dict_name FROM wardrobe_item r
 JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.style_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=5005 AND d.dict_code=CAST(tok.token AS UNSIGNED) AND d.deleted=0
 WHERE COALESCE(r.style_tags,'')<>'';
 INSERT INTO wardrobe_dictionary_migration_resolution(business_type,business_id,dict_type,ordinal,raw_value,owner_id,resolution,source_id,name_snapshot)
 SELECT 'ITEM',r.id,5005,tok.ordinal,tok.token,r.user_id,'PERSONAL_CURRENT',d.id,d.dict_name FROM wardrobe_item r
 JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.style_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=5005 AND d.dict_code=CAST(tok.token AS UNSIGNED) AND d.deleted=0
 WHERE COALESCE(r.style_tags,'')<>'';
 INSERT INTO wardrobe_dictionary_tag_snapshot(business_type,business_id,dict_type,ordinal,source_id,name_snapshot)
 SELECT 'OUTFIT',r.id,5004,tok.ordinal,d.id,d.dict_name FROM wardrobe_outfit r
 JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.scene_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=5004 AND d.dict_code=CAST(tok.token AS UNSIGNED) AND d.deleted=0
 WHERE COALESCE(r.scene_tags,'')<>'';
 INSERT INTO wardrobe_dictionary_migration_resolution(business_type,business_id,dict_type,ordinal,raw_value,owner_id,resolution,source_id,name_snapshot)
 SELECT 'OUTFIT',r.id,5004,tok.ordinal,tok.token,r.user_id,'PERSONAL_CURRENT',d.id,d.dict_name FROM wardrobe_outfit r
 JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.scene_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=5004 AND d.dict_code=CAST(tok.token AS UNSIGNED) AND d.deleted=0
 WHERE COALESCE(r.scene_tags,'')<>'';
 INSERT INTO wardrobe_dictionary_tag_snapshot(business_type,business_id,dict_type,ordinal,source_id,name_snapshot)
 SELECT 'OUTFIT',r.id,5005,tok.ordinal,d.id,d.dict_name FROM wardrobe_outfit r
 JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.style_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=5005 AND d.dict_code=CAST(tok.token AS UNSIGNED) AND d.deleted=0
 WHERE COALESCE(r.style_tags,'')<>'';
 INSERT INTO wardrobe_dictionary_migration_resolution(business_type,business_id,dict_type,ordinal,raw_value,owner_id,resolution,source_id,name_snapshot)
 SELECT 'OUTFIT',r.id,5005,tok.ordinal,tok.token,r.user_id,'PERSONAL_CURRENT',d.id,d.dict_name FROM wardrobe_outfit r
 JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.style_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=5005 AND d.dict_code=CAST(tok.token AS UNSIGNED) AND d.deleted=0
 WHERE COALESCE(r.style_tags,'')<>'';
 INSERT INTO wardrobe_dictionary_tag_snapshot(business_type,business_id,dict_type,ordinal,source_id,name_snapshot)
 SELECT 'WEAR_RECORD',r.id,5004,tok.ordinal,d.id,d.dict_name FROM wardrobe_wear_record r
 JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.scene_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=5004 AND d.dict_code=CAST(tok.token AS UNSIGNED) AND d.deleted=0
 WHERE COALESCE(r.scene_tags,'')<>'';
 INSERT INTO wardrobe_dictionary_migration_resolution(business_type,business_id,dict_type,ordinal,raw_value,owner_id,resolution,source_id,name_snapshot)
 SELECT 'WEAR_RECORD',r.id,5004,tok.ordinal,tok.token,r.user_id,'PERSONAL_CURRENT',d.id,d.dict_name FROM wardrobe_wear_record r
 JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.scene_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=5004 AND d.dict_code=CAST(tok.token AS UNSIGNED) AND d.deleted=0
 WHERE COALESCE(r.scene_tags,'')<>'';
 UPDATE wardrobe_item SET dictionary_snapshot_status=1;
 UPDATE wardrobe_outfit SET dictionary_snapshot_status=1;
 UPDATE wardrobe_wear_record SET dictionary_snapshot_status=1;
 UPDATE wardrobe_dictionary_migration_state SET phase='READY',updated_at=NOW() WHERE id=1;
 COMMIT;
 DROP TEMPORARY TABLE IF EXISTS iw_wardrobe_new_scopes;
END$$
DELIMITER ;
CALL iw_wardrobe_backfill();
DROP PROCEDURE iw_wardrobe_backfill;
SELECT phase FROM wardrobe_dictionary_migration_state WHERE id=1;

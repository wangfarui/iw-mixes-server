-- LOCAL TEST DATA ONLY. Never use on production. Requires an explicitly confirmed local baseline.
-- MySQL 8; reviewed migration-time name baseline. Run only while affected writers are stopped.
-- Entire DML is atomic. Existing READY batches are not reparsed; execute 03-verify separately.
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_wardrobe_local_backfill$$
CREATE PROCEDURE iw_wardrobe_local_backfill()
main: BEGIN
 DECLARE migration_phase VARCHAR(24);
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
 SET @dict_trim_pattern = CONCAT('^[', CONVERT(0x090a0b0c0d1c1d1e1f20c285c2a0e19a80e28080e28081e28082e28083e28084e28085e28086e28087e28088e28089e2808ae280a8e280a9e280afe2819fe38080 USING utf8mb4), ']+|[', CONVERT(0x090a0b0c0d1c1d1e1f20c285c2a0e19a80e28080e28081e28082e28083e28084e28085e28086e28087e28088e28089e2808ae280a8e280a9e280afe2819fe38080 USING utf8mb4), ']+$');
 IF DATABASE()<>'iw_mixes' OR COALESCE(@iw_confirm_local_wardrobe,0)<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: local iw_mixes approval marker required'; END IF;
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
 INSERT INTO wardrobe_dictionary_migration_baseline(business_type,business_id,owner_id,deleted,raw_snapshot) SELECT 'ITEM',id,user_id,deleted,JSON_OBJECT('category',category,'item_style',item_style,'color_name',color_name,'scene_tags',scene_tags,'style_tags',style_tags) FROM wardrobe_item;
 INSERT INTO wardrobe_dictionary_migration_baseline(business_type,business_id,owner_id,deleted,raw_snapshot) SELECT 'OUTFIT_ITEM',id,user_id,deleted,JSON_OBJECT('category',category,'item_style',item_style) FROM wardrobe_outfit_item;
 INSERT INTO wardrobe_dictionary_migration_baseline(business_type,business_id,owner_id,deleted,raw_snapshot) SELECT 'WEAR_RECORD_ITEM',id,user_id,deleted,JSON_OBJECT('category',category,'item_style',item_style) FROM wardrobe_wear_record_item;
 INSERT INTO wardrobe_dictionary_migration_baseline(business_type,business_id,owner_id,deleted,raw_snapshot) SELECT 'OUTFIT',id,user_id,deleted,JSON_OBJECT('scene_tags',scene_tags,'style_tags',style_tags) FROM wardrobe_outfit;
 INSERT INTO wardrobe_dictionary_migration_baseline(business_type,business_id,owner_id,deleted,raw_snapshot) SELECT 'WEAR_RECORD',id,user_id,deleted,JSON_OBJECT('scene_tags',scene_tags) FROM wardrobe_wear_record;
 INSERT INTO wardrobe_dictionary_migration_baseline(business_type,business_id,owner_id,deleted,raw_snapshot) SELECT 'DICT',id,user_id,deleted,JSON_OBJECT('parent_id',parent_id,'dict_type',dict_type,'dict_code',dict_code,'dict_name',dict_name,'dict_status',dict_status,'scope_type',scope_type,'family_group_id',family_group_id) FROM base_dict;
 CREATE TEMPORARY TABLE iw_local_sources AS SELECT * FROM base_dict WHERE dict_type BETWEEN 5002 AND 5006 AND deleted=0 AND scope_type=0 AND family_group_id=0;
 CREATE TEMPORARY TABLE iw_local_scalar(business_type VARCHAR(24),business_id INT UNSIGNED,owner_id INT UNSIGNED,dict_type INT,raw_value VARCHAR(32),source_id INT UNSIGNED NULL,lookup_id INT UNSIGNED NULL,name_snapshot VARCHAR(32),match_kind INT NULL,parent_code INT NULL,resolution VARCHAR(32),PRIMARY KEY(business_type,business_id,dict_type));
 INSERT INTO iw_local_scalar(business_type,business_id,owner_id,dict_type,raw_value)
 SELECT 'ITEM',id,user_id,5002,CAST(category AS CHAR) FROM wardrobe_item;
 INSERT INTO iw_local_scalar(business_type,business_id,owner_id,dict_type,raw_value)
 SELECT 'ITEM',id,user_id,5006,CAST(item_style AS CHAR) FROM wardrobe_item;
 INSERT INTO iw_local_scalar(business_type,business_id,owner_id,dict_type,raw_value)
 SELECT 'OUTFIT_ITEM',id,user_id,5002,CAST(category AS CHAR) FROM wardrobe_outfit_item;
 INSERT INTO iw_local_scalar(business_type,business_id,owner_id,dict_type,raw_value)
 SELECT 'OUTFIT_ITEM',id,user_id,5006,CAST(item_style AS CHAR) FROM wardrobe_outfit_item;
 INSERT INTO iw_local_scalar(business_type,business_id,owner_id,dict_type,raw_value)
 SELECT 'WEAR_RECORD_ITEM',id,user_id,5002,CAST(category AS CHAR) FROM wardrobe_wear_record_item;
 INSERT INTO iw_local_scalar(business_type,business_id,owner_id,dict_type,raw_value)
 SELECT 'WEAR_RECORD_ITEM',id,user_id,5006,CAST(item_style AS CHAR) FROM wardrobe_wear_record_item;
 CREATE TEMPORARY TABLE iw_local_templates AS SELECT * FROM iw_local_sources WHERE user_id=0;
 UPDATE iw_local_scalar r LEFT JOIN iw_local_sources p ON p.user_id=r.owner_id AND p.dict_type=r.dict_type AND p.dict_code=CAST(r.raw_value AS UNSIGNED)
 LEFT JOIN iw_local_templates t ON t.dict_type=r.dict_type AND t.dict_code=CAST(r.raw_value AS UNSIGNED)
 SET r.source_id=p.id,r.lookup_id=COALESCE(p.id,t.id),r.name_snapshot=COALESCE(p.dict_name,t.dict_name),
 r.match_kind=COALESCE(p.dict_code,t.dict_code),r.parent_code=COALESCE(p.parent_id,t.parent_id),
 r.resolution=IF(p.id IS NULL,'BUILTIN_BASELINE','PERSONAL_CURRENT') WHERE NOT(r.dict_type=5006 AND r.raw_value='0');
 UPDATE iw_local_scalar SET resolution='UNSET' WHERE dict_type=5006 AND raw_value='0';
 IF EXISTS(SELECT 1 FROM iw_local_scalar WHERE resolution IS NULL OR (resolution<>'UNSET' AND (lookup_id IS NULL OR name_snapshot IS NULL OR (dict_type=5002 AND match_kind NOT BETWEEN 1 AND 11)))) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: local scalar source missing or semantic kind unknown'; END IF;
 CREATE TEMPORARY TABLE iw_local_categories AS SELECT * FROM iw_local_scalar WHERE dict_type=5002;
 IF EXISTS(SELECT 1 FROM iw_local_scalar s JOIN iw_local_categories c ON c.business_type=s.business_type AND c.business_id=s.business_id
 JOIN iw_local_sources p ON p.id=s.parent_code WHERE s.dict_type=5006 AND s.resolution<>'UNSET' AND p.dict_code<>CAST(c.raw_value AS UNSIGNED)) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: local category/style mismatch'; END IF;
 CREATE TEMPORARY TABLE iw_local_tags(business_type VARCHAR(24),business_id INT UNSIGNED,owner_id INT UNSIGNED,dict_type INT,ordinal INT UNSIGNED,raw_value VARCHAR(128),source_id INT UNSIGNED NULL,name_snapshot VARCHAR(32) NULL,resolution VARCHAR(32) NULL,PRIMARY KEY(business_type,business_id,dict_type,ordinal));
 INSERT INTO iw_local_tags(business_type,business_id,owner_id,dict_type,ordinal,raw_value)
 SELECT 'ITEM',r.id,r.user_id,5004,tok.ordinal,tok.token FROM wardrobe_item r
 JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.scene_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 WHERE COALESCE(r.scene_tags,'')<>'';
 INSERT INTO iw_local_tags(business_type,business_id,owner_id,dict_type,ordinal,raw_value)
 SELECT 'ITEM',r.id,r.user_id,5005,tok.ordinal,tok.token FROM wardrobe_item r
 JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.style_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 WHERE COALESCE(r.style_tags,'')<>'';
 INSERT INTO iw_local_tags(business_type,business_id,owner_id,dict_type,ordinal,raw_value)
 SELECT 'OUTFIT',r.id,r.user_id,5004,tok.ordinal,tok.token FROM wardrobe_outfit r
 JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.scene_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 WHERE COALESCE(r.scene_tags,'')<>'';
 INSERT INTO iw_local_tags(business_type,business_id,owner_id,dict_type,ordinal,raw_value)
 SELECT 'OUTFIT',r.id,r.user_id,5005,tok.ordinal,tok.token FROM wardrobe_outfit r
 JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.style_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 WHERE COALESCE(r.style_tags,'')<>'';
 INSERT INTO iw_local_tags(business_type,business_id,owner_id,dict_type,ordinal,raw_value)
 SELECT 'WEAR_RECORD',r.id,r.user_id,5004,tok.ordinal,tok.token FROM wardrobe_wear_record r
 JOIN JSON_TABLE(CONCAT('[',REPLACE(JSON_QUOTE(COALESCE(r.scene_tags,'')),',','","'),']'),'$[*]' COLUMNS(ordinal FOR ORDINALITY,token VARCHAR(128) PATH '$')) tok
 WHERE COALESCE(r.scene_tags,'')<>'';
 IF EXISTS(SELECT 1 FROM iw_local_tags GROUP BY business_type,business_id,dict_type,CONVERT(raw_value USING binary) HAVING COUNT(*)>1) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: duplicate local tag tokens'; END IF;
 UPDATE iw_local_tags r LEFT JOIN iw_local_sources d ON d.user_id=r.owner_id AND d.dict_type=r.dict_type AND d.dict_code=CASE WHEN REGEXP_LIKE(r.raw_value,'^(0|[1-9][0-9]{0,4})$') THEN CAST(r.raw_value AS UNSIGNED) ELSE NULL END
 SET r.source_id=d.id,r.name_snapshot=CASE WHEN REGEXP_LIKE(r.raw_value,'^[0-9]+$') THEN d.dict_name ELSE r.raw_value END,
 r.resolution=IF(REGEXP_LIKE(r.raw_value,'^[0-9]+$'),'PERSONAL_CURRENT','LEGACY_LITERAL');
 IF EXISTS(SELECT 1 FROM iw_local_tags WHERE name_snapshot IS NULL OR CHAR_LENGTH(raw_value) NOT BETWEEN 1 AND 32 OR BINARY raw_value<>BINARY REGEXP_REPLACE(raw_value,@dict_trim_pattern,'')) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: unknown numeric/local tag source or noncanonical literal'; END IF;
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
 UPDATE wardrobe_item r JOIN iw_local_scalar c ON c.business_type='ITEM' AND c.business_id=r.id AND c.dict_type=5002
 SET r.category_source_id=c.source_id,r.category_name_snapshot=c.name_snapshot,r.category_match_kind_snapshot=c.match_kind;
 UPDATE wardrobe_item r JOIN iw_local_scalar s ON s.business_type='ITEM' AND s.business_id=r.id AND s.dict_type=5006
 SET r.item_style_source_id=s.source_id,r.item_style_name_snapshot=s.name_snapshot,r.dictionary_snapshot_status=1;
 UPDATE wardrobe_outfit_item r JOIN iw_local_scalar c ON c.business_type='OUTFIT_ITEM' AND c.business_id=r.id AND c.dict_type=5002
 SET r.category_source_id=c.source_id,r.category_name_snapshot=c.name_snapshot,r.category_match_kind_snapshot=c.match_kind;
 UPDATE wardrobe_outfit_item r JOIN iw_local_scalar s ON s.business_type='OUTFIT_ITEM' AND s.business_id=r.id AND s.dict_type=5006
 SET r.item_style_source_id=s.source_id,r.item_style_name_snapshot=s.name_snapshot,r.dictionary_snapshot_status=1;
 UPDATE wardrobe_wear_record_item r JOIN iw_local_scalar c ON c.business_type='WEAR_RECORD_ITEM' AND c.business_id=r.id AND c.dict_type=5002
 SET r.category_source_id=c.source_id,r.category_name_snapshot=c.name_snapshot,r.category_match_kind_snapshot=c.match_kind;
 UPDATE wardrobe_wear_record_item r JOIN iw_local_scalar s ON s.business_type='WEAR_RECORD_ITEM' AND s.business_id=r.id AND s.dict_type=5006
 SET r.item_style_source_id=s.source_id,r.item_style_name_snapshot=s.name_snapshot,r.dictionary_snapshot_status=1;
 INSERT INTO wardrobe_dictionary_migration_resolution(business_type,business_id,dict_type,ordinal,raw_value,owner_id,resolution,source_id,name_snapshot)
 SELECT business_type,business_id,dict_type,0,raw_value,owner_id,resolution,source_id,name_snapshot FROM iw_local_scalar;
 UPDATE wardrobe_item r JOIN iw_local_sources c ON c.user_id=r.user_id AND c.dict_type=5003 AND BINARY c.dict_name=BINARY r.color_name SET r.color_source_id=c.id WHERE r.color_name<>'';
 INSERT INTO wardrobe_dictionary_migration_resolution(business_type,business_id,dict_type,ordinal,raw_value,owner_id,resolution,source_id,name_snapshot)
 SELECT 'ITEM',id,5003,0,color_name,user_id,IF(color_name='','UNSET',IF(color_source_id IS NULL,'LEGACY_LITERAL','PERSONAL_TEXT')),color_source_id,color_name FROM wardrobe_item;
 INSERT INTO wardrobe_dictionary_tag_snapshot(business_type,business_id,dict_type,ordinal,source_id,name_snapshot)
 SELECT business_type,business_id,dict_type,ordinal,source_id,name_snapshot FROM iw_local_tags;
 INSERT INTO wardrobe_dictionary_migration_resolution(business_type,business_id,dict_type,ordinal,raw_value,owner_id,resolution,source_id,name_snapshot)
 SELECT business_type,business_id,dict_type,ordinal,raw_value,owner_id,resolution,source_id,name_snapshot FROM iw_local_tags;
 UPDATE wardrobe_outfit SET dictionary_snapshot_status=1;
 UPDATE wardrobe_wear_record SET dictionary_snapshot_status=1;
 UPDATE wardrobe_dictionary_migration_state SET phase='READY',updated_at=NOW() WHERE id=1;
 COMMIT;
 DROP TEMPORARY TABLE IF EXISTS iw_local_sources,iw_local_templates,iw_local_scalar,iw_local_categories,iw_local_tags,iw_wardrobe_new_scopes;
END$$
DELIMITER ;
CALL iw_wardrobe_local_backfill();
DROP PROCEDURE iw_wardrobe_local_backfill;
SELECT phase FROM wardrobe_dictionary_migration_state WHERE id=1;

-- MySQL 8; run only while affected writers are stopped.
-- Entire DML is atomic. Existing READY batches are not reparsed; execute 03-verify separately.
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_kitchen_backfill$$
CREATE PROCEDURE iw_kitchen_backfill()
main: BEGIN
 DECLARE migration_phase VARCHAR(24);
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
 SET @dict_trim_pattern = CONCAT('^[', CONVERT(0x090a0b0c0d1c1d1e1f20c285c2a0e19a80e28080e28081e28082e28083e28084e28085e28086e28087e28088e28089e2808ae280a8e280a9e280afe2819fe38080 USING utf8mb4), ']+|[', CONVERT(0x090a0b0c0d1c1d1e1f20c285c2a0e19a80e28080e28081e28082e28083e28084e28085e28086e28087e28088e28089e2808ae280a8e280a9e280afe2819fe38080 USING utf8mb4), ']+$');
 START TRANSACTION;
 SELECT phase INTO migration_phase FROM kitchen_dictionary_migration_state WHERE id=1 FOR UPDATE;
 IF migration_phase='READY' THEN COMMIT; LEAVE main; END IF;
 IF migration_phase IS NULL OR migration_phase<>'SCHEMA' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: migration phase invalid'; END IF;

 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type IN (3010,3011) AND
  (scope_type NOT IN (0,1) OR (scope_type=0 AND family_group_id<>0) OR (scope_type=1 AND (family_group_id=0 OR user_id<>0)))) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: invalid kitchen dictionary scope';
 END IF;
 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type IN (3010,3011) AND deleted=0 AND dict_code IS NOT NULL
  GROUP BY scope_type,family_group_id,user_id,dict_type,dict_code HAVING COUNT(*)>1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: duplicate kitchen legacy codes';
 END IF;
 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type IN (3010,3011) AND deleted=0
  GROUP BY scope_type,family_group_id,user_id,dict_type,CONVERT(dict_name USING binary) HAVING COUNT(*)>1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: duplicate kitchen names';
 END IF;
 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type IN (3010,3011) AND
  (CHAR_LENGTH(dict_name) NOT BETWEEN 1 AND 32 OR BINARY dict_name<>BINARY REGEXP_REPLACE(dict_name,@dict_trim_pattern,''))) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: noncanonical Unicode names';
 END IF;
 IF (SELECT COUNT(DISTINCT dict_type) FROM base_dict WHERE user_id=0 AND scope_type=0 AND family_group_id=0 AND dict_type IN (3010,3011) AND deleted=0 AND dict_status=1)<>2 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: system kitchen templates missing';
 END IF;
 IF EXISTS(SELECT 1 FROM eat_fridge_food r LEFT JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id
  AND d.dict_type=3010 AND d.dict_code=r.category AND d.deleted=0
  WHERE COALESCE(r.category,0)<>0 GROUP BY r.id HAVING COUNT(d.id)<>1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: eat_fridge_food.category source missing or ambiguous';
 END IF;
 IF EXISTS(SELECT 1 FROM eat_fridge_food r LEFT JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id
  AND d.dict_type=3011 AND d.dict_code=r.section AND d.deleted=0
  WHERE COALESCE(r.section,0)<>0 GROUP BY r.id HAVING COUNT(d.id)<>1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: eat_fridge_food.section source missing or ambiguous';
 END IF;

 INSERT INTO kitchen_dictionary_migration_baseline(business_type,business_id,owner_id,deleted,raw_snapshot)
 SELECT 'FOOD',id,user_id,deleted,JSON_OBJECT('category',category,'section',section) FROM eat_fridge_food;
 INSERT INTO kitchen_dictionary_migration_baseline(business_type,business_id,owner_id,deleted,raw_snapshot)
 SELECT 'DICT',id,user_id,deleted,JSON_OBJECT('parent_id',parent_id,'dict_type',dict_type,'dict_code',dict_code,'dict_name',dict_name,'dict_status',dict_status,'scope_type',scope_type,'family_group_id',family_group_id)
 FROM base_dict WHERE dict_type IN (3010,3011);

 CREATE TEMPORARY TABLE IF NOT EXISTS iw_kitchen_new_scopes(scope_type TINYINT NOT NULL,owner_id INT UNSIGNED NOT NULL,PRIMARY KEY(scope_type,owner_id));
 DELETE FROM iw_kitchen_new_scopes;
 INSERT INTO iw_kitchen_new_scopes
 SELECT 0,u.id FROM auth_user u WHERE u.deleted=0 AND u.id>0
 AND NOT EXISTS(SELECT 1 FROM base_dict d WHERE d.scope_type=0 AND d.family_group_id=0 AND d.user_id=u.id AND d.dict_type IN (3010,3011));
 INSERT INTO iw_kitchen_new_scopes
 SELECT 1,g.id FROM auth_family_group g WHERE g.deleted=0 AND g.status=1
 AND NOT EXISTS(SELECT 1 FROM base_dict d WHERE d.scope_type=1 AND d.family_group_id=g.id AND d.user_id=0 AND d.dict_type IN (3010,3011));

 INSERT INTO base_dict(parent_id,dict_type,dict_code,dict_name,dict_status,sort,user_id,scope_type,family_group_id,revision,deleted)
 SELECT 0,t.dict_type,t.dict_code,t.dict_name,1,t.sort,IF(s.scope_type=0,s.owner_id,0),s.scope_type,IF(s.scope_type=1,s.owner_id,0),0,0
 FROM iw_kitchen_new_scopes s CROSS JOIN base_dict t
 WHERE t.user_id=0 AND t.scope_type=0 AND t.family_group_id=0 AND t.dict_type IN (3010,3011) AND t.deleted=0 AND t.dict_status=1;

 INSERT INTO kitchen_dictionary_scope_state(scope_type,owner_id,scope_revision,initialized_at)
 SELECT 0,u.id,0,NOW() FROM auth_user u WHERE u.deleted=0 AND u.id>0
 AND NOT EXISTS(SELECT 1 FROM kitchen_dictionary_scope_state s WHERE s.scope_type=0 AND s.owner_id=u.id);
 INSERT INTO kitchen_dictionary_scope_state(scope_type,owner_id,scope_revision,initialized_at)
 SELECT 1,g.id,0,NOW() FROM auth_family_group g WHERE g.deleted=0 AND g.status=1
 AND NOT EXISTS(SELECT 1 FROM kitchen_dictionary_scope_state s WHERE s.scope_type=1 AND s.owner_id=g.id);

 UPDATE eat_fridge_food r
 LEFT JOIN base_dict c ON c.scope_type=0 AND c.family_group_id=0 AND c.user_id=r.user_id AND c.dict_type=3010 AND c.dict_code=r.category AND c.deleted=0 AND COALESCE(r.category,0)<>0
 LEFT JOIN base_dict s ON s.scope_type=0 AND s.family_group_id=0 AND s.user_id=r.user_id AND s.dict_type=3011 AND s.dict_code=r.section AND s.deleted=0 AND COALESCE(r.section,0)<>0
 SET r.category_source_id=c.id,r.category_name_snapshot=c.dict_name,
  r.section_source_id=s.id,r.section_name_snapshot=s.dict_name,
  r.dictionary_snapshot_status=1;

 INSERT INTO kitchen_dictionary_migration_resolution(business_type,business_id,dict_type,ordinal,raw_value,owner_id,resolution,source_id,name_snapshot)
 SELECT 'FOOD',r.id,3010,0,CAST(COALESCE(r.category,0) AS CHAR),r.user_id,
  IF(COALESCE(r.category,0)=0,'UNSET','PERSONAL_CURRENT'),r.category_source_id,r.category_name_snapshot FROM eat_fridge_food r
 UNION ALL
 SELECT 'FOOD',r.id,3011,0,CAST(COALESCE(r.section,0) AS CHAR),r.user_id,
  IF(COALESCE(r.section,0)=0,'UNSET','PERSONAL_CURRENT'),r.section_source_id,r.section_name_snapshot FROM eat_fridge_food r;

 UPDATE kitchen_dictionary_migration_state SET phase='READY',updated_at=NOW() WHERE id=1;
 COMMIT;
 DROP TEMPORARY TABLE IF EXISTS iw_kitchen_new_scopes;
END$$
DELIMITER ;
CALL iw_kitchen_backfill();
DROP PROCEDURE iw_kitchen_backfill;
SELECT phase FROM kitchen_dictionary_migration_state WHERE id=1;
-- MySQL 8; reviewed migration-time name baseline. Run only while kitchen/fridge writers are stopped.
-- Entire DML is atomic. Existing READY batches are not reparsed; execute 03-verify separately.
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_kitchen_backfill$$
CREATE PROCEDURE iw_kitchen_backfill()
main: BEGIN
 DECLARE migration_phase VARCHAR(24);
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
 START TRANSACTION;
 SELECT phase INTO migration_phase FROM kitchen_dictionary_migration_state WHERE id=1 FOR UPDATE;
 IF migration_phase='READY' THEN COMMIT; LEAVE main; END IF;
 IF migration_phase IS NULL OR migration_phase<>'SCHEMA' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: migration phase invalid'; END IF;

 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type IN (3010,3011) AND
 (scope_type NOT IN (0,1) OR (scope_type=0 AND family_group_id<>0) OR (scope_type=1 AND (family_group_id=0 OR user_id<>0)))) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: invalid dictionary scope'; END IF;
 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type IN (3010,3011) AND deleted=0
 GROUP BY scope_type,family_group_id,user_id,dict_type,dict_code HAVING COUNT(*)>1) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: duplicate legacy codes'; END IF;
 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type IN (3010,3011) AND deleted=0
 GROUP BY scope_type,family_group_id,user_id,dict_type,CONVERT(dict_name USING binary) HAVING COUNT(*)>1) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: duplicate names'; END IF;

 -- Ambiguous or missing non-empty category codes
 IF EXISTS(SELECT 1 FROM eat_fridge_food r LEFT JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id
 AND d.dict_type=3010 AND d.dict_code=r.category AND d.deleted=0
 WHERE r.category IS NOT NULL AND r.category<>0
 GROUP BY r.id HAVING COUNT(d.id)<>1) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: eat_fridge_food.category source missing or ambiguous'; END IF;
 IF EXISTS(SELECT 1 FROM eat_fridge_food r LEFT JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id
 AND d.dict_type=3011 AND d.dict_code=r.section AND d.deleted=0
 WHERE r.section IS NOT NULL AND r.section<>0
 GROUP BY r.id HAVING COUNT(d.id)<>1) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: eat_fridge_food.section source missing or ambiguous'; END IF;

 INSERT IGNORE INTO kitchen_dictionary_migration_baseline(business_type,business_id,owner_id,deleted,raw_snapshot)
 SELECT 'FOOD',id,user_id,deleted,JSON_OBJECT('category',category,'section',section,'name',name)
 FROM eat_fridge_food;

 INSERT INTO kitchen_dictionary_migration_resolution(business_type,business_id,dict_type,ordinal,raw_value,owner_id,resolution,source_id,name_snapshot)
 SELECT 'FOOD',r.id,3010,0,CAST(r.category AS CHAR),r.user_id,'RESOLVED',d.id,d.dict_name
 FROM eat_fridge_food r
 JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=3010 AND d.dict_code=r.category AND d.deleted=0
 WHERE r.category IS NOT NULL AND r.category<>0
 ON DUPLICATE KEY UPDATE resolution=VALUES(resolution),source_id=VALUES(source_id),name_snapshot=VALUES(name_snapshot);

 INSERT INTO kitchen_dictionary_migration_resolution(business_type,business_id,dict_type,ordinal,raw_value,owner_id,resolution,source_id,name_snapshot)
 SELECT 'FOOD',r.id,3011,0,CAST(r.section AS CHAR),r.user_id,'RESOLVED',d.id,d.dict_name
 FROM eat_fridge_food r
 JOIN base_dict d ON d.scope_type=0 AND d.family_group_id=0 AND d.user_id=r.user_id AND d.dict_type=3011 AND d.dict_code=r.section AND d.deleted=0
 WHERE r.section IS NOT NULL AND r.section<>0
 ON DUPLICATE KEY UPDATE resolution=VALUES(resolution),source_id=VALUES(source_id),name_snapshot=VALUES(name_snapshot);

 UPDATE eat_fridge_food r
 JOIN kitchen_dictionary_migration_resolution x ON x.business_type='FOOD' AND x.business_id=r.id AND x.dict_type=3010
 SET r.category_source_id=x.source_id,r.category_name_snapshot=x.name_snapshot
 WHERE r.category_source_id IS NULL AND x.source_id IS NOT NULL;

 UPDATE eat_fridge_food r
 JOIN kitchen_dictionary_migration_resolution x ON x.business_type='FOOD' AND x.business_id=r.id AND x.dict_type=3011
 SET r.section_source_id=x.source_id,r.section_name_snapshot=x.name_snapshot
 WHERE r.section_source_id IS NULL AND x.source_id IS NOT NULL;

 UPDATE eat_fridge_food SET dictionary_snapshot_status=1
 WHERE (category IS NULL OR category=0 OR category_source_id IS NOT NULL)
 AND (section IS NULL OR section=0 OR section_source_id IS NOT NULL);

 -- One-time scope init markers for owners that already have kitchen personal history
 INSERT IGNORE INTO kitchen_dictionary_scope_state(scope_type,owner_id,scope_revision,initialized_at)
 SELECT 0,user_id,0,NOW() FROM base_dict
 WHERE dict_type IN (3010,3011) AND scope_type=0 AND family_group_id=0 AND user_id>0
 GROUP BY user_id;

 INSERT IGNORE INTO kitchen_dictionary_scope_state(scope_type,owner_id,scope_revision,initialized_at)
 SELECT 1,family_group_id,0,NOW() FROM base_dict
 WHERE dict_type IN (3010,3011) AND scope_type=1 AND family_group_id>0
 GROUP BY family_group_id;

 -- Existing active families without family kitchen rows get template copy once
 INSERT IGNORE INTO kitchen_dictionary_scope_state(scope_type,owner_id,scope_revision,initialized_at)
 SELECT 1,g.id,0,NOW() FROM auth_family_group g
 WHERE g.deleted=0 AND g.status=1
 AND NOT EXISTS(SELECT 1 FROM kitchen_dictionary_scope_state s WHERE s.scope_type=1 AND s.owner_id=g.id)
 AND NOT EXISTS(SELECT 1 FROM base_dict d WHERE d.scope_type=1 AND d.family_group_id=g.id AND d.dict_type IN (3010,3011));

 INSERT INTO base_dict(parent_id,dict_type,dict_code,dict_name,dict_status,sort,user_id,scope_type,family_group_id,previous_id,revision,deleted,create_time,update_time)
 SELECT 0,t.dict_type,t.dict_code,t.dict_name,t.dict_status,t.sort,0,1,g.id,NULL,0,0,NOW(),NOW()
 FROM auth_family_group g
 JOIN base_dict t ON t.scope_type=0 AND t.family_group_id=0 AND t.user_id=0 AND t.dict_type IN (3010,3011) AND t.deleted=0 AND t.dict_status=1
 JOIN kitchen_dictionary_scope_state s ON s.scope_type=1 AND s.owner_id=g.id
 WHERE g.deleted=0 AND g.status=1
 AND NOT EXISTS(SELECT 1 FROM base_dict d WHERE d.scope_type=1 AND d.family_group_id=g.id AND d.dict_type IN (3010,3011));

 UPDATE kitchen_dictionary_migration_state SET phase='READY',updated_at=NOW() WHERE id=1;
 COMMIT;
END$$
DELIMITER ;
CALL iw_kitchen_backfill();
DROP PROCEDURE iw_kitchen_backfill;
SELECT 'BACKFILL_COMPLETE_OR_ALREADY_READY' AS result;

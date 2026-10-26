-- MySQL 8.0.21+; incremental schema only. Stop fridge/dictionary writers first.
-- DDL implicitly commits. Rerun checks definitions; do not use --force.
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_kitchen_add_column$$
CREATE PROCEDURE iw_kitchen_add_column(IN tab_name VARCHAR(64),IN col_name VARCHAR(64),IN col_ddl TEXT)
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=tab_name AND column_name=col_name) THEN
  SET @kitchen_ddl=CONCAT('ALTER TABLE `',tab_name,'` ADD COLUMN `',col_name,'` ',col_ddl);
  PREPARE kitchen_stmt FROM @kitchen_ddl; EXECUTE kitchen_stmt; DEALLOCATE PREPARE kitchen_stmt;
 ELSEIF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=tab_name AND column_name=col_name
  AND (LOWER(column_type)<>SUBSTRING_INDEX(LOWER(col_ddl),' ',IF(LOCATE(' UNSIGNED',UPPER(col_ddl))>0,2,1))
  OR (LOCATE('GENERATED ALWAYS',UPPER(col_ddl))>0 AND extra NOT LIKE '%STORED GENERATED%')
  OR is_nullable<>IF(LOCATE('NOT NULL',UPPER(col_ddl))>0,'NO','YES')
  OR NOT(column_default<=>IF(LOCATE('DEFAULT 0',UPPER(col_ddl))>0,'0',NULL)))) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: existing kitchen column definition differs';
 END IF;
END$$
DELIMITER ;

CALL iw_kitchen_add_column('base_dict','kitchen_scope_owner','INT UNSIGNED GENERATED ALWAYS AS (CASE WHEN dict_type IN (3010,3011) AND deleted=0 THEN CASE WHEN scope_type=0 THEN user_id ELSE family_group_id END ELSE NULL END) STORED');
CALL iw_kitchen_add_column('base_dict','kitchen_name_key','VARBINARY(128) GENERATED ALWAYS AS (CASE WHEN dict_type IN (3010,3011) AND deleted=0 THEN CONVERT(dict_name USING binary) ELSE NULL END) STORED');
CALL iw_kitchen_add_column('eat_fridge_food','category_source_id','INT UNSIGNED NULL');
CALL iw_kitchen_add_column('eat_fridge_food','category_name_snapshot','VARCHAR(32) NULL');
CALL iw_kitchen_add_column('eat_fridge_food','section_source_id','INT UNSIGNED NULL');
CALL iw_kitchen_add_column('eat_fridge_food','section_name_snapshot','VARCHAR(32) NULL');
CALL iw_kitchen_add_column('eat_fridge_food','dictionary_snapshot_status','TINYINT UNSIGNED NOT NULL DEFAULT 0');
DROP PROCEDURE iw_kitchen_add_column;

CREATE TABLE IF NOT EXISTS kitchen_dictionary_scope_state (
 scope_type TINYINT UNSIGNED NOT NULL,owner_id INT UNSIGNED NOT NULL,
 scope_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,initialized_at DATETIME NOT NULL,
 PRIMARY KEY(scope_type,owner_id),CONSTRAINT ck_kitchen_scope CHECK(scope_type IN (0,1) AND owner_id>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kitchen_dictionary_migration_state (
 id TINYINT NOT NULL,phase VARCHAR(24) NOT NULL,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(id),CONSTRAINT ck_kitchen_migration_id CHECK(id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kitchen_dictionary_migration_baseline (
 business_type VARCHAR(24) NOT NULL,business_id INT UNSIGNED NOT NULL,owner_id INT UNSIGNED NOT NULL,
 deleted TINYINT NOT NULL,raw_snapshot JSON NOT NULL,PRIMARY KEY(business_type,business_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kitchen_dictionary_migration_resolution (
 business_type VARCHAR(24) NOT NULL,business_id INT UNSIGNED NOT NULL,dict_type SMALLINT UNSIGNED NOT NULL,
 ordinal INT UNSIGNED NOT NULL DEFAULT 0,raw_value TEXT NOT NULL,owner_id INT UNSIGNED NOT NULL,
 resolution VARCHAR(32) NOT NULL,source_id INT UNSIGNED NULL,name_snapshot VARCHAR(32) NULL,
 resolved_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(business_type,business_id,dict_type,ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO kitchen_dictionary_migration_state(id,phase) VALUES(1,'SCHEMA');

DELIMITER $$
DROP PROCEDURE IF EXISTS iw_kitchen_index$$
CREATE PROCEDURE iw_kitchen_index(IN idx VARCHAR(64),IN expected_cols VARCHAR(255),IN is_non_unique TINYINT,IN def TEXT)
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='base_dict' AND index_name=idx) THEN
  SET @kitchen_index=CONCAT('ALTER TABLE base_dict ADD ',def);
  PREPARE kitchen_idx FROM @kitchen_index; EXECUTE kitchen_idx; DEALLOCATE PREPARE kitchen_idx;
 ELSEIF (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='base_dict' AND index_name=idx)<>expected_cols
  OR (SELECT MAX(non_unique) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='base_dict' AND index_name=idx)<>is_non_unique THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: existing kitchen index definition differs';
 END IF;
END$$
DELIMITER ;
CALL iw_kitchen_index('uk_kitchen_dict_name','dict_type,scope_type,kitchen_scope_owner,kitchen_name_key',0,'UNIQUE KEY uk_kitchen_dict_name(dict_type,scope_type,kitchen_scope_owner,kitchen_name_key)');
CALL iw_kitchen_index('idx_kitchen_dict_scope','scope_type,family_group_id,user_id,dict_type,deleted,dict_status,sort,id',1,'KEY idx_kitchen_dict_scope(scope_type,family_group_id,user_id,dict_type,deleted,dict_status,sort,id)');
DROP PROCEDURE iw_kitchen_index;

DELIMITER $$
DROP PROCEDURE IF EXISTS iw_kitchen_schema_assert$$
CREATE PROCEDURE iw_kitchen_schema_assert()
BEGIN
 IF VERSION() NOT LIKE '8.%' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: migration requires MySQL 8'; END IF;
 IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN
  ('base_dict','eat_fridge_food','kitchen_dictionary_scope_state','kitchen_dictionary_migration_state',
   'kitchen_dictionary_migration_baseline','kitchen_dictionary_migration_resolution') AND engine<>'InnoDB') THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: all migration tables must be InnoDB';
 END IF;
 IF (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='base_dict' AND index_name='uk_kitchen_dict_name')<>'dict_type,scope_type,kitchen_scope_owner,kitchen_name_key'
  OR (SELECT MAX(non_unique) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='base_dict' AND index_name='uk_kitchen_dict_name')<>0 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: kitchen unique key differs';
 END IF;
 IF (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='kitchen_dictionary_scope_state' AND index_name='PRIMARY')<>'scope_type,owner_id'
  OR (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='kitchen_dictionary_migration_baseline' AND index_name='PRIMARY')<>'business_type,business_id'
  OR (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='kitchen_dictionary_migration_resolution' AND index_name='PRIMARY')<>'business_type,business_id,dict_type,ordinal' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: kitchen audit primary keys differ';
 END IF;
END$$
DELIMITER ;
CALL iw_kitchen_schema_assert();
DROP PROCEDURE iw_kitchen_schema_assert;
SELECT 'SCHEMA_PRESENT_VERIFY_DEFINITIONS_BEFORE_BACKFILL' AS result;
-- MySQL 8.0.21+; incremental kitchen schema only. Stop kitchen/fridge writers first.
-- DDL implicitly commits. Rerun checks definitions; never use --force.
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_kitchen_add_column$$
CREATE PROCEDURE iw_kitchen_add_column(IN tab_name VARCHAR(64),IN col_name VARCHAR(64),IN col_ddl TEXT)
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=tab_name AND column_name=col_name) THEN
 SET @kitchen_ddl=CONCAT('ALTER TABLE `',tab_name,'` ADD COLUMN `',col_name,'` ',col_ddl);
 PREPARE kitchen_stmt FROM @kitchen_ddl; EXECUTE kitchen_stmt; DEALLOCATE PREPARE kitchen_stmt;
 ELSEIF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=tab_name AND column_name=col_name
 AND (LOWER(column_type)<>SUBSTRING_INDEX(LOWER(col_ddl),' ',IF(LOCATE(' UNSIGNED',UPPER(col_ddl))>0,2,1))
 OR (LOCATE('GENERATED ALWAYS',UPPER(col_ddl))>0 AND extra NOT LIKE '%STORED GENERATED%')
 OR is_nullable<>IF(LOCATE('NOT NULL',UPPER(col_ddl))>0,'NO','YES')
 OR NOT(column_default<=>IF(LOCATE('DEFAULT 0',UPPER(col_ddl))>0,'0',NULL)))) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: existing kitchen column definition differs';
 END IF;
END$$
DELIMITER ;
CALL iw_kitchen_add_column('base_dict','kitchen_scope_owner','INT UNSIGNED GENERATED ALWAYS AS (CASE WHEN dict_type IN (3010,3011) AND deleted=0 THEN CASE WHEN scope_type=0 THEN user_id ELSE family_group_id END ELSE NULL END) STORED');
CALL iw_kitchen_add_column('base_dict','kitchen_name_key','VARBINARY(128) GENERATED ALWAYS AS (CASE WHEN dict_type IN (3010,3011) AND deleted=0 THEN CONVERT(dict_name USING binary) ELSE NULL END) STORED');
CALL iw_kitchen_add_column('eat_fridge_food','category_source_id','INT UNSIGNED NULL');
CALL iw_kitchen_add_column('eat_fridge_food','category_name_snapshot','VARCHAR(32) NULL');
CALL iw_kitchen_add_column('eat_fridge_food','section_source_id','INT UNSIGNED NULL');
CALL iw_kitchen_add_column('eat_fridge_food','section_name_snapshot','VARCHAR(32) NULL');
CALL iw_kitchen_add_column('eat_fridge_food','dictionary_snapshot_status','TINYINT UNSIGNED NOT NULL DEFAULT 0');
CREATE TABLE IF NOT EXISTS kitchen_dictionary_scope_state (
 scope_type TINYINT UNSIGNED NOT NULL,owner_id INT UNSIGNED NOT NULL,
 scope_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,initialized_at DATETIME NOT NULL,
 PRIMARY KEY(scope_type,owner_id),CONSTRAINT ck_kitchen_scope CHECK(scope_type IN (0,1) AND owner_id>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS kitchen_dictionary_migration_baseline (
 business_type VARCHAR(24) NOT NULL,business_id INT UNSIGNED NOT NULL,owner_id INT UNSIGNED NOT NULL,
 deleted TINYINT NOT NULL,raw_snapshot JSON NOT NULL,PRIMARY KEY(business_type,business_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS kitchen_dictionary_migration_resolution (
 business_type VARCHAR(24) NOT NULL,business_id INT UNSIGNED NOT NULL,dict_type SMALLINT UNSIGNED NOT NULL,
 ordinal INT UNSIGNED NOT NULL DEFAULT 0,raw_value TEXT NOT NULL,owner_id INT UNSIGNED NOT NULL,
 resolution VARCHAR(32) NOT NULL,source_id INT UNSIGNED NULL,name_snapshot VARCHAR(32) NULL,
 resolved_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(business_type,business_id,dict_type,ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS kitchen_dictionary_migration_state (
 id TINYINT NOT NULL,phase VARCHAR(24) NOT NULL,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(id),CONSTRAINT ck_kitchen_migration_id CHECK(id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO kitchen_dictionary_migration_state(id,phase) VALUES(1,'SCHEMA');
DROP PROCEDURE iw_kitchen_add_column;
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_kitchen_index$$
CREATE PROCEDURE iw_kitchen_index(IN idx VARCHAR(64),IN expected_cols VARCHAR(255),IN is_non_unique TINYINT,IN def TEXT)
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='base_dict' AND index_name=idx) THEN
 SET @kitchen_index=CONCAT('ALTER TABLE base_dict ADD ',def);
 PREPARE kitchen_idx FROM @kitchen_index; EXECUTE kitchen_idx; DEALLOCATE PREPARE kitchen_idx;
 ELSEIF (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='base_dict' AND index_name=idx)<>expected_cols
 OR (SELECT MAX(non_unique) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='base_dict' AND index_name=idx)<>is_non_unique THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: existing kitchen index definition differs'; END IF;
END$$
DELIMITER ;
CALL iw_kitchen_index('uk_kitchen_dict_name','dict_type,scope_type,kitchen_scope_owner,kitchen_name_key',0,'UNIQUE KEY uk_kitchen_dict_name(dict_type,scope_type,kitchen_scope_owner,kitchen_name_key)');
CALL iw_kitchen_index('idx_kitchen_dict_scope','scope_type,family_group_id,user_id,dict_type,deleted,dict_status,sort,id',1,'KEY idx_kitchen_dict_scope(scope_type,family_group_id,user_id,dict_type,deleted,dict_status,sort,id)');
DROP PROCEDURE iw_kitchen_index;
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_kitchen_schema_assert$$
CREATE PROCEDURE iw_kitchen_schema_assert()
BEGIN
 IF VERSION() NOT LIKE '8.%' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: migration requires MySQL 8'; END IF;
 IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN
 ('base_dict','eat_fridge_food','kitchen_dictionary_scope_state','kitchen_dictionary_migration_baseline','kitchen_dictionary_migration_resolution','kitchen_dictionary_migration_state') AND engine<>'InnoDB') THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: all migration tables must be InnoDB'; END IF;
 IF (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='kitchen_dictionary_scope_state' AND index_name='PRIMARY')<>'scope_type,owner_id'
 OR (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='kitchen_dictionary_migration_baseline' AND index_name='PRIMARY')<>'business_type,business_id'
 OR (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='kitchen_dictionary_migration_resolution' AND index_name='PRIMARY')<>'business_type,business_id,dict_type,ordinal'
 OR (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='kitchen_dictionary_migration_state' AND index_name='PRIMARY')<>'id' THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: kitchen migration primary keys differ'; END IF;
END$$
DELIMITER ;
CALL iw_kitchen_schema_assert();
DROP PROCEDURE iw_kitchen_schema_assert;
SELECT 'SCHEMA_PRESENT_VERIFY_DEFINITIONS_BEFORE_BACKFILL' AS result;

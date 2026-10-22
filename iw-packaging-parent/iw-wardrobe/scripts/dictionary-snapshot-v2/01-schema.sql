-- MySQL 8.0.21+; incremental schema only. Stop wardrobe/dictionary writers first.
-- DDL implicitly commits. Rerun checks definitions; never use full wardrobe init here.
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_wardrobe_add_column$$
CREATE PROCEDURE iw_wardrobe_add_column(IN tab_name VARCHAR(64),IN col_name VARCHAR(64),IN col_ddl TEXT)
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=tab_name AND column_name=col_name) THEN
 SET @wardrobe_ddl=CONCAT('ALTER TABLE `',tab_name,'` ADD COLUMN `',col_name,'` ',col_ddl);
 PREPARE wardrobe_stmt FROM @wardrobe_ddl; EXECUTE wardrobe_stmt; DEALLOCATE PREPARE wardrobe_stmt;
 ELSEIF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=tab_name AND column_name=col_name
 AND (LOWER(column_type)<>SUBSTRING_INDEX(LOWER(col_ddl),' ',IF(LOCATE(' UNSIGNED',UPPER(col_ddl))>0,2,1))
 OR (LOCATE('GENERATED ALWAYS',UPPER(col_ddl))>0 AND extra NOT LIKE '%STORED GENERATED%')
 OR is_nullable<>IF(LOCATE('NOT NULL',UPPER(col_ddl))>0,'NO','YES')
 OR NOT(column_default<=>IF(LOCATE('DEFAULT 0',UPPER(col_ddl))>0,'0',NULL)))) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: existing wardrobe column definition differs';
 END IF;
END$$
DELIMITER ;
CALL iw_wardrobe_add_column('base_dict','wardrobe_category_lineage_id','INT UNSIGNED NULL');
CALL iw_wardrobe_add_column('base_dict','wardrobe_match_kind','TINYINT UNSIGNED NULL');
CALL iw_wardrobe_add_column('base_dict','wardrobe_parent_key','INT UNSIGNED GENERATED ALWAYS AS (CASE WHEN dict_type=5006 THEN COALESCE(wardrobe_category_lineage_id,0) ELSE 0 END) STORED');
CALL iw_wardrobe_add_column('base_dict','wardrobe_name_key','VARBINARY(128) GENERATED ALWAYS AS (CASE WHEN dict_type BETWEEN 5002 AND 5006 AND deleted=0 AND (dict_type<>5006 OR wardrobe_category_lineage_id>0) THEN CONVERT(dict_name USING binary) ELSE NULL END) STORED');
CALL iw_wardrobe_add_column('wardrobe_item','category_source_id','INT UNSIGNED NULL');
CALL iw_wardrobe_add_column('wardrobe_item','category_name_snapshot','VARCHAR(32) NULL');
CALL iw_wardrobe_add_column('wardrobe_item','category_match_kind_snapshot','TINYINT UNSIGNED NULL');
CALL iw_wardrobe_add_column('wardrobe_item','item_style_source_id','INT UNSIGNED NULL');
CALL iw_wardrobe_add_column('wardrobe_item','item_style_name_snapshot','VARCHAR(32) NULL');
CALL iw_wardrobe_add_column('wardrobe_outfit_item','category_source_id','INT UNSIGNED NULL');
CALL iw_wardrobe_add_column('wardrobe_outfit_item','category_name_snapshot','VARCHAR(32) NULL');
CALL iw_wardrobe_add_column('wardrobe_outfit_item','category_match_kind_snapshot','TINYINT UNSIGNED NULL');
CALL iw_wardrobe_add_column('wardrobe_outfit_item','item_style_source_id','INT UNSIGNED NULL');
CALL iw_wardrobe_add_column('wardrobe_outfit_item','item_style_name_snapshot','VARCHAR(32) NULL');
CALL iw_wardrobe_add_column('wardrobe_wear_record_item','category_source_id','INT UNSIGNED NULL');
CALL iw_wardrobe_add_column('wardrobe_wear_record_item','category_name_snapshot','VARCHAR(32) NULL');
CALL iw_wardrobe_add_column('wardrobe_wear_record_item','category_match_kind_snapshot','TINYINT UNSIGNED NULL');
CALL iw_wardrobe_add_column('wardrobe_wear_record_item','item_style_source_id','INT UNSIGNED NULL');
CALL iw_wardrobe_add_column('wardrobe_wear_record_item','item_style_name_snapshot','VARCHAR(32) NULL');
CALL iw_wardrobe_add_column('wardrobe_item','color_source_id','INT UNSIGNED NULL');
CALL iw_wardrobe_add_column('wardrobe_item','dictionary_snapshot_status','TINYINT UNSIGNED NOT NULL DEFAULT 0');
CALL iw_wardrobe_add_column('wardrobe_outfit','dictionary_snapshot_status','TINYINT UNSIGNED NOT NULL DEFAULT 0');
CALL iw_wardrobe_add_column('wardrobe_outfit_item','dictionary_snapshot_status','TINYINT UNSIGNED NOT NULL DEFAULT 0');
CALL iw_wardrobe_add_column('wardrobe_wear_record','dictionary_snapshot_status','TINYINT UNSIGNED NOT NULL DEFAULT 0');
CALL iw_wardrobe_add_column('wardrobe_wear_record_item','dictionary_snapshot_status','TINYINT UNSIGNED NOT NULL DEFAULT 0');
CREATE TABLE IF NOT EXISTS wardrobe_dictionary_scope_state (
 scope_type TINYINT UNSIGNED NOT NULL,owner_id INT UNSIGNED NOT NULL,
 scope_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,initialized_at DATETIME NOT NULL,
 PRIMARY KEY(scope_type,owner_id),CONSTRAINT ck_wardrobe_scope CHECK(scope_type IN (0,1) AND owner_id>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS wardrobe_dictionary_tag_snapshot (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,business_type VARCHAR(16) NOT NULL,business_id INT UNSIGNED NOT NULL,
 dict_type SMALLINT UNSIGNED NOT NULL,ordinal INT UNSIGNED NOT NULL,source_id INT UNSIGNED NULL,
 name_snapshot VARCHAR(32) NOT NULL,
 PRIMARY KEY(id),UNIQUE KEY uk_wardrobe_tag_ordinal(business_type,business_id,dict_type,ordinal),KEY idx_wardrobe_tag_source(source_id),
 CONSTRAINT ck_wardrobe_tag CHECK(ordinal>0 AND ((business_type IN ('ITEM','OUTFIT') AND dict_type IN (5004,5005)) OR (business_type='WEAR_RECORD' AND dict_type=5004)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE IF NOT EXISTS wardrobe_dictionary_migration_baseline (
 business_type VARCHAR(24) NOT NULL,business_id INT UNSIGNED NOT NULL,owner_id INT UNSIGNED NOT NULL,
 deleted TINYINT NOT NULL,raw_snapshot JSON NOT NULL,PRIMARY KEY(business_type,business_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS wardrobe_dictionary_migration_resolution (
 business_type VARCHAR(24) NOT NULL,business_id INT UNSIGNED NOT NULL,dict_type SMALLINT UNSIGNED NOT NULL,
 ordinal INT UNSIGNED NOT NULL DEFAULT 0,raw_value TEXT NOT NULL,owner_id INT UNSIGNED NOT NULL,
 resolution VARCHAR(32) NOT NULL,source_id INT UNSIGNED NULL,name_snapshot VARCHAR(32) NULL,
 resolved_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(business_type,business_id,dict_type,ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS wardrobe_dictionary_migration_state (
 id TINYINT NOT NULL,phase VARCHAR(24) NOT NULL,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(id),CONSTRAINT ck_wardrobe_migration_id CHECK(id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO wardrobe_dictionary_migration_state(id,phase) VALUES(1,'SCHEMA');
DROP PROCEDURE iw_wardrobe_add_column;
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_wardrobe_index$$
CREATE PROCEDURE iw_wardrobe_index(IN idx VARCHAR(64),IN expected_cols VARCHAR(255),IN is_non_unique TINYINT,IN def TEXT)
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='base_dict' AND index_name=idx) THEN
 SET @wardrobe_index=CONCAT('ALTER TABLE base_dict ADD ',def);
 PREPARE wardrobe_idx FROM @wardrobe_index; EXECUTE wardrobe_idx; DEALLOCATE PREPARE wardrobe_idx;
 ELSEIF (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='base_dict' AND index_name=idx)<>expected_cols
 OR (SELECT MAX(non_unique) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='base_dict' AND index_name=idx)<>is_non_unique THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: existing wardrobe index definition differs'; END IF;
END$$
DELIMITER ;
CALL iw_wardrobe_index('uk_wardrobe_dict_name','scope_type,family_group_id,user_id,dict_type,wardrobe_parent_key,wardrobe_name_key',0,'UNIQUE KEY uk_wardrobe_dict_name(scope_type,family_group_id,user_id,dict_type,wardrobe_parent_key,wardrobe_name_key)');
CALL iw_wardrobe_index('idx_wardrobe_dict_scope','scope_type,family_group_id,user_id,dict_type,wardrobe_category_lineage_id,deleted,dict_status,sort,id',1,'KEY idx_wardrobe_dict_scope(scope_type,family_group_id,user_id,dict_type,wardrobe_category_lineage_id,deleted,dict_status,sort,id)');
DROP PROCEDURE iw_wardrobe_index;
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_wardrobe_schema_assert$$
CREATE PROCEDURE iw_wardrobe_schema_assert()
BEGIN
 IF VERSION() NOT LIKE '8.%' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: migration requires MySQL 8'; END IF;
 IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN
 ('base_dict','wardrobe_item','wardrobe_outfit','wardrobe_outfit_item','wardrobe_wear_record','wardrobe_wear_record_item',
 'wardrobe_dictionary_scope_state','wardrobe_dictionary_tag_snapshot','wardrobe_dictionary_migration_baseline','wardrobe_dictionary_migration_resolution','wardrobe_dictionary_migration_state') AND engine<>'InnoDB') THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: all migration tables must be InnoDB'; END IF;
 IF (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wardrobe_dictionary_scope_state' AND index_name='PRIMARY')<>'scope_type,owner_id'
 OR (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wardrobe_dictionary_tag_snapshot' AND index_name='uk_wardrobe_tag_ordinal')<>'business_type,business_id,dict_type,ordinal'
 OR (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wardrobe_dictionary_tag_snapshot' AND index_name='uk_wardrobe_tag_ordinal' AND non_unique=0)<>4 THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: missing/incorrect scope or tag uniqueness'; END IF;
 IF (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wardrobe_dictionary_migration_baseline' AND index_name='PRIMARY')<>'business_type,business_id'
 OR (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wardrobe_dictionary_migration_resolution' AND index_name='PRIMARY')<>'business_type,business_id,dict_type,ordinal'
 OR (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wardrobe_dictionary_migration_state' AND index_name='PRIMARY')<>'id' THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: migration audit primary keys differ'; END IF;
 IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='wardrobe_dictionary_migration_baseline' AND column_name='raw_snapshot' AND data_type='json' AND is_nullable='NO')<>1
 OR (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='wardrobe_dictionary_scope_state' AND column_name='scope_revision' AND column_type='bigint unsigned' AND is_nullable='NO')<>1
 OR (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='wardrobe_dictionary_tag_snapshot' AND column_name='source_id' AND column_type='int unsigned' AND is_nullable='YES')<>1 THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: audit/scope/snapshot column definitions differ'; END IF;
 IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='wardrobe_dictionary_tag_snapshot' AND column_name='name_snapshot' AND collation_name='utf8mb4_0900_bin')<>1 THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: snapshot tag name collation differs'; END IF;
END$$
DELIMITER ;
CALL iw_wardrobe_schema_assert();
DROP PROCEDURE iw_wardrobe_schema_assert;
SELECT 'SCHEMA_PRESENT_VERIFY_DEFINITIONS_BEFORE_BACKFILL' AS result;

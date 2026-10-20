-- Incremental migration; explicit execution authorization required. MySQL 8.0.21+, InnoDB, utf8mb4.
-- Select the single iw-core database explicitly. Stop old writers and scheduled jobs first.
SET NAMES utf8mb4;
SET @dict_trim_pattern = CONCAT('^[', CONVERT(0x090a0b0c0d1c1d1e1f20c285c2a0e19a80e28080e28081e28082e28083e28084e28085e28086e28087e28088e28089e2808ae280a8e280a9e280afe2819fe38080 USING utf8mb4), ']+|[', CONVERT(0x090a0b0c0d1c1d1e1f20c285c2a0e19a80e28080e28081e28082e28083e28084e28085e28086e28087e28088e28089e2808ae280a8e280a9e280afe2819fe38080 USING utf8mb4), ']+$');

DELIMITER $$
DROP PROCEDURE IF EXISTS iw_dict_v2_schema$$
CREATE PROCEDURE iw_dict_v2_schema()
BEGIN
 IF DATABASE() IS NULL OR VERSION() LIKE '%MariaDB%' OR
 CAST(SUBSTRING_INDEX(VERSION(),'.',1) AS UNSIGNED)<>8 OR
 (CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'.',2),'.',-1) AS UNSIGNED)=0 AND
 CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'.',3),'.',-1) AS UNSIGNED)<21) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: supported MySQL version is 8.0.21+ (8.x); select database'; END IF;
 IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN
 ('base_dict','base_dict_business_relation','bookkeeping_records','bookkeeping_wallet','bookkeeping_budget','bookkeeping_actions',
 'auth_user','auth_family_group','auth_family_member','points_task_plan','points_task_basics','points_task_bookkeeping'))<>12 THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: required tables missing; do not run full init on an existing database'; END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bookkeeping_records' AND column_name='expense_user_id')
 OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='points_task_plan' AND column_name='bookkeeping_template')
 OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='points_task_bookkeeping' AND column_name='template_snapshot')
 OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='points_task_basics' AND column_name='completion_reward_points')
 OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bookkeeping_records' AND column_name='share_state')
 OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='points_task_basics' AND column_name='source_request_id')
 OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='points_task_basics' AND column_name='scheduled_plan_date') THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: missing payer/family/task prerequisite migration; read runbook'; END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bookkeeping_records' AND column_name='group_id')
 OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='auth_user' AND column_name='family_group_id')
 OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='auth_family_member' AND column_name='role') THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: missing family prerequisite columns'; END IF;
 IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN
 ('base_dict','base_dict_business_relation','bookkeeping_records','bookkeeping_wallet','bookkeeping_budget','bookkeeping_actions','auth_user','auth_family_group','auth_family_member','points_task_plan','points_task_basics','points_task_bookkeeping') AND engine<>'InnoDB') THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: participating tables must use InnoDB'; END IF;
 IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='base_dict' AND column_name='dict_name' AND character_set_name<>'utf8mb4') THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: base_dict.dict_name must be utf8mb4; review explicit charset migration'; END IF;
 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type IN (4001,4002,4011) AND
 (CHAR_LENGTH(REGEXP_REPLACE(dict_name,@dict_trim_pattern,'')) NOT BETWEEN 1 AND 32 OR BINARY dict_name<>BINARY REGEXP_REPLACE(dict_name,@dict_trim_pattern,''))) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: noncanonical names require reviewed data repair; no automatic normalization'; END IF;
 IF EXISTS(SELECT 1 FROM bookkeeping_records WHERE record_category IS NULL OR record_category NOT IN (1,2)) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: invalid historical bill category; review 00-preflight output'; END IF;
 IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE()
  AND table_name='bookkeeping_dictionary_migration_baseline') THEN
  IF (SELECT COUNT(*) FROM bookkeeping_dictionary_migration_baseline)<>4 OR
   (SELECT COUNT(*) FROM bookkeeping_dictionary_migration_baseline WHERE metric IN ('records','wallet','relations','other_dict'))<>4 THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: existing migration baseline incomplete; never regenerate missing metrics';
  END IF;
 END IF;
 -- The fresh-schema and already-partially-migrated paths share the same source rule.
 SET @pending=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='bookkeeping_records' AND column_name='dictionary_snapshot_status'),'r.dictionary_snapshot_status=0','1=1');
 SET @personal=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='base_dict' AND column_name='scope_type'),' AND d.scope_type=0 AND d.family_group_id=0','');
 SET @query=CONCAT('SELECT COUNT(*) INTO @bad_sources FROM (SELECT r.id FROM bookkeeping_records r LEFT JOIN base_dict d ON d.user_id=r.user_id AND d.dict_type=4002 AND d.dict_code=r.record_type',
  @personal,' WHERE ',@pending,' GROUP BY r.id,r.record_type HAVING COUNT(d.id)>1 OR (COALESCE(r.record_type,0)<>0 AND COUNT(d.id)=0)) bad');
 PREPARE dict_guard FROM @query; EXECUTE dict_guard; DEALLOCATE PREPARE dict_guard;
 IF @bad_sources<>0 THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: historical category source missing/ambiguous; review 00-preflight output'; END IF;
END$$
DROP PROCEDURE IF EXISTS iw_dict_v2_add_column$$
CREATE PROCEDURE iw_dict_v2_add_column(IN tbl VARCHAR(64), IN col VARCHAR(64), IN definition TEXT)
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=tbl AND column_name=col) THEN
 SET @ddl=CONCAT('ALTER TABLE `',tbl,'` ADD COLUMN `',col,'` ',definition);
 PREPARE dict_ddl FROM @ddl; EXECUTE dict_ddl; DEALLOCATE PREPARE dict_ddl; END IF;
END$$
DROP PROCEDURE IF EXISTS iw_dict_v2_add_index$$
CREATE PROCEDURE iw_dict_v2_add_index(IN tbl VARCHAR(64),IN idx VARCHAR(64),IN definition TEXT)
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=tbl AND index_name=idx) THEN
 SET @ddl=CONCAT('ALTER TABLE `',tbl,'` ADD ',definition);
 PREPARE dict_ddl FROM @ddl; EXECUTE dict_ddl; DEALLOCATE PREPARE dict_ddl; END IF;
END$$
DELIMITER ;
CALL iw_dict_v2_schema();
CALL iw_dict_v2_add_column('base_dict','scope_type','TINYINT NOT NULL DEFAULT 0');
CALL iw_dict_v2_add_column('base_dict','family_group_id','INT UNSIGNED NOT NULL DEFAULT 0');
CALL iw_dict_v2_add_column('base_dict','previous_id','INT UNSIGNED NULL');
CALL iw_dict_v2_add_column('base_dict','revision','INT UNSIGNED NOT NULL DEFAULT 0');
CALL iw_dict_v2_add_column('base_dict','active_name_key','VARBINARY(128) GENERATED ALWAYS AS (CASE WHEN dict_type IN (4001,4002,4011) AND deleted=0 THEN CONVERT(dict_name USING binary) ELSE NULL END) STORED');
-- Duplicate names STOP with index error. Never use IGNORE/DELETE to make this succeed.
CALL iw_dict_v2_add_index('base_dict','uk_bookkeeping_dict_name','UNIQUE KEY uk_bookkeeping_dict_name(scope_type,family_group_id,user_id,dict_type,active_name_key)');
CALL iw_dict_v2_add_index('base_dict','uk_dict_previous','UNIQUE KEY uk_dict_previous(previous_id)');
CALL iw_dict_v2_add_index('base_dict','idx_bookkeeping_scope','KEY idx_bookkeeping_scope(scope_type,family_group_id,user_id,dict_type,deleted)');
CALL iw_dict_v2_add_column('bookkeeping_records','record_type_id','INT UNSIGNED NULL');
CALL iw_dict_v2_add_column('bookkeeping_records','record_type_name','VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL');
CALL iw_dict_v2_add_column('bookkeeping_records','dictionary_snapshot_status','TINYINT NOT NULL DEFAULT 0');
CALL iw_dict_v2_add_column('bookkeeping_budget','record_type_id','INT UNSIGNED NULL');
CALL iw_dict_v2_add_column('bookkeeping_budget','record_type_name','VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL');
CALL iw_dict_v2_add_column('bookkeeping_actions','record_type_id','INT UNSIGNED NULL');
CREATE TABLE IF NOT EXISTS bookkeeping_record_tag_snapshot (
 id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,record_id INT UNSIGNED NOT NULL,
 dict_type SMALLINT UNSIGNED NOT NULL,source_dict_id INT UNSIGNED NOT NULL,
 tag_name VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 UNIQUE KEY uk_record_tag_source(record_id,dict_type,source_dict_id),
 KEY idx_record_tag_type(record_id,dict_type),
 CONSTRAINT ck_bookkeeping_tag_type CHECK (dict_type IN (4001,4011))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS bookkeeping_family_dict_state (
 family_group_id INT UNSIGNED PRIMARY KEY,initialized_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
-- This baseline is created once and not overwritten on a rerun. Compare during the same stop-write window.
CREATE TABLE IF NOT EXISTS bookkeeping_dictionary_migration_baseline (
 metric VARCHAR(64) CHARACTER SET ascii PRIMARY KEY, row_count BIGINT UNSIGNED NOT NULL,
 amount DECIMAL(30,2) NULL, fingerprint BIGINT UNSIGNED NOT NULL, captured_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
INSERT INTO bookkeeping_dictionary_migration_baseline(metric,row_count,amount,fingerprint)
 SELECT 'records',COUNT(*),COALESCE(SUM(amount),0),BIT_XOR(CRC32(CONCAT_WS('|',id,user_id,COALESCE(expense_user_id,'NULL'),record_category,amount,record_date,group_id,share_state,deleted))) FROM bookkeeping_records
 ON DUPLICATE KEY UPDATE metric=metric;
INSERT INTO bookkeeping_dictionary_migration_baseline(metric,row_count,amount,fingerprint)
 SELECT 'wallet',COUNT(*),COALESCE(SUM(wallet_balance),0),BIT_XOR(CRC32(CONCAT_WS('|',id,user_id,wallet_balance,wallet_assets,deleted))) FROM bookkeeping_wallet
 ON DUPLICATE KEY UPDATE metric=metric;
INSERT INTO bookkeeping_dictionary_migration_baseline(metric,row_count,amount,fingerprint)
 SELECT 'relations',COUNT(*),NULL,BIT_XOR(CRC32(CONCAT_WS('|',id,business_type,business_id,dict_id))) FROM base_dict_business_relation
 ON DUPLICATE KEY UPDATE metric=metric;
INSERT INTO bookkeeping_dictionary_migration_baseline(metric,row_count,amount,fingerprint)
 SELECT 'other_dict',COUNT(*),NULL,BIT_XOR(CRC32(CONCAT_WS('|',id,user_id,dict_type,dict_code,dict_name,dict_status,sort,deleted))) FROM base_dict WHERE dict_type NOT IN (4001,4002,4011)
 ON DUPLICATE KEY UPDATE metric=metric;
DROP PROCEDURE iw_dict_v2_schema;
DROP PROCEDURE iw_dict_v2_add_column;
DROP PROCEDURE iw_dict_v2_add_index;
SELECT * FROM bookkeeping_dictionary_migration_baseline ORDER BY metric;

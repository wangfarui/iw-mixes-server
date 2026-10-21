-- Requires reviewed backup and stopped writers. MySQL DDL commits; keep stop-write until 02 passes.
-- Explicit opt-in is session-local: SET @iw_mtype_migration_approved=1; then SOURCE this file.
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_mtype_migrate$$
CREATE PROCEDURE iw_mtype_migrate()
BEGIN
 IF COALESCE(@iw_mtype_migration_approved,0)<>1 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: backup, target and stop-write approval required';
 END IF;
 IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='base_dict'
     AND column_name IN ('scope_type','family_group_id','previous_id','revision'))<>4 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: dictionary-v2 schema must be installed first';
 END IF;
 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type=4004 AND (scope_type<>0 OR family_group_id<>0)) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: unexpected family membership sources; review manually';
 END IF;
 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type=4004 AND deleted=0 AND
     (dict_name IS NULL OR CHAR_LENGTH(dict_name) NOT BETWEEN 1 AND 32 OR BINARY dict_name<>BINARY TRIM(dict_name))) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: normalize invalid membership names manually';
 END IF;
 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type=4004 AND deleted=0
     GROUP BY scope_type,family_group_id,user_id,HEX(dict_name) HAVING COUNT(*)>1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: duplicate current membership names';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bookkeeping_membership_subscription' AND column_name='membership_type_id') THEN
  ALTER TABLE bookkeeping_membership_subscription ADD COLUMN membership_type_id INT UNSIGNED NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bookkeeping_membership_subscription' AND column_name='membership_type_name') THEN
  ALTER TABLE bookkeeping_membership_subscription ADD COLUMN membership_type_name VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bookkeeping_membership_subscription' AND column_name='membership_type_snapshot_status') THEN
  ALTER TABLE bookkeeping_membership_subscription ADD COLUMN membership_type_snapshot_status TINYINT NOT NULL DEFAULT 0;
 END IF;
 -- A separate key preserves the released dictionary-v2 generated expression unchanged.
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='base_dict' AND column_name='membership_active_name_key') THEN
  ALTER TABLE base_dict ADD COLUMN membership_active_name_key VARBINARY(128) GENERATED ALWAYS AS
    (CASE WHEN dict_type=4004 AND deleted=0 THEN CONVERT(dict_name USING binary) ELSE NULL END) STORED;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='base_dict' AND index_name='uk_membership_dict_name') THEN
  ALTER TABLE base_dict ADD UNIQUE KEY uk_membership_dict_name(scope_type,family_group_id,user_id,dict_type,membership_active_name_key);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='bookkeeping_membership_subscription' AND index_name='idx_membership_type_snapshot') THEN
  ALTER TABLE bookkeeping_membership_subscription ADD KEY idx_membership_type_snapshot(user_id,deleted,membership_type_snapshot_status,membership_type_name);
 END IF;
 CREATE TABLE IF NOT EXISTS bookkeeping_membership_migration_baseline (
   id INT UNSIGNED PRIMARY KEY,user_id INT UNSIGNED NOT NULL,original_row JSON NOT NULL
 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
 -- Capture once. Reruns must not fill missing baseline rows and thereby hide intervening writes.
 IF NOT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='bookkeeping_membership_migration_state') THEN
  CREATE TABLE bookkeeping_membership_migration_state (id TINYINT PRIMARY KEY,captured_at DATETIME NOT NULL) ENGINE=InnoDB;
  INSERT INTO bookkeeping_membership_migration_baseline
  SELECT id,user_id,JSON_OBJECT('id',id,'userId',user_id,'type',membership_type,'name',membership_name,
    'amount',amount,'billingCycle',billing_cycle,'cycleNum',cycle_num,'cycleUnit',cycle_unit,
    'startDate',start_date,'endDate',end_date,'autoRenew',auto_renew,'payWay',pay_way,
    'remindDays',remind_days,'remark',remark,'deleted',deleted,'createTime',create_time,'updateTime',update_time)
  FROM bookkeeping_membership_subscription;
  INSERT INTO bookkeeping_membership_migration_state VALUES(1,NOW());
 END IF;
END$$
DELIMITER ;
CALL iw_mtype_migrate();
DROP PROCEDURE iw_mtype_migrate;
-- Only uniquely provable, still-present personal sources with positive legacy codes.
-- Count ALL versions, including deleted rows: deleted/reused/ambiguous codes stay pending.
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_mtype_backfill$$
CREATE PROCEDURE iw_mtype_backfill()
BEGIN
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
 IF (SELECT COUNT(*) FROM bookkeeping_membership_migration_state WHERE id=1)<>1 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: baseline capture incomplete';
 END IF;
 START TRANSACTION;

UPDATE bookkeeping_membership_subscription s
 JOIN (SELECT user_id,dict_code,MIN(id) AS source_id FROM base_dict
       WHERE dict_type=4004 AND scope_type=0 AND family_group_id=0
       GROUP BY user_id,dict_code HAVING COUNT(*)=1 AND dict_code>0) candidate
   ON candidate.user_id=s.user_id AND candidate.dict_code=s.membership_type
 JOIN base_dict d ON d.id=candidate.source_id AND d.deleted=0 AND d.previous_id IS NULL
 SET s.membership_type_id=d.id,s.membership_type_name=d.dict_name,s.membership_type_snapshot_status=1,s.update_time=s.update_time
 WHERE s.membership_type_snapshot_status=0 AND s.membership_type_id IS NULL AND s.membership_type_name IS NULL;
SELECT ROW_COUNT() AS migrated_this_run;
 IF EXISTS(SELECT 1 FROM bookkeeping_membership_subscription s LEFT JOIN base_dict d ON d.id=s.membership_type_id
   WHERE s.membership_type_snapshot_status=1 AND (d.id IS NULL OR d.user_id<>s.user_id OR d.scope_type<>0
     OR d.family_group_id<>0 OR d.dict_type<>4004 OR s.membership_type_name IS NULL)) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: invalid snapshot mapping; backfill rolled back';
 END IF;
 COMMIT;
END$$
DELIMITER ;
CALL iw_mtype_backfill();
DROP PROCEDURE iw_mtype_backfill;
SELECT membership_type_snapshot_status,COUNT(*) AS subscriptions FROM bookkeeping_membership_subscription GROUP BY membership_type_snapshot_status;
SELECT id,user_id,membership_type FROM bookkeeping_membership_subscription WHERE membership_type_snapshot_status=0 ORDER BY id;

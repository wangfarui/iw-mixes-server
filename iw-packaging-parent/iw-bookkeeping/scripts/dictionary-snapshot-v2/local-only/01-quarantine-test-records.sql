-- LOCAL ONLY. Not part of production migration. Draft for the confirmed 20 test bills.
-- Requires separate approval to execute and a stopped local iw-core/other writers.
-- Exact target: localhost:3306 / server hostname wray / database iw_mixes.
-- Archive 20 bills (1149.23), remove them, archive the old four metrics, and explicitly
-- rebase ONLY the records metric for the new local test cohort. Wallet/relations/other_dict stay fixed.
SET NAMES utf8mb4;
SET SESSION group_concat_max_len=65536;
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_dict_v2_local_guard$$
CREATE PROCEDURE iw_dict_v2_local_guard()
BEGIN
 IF DATABASE()<>'iw_mixes' OR @@hostname<>'wray' OR @@port<>3306 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: local-only cleanup; target server/database does not match';
 END IF;
END$$
DELIMITER ;
CALL iw_dict_v2_local_guard();
DROP PROCEDURE iw_dict_v2_local_guard;
CREATE TABLE IF NOT EXISTS bookkeeping_dict_v2_local_archive_records LIKE bookkeeping_records;
CREATE TABLE IF NOT EXISTS bookkeeping_dict_v2_local_archive_baseline LIKE bookkeeping_dictionary_migration_baseline;
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_dict_v2_local_cleanup$$
CREATE PROCEDURE iw_dict_v2_local_cleanup()
BEGIN
 DECLARE target_count INT;
 DECLARE archive_count INT;
 DECLARE target_hash VARCHAR(64);
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
 START TRANSACTION;
 SELECT COUNT(*) INTO target_count FROM bookkeeping_records
 WHERE id IN (3,4,5,6,7,8,9,10,11,16,17,21,53,75,76,77,78,84,85,86);
 SELECT COUNT(*) INTO archive_count FROM bookkeeping_dict_v2_local_archive_records;
 IF target_count=0 AND archive_count=20 THEN
  IF (SELECT COUNT(*) FROM bookkeeping_records)<>150 OR
     (SELECT COALESCE(SUM(amount),0) FROM bookkeeping_records)<>25719.64 THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: local cleanup rerun cohort has changed';
  END IF;
  COMMIT;
  SELECT 'ALREADY_QUARANTINED' result,0 removed_this_run;
 ELSE
  SELECT SHA2(GROUP_CONCAT(CONCAT_WS('|',id,user_id,record_category,record_type,amount,deleted)
   ORDER BY id SEPARATOR ';'),256) INTO target_hash FROM bookkeeping_records
   WHERE id IN (3,4,5,6,7,8,9,10,11,16,17,21,53,75,76,77,78,84,85,86);
  IF target_count<>20 OR archive_count<>0 OR
    NOT(target_hash<=>'4ec17ecccbb807fbcd88e918d6902c7d44311ec27cdf278a245390cefb73869a') OR
    (SELECT COUNT(*) FROM bookkeeping_dict_v2_local_archive_baseline)<>0 OR
    (SELECT COUNT(*) FROM bookkeeping_records)<>170 OR
    (SELECT COALESCE(SUM(amount),0) FROM bookkeeping_records)<>26868.87 OR
    (SELECT COUNT(*) FROM bookkeeping_records WHERE dictionary_snapshot_status=0)<>20 THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: exact local test target no longer matches; no cleanup performed';
  END IF;
  IF EXISTS(SELECT 1 FROM bookkeeping_record_tag_snapshot WHERE record_id IN (3,4,5,6,7,8,9,10,11,16,17,21,53,75,76,77,78,84,85,86)) OR
   EXISTS(SELECT 1 FROM base_dict_business_relation WHERE business_type=4001 AND business_id IN (3,4,5,6,7,8,9,10,11,16,17,21,53,75,76,77,78,84,85,86)) OR
   EXISTS(SELECT 1 FROM points_task_bookkeeping WHERE bookkeeping_record_id IN (3,4,5,6,7,8,9,10,11,16,17,21,53,75,76,77,78,84,85,86)) OR
   EXISTS(SELECT 1 FROM base_business_file WHERE business_type=1 AND business_id IN (3,4,5,6,7,8,9,10,11,16,17,21,53,75,76,77,78,84,85,86)) THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: target test bills have related data; review separately';
  END IF;
  IF (SELECT COUNT(*) FROM bookkeeping_dictionary_migration_baseline WHERE metric IN ('records','wallet','relations','other_dict'))<>4 THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: baseline incomplete';
  END IF;
  IF EXISTS(SELECT 1 FROM (
SELECT b.metric,b.row_count expected_rows,v.row_count actual_rows,b.amount expected_amount,v.amount actual_amount,
 b.fingerprint expected_fingerprint,v.fingerprint actual_fingerprint,
 (b.row_count=v.row_count AND (b.amount<=>v.amount) AND b.fingerprint=v.fingerprint) unchanged
 FROM bookkeeping_dictionary_migration_baseline b JOIN (
 SELECT 'records' metric,COUNT(*) row_count,COALESCE(SUM(amount),0) amount,
 BIT_XOR(CRC32(CONCAT_WS('|',id,user_id,COALESCE(expense_user_id,'NULL'),record_category,amount,record_date,group_id,share_state,deleted))) fingerprint FROM bookkeeping_records
 UNION ALL SELECT 'wallet',COUNT(*),COALESCE(SUM(wallet_balance),0),BIT_XOR(CRC32(CONCAT_WS('|',id,user_id,wallet_balance,wallet_assets,deleted))) FROM bookkeeping_wallet
 UNION ALL SELECT 'relations',COUNT(*),NULL,BIT_XOR(CRC32(CONCAT_WS('|',id,business_type,business_id,dict_id))) FROM base_dict_business_relation
 UNION ALL SELECT 'other_dict',COUNT(*),NULL,BIT_XOR(CRC32(CONCAT_WS('|',id,user_id,dict_type,dict_code,dict_name,dict_status,sort,deleted))) FROM base_dict WHERE dict_type NOT IN (4001,4002,4011)
 ) v ON b.metric=v.metric
) b WHERE unchanged<>1) THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: original stop-write baseline changed; no cleanup performed';
  END IF;
  INSERT INTO bookkeeping_dict_v2_local_archive_records SELECT * FROM bookkeeping_records
   WHERE id IN (3,4,5,6,7,8,9,10,11,16,17,21,53,75,76,77,78,84,85,86);
  INSERT INTO bookkeeping_dict_v2_local_archive_baseline SELECT * FROM bookkeeping_dictionary_migration_baseline;
  DELETE FROM bookkeeping_records WHERE id IN (3,4,5,6,7,8,9,10,11,16,17,21,53,75,76,77,78,84,85,86);
  IF ROW_COUNT()<>20 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: unexpected affected rows'; END IF;
  UPDATE bookkeeping_dictionary_migration_baseline b JOIN (
   SELECT COUNT(*) n,COALESCE(SUM(amount),0) a,
    BIT_XOR(CRC32(CONCAT_WS('|',id,user_id,COALESCE(expense_user_id,'NULL'),record_category,amount,record_date,group_id,share_state,deleted))) f
   FROM bookkeeping_records
  ) r SET b.row_count=r.n,b.amount=r.a,b.fingerprint=r.f,b.captured_at=CURRENT_TIMESTAMP WHERE b.metric='records';
  IF (SELECT COUNT(*) FROM bookkeeping_records)<>150 OR
     (SELECT COALESCE(SUM(amount),0) FROM bookkeeping_records)<>25719.64 THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: unexpected remaining local test cohort';
  END IF;
  COMMIT;
  SELECT 'LOCAL_TEST_QUARANTINE_PASS' result,20 removed_this_run,1149.23 quarantined_amount;
 END IF;
END$$
DELIMITER ;
CALL iw_dict_v2_local_cleanup();
DROP PROCEDURE iw_dict_v2_local_cleanup;
SELECT COUNT(*) archived_records,COALESCE(SUM(amount),0) archived_amount FROM bookkeeping_dict_v2_local_archive_records;
SELECT COUNT(*) remaining_records,COALESCE(SUM(amount),0) remaining_amount FROM bookkeeping_records;

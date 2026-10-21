-- Run while writers remain stopped, including after the idempotency rerun.
DELIMITER $$
DROP PROCEDURE IF EXISTS iw_mtype_verify$$
CREATE PROCEDURE iw_mtype_verify()
BEGIN
 DECLARE bad BIGINT DEFAULT 0;
 IF (SELECT COUNT(*) FROM bookkeeping_membership_migration_state WHERE id=1)<>1 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='FAIL: migration state missing';
 END IF;
 SELECT COUNT(*) INTO bad FROM bookkeeping_membership_subscription s
 LEFT JOIN bookkeeping_membership_migration_baseline b ON b.id=s.id AND b.user_id=s.user_id
 WHERE b.id IS NULL OR b.original_row<>JSON_OBJECT('id',s.id,'userId',s.user_id,'type',s.membership_type,'name',s.membership_name,
    'amount',s.amount,'billingCycle',s.billing_cycle,'cycleNum',s.cycle_num,'cycleUnit',s.cycle_unit,
    'startDate',s.start_date,'endDate',s.end_date,'autoRenew',s.auto_renew,'payWay',s.pay_way,
    'remindDays',s.remind_days,'remark',s.remark,'deleted',s.deleted,'createTime',s.create_time,'updateTime',s.update_time);
 IF bad<>0 OR (SELECT COUNT(*) FROM bookkeeping_membership_subscription)<>(SELECT COUNT(*) FROM bookkeeping_membership_migration_baseline) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='FAIL: original subscription fields or cohort changed';
 END IF;
 SELECT COUNT(*) INTO bad FROM bookkeeping_membership_subscription s LEFT JOIN base_dict d ON d.id=s.membership_type_id
 WHERE s.membership_type_snapshot_status NOT IN (0,1)
 OR (s.membership_type_snapshot_status=1 AND (d.id IS NULL OR d.user_id<>s.user_id OR d.scope_type<>0
    OR d.family_group_id<>0 OR d.dict_type<>4004 OR s.membership_type_name IS NULL OR CHAR_LENGTH(s.membership_type_name) NOT BETWEEN 1 AND 32))
 OR (s.membership_type_snapshot_status=0 AND (s.membership_type_id IS NOT NULL OR s.membership_type_name IS NOT NULL));
 IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='FAIL: invalid snapshot or cross-owner reference'; END IF;
 IF EXISTS(SELECT 1 FROM base_dict WHERE dict_type=4004 AND (scope_type<>0 OR family_group_id<>0)) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='FAIL: family membership source';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bookkeeping_membership_subscription' AND column_name='membership_type_name' AND character_set_name='utf8mb4' AND collation_name='utf8mb4_bin' AND character_maximum_length=32)
 OR COALESCE((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='base_dict' AND index_name='uk_membership_dict_name' AND non_unique=0),'')<>'scope_type,family_group_id,user_id,dict_type,membership_active_name_key'
 OR COALESCE((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='bookkeeping_membership_subscription' AND index_name='idx_membership_type_snapshot'),'')<>'user_id,deleted,membership_type_snapshot_status,membership_type_name' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='FAIL: incompatible snapshot schema/index';
 END IF;
 SELECT 'MEMBERSHIP_MIGRATION_PASS' AS result,COUNT(*) AS total,
 SUM(membership_type_snapshot_status=1) AS verified,SUM(membership_type_snapshot_status=0) AS pending FROM bookkeeping_membership_subscription;
END$$
DELIMITER ;
CALL iw_mtype_verify();
DROP PROCEDURE iw_mtype_verify;

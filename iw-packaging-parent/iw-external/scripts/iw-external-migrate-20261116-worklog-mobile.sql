-- Run before deploying the phone-based worklog notifications. Repeatable on MySQL 5.7/8.0.

SET @zg_mobile_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='external_zhaogang_workbench_profile' AND COLUMN_NAME='mobile')=0, 'ALTER TABLE external_zhaogang_workbench_profile ADD COLUMN mobile VARCHAR(11) NULL COMMENT ''企微提醒手机号，同一CODING团队共享''', 'SELECT 1');
PREPARE zg_mobile_stmt FROM @zg_mobile_ddl;
EXECUTE zg_mobile_stmt;
DEALLOCATE PREPARE zg_mobile_stmt;

SET @zg_mobile_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='external_zhaogang_worklog_notify_config' AND COLUMN_NAME='summary_member_id')=0, 'ALTER TABLE external_zhaogang_worklog_notify_config ADD COLUMN summary_member_id BIGINT NULL', 'SELECT 1');
PREPARE zg_mobile_stmt FROM @zg_mobile_ddl;
EXECUTE zg_mobile_stmt;
DEALLOCATE PREPARE zg_mobile_stmt;

SET @zg_mobile_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='external_zhaogang_worklog_notify_message' AND COLUMN_NAME='message_type')=0, 'ALTER TABLE external_zhaogang_worklog_notify_message ADD COLUMN message_type VARCHAR(16) NOT NULL DEFAULT ''markdown''', 'SELECT 1');
PREPARE zg_mobile_stmt FROM @zg_mobile_ddl;
EXECUTE zg_mobile_stmt;
DEALLOCATE PREPARE zg_mobile_stmt;

SET @zg_mobile_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='external_zhaogang_worklog_notify_message' AND COLUMN_NAME='mentioned_mobiles')=0, 'ALTER TABLE external_zhaogang_worklog_notify_message ADD COLUMN mentioned_mobiles VARCHAR(255) NOT NULL DEFAULT ''''', 'SELECT 1');
PREPARE zg_mobile_stmt FROM @zg_mobile_ddl;
EXECUTE zg_mobile_stmt;
DEALLOCATE PREPARE zg_mobile_stmt;

-- Apply after the worklog notification migrations and before deploying iw-external.
-- Run this repeatable migration on one connection. Existing teams default to 17:00 Beijing time.

SET @zg_notify_ddl = IF(
  EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='external_zhaogang_worklog_notify_config' AND COLUMN_NAME='send_time'),
  'SELECT 1',
  'ALTER TABLE external_zhaogang_worklog_notify_config ADD COLUMN send_time TIME NOT NULL DEFAULT ''17:00:00'''
);
PREPARE zg_notify_stmt FROM @zg_notify_ddl;
EXECUTE zg_notify_stmt;
DEALLOCATE PREPARE zg_notify_stmt;

SET @zg_notify_ddl = IF(
  EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='external_zhaogang_worklog_notify_config' AND COLUMN_NAME='delivery_revision'),
  'SELECT 1',
  'ALTER TABLE external_zhaogang_worklog_notify_config ADD COLUMN delivery_revision BIGINT NULL'
);
PREPARE zg_notify_stmt FROM @zg_notify_ddl;
EXECUTE zg_notify_stmt;
DEALLOCATE PREPARE zg_notify_stmt;

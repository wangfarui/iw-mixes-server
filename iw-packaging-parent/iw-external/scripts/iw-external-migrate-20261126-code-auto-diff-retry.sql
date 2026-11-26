-- Apply before starting the iw-external version with automatic Git diff retries.
-- One atomic ALTER on MySQL 8.0. Reapplying this migration is a no-op.
SET @migration = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'external_zhaogang_code_commit'
       AND column_name = 'diff_retry_attempts'
), 'DO 0', 'ALTER TABLE external_zhaogang_code_commit
    ADD COLUMN diff_retry_attempts tinyint unsigned NOT NULL DEFAULT 0,
    ADD COLUMN diff_retry_after datetime NULL,
    ADD COLUMN diff_retry_credential_signature char(64) NULL,
    ADD COLUMN diff_retry_error varchar(32) NULL,
    ADD INDEX idx_code_diff_retry (coding_team_id, scope_type, scope_id, diff_status, commit_date)');
PREPARE code_auto_diff_retry_migration FROM @migration;
EXECUTE code_auto_diff_retry_migration;
DEALLOCATE PREPARE code_auto_diff_retry_migration;

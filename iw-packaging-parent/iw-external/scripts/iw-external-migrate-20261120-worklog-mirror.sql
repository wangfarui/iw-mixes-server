-- Additive and repeatable. Run before deploying iw-external with worklog mirroring.
CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_source (
    source_key CHAR(64) NOT NULL,
    coding_team_id BIGINT NOT NULL,
    coding_user_id BIGINT NOT NULL,
    management_team_id BIGINT NOT NULL DEFAULT 0,
    credential_fingerprint CHAR(64) NOT NULL,
    synced_through BIGINT NOT NULL DEFAULT 0,
    history_before BIGINT NOT NULL DEFAULT 0,
    increment_at BIGINT NOT NULL DEFAULT 0,
    data_version BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    warning VARCHAR(255) NOT NULL DEFAULT '',
    blocked TINYINT NOT NULL DEFAULT 0,
    incomplete TINYINT NOT NULL DEFAULT 0,
    requested_from BIGINT NOT NULL DEFAULT -1,
    incremental TINYINT NOT NULL DEFAULT 1,
    priority_no INT NOT NULL DEFAULT 10,
    rerun_requested TINYINT NOT NULL DEFAULT 0,
    lease_id VARCHAR(36) NOT NULL DEFAULT '',
    lease_until BIGINT NOT NULL DEFAULT 0,
    retry_after BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (source_key),
    KEY idx_worklog_member (coding_team_id, coding_user_id),
    KEY idx_worklog_pending (state, retry_after, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_entry (
    source_key CHAR(64) NOT NULL,
    worklog_id BIGINT NOT NULL,
    coding_team_id BIGINT NOT NULL,
    coding_user_id BIGINT NOT NULL,
    project_name VARCHAR(255) NOT NULL,
    issue_id BIGINT NOT NULL,
    issue_code BIGINT NOT NULL,
    start_at BIGINT NULL,
    created_at BIGINT NULL,
    updated_at BIGINT NULL,
    hours DECIMAL(18,4) NOT NULL,
    working_desc MEDIUMTEXT NOT NULL,
    observed_at BIGINT NOT NULL,
    missing TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (source_key, worklog_id),
    KEY idx_worklog_page (source_key, missing, start_at, updated_at, worklog_id),
    KEY idx_worklog_creation (source_key, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_issue_snapshot (
    source_key CHAR(64) NOT NULL,
    project_name VARCHAR(255) NOT NULL,
    issue_code BIGINT NOT NULL,
    issue_json MEDIUMTEXT NOT NULL,
    checked_at BIGINT NOT NULL,
    PRIMARY KEY (source_key, project_name, issue_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_sync_scope (
    scope_key CHAR(64) NOT NULL,
    requested_at BIGINT NOT NULL,
    synced_at BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (scope_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Upgrade an earlier local mirror schema without dropping audit records. Repeatable on MySQL 8.
SET @worklog_history_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='external_zhaogang_worklog_source' AND column_name='history_before'),
  'SELECT 1',
  'ALTER TABLE external_zhaogang_worklog_source ADD COLUMN history_before BIGINT NOT NULL DEFAULT 0');
PREPARE worklog_history_stmt FROM @worklog_history_ddl;
EXECUTE worklog_history_stmt;
DEALLOCATE PREPARE worklog_history_stmt;

SET @worklog_member_index_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='external_zhaogang_worklog_source' AND index_name='idx_worklog_member'),
  'SELECT 1',
  'ALTER TABLE external_zhaogang_worklog_source ADD KEY idx_worklog_member (coding_team_id,coding_user_id)');
PREPARE worklog_member_index_stmt FROM @worklog_member_index_ddl;
EXECUTE worklog_member_index_stmt;
DEALLOCATE PREPARE worklog_member_index_stmt;

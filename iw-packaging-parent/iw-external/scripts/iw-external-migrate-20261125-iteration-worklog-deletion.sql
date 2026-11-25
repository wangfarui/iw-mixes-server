-- Run against the iw-external database before starting the new application. Safe to rerun.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='external_zhaogang_iteration_issue_worklog' AND column_name='coding_worklog_id')=0,
    'ALTER TABLE external_zhaogang_iteration_issue_worklog ADD COLUMN coding_worklog_id BIGINT UNSIGNED NULL', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='external_zhaogang_iteration_issue_worklog' AND column_name='source')=0,
    'ALTER TABLE external_zhaogang_iteration_issue_worklog ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT ''WORKBENCH''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='external_zhaogang_iteration_issue_worklog' AND column_name='coding_missing')=0,
    'ALTER TABLE external_zhaogang_iteration_issue_worklog ADD COLUMN coding_missing TINYINT(1) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='external_zhaogang_iteration_issue_worklog' AND index_name='uk_issue_coding_worklog')=0,
    'ALTER TABLE external_zhaogang_iteration_issue_worklog ADD UNIQUE KEY uk_issue_coding_worklog (issue_id,coding_worklog_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_deleted_entry (
    coding_team_id BIGINT NOT NULL,
    coding_team_key VARCHAR(64) NOT NULL,
    worklog_id BIGINT NOT NULL,
    confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(coding_team_id,worklog_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='external_zhaogang_iteration_issue_worklog' AND column_name='delete_started_at')=0,
    'ALTER TABLE external_zhaogang_iteration_issue_worklog ADD COLUMN delete_started_at DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='external_zhaogang_iteration_issue' AND column_name='coding_worklogs_loaded')=0,
    'ALTER TABLE external_zhaogang_iteration_issue ADD COLUMN coding_worklogs_loaded TINYINT(1) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_deleted_issue_key (
    coding_team_id BIGINT NOT NULL,
    project_name VARCHAR(255) NOT NULL,
    issue_code BIGINT NOT NULL,
    confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(coding_team_id,project_name,issue_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

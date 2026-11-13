CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_deletion_revision (
    coding_team_id BIGINT UNSIGNED NOT NULL,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (coding_team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_deleted_issue (
    coding_team_id BIGINT UNSIGNED NOT NULL,
    coding_issue_id BIGINT UNSIGNED NOT NULL,
    project_name VARCHAR(255) NOT NULL,
    issue_code BIGINT UNSIGNED NOT NULL,
    first_confirmed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (coding_team_id, coding_issue_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

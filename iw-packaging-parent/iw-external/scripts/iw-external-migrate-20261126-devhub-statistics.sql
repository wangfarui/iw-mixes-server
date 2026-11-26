-- Dev Hub statistics. MySQL >= 8.0.29 (CREATE TRIGGER IF NOT EXISTS).
-- Apply after the current Dev Hub mirror, team, calendar and code migrations.
-- Stop writers until all triggers exist. No source rows are modified by this migration.
-- Preflight: SELECT CURRENT_USER(), @@GLOBAL.log_bin, @@GLOBAL.log_bin_trust_function_creators;
-- With binlog ON and trust OFF, use a DBA account allowed to create triggers under that policy (ERROR 1419 otherwise).
-- DDL commits individually. After an interruption, verify the objects and rerun without skipping triggers.
CREATE TABLE IF NOT EXISTS external_zhaogang_statistics_revision (
    coding_team_id BIGINT UNSIGNED NOT NULL,
    stat_month CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_no BIGINT UNSIGNED NOT NULL DEFAULT 0,
    visibility_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    code_visibility_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (coding_team_id, stat_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_statistics_month (
    coding_team_id BIGINT UNSIGNED NOT NULL,
    stat_month CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    generation CHAR(36) NOT NULL,
    valid_until BIGINT NOT NULL,
    worklog_synced_at BIGINT,
    code_synced_at BIGINT,
    PRIMARY KEY (coding_team_id, stat_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_statistics_day (
    coding_team_id BIGINT UNSIGNED NOT NULL,
    coding_user_id BIGINT UNSIGNED NOT NULL,
    stat_date DATE NOT NULL,
    worklog_hours DECIMAL(24,4) NOT NULL,
    eligible_workday TINYINT(1) NOT NULL,
    overtime_hours DECIMAL(24,4) NOT NULL,
    overtime_days TINYINT UNSIGNED NOT NULL,
    commit_count BIGINT UNSIGNED NOT NULL,
    additions BIGINT UNSIGNED NOT NULL,
    deletions BIGINT UNSIGNED NOT NULL,
    worklog_reason VARCHAR(32),
    commit_reason VARCHAR(32),
    lines_reason VARCHAR(32),
    PRIMARY KEY (coding_team_id, stat_date, coding_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_statistics_refresh_task (
    coding_team_id BIGINT UNSIGNED NOT NULL,
    range_key VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) NOT NULL,
    state VARCHAR(12) NOT NULL,
    priority_no TINYINT UNSIGNED NOT NULL,
    attempt INT UNSIGNED NOT NULL DEFAULT 0,
    lease_id CHAR(36),
    lease_until BIGINT NOT NULL DEFAULT 0,
    next_retry_at BIGINT NOT NULL,
    requested_at BIGINT NOT NULL,
    requested_visibility BIGINT UNSIGNED NOT NULL DEFAULT 0,
    requested_source_version CHAR(64) NOT NULL,
    completed_at BIGINT NOT NULL DEFAULT 0,
    safe_error VARCHAR(128),
    PRIMARY KEY (coding_team_id, range_key),
    KEY idx_statistics_dispatch (state, priority_no, next_retry_at, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_statistics_report (
    coding_team_id BIGINT UNSIGNED NOT NULL,
    range_key VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_id CHAR(36) NOT NULL,
    visibility_version BIGINT UNSIGNED NOT NULL,
    source_version CHAR(64) NOT NULL,
    policy_key VARCHAR(64) NOT NULL,
    generated_at BIGINT NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    PRIMARY KEY (coding_team_id, range_key),
    KEY idx_statistics_report_age (generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The worker pages by depot and SHA across current team scopes within each month.
SET @zg_stats_add_index = IF(EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'external_zhaogang_code_commit'
      AND index_name = 'idx_org_month_depot_sha'
), 'DO 0', 'ALTER TABLE external_zhaogang_code_commit ADD INDEX idx_org_month_depot_sha
    (coding_team_id, scope_type, month, depot_id, sha)');
PREPARE zg_stats_index FROM @zg_stats_add_index;
EXECUTE zg_stats_index;
DEALLOCATE PREPARE zg_stats_index;

-- Verify duplicate SHA dates across the organization without scanning other commits for each row.
SET @zg_stats_add_sha_lookup = IF(EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'external_zhaogang_code_commit'
      AND index_name = 'idx_org_depot_sha'
), 'DO 0', 'ALTER TABLE external_zhaogang_code_commit ADD INDEX idx_org_depot_sha
    (coding_team_id, scope_type, depot_id, sha, scope_id, month)');
PREPARE zg_stats_sha_lookup FROM @zg_stats_add_sha_lookup;
EXECUTE zg_stats_sha_lookup;
DEALLOCATE PREPARE zg_stats_sha_lookup;

DELIMITER $$

CREATE TRIGGER IF NOT EXISTS zg_stats_team_u AFTER UPDATE ON external_zhaogang_workbench_team
FOR EACH ROW
BEGIN
    IF (NOT (NEW.coding_team_id <=> OLD.coding_team_id) OR NOT (NEW.coding_team_key <=> OLD.coding_team_key) OR NOT (NEW.coding_team_host <=> OLD.coding_team_host)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
        IF NEW.coding_team_id <> OLD.coding_team_id THEN
            INSERT INTO external_zhaogang_statistics_revision
                (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
            SELECT OLD.coding_team_id, '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE (OLD.coding_team_id) IS NOT NULL
            ON DUPLICATE KEY UPDATE version_no = version_no + 1,
                visibility_version = visibility_version + VALUES(visibility_version),
                code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
        END IF;
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_team_d AFTER DELETE ON external_zhaogang_workbench_team
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT OLD.coding_team_id, '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE (OLD.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_member_i AFTER INSERT ON external_zhaogang_workbench_team_member
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id), '*', 1, 1, 0 WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id)) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_member_u AFTER UPDATE ON external_zhaogang_workbench_team_member
FOR EACH ROW
BEGIN
    IF (NOT (NEW.coding_user_id <=> OLD.coding_user_id) OR NOT (NEW.user_name <=> OLD.user_name) OR NOT (NEW.avatar <=> OLD.avatar) OR NOT (NEW.managed_statistics <=> OLD.managed_statistics) OR NOT (NEW.ignore_team_statistics <=> OLD.ignore_team_statistics) OR NOT (NEW.source_project_id <=> OLD.source_project_id)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id), '*', 1, 1, 0 WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id)) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_member_d AFTER DELETE ON external_zhaogang_workbench_team_member
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = OLD.team_id), '*', 1, 1, 0 WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = OLD.team_id)) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_member_code_i AFTER INSERT ON external_zhaogang_workbench_team_member
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id), '*', 1, 0, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id)) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_member_code_u AFTER UPDATE ON external_zhaogang_workbench_team_member
FOR EACH ROW
BEGIN
    IF NOT (NEW.managed_statistics <=> OLD.managed_statistics)
        OR NOT (NEW.ignore_team_statistics <=> OLD.ignore_team_statistics)
        OR NOT (NEW.source_project_id <=> OLD.source_project_id)
        OR NOT (NEW.coding_user_id <=> OLD.coding_user_id) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id), '*', 1, 0, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id)) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_member_code_d AFTER DELETE ON external_zhaogang_workbench_team_member
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = OLD.team_id), '*', 1, 0, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = OLD.team_id)) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_manager_i AFTER INSERT ON external_zhaogang_workbench_team_token
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id), '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id)) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_manager_u AFTER UPDATE ON external_zhaogang_workbench_team_token
FOR EACH ROW
BEGIN
    IF (NOT (NEW.token_ciphertext <=> OLD.token_ciphertext) OR NOT (NEW.owner_user_id <=> OLD.owner_user_id)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id), '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id)) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_manager_d AFTER DELETE ON external_zhaogang_workbench_team_token
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = OLD.team_id), '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = OLD.team_id)) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_credential_i AFTER INSERT ON external_zhaogang_coding_credential
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT NEW.coding_team_id, '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE (NEW.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_credential_u AFTER UPDATE ON external_zhaogang_coding_credential
FOR EACH ROW
BEGIN
    IF (NOT (NEW.token_fingerprint <=> OLD.token_fingerprint)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_credential_d AFTER DELETE ON external_zhaogang_coding_credential
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT OLD.coding_team_id, '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE (OLD.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_role_i AFTER INSERT ON external_zhaogang_workbench_profile
FOR EACH ROW
BEGIN
    IF NEW.coding_role IS NOT NULL THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_role_d AFTER DELETE ON external_zhaogang_workbench_profile
FOR EACH ROW
BEGIN
    IF OLD.coding_role IS NOT NULL THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT OLD.coding_team_id, '*', 1, 1, 0 WHERE (OLD.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_role_u AFTER UPDATE ON external_zhaogang_workbench_profile
FOR EACH ROW
BEGIN
    IF (NOT (NEW.coding_role <=> OLD.coding_role)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_calendar_u AFTER UPDATE ON external_zhaogang_work_calendar
FOR EACH ROW
BEGIN
    IF (NOT (NEW.version_no <=> OLD.version_no)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_calendar_d AFTER DELETE ON external_zhaogang_work_calendar
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT OLD.coding_team_id, '*', 1, 1, 0 WHERE (OLD.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_calendar_day_i AFTER INSERT ON external_zhaogang_work_calendar_day
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT (SELECT coding_team_id FROM external_zhaogang_work_calendar WHERE id = NEW.calendar_id), '*', 1, 1, 0 WHERE ((SELECT coding_team_id FROM external_zhaogang_work_calendar WHERE id = NEW.calendar_id)) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_calendar_day_u AFTER UPDATE ON external_zhaogang_work_calendar_day
FOR EACH ROW
BEGIN
    IF (NOT (NEW.work_date <=> OLD.work_date) OR NOT (NEW.day_type <=> OLD.day_type)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT (SELECT coding_team_id FROM external_zhaogang_work_calendar WHERE id = NEW.calendar_id), '*', 1, 1, 0 WHERE ((SELECT coding_team_id FROM external_zhaogang_work_calendar WHERE id = NEW.calendar_id)) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_calendar_day_d AFTER DELETE ON external_zhaogang_work_calendar_day
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT (SELECT coding_team_id FROM external_zhaogang_work_calendar WHERE id = OLD.calendar_id), '*', 1, 1, 0 WHERE ((SELECT coding_team_id FROM external_zhaogang_work_calendar WHERE id = OLD.calendar_id)) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_leave_i AFTER INSERT ON external_zhaogang_work_calendar_leave
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_leave_u AFTER UPDATE ON external_zhaogang_work_calendar_leave
FOR EACH ROW
BEGIN
    IF (NOT (NEW.leave_date <=> OLD.leave_date) OR NOT (NEW.coding_user_id <=> OLD.coding_user_id)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_leave_d AFTER DELETE ON external_zhaogang_work_calendar_leave
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT OLD.coding_team_id, '*', 1, 1, 0 WHERE (OLD.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_identity_i AFTER INSERT ON external_zhaogang_code_identity
FOR EACH ROW
BEGIN
    IF NEW.scope_type = 'SELF' THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_identity_u AFTER UPDATE ON external_zhaogang_code_identity
FOR EACH ROW
BEGIN
    IF NEW.scope_type = 'SELF' AND (NOT (NEW.identity_type <=> OLD.identity_type) OR NOT (NEW.identity_key <=> OLD.identity_key) OR NOT (NEW.coding_user_id <=> OLD.coding_user_id) OR NOT (NEW.ignored <=> OLD.ignored) OR NOT (NEW.source <=> OLD.source)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_identity_d AFTER DELETE ON external_zhaogang_code_identity
FOR EACH ROW
BEGIN
    IF OLD.scope_type = 'SELF' THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT OLD.coding_team_id, '*', 1, 1, 0 WHERE (OLD.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_worklog_tombstone_i AFTER INSERT ON external_zhaogang_worklog_deleted_entry
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_worklog_tombstone_u AFTER UPDATE ON external_zhaogang_worklog_deleted_entry
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_issue_tombstone_i AFTER INSERT ON external_zhaogang_worklog_deleted_issue
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_issue_tombstone_u AFTER UPDATE ON external_zhaogang_worklog_deleted_issue
FOR EACH ROW
BEGIN
    IF (NOT (NEW.project_name <=> OLD.project_name) OR NOT (NEW.issue_code <=> OLD.issue_code)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_issue_key_tombstone_i AFTER INSERT ON external_zhaogang_worklog_deleted_issue_key
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_issue_key_tombstone_u AFTER UPDATE ON external_zhaogang_worklog_deleted_issue_key
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_source_i AFTER INSERT ON external_zhaogang_worklog_source
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT NEW.coding_team_id, '*', 1, 0, 0 WHERE (NEW.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_source_d AFTER DELETE ON external_zhaogang_worklog_source
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT OLD.coding_team_id, '*', 1, 1, 0 WHERE (OLD.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_source_u AFTER UPDATE ON external_zhaogang_worklog_source
FOR EACH ROW
BEGIN
    IF NEW.blocked <> OLD.blocked OR NEW.credential_fingerprint <> OLD.credential_fingerprint
        OR NEW.management_team_id <> OLD.management_team_id THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    ELSEIF NEW.increment_at <> OLD.increment_at OR NEW.history_before <> OLD.history_before
        OR NEW.incomplete <> OLD.incomplete OR (NEW.state = 'FAILED') <> (OLD.state = 'FAILED')
        OR (NEW.synced_through = 0) <> (OLD.synced_through = 0) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 0, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_entry_i AFTER INSERT ON external_zhaogang_worklog_entry
FOR EACH ROW
BEGIN
    IF NEW.start_at IS NOT NULL AND NEW.missing = 0 THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, DATE_FORMAT(DATE_ADD('1970-01-01', INTERVAL FLOOR((NEW.start_at + 28800000) / 86400000) DAY), '%Y-%m'), 1, 0, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_entry_u AFTER UPDATE ON external_zhaogang_worklog_entry
FOR EACH ROW
BEGIN
    IF OLD.missing = 0 AND (NEW.missing <> 0 OR NOT (OLD.start_at <=> NEW.start_at)
        OR NOT (OLD.hours <=> NEW.hours) OR NOT (OLD.issue_id <=> NEW.issue_id)
        OR NOT (OLD.project_name <=> NEW.project_name) OR OLD.issue_code <> NEW.issue_code) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    ELSEIF OLD.missing <> 0 AND NEW.missing = 0 AND NEW.start_at IS NOT NULL THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, DATE_FORMAT(DATE_ADD('1970-01-01', INTERVAL FLOOR((NEW.start_at + 28800000) / 86400000) DAY), '%Y-%m'), 1, 0, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_entry_d AFTER DELETE ON external_zhaogang_worklog_entry
FOR EACH ROW
BEGIN
    IF OLD.missing = 0 THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT OLD.coding_team_id, '*', 1, 1, 0 WHERE (OLD.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_commit_i AFTER INSERT ON external_zhaogang_code_commit
FOR EACH ROW
BEGIN
    IF NEW.scope_type = 'WORKBENCH_TEAM' THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, NEW.month, 1, 0, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_commit_u AFTER UPDATE ON external_zhaogang_code_commit
FOR EACH ROW
BEGIN
    IF NEW.scope_type = 'WORKBENCH_TEAM' AND (NOT (NEW.commit_at <=> OLD.commit_at)
        OR NOT (NEW.author_email_hash <=> OLD.author_email_hash)
        OR NOT (NEW.author_name_key <=> OLD.author_name_key) OR NEW.is_merge <> OLD.is_merge
        OR NEW.depot_name <> OLD.depot_name OR (NEW.diff_status = 'DIFF_PERMISSION_DENIED'
        AND OLD.diff_status <> NEW.diff_status)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    ELSEIF NEW.scope_type = 'WORKBENCH_TEAM' AND (NOT (NEW.additions <=> OLD.additions)
        OR NOT (NEW.deletions <=> OLD.deletions) OR NEW.diff_status <> OLD.diff_status) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, NEW.month, 1, 0, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_commit_d AFTER DELETE ON external_zhaogang_code_commit
FOR EACH ROW
BEGIN
    IF OLD.scope_type = 'WORKBENCH_TEAM' THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT OLD.coding_team_id, '*', 1, 1, 0 WHERE (OLD.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_scan_u AFTER UPDATE ON external_zhaogang_code_scan_job
FOR EACH ROW
BEGIN
    IF NEW.scope_type = 'WORKBENCH_TEAM' AND NEW.status <> OLD.status
        AND NEW.scan_mode <> 'DIFF_RETRY' AND NEW.status NOT IN ('RUNNING', 'QUEUED') THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, IF(NEW.failed_repos > 0 OR NEW.status = 'FAILED', 1, 0), 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

DELIMITER ;

-- Apply after iw-external-migrate-20261127-shared-statistics.sql.
CREATE TABLE IF NOT EXISTS external_zhaogang_code_diff_attempt (
    commit_id BIGINT UNSIGNED NOT NULL,
    credential_fingerprint CHAR(64) CHARACTER SET ascii NOT NULL,
    read_method VARCHAR(12) CHARACTER SET ascii NOT NULL,
    failure_reason VARCHAR(32) CHARACTER SET ascii NOT NULL,
    terminal TINYINT(1) NOT NULL,
    PRIMARY KEY (commit_id, credential_fingerprint, read_method),
    CONSTRAINT fk_zg_diff_attempt_commit FOREIGN KEY (commit_id)
        REFERENCES external_zhaogang_shared_code_commit (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

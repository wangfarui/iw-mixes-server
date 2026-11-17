CREATE TABLE IF NOT EXISTS external_zhaogang_skill (
    id CHAR(36) PRIMARY KEY,
    machine_name VARCHAR(64) NOT NULL,
    active_name VARCHAR(64) UNIQUE,
    creator_team BIGINT NOT NULL,
    creator_user BIGINT NOT NULL,
    current_version VARCHAR(32),
    revision BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMP NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_skill_version (
    skill_id CHAR(36) NOT NULL,
    version VARCHAR(32) NOT NULL,
    major_no INT NOT NULL,
    minor_no INT NOT NULL,
    patch_no INT NOT NULL,
    title VARCHAR(120) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    notes VARCHAR(2000) NOT NULL,
    package_id CHAR(36) NOT NULL UNIQUE,
    sha256 CHAR(64) NOT NULL,
    actor_name VARCHAR(120) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    purged_at TIMESTAMP NULL,
    PRIMARY KEY (skill_id, version),
    KEY idx_zg_skill_version_purge (deleted_at, purged_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_skill_draft (
    id CHAR(36) PRIMARY KEY,
    actor_team BIGINT NOT NULL,
    actor_user BIGINT NOT NULL,
    skill_id CHAR(36),
    base_revision BIGINT,
    machine_name VARCHAR(64) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    published_skill_id CHAR(36),
    published_version VARCHAR(32),
    KEY idx_zg_skill_draft_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_skill_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    skill_id CHAR(36) NOT NULL,
    actor_team BIGINT NOT NULL,
    actor_user BIGINT NOT NULL,
    actor_name VARCHAR(120) NOT NULL,
    source VARCHAR(12) NOT NULL,
    action VARCHAR(24) NOT NULL,
    previous_version VARCHAR(32),
    current_version VARCHAR(32),
    target_version VARCHAR(32),
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_zg_skill_event_history (skill_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_skill_device (
    id CHAR(36) PRIMARY KEY,
    actor_team BIGINT NOT NULL,
    actor_user BIGINT NOT NULL,
    label VARCHAR(120) NOT NULL,
    actor_name VARCHAR(120) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_zg_skill_device_actor (actor_team, actor_user)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_skill_device_epoch (
    actor_team BIGINT NOT NULL,
    actor_user BIGINT NOT NULL,
    epoch BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (actor_team, actor_user)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_skill_pair (
    code CHAR(10) PRIMARY KEY,
    secret_hash CHAR(64) NOT NULL UNIQUE,
    label VARCHAR(120) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    actor_team BIGINT,
    actor_user BIGINT,
    actor_name VARCHAR(120),
    epoch BIGINT,
    KEY idx_zg_skill_pair_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

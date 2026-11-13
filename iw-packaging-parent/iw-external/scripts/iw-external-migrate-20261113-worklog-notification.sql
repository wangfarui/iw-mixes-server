-- Additive, repeatable migration. No secrets/default enabled notifications.
CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_notify_config (
  team_id BIGINT NOT NULL PRIMARY KEY,
  coding_team_id BIGINT NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 0,
  webhook_cipher TEXT NULL,
  webhook_hash CHAR(64) NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at BIGINT NOT NULL DEFAULT 0,
  last_test_at BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_notify_batch (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  team_id BIGINT NOT NULL,
  coding_team_id BIGINT NOT NULL,
  batch_key VARCHAR(80) NOT NULL,
  kind VARCHAR(12) NOT NULL,
  revision BIGINT NOT NULL,
  webhook_hash CHAR(64) NOT NULL,
  member_ids TEXT NOT NULL,
  snapshot_json LONGTEXT NULL,
  state VARCHAR(16) NOT NULL,
  reason VARCHAR(200) NOT NULL DEFAULT '',
  total_count INT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  UNIQUE KEY uk_team_day (team_id, batch_key),
  KEY idx_state_created (state, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_notify_message (
  batch_id VARCHAR(36) NOT NULL,
  sequence_no INT NOT NULL,
  content TEXT NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'WAITING',
  attempts INT NOT NULL DEFAULT 0,
  next_at BIGINT NOT NULL DEFAULT 0,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (batch_id, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_notify_channel (
  webhook_hash CHAR(64) NOT NULL PRIMARY KEY,
  active_batch VARCHAR(36) NULL,
  next_at BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

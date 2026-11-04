-- 本地持续同步；仅扩展既有代码统计表。执行前断言字段不存在、无活动任务，并备份四张功能表。
ALTER TABLE external_zhaogang_code_scan_job
    ADD COLUMN sync_from date NULL,
    ADD COLUMN sync_to date NULL,
    ADD COLUMN sync_team_key varchar(128) NULL,
    ADD COLUMN sync_team_host varchar(255) NULL,
    ADD INDEX idx_code_sync_scope (coding_team_id, scope_type, scope_id, scan_mode, id);
ALTER TABLE external_zhaogang_code_scan_repo ADD COLUMN sync_state longtext NULL;
ALTER TABLE external_zhaogang_code_commit
    ADD INDEX idx_code_scope_date (coding_team_id, scope_type, scope_id, commit_date);
-- 范围级并发保护；兼容仍使用旧 active_key 格式的本地进程。
ALTER TABLE external_zhaogang_code_scan_job
    ADD COLUMN active_scope_key varchar(128) GENERATED ALWAYS AS
      (CASE WHEN status IN ('QUEUED','RUNNING') THEN concat(coding_team_id, ':', scope_type, ':', scope_id) ELSE NULL END) STORED,
    ADD UNIQUE INDEX uk_code_active_scope (active_scope_key);

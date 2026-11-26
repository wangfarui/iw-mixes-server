-- Apply before deploying iw-external with partitioned code checkpoints.
-- Legacy checkpoints remain in external_zhaogang_code_scan_repo.sync_state.
CREATE TABLE IF NOT EXISTS external_zhaogang_code_checkpoint_part
(
    job_id          BIGINT UNSIGNED NOT NULL,
    depot_id        BIGINT UNSIGNED NOT NULL,
    part_no         SMALLINT UNSIGNED NOT NULL,
    checkpoint_data MEDIUMTEXT CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (job_id, depot_id, part_no),
    CONSTRAINT fk_zg_code_checkpoint_repo FOREIGN KEY (job_id, depot_id)
        REFERENCES external_zhaogang_code_scan_repo (job_id, depot_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

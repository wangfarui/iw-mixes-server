-- Apply before deploying the repository coverage queries. Run in one MySQL connection.
SET @zg_coverage_finished = IF(EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='external_zhaogang_code_scan_job'
      AND index_name='idx_code_coverage_finished'
), 'DO 0', 'ALTER TABLE external_zhaogang_code_scan_job
    ADD INDEX idx_code_coverage_finished (coding_team_id, finished_at, scan_mode), ALGORITHM=INPLACE, LOCK=NONE');
PREPARE zg_coverage_finished_stmt FROM @zg_coverage_finished;
EXECUTE zg_coverage_finished_stmt;
DEALLOCATE PREPARE zg_coverage_finished_stmt;

SET @zg_coverage_range = IF(EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='external_zhaogang_code_scan_job'
      AND index_name='idx_code_coverage_range'
), 'DO 0', 'ALTER TABLE external_zhaogang_code_scan_job
    ADD INDEX idx_code_coverage_range (coding_team_id, sync_to, sync_from, scan_mode, finished_at), ALGORITHM=INPLACE, LOCK=NONE');
PREPARE zg_coverage_range_stmt FROM @zg_coverage_range;
EXECUTE zg_coverage_range_stmt;
DEALLOCATE PREPARE zg_coverage_range_stmt;

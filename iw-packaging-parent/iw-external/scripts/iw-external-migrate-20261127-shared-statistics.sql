-- Stop iw-external writers and take a database backup before running this migration.
-- Legacy code, worklogs and checkpoint parts remain in their original tables.
CREATE TABLE IF NOT EXISTS external_zhaogang_shared_code_commit
(
    id                   bigint unsigned not null auto_increment comment '提交统计行ID',
    coding_team_id       bigint unsigned not null comment 'CODING顶层团队ID',
    scope_type           varchar(16)     not null default 'SHARED',
    scope_id             bigint unsigned not null default 0,
    month                char(7)         not null comment '提交所属自然月 YYYY-MM',
    project_id           bigint unsigned not null comment 'CODING项目ID',
    project_name         varchar(128)    not null comment 'CODING项目路径/名称',
    project_display_name varchar(128)    not null default '' comment '项目展示名快照',
    depot_id             bigint unsigned not null comment 'CODING仓库ID',
    depot_name           varchar(128)    not null comment '仓库名称',
    sha                  varchar(64)     not null comment 'Git提交SHA',
    short_sha            varchar(12)     not null comment '短SHA',
    author_name          varchar(128)    not null default '' comment 'Git作者名，仅展示',
    author_email_hash    char(64)        null comment '作者邮箱SHA-256，不存明文邮箱',
    author_name_key      varchar(64)     not null default '' comment '归一化作者名SHA-256，空名不参与匹配',
    commit_at            datetime        not null comment '作者提交时间（Asia/Shanghai）',
    commit_date          date            not null comment '作者提交日期（Asia/Shanghai）',
    message              varchar(1024)   not null default '' comment '提交说明，截断保存，仅纯文本展示',
    branch_names         text            null comment '该提交出现过的分支名JSON数组，最多50个',
    branch_count         int unsigned default 0 not null comment '该提交出现过的分支数',
    branch_truncated     tinyint(1) default 0 not null comment '分支名是否因上限截断',
    additions            bigint unsigned null comment '新增行数；null=未统计',
    deletions            bigint unsigned null comment '删除行数；null=未统计',
    files_changed        int unsigned    null comment '修改文件数；null=未统计',
    file_type_stats      json            null comment '各扩展名新增与删除行数，SQL NULL 表示未统计',
    file_types_checked_at datetime       null comment '历史文件类型最近一次补算时间',
    diff_status          varchar(24)     not null comment 'PENDING/OK/SKIPPED_UNMAPPED/SKIPPED_MERGE/SKIPPED_REPOSITORY/FAILED/TOO_LARGE',
    diff_retry_attempts  tinyint unsigned not null default 0,
    diff_retry_after     datetime         null,
    diff_retry_credential_signature char(64) null,
    diff_retry_error     varchar(32)      null,
    is_merge             tinyint(1) default 0 not null comment '是否合并提交（Parents>1）',
    commit_url           varchar(768)    not null default '' comment 'CODING提交Web地址',
    last_seen_scan_id    bigint unsigned not null default 0 comment '最近一次看到该提交的全量扫描任务ID',
    create_time          datetime default current_timestamp not null,
    update_time          datetime default current_timestamp not null on update current_timestamp,
    author_conflict tinyint NOT NULL DEFAULT 0,
    primary key (id),
    unique key uk_org_depot_sha (coding_team_id, depot_id, sha),
    key idx_org_commit_date (coding_team_id, commit_date, depot_id),
    key idx_code_scope_date (coding_team_id, scope_type, scope_id, commit_date),
    key idx_code_diff_retry (coding_team_id, scope_type, scope_id, diff_status, commit_date),
    key idx_scope_month_date (coding_team_id, scope_type, scope_id, month, commit_date),
    key idx_scope_month_depot (coding_team_id, scope_type, scope_id, month, depot_id),
    key idx_scope_month_email (coding_team_id, scope_type, scope_id, month, author_email_hash),
    key idx_scope_month_name (coding_team_id, scope_type, scope_id, month, author_name_key),
    key idx_scope_seen (coding_team_id, scope_type, scope_id, month, last_seen_scan_id)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台代码提交统计缓存';

CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_project (
    source_key CHAR(64) NOT NULL,
    project_name VARCHAR(255) NOT NULL,
    credential_fingerprint CHAR(64) NOT NULL,
    synced_through BIGINT NOT NULL DEFAULT 0,
    history_before BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (source_key, project_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_access (
    coding_team_id BIGINT NOT NULL,
    credential_fingerprint CHAR(64) NOT NULL,
    project_names JSON NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    checked_at BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (coding_team_id, credential_fingerprint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_code_access (
    coding_team_id BIGINT UNSIGNED NOT NULL,
    credential_fingerprint CHAR(64) NOT NULL,
    depot_ids JSON NOT NULL,
    checked_at BIGINT NOT NULL,
    complete TINYINT NOT NULL,
    PRIMARY KEY (coding_team_id, credential_fingerprint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DELIMITER $$
CREATE TRIGGER IF NOT EXISTS zg_stats_wl_proj_i AFTER INSERT ON external_zhaogang_worklog_project
FOR EACH ROW
BEGIN

    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT coding_team_id, '*', 1, 1, 0 FROM external_zhaogang_worklog_source WHERE source_key=NEW.source_key
    ON DUPLICATE KEY UPDATE version_no=version_no+1, visibility_version=visibility_version+1;

END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_wl_proj_u AFTER UPDATE ON external_zhaogang_worklog_project
FOR EACH ROW
BEGIN
    IF NEW.credential_fingerprint<>OLD.credential_fingerprint THEN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT coding_team_id, '*', 1, 1, 0 FROM external_zhaogang_worklog_source WHERE source_key=NEW.source_key
    ON DUPLICATE KEY UPDATE version_no=version_no+1, visibility_version=visibility_version+1;
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_shared_commit_i AFTER INSERT ON external_zhaogang_shared_code_commit
FOR EACH ROW
BEGIN
    IF NEW.scope_type = 'SHARED' THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, NEW.month, 1, 0, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_shared_commit_u AFTER UPDATE ON external_zhaogang_shared_code_commit
FOR EACH ROW
BEGIN
    IF NEW.scope_type = 'SHARED' AND (NOT (NEW.commit_at <=> OLD.commit_at)
        OR NOT (NEW.author_email_hash <=> OLD.author_email_hash)
        OR NOT (NEW.author_name_key <=> OLD.author_name_key) OR NEW.is_merge <> OLD.is_merge OR NEW.author_conflict <> OLD.author_conflict
        OR NEW.depot_name <> OLD.depot_name OR (NEW.diff_status = 'DIFF_PERMISSION_DENIED'
        AND OLD.diff_status <> NEW.diff_status)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    ELSEIF NEW.scope_type = 'SHARED' AND (NOT (NEW.additions <=> OLD.additions)
        OR NOT (NEW.deletions <=> OLD.deletions) OR NEW.diff_status <> OLD.diff_status) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, NEW.month, 1, 0, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_shared_commit_d AFTER DELETE ON external_zhaogang_shared_code_commit
FOR EACH ROW
BEGIN
    IF OLD.scope_type = 'SHARED' THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT OLD.coding_team_id, '*', 1, 1, 0 WHERE (OLD.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$


CREATE TRIGGER IF NOT EXISTS zg_stats_shared_scan_u AFTER UPDATE ON external_zhaogang_code_scan_job
FOR EACH ROW
BEGIN
    IF NEW.scope_type='SELF' AND NEW.scan_mode IN ('SYNC','SYNC_V2','SNAPSHOT_V1')
       AND NEW.status<>OLD.status AND NEW.status IN ('COMPLETED','PARTIAL') THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id,stat_month,version_no,visibility_version,code_visibility_ms)
        VALUES (NEW.coding_team_id,'*',1,0,0)
        ON DUPLICATE KEY UPDATE version_no=version_no+1;
    END IF;
END$$

DELIMITER ;

SET @zg_checkpoint_column = IF(EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='external_zhaogang_code_scan_repo'
      AND column_name='shared_checkpoint'
), 'DO 0', 'ALTER TABLE external_zhaogang_code_scan_repo ADD shared_checkpoint TINYINT NOT NULL DEFAULT 0');
PREPARE zg_checkpoint_column_stmt FROM @zg_checkpoint_column;
EXECUTE zg_checkpoint_column_stmt;
DEALLOCATE PREPARE zg_checkpoint_column_stmt;

SET @zg_checkpoint_index = IF(EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='external_zhaogang_code_scan_repo'
      AND index_name='idx_depot_checkpoint'
), 'DO 0', 'ALTER TABLE external_zhaogang_code_scan_repo ADD INDEX idx_depot_checkpoint (depot_id,shared_checkpoint,job_id)');
PREPARE zg_checkpoint_index_stmt FROM @zg_checkpoint_index;
EXECUTE zg_checkpoint_index_stmt;
DEALLOCATE PREPARE zg_checkpoint_index_stmt;

SET @zg_issue_fingerprint = IF(EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='external_zhaogang_worklog_issue_snapshot'
      AND column_name='credential_fingerprint'
), 'DO 0', 'ALTER TABLE external_zhaogang_worklog_issue_snapshot ADD credential_fingerprint CHAR(64) NOT NULL DEFAULT ''''');
PREPARE zg_issue_fingerprint_stmt FROM @zg_issue_fingerprint;
EXECUTE zg_issue_fingerprint_stmt;
DEALLOCATE PREPARE zg_issue_fingerprint_stmt;

-- Observed projects are the only legacy evidence available. The worker verifies the
-- full project directory before using this coverage for any new publication.
INSERT IGNORE INTO external_zhaogang_worklog_project
    (source_key, project_name, credential_fingerprint, synced_through, history_before)
SELECT DISTINCT s.source_key, e.project_name, s.credential_fingerprint, s.synced_through, s.history_before
FROM external_zhaogang_worklog_source s
JOIN external_zhaogang_worklog_entry e ON e.source_key=s.source_key
WHERE s.source_key=SHA2(CONCAT('MEMBER:',s.coding_team_id,':',s.coding_user_id),256)
  AND s.synced_through>0 AND s.blocked=0 AND s.credential_fingerprint<>'';

INSERT IGNORE INTO external_zhaogang_worklog_access(coding_team_id,credential_fingerprint,project_names)
SELECT coding_team_id,credential_fingerprint,JSON_ARRAYAGG(project_name)
FROM (
    SELECT DISTINCT s.coding_team_id,p.credential_fingerprint,p.project_name
    FROM external_zhaogang_worklog_project p
    JOIN external_zhaogang_worklog_source s ON s.source_key=p.source_key
    WHERE p.credential_fingerprint<>''
) projects
GROUP BY coding_team_id,credential_fingerprint;

UPDATE external_zhaogang_worklog_issue_snapshot i
JOIN external_zhaogang_worklog_project p ON p.source_key=i.source_key AND p.project_name=i.project_name
SET i.credential_fingerprint=p.credential_fingerprint
WHERE i.credential_fingerprint='' AND p.credential_fingerprint<>'';

-- Very early installations used more than one source per member. Keep the source rows
-- for audit, and import facts into the stable member key. Never infer coverage from them.
START TRANSACTION;
INSERT IGNORE INTO external_zhaogang_worklog_source
    (source_key,coding_team_id,coding_user_id,credential_fingerprint,state,blocked)
SELECT DISTINCT SHA2(CONCAT('MEMBER:',coding_team_id,':',coding_user_id),256),
       coding_team_id,coding_user_id,'','IDLE',1
FROM external_zhaogang_worklog_source
WHERE source_key<>SHA2(CONCAT('MEMBER:',coding_team_id,':',coding_user_id),256);
INSERT IGNORE INTO external_zhaogang_worklog_entry
    (source_key,worklog_id,coding_team_id,coding_user_id,project_name,issue_id,issue_code,
     start_at,created_at,updated_at,hours,working_desc,observed_at,missing)
SELECT stable_key,worklog_id,coding_team_id,coding_user_id,project_name,issue_id,issue_code,
       start_at,created_at,updated_at,hours,working_desc,observed_at,missing
FROM (
    SELECT legacy.*,SHA2(CONCAT('MEMBER:',coding_team_id,':',coding_user_id),256) AS stable_key,
        ROW_NUMBER() OVER (PARTITION BY coding_team_id,coding_user_id,worklog_id
                           ORDER BY observed_at DESC,missing DESC,source_key) AS pos
    FROM external_zhaogang_worklog_entry legacy
    WHERE source_key<>SHA2(CONCAT('MEMBER:',coding_team_id,':',coding_user_id),256)
) ranked
WHERE pos=1;
UPDATE external_zhaogang_worklog_source
SET state='RETIRED',blocked=1,lease_id='',lease_until=0
WHERE source_key<>SHA2(CONCAT('MEMBER:',coding_team_id,':',coding_user_id),256) AND state<>'RETIRED';
COMMIT;

DROP TEMPORARY TABLE IF EXISTS zg_shared_code_evidence;
CREATE TEMPORARY TABLE zg_shared_code_evidence AS
SELECT coding_team_id, depot_id, sha,
       (COUNT(DISTINCT NULLIF(author_email_hash,'')) > 1 OR COUNT(DISTINCT NULLIF(author_name_key,'')) > 1
        OR COUNT(DISTINCT commit_at)>1 OR COUNT(DISTINCT is_merge)>1) AS author_conflict,
       COUNT(DISTINCT IF(diff_status='OK' AND additions IS NOT NULL AND deletions IS NOT NULL AND files_changed IS NOT NULL,
                         CONCAT(additions,':',deletions,':',files_changed),NULL)) > 1 AS diff_conflict,
       COUNT(DISTINCT IF(diff_status='OK',CAST(file_type_stats AS CHAR),NULL)) > 1 AS types_conflict
FROM external_zhaogang_code_commit
GROUP BY coding_team_id, depot_id, sha;
ALTER TABLE zg_shared_code_evidence ADD PRIMARY KEY (coding_team_id, depot_id, sha);

DROP TEMPORARY TABLE IF EXISTS zg_shared_code_branches;
CREATE TEMPORARY TABLE zg_shared_code_branches AS
SELECT coding_team_id, depot_id, sha, COUNT(*) AS branch_count,
       JSON_ARRAYAGG(branch_name) AS branch_names
FROM (
    SELECT distinct c.coding_team_id, c.depot_id, c.sha, CONVERT(b.branch_name USING utf8mb4) COLLATE utf8mb4_bin AS branch_name
    FROM external_zhaogang_code_commit c
    JOIN JSON_TABLE(IF(JSON_VALID(c.branch_names),c.branch_names,'[]'), '$[*]'
         COLUMNS (branch_name VARCHAR(255) PATH '$')) AS b
) branches
GROUP BY coding_team_id,depot_id,sha;
ALTER TABLE zg_shared_code_branches ADD PRIMARY KEY (coding_team_id, depot_id, sha);

START TRANSACTION;
INSERT IGNORE INTO external_zhaogang_shared_code_commit
(coding_team_id, month, project_id, project_name, project_display_name, depot_id, depot_name, sha, short_sha,
 author_name, author_email_hash, author_name_key, commit_at, commit_date, message, branch_names, branch_count,
 branch_truncated, additions, deletions, files_changed, diff_status, is_merge, commit_url, last_seen_scan_id,
 file_type_stats, author_conflict, diff_retry_attempts, diff_retry_after, diff_retry_credential_signature, diff_retry_error)
SELECT c.coding_team_id, c.month, c.project_id, c.project_name, c.project_display_name, c.depot_id, c.depot_name, c.sha, c.short_sha,
 c.author_name, c.author_email_hash, c.author_name_key, c.commit_at, c.commit_date, c.message,
 IF(b.branch_count>0,CAST(JSON_EXTRACT(b.branch_names,'$[0 to 49]') AS CHAR),c.branch_names),
 GREATEST(COALESCE(b.branch_count,0),c.branch_count), c.branch_truncated OR COALESCE(b.branch_count,0)>50,
 IF(e.diff_conflict,NULL,c.additions), IF(e.diff_conflict,NULL,c.deletions), IF(e.diff_conflict,NULL,c.files_changed),
 IF(e.diff_conflict OR c.diff_status='SKIPPED_UNMAPPED','PENDING',c.diff_status), c.is_merge, c.commit_url, c.last_seen_scan_id,
 IF(e.diff_conflict OR e.types_conflict,NULL,c.file_type_stats), e.author_conflict,
 IF(e.diff_conflict,0,c.diff_retry_attempts), IF(e.diff_conflict,NULL,c.diff_retry_after),
 IF(e.diff_conflict,NULL,c.diff_retry_credential_signature), IF(e.diff_conflict,NULL,c.diff_retry_error)
FROM (
    SELECT original.*, ROW_NUMBER() OVER (
        PARTITION BY coding_team_id,depot_id,sha
        ORDER BY (diff_status='OK' AND additions IS NOT NULL AND deletions IS NOT NULL AND files_changed IS NOT NULL) DESC,
                 (file_type_stats IS NOT NULL) DESC, update_time DESC, id DESC) AS pos
    FROM external_zhaogang_code_commit original
) c
JOIN zg_shared_code_evidence e USING (coding_team_id,depot_id,sha)
LEFT JOIN zg_shared_code_branches b USING (coding_team_id,depot_id,sha)
WHERE c.pos=1;
COMMIT;

-- The missing count must be zero before switching application versions.
SELECT COUNT(*) AS missing_shared_commits
FROM zg_shared_code_evidence e
LEFT JOIN external_zhaogang_shared_code_commit c USING (coding_team_id,depot_id,sha)
WHERE c.id IS NULL;
DROP TEMPORARY TABLE zg_shared_code_evidence;
DROP TEMPORARY TABLE zg_shared_code_branches;

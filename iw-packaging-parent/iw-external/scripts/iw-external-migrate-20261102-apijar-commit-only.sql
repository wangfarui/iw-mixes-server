-- 本地应用业务规则前备份受影响记录；仅处理指定仓库的非合并提交，可重复执行。
-- 不修改提交归属、SHA、提交次数或其他仓库；合并提交仍按原规则排除。
update external_zhaogang_code_commit
   set additions = null, deletions = null, files_changed = null,
       diff_status = 'SKIPPED_REPOSITORY', update_time = current_timestamp
 where binary depot_name = binary 'online.base.apijar.service' and is_merge = 0
   and (diff_status <> 'SKIPPED_REPOSITORY' or additions is not null
        or deletions is not null or files_changed is not null);

-- 部署新版 iw-external 并结束旧版本正在执行的代码扫描后，在工作台数据库执行。
-- 修复个人和团队代码明细中重复的 /git，仅处理链接末尾 SHA 与记录一致的行，可重复执行。
update external_zhaogang_code_commit
   set commit_url = replace(commit_url, '/git/git/commit/', '/git/commit/'),
       update_time = update_time
 where commit_url regexp '^https?://[^/?#]+/p/[^/?#]+/d/[^/?#]+/git/git/commit/([[:xdigit:]]{40}|[[:xdigit:]]{64})$'
   and cast(right(commit_url, char_length(concat('/git/git/commit/', sha))) as binary)
       = cast(concat('/git/git/commit/', sha) as binary);

select row_count() as repaired_commit_urls;

-- 仅操作代码统计功能表；执行前备份，停写，确认当前连接本地 iw_mixes。
set @identity_first_ddl = if(
 (select count(*) from information_schema.columns where table_schema=database()
  and table_name='external_zhaogang_code_scan_job' and column_name='identity_signature')=0,
 'alter table external_zhaogang_code_scan_job add column identity_signature char(64) null comment ''成员身份集合指纹''',
 'select 1');
prepare identity_first_stmt from @identity_first_ddl;
execute identity_first_stmt;
deallocate prepare identity_first_stmt;
-- 旧团队映射导入成员的 SELF 身份；已有个人配置（含忽略/删除）优先，拒绝覆盖。
insert ignore into external_zhaogang_code_identity
 (coding_team_id,scope_type,scope_id,identity_type,identity_key,coding_user_id,ignored,author_name,source,updated_by)
select coding_team_id,'SELF',coding_user_id,identity_type,identity_key,coding_user_id,0,author_name,
       case when source='AUTO' then 'DEFAULT' else 'SELF' end,updated_by
from external_zhaogang_code_identity
where scope_type='WORKBENCH_TEAM' and coding_user_id is not null and ignored=0;

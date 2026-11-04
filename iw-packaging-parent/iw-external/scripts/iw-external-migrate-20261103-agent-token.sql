-- 找钢工作台 Agent 授权令牌。本文件未在当前会话执行。
create table if not exists external_zhaogang_agent_token
(
    coding_team_id bigint unsigned not null comment 'CODING团队ID',
    coding_user_id bigint unsigned not null comment 'CODING用户ID',
    user_name      varchar(128)    not null comment '用户名称快照',
    avatar         varchar(512)    null comment '用户头像快照',
    token_hash     char(64)        not null comment 'Agent令牌SHA-256',
    token_hint     varchar(64)     not null comment '令牌前后缀脱敏展示',
    created_at     datetime default current_timestamp not null comment '生成时间',
    last_used_at   datetime        null comment '最近使用时间',
    primary key (coding_team_id, coding_user_id),
    unique key uk_agent_token_hash (token_hash),
    key idx_agent_token_user (coding_user_id)
) engine = InnoDB default charset = utf8mb4 comment = '找钢工作台Agent访问令牌';

create table if not exists external_zhaogang_coding_credential
(
    coding_team_id    bigint unsigned                    not null comment 'CODING团队ID',
    coding_user_id    bigint unsigned                    not null comment 'CODING用户ID',
    token_plaintext   varchar(512)                       not null comment 'CODING PAT明文备份，仅服务端团队工时查询使用',
    token_fingerprint varchar(128)                       not null comment 'PAT SHA-256指纹',
    user_name         varchar(128)                       not null comment 'CODING用户名称快照',
    avatar            varchar(512)                       null comment 'CODING用户头像快照',
    last_verified_at  datetime                           not null comment '最近一次令牌校验时间',
    create_time       datetime default current_timestamp not null,
    update_time       datetime default current_timestamp not null on update current_timestamp,
    primary key (coding_team_id, coding_user_id),
    key idx_token_fingerprint (token_fingerprint)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台CODING成员凭证备份';

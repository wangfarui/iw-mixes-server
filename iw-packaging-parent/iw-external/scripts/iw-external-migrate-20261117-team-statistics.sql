alter table external_zhaogang_workbench_team_member
    add column managed_statistics tinyint(1) not null default 0 comment '是否使用工作台团队管理令牌统计' after sort_no;

create table external_zhaogang_workbench_team_token
(
    team_id          bigint unsigned not null comment '工作台团队ID',
    token_ciphertext varchar(2048)    not null comment '管理令牌密文',
    owner_user_id    bigint unsigned not null comment '令牌所属CODING用户ID',
    owner_name       varchar(128)    not null comment '令牌所属CODING用户名称',
    verified_at      datetime        not null comment '最近验证时间（北京时间）',
    primary key (team_id)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台团队统计管理令牌';

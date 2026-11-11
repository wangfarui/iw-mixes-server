create table if not exists external_zhaogang_workbench_profile
(
    coding_team_id  bigint unsigned not null comment 'CODING顶层团队ID',
    coding_user_id  bigint unsigned not null comment 'CODING用户ID',
    coding_role     varchar(16) null comment '工作台偏好的CODING角色',
    role_configured tinyint(1) not null default 0 comment '是否已明确设置角色，含清空',
    last_used_at    datetime null comment '最近使用工作台的时间（北京时间）',
    primary key (coding_team_id, coding_user_id)
) engine = InnoDB default charset = utf8mb4 comment = '找钢工作台用户资料';

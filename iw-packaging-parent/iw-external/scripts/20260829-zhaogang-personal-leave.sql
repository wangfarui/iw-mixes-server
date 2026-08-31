create table external_zhaogang_work_calendar_leave
(
    id             bigint unsigned not null auto_increment comment '个人请假记录ID',
    coding_team_id bigint unsigned not null comment 'CODING顶层团队ID',
    coding_user_id bigint unsigned not null comment 'CODING用户ID',
    leave_date     date             not null comment '请假日期',
    create_time    datetime default current_timestamp not null comment '创建时间',
    primary key (id),
    unique key uk_user_date (coding_team_id, coding_user_id, leave_date),
    key idx_leave_date (coding_team_id, leave_date)
) engine = InnoDB default charset = utf8mb4 comment '找钢个人请假日';

create table external_zhaogang_work_calendar
(
    id             bigint unsigned                    not null auto_increment comment '工作日历ID',
    coding_team_id bigint unsigned                    not null comment 'CODING顶层团队ID',
    version_no     int unsigned default 0             not null comment '日历版本，用于工时缓存失效',
    create_time    datetime default current_timestamp not null comment '创建时间',
    update_time    datetime default current_timestamp not null on update current_timestamp comment '更新时间',
    primary key (id),
    unique key uk_coding_team (coding_team_id)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作日历';

create table external_zhaogang_work_calendar_day
(
    id                bigint unsigned                    not null auto_increment comment '日期覆盖ID',
    calendar_id       bigint unsigned                    not null comment '工作日历ID',
    work_date         date                               not null comment '日期',
    day_type          varchar(16)                        not null comment 'WORKDAY/REST_DAY',
    updater_user_id   bigint unsigned                    not null comment '最后修改人CODING用户ID',
    updater_user_name varchar(128)                       not null comment '最后修改人名称快照',
    create_time       datetime default current_timestamp not null comment '创建时间',
    update_time       datetime default current_timestamp not null on update current_timestamp comment '更新时间',
    primary key (id),
    unique key uk_calendar_date (calendar_id, work_date),
    key idx_work_date (work_date)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作日历日期覆盖';

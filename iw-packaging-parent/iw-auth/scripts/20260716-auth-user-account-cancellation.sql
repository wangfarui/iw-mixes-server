-- auth_user 账号注销审计字段增量迁移脚本（MySQL 8.x）

alter table auth_user
    add column cancelled_time datetime null comment '账号注销时间' after last_login_time;

-- base_ai_task 置顶字段增量迁移脚本（MySQL 8.x）

alter table base_ai_task
    add column is_top tinyint(1) unsigned default 0 not null comment '是否置顶(0否 1是)' after task_status,
    add column top_time datetime null comment '置顶时间' after is_top,
    add index idx_user_top_time (user_id, is_top, top_time, last_active_at);

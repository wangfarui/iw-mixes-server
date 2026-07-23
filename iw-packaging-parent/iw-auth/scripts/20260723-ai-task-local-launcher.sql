-- base_ai_task 本地快捷启动字段增量迁移脚本（MySQL 8.x）

alter table base_ai_task
    add column model_provider varchar(64) default '' not null comment '模型提供方' after model_name;

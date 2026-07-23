-- 将衣柜图片优化的外部异步任务协议替换为同步参考图 provider module。

alter table wardrobe_image_optimization_task
    add column error_code varchar(64) default '' not null comment '错误摘要码' after result_image_url;

alter table wardrobe_image_optimization_attempt
    add column model varchar(128) default '' not null comment '实际模型' after provider,
    add column error_code varchar(64) default '' not null comment '错误摘要码' after revised_prompt;

update wardrobe_image_optimization_attempt
set status = 'failed',
    error_code = 'INTEGRATION_ERROR',
    error_message = '服务升级后未完成的图片优化任务需要主动重试',
    complete_time = current_timestamp,
    claim_token = '',
    claim_expire_time = null
where status = 'running';

update wardrobe_image_optimization_task
set status = 'failed',
    error_code = 'INTEGRATION_ERROR',
    error_message = '服务升级后未完成的图片优化任务需要主动重试',
    complete_time = current_timestamp
where status = 'running';

alter table wardrobe_image_optimization_attempt
    drop index idx_runnable,
    drop column external_task_id,
    drop column next_poll_time,
    add key idx_runnable (status, claim_expire_time);

-- 将衣柜图片优化从 Redis + 通用记录迁移为 MySQL 专用持久任务。

create table wardrobe_image_optimization_task
(
    id int unsigned auto_increment not null,
    task_id varchar(64) not null,
    item_id int unsigned not null,
    fingerprint char(64) not null,
    source_image_url varchar(512) default '' not null,
    user_prompt varchar(1024) default '' not null,
    normalized_prompt text null,
    rule_version varchar(64) default '' not null,
    input_snapshot text null,
    status varchar(32) default 'queued' not null,
    current_attempt_no int unsigned default 1 not null,
    result_image_url varchar(512) default '' not null,
    error_message varchar(512) default '' not null,
    result_deleted_time datetime null,
    complete_time datetime null,
    active_item_key varchar(64) generated always as
        (case when deleted = 0 and status in ('queued', 'running') then concat(user_id, ':', item_id) else null end) stored,
    deleted tinyint(1) default 0 not null,
    create_time datetime default CURRENT_TIMESTAMP not null,
    update_time datetime default CURRENT_TIMESTAMP not null,
    user_id int unsigned default 0 not null,
    primary key (id),
    unique key uk_task_id (task_id),
    unique key uk_user_item_fingerprint (user_id, item_id, fingerprint),
    unique key uk_active_item (active_item_key),
    key idx_user_item (user_id, item_id),
    key idx_status (status),
    key idx_user_id (user_id)
) comment '衣柜图片优化任务表';

create table wardrobe_image_optimization_attempt
(
    id int unsigned auto_increment not null,
    task_id varchar(64) not null,
    attempt_no int unsigned default 1 not null,
    status varchar(32) default 'queued' not null,
    claim_token varchar(64) default '' not null,
    claim_expire_time datetime null,
    next_poll_time datetime null,
    start_time datetime null,
    deadline_time datetime null,
    provider varchar(64) default '' not null,
    external_task_id varchar(128) default '' not null,
    result_image_url varchar(512) default '' not null,
    result_mime_type varchar(64) default '' not null,
    revised_prompt varchar(1024) default '' not null,
    error_message varchar(512) default '' not null,
    complete_time datetime null,
    deleted tinyint(1) default 0 not null,
    create_time datetime default CURRENT_TIMESTAMP not null,
    update_time datetime default CURRENT_TIMESTAMP not null,
    user_id int unsigned default 0 not null,
    primary key (id),
    unique key uk_task_attempt (task_id, attempt_no),
    key idx_runnable (status, next_poll_time, claim_expire_time),
    key idx_deadline (status, deadline_time),
    key idx_user_id (user_id)
) comment '衣柜图片优化执行attempt表';

create table wardrobe_image_file_cleanup
(
    id int unsigned auto_increment not null,
    task_id varchar(64) default '' not null,
    item_id int unsigned null,
    attempt_no int unsigned null,
    file_url varchar(512) not null,
    reason varchar(64) default '' not null,
    status varchar(32) default 'pending' not null,
    retry_count int unsigned default 0 not null,
    next_retry_time datetime not null,
    last_attempt_time datetime null,
    last_error varchar(512) default '' not null,
    claim_token varchar(64) default '' not null,
    claim_expire_time datetime null,
    manual_required_time datetime null,
    complete_time datetime null,
    deleted tinyint(1) default 0 not null,
    create_time datetime default CURRENT_TIMESTAMP not null,
    update_time datetime default CURRENT_TIMESTAMP not null,
    user_id int unsigned default 0 not null,
    primary key (id),
    unique key uk_file_url (file_url),
    key idx_runnable (status, next_retry_time, claim_expire_time),
    key idx_user_id (user_id)
) comment '衣柜图片OSS清理任务表';

insert into wardrobe_image_optimization_task
    (task_id, item_id, fingerprint, source_image_url, user_prompt, normalized_prompt, rule_version,
     input_snapshot, status, current_attempt_no, result_image_url, error_message, complete_time,
     deleted, create_time, update_time, user_id)
select if(record.task_id = '', concat('legacy-', record.id), record.task_id),
       cast(record.business_id as unsigned), record.dedupe_key, record.source_image_url, '', record.prompt,
       'legacy/v0', '{}',
       case
           when record.status = 'success' then 'succeeded'
           when record.status = 'processing' and record.external_task_id <> ''
               and not exists (select 1 from ai_image_generate_record newer
                               where newer.business_type = record.business_type
                                 and newer.business_id = record.business_id
                                 and newer.user_id = record.user_id
                                 and newer.status = 'processing' and newer.id > record.id)
               then 'running'
           else 'failed'
       end,
       1, record.result_image_url,
       case when record.status = 'processing' and record.external_task_id = ''
            then '服务升级后无法安全重发旧同步任务，请手动重试' else record.error_message end,
       case when record.status in ('success', 'failed') then record.update_time else null end,
       record.deleted, record.create_time, record.update_time, record.user_id
from ai_image_generate_record record
where record.business_type = 'WARDROBE_ITEM_IMAGE_OPTIMIZE';

insert into wardrobe_image_optimization_attempt
    (task_id, attempt_no, status, next_poll_time, start_time, deadline_time, provider, external_task_id,
     result_image_url, result_mime_type, revised_prompt, error_message, complete_time,
     deleted, create_time, update_time, user_id)
select task.task_id, 1, task.status,
       case when task.status = 'running' then current_timestamp else null end,
       record.create_time,
       case when task.status = 'running' then date_add(current_timestamp, interval 15 minute) else null end,
       'legacy-iw-external', record.external_task_id, record.result_image_url, record.result_mime_type,
       record.revised_prompt, task.error_message, task.complete_time,
       record.deleted, record.create_time, record.update_time, record.user_id
from ai_image_generate_record record
inner join wardrobe_image_optimization_task task
        on task.fingerprint = record.dedupe_key
       and task.user_id = record.user_id
       and task.item_id = cast(record.business_id as unsigned)
where record.business_type = 'WARDROBE_ITEM_IMAGE_OPTIMIZE';

drop table ai_image_generate_record;

-- 衣物 item_image 恢复为原图，当前优化图迁移到业务文件表。
-- 业务文件类型 30 对应 WARDROBE_ITEM_OPTIMIZED_IMAGE。

insert into base_business_file
    (business_type, business_id, file_name, file_url, deleted, create_time, update_time, user_id)
select 30,
       item.id,
       substring_index(substring_index(record.result_image_url, '?', 1), '/', -1),
       record.result_image_url,
       0,
       current_timestamp,
       current_timestamp,
       item.user_id
from wardrobe_item item
         inner join ai_image_generate_record record
                    on record.business_type = 'WARDROBE_ITEM_IMAGE_OPTIMIZE'
                        and record.business_id = cast(item.id as char)
                        and record.user_id = item.user_id
                        and record.status = 'success'
                        and record.result_image_url = item.item_image
                        and record.result_image_url <> ''
                        and record.source_image_url <> ''
                        and record.deleted = 0
         left join ai_image_generate_record newer_record
                   on newer_record.business_type = record.business_type
                       and newer_record.business_id = record.business_id
                       and newer_record.user_id = record.user_id
                       and newer_record.status = 'success'
                       and newer_record.result_image_url = item.item_image
                       and newer_record.deleted = 0
                       and newer_record.id > record.id
         left join base_business_file business_file
                   on business_file.business_type = 30
                       and business_file.business_id = item.id
                       and business_file.user_id = item.user_id
                       and business_file.deleted = 0
where item.deleted = 0
  and newer_record.id is null
  and business_file.id is null;

update wardrobe_item item
    inner join ai_image_generate_record record
               on record.business_type = 'WARDROBE_ITEM_IMAGE_OPTIMIZE'
                   and record.business_id = cast(item.id as char)
                   and record.user_id = item.user_id
                   and record.status = 'success'
                   and record.result_image_url = item.item_image
                   and record.result_image_url <> ''
                   and record.source_image_url <> ''
                   and record.deleted = 0
    left join ai_image_generate_record newer_record
              on newer_record.business_type = record.business_type
                  and newer_record.business_id = record.business_id
                  and newer_record.user_id = record.user_id
                  and newer_record.status = 'success'
                  and newer_record.result_image_url = item.item_image
                  and newer_record.deleted = 0
                  and newer_record.id > record.id
set item.item_image = record.source_image_url,
    item.update_time = current_timestamp
where item.deleted = 0
  and newer_record.id is null;

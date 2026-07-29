-- 衣物所属人沿用 user_id；历史数据的所属人即原 user_id。
alter table wardrobe_item
    add column create_user_id int unsigned null comment '创建人用户ID' after remark,
    add column update_user_id int unsigned null comment '最近维护人用户ID' after create_user_id,
    add key idx_user_id (user_id, id);

update wardrobe_item
set create_user_id = user_id,
    update_user_id = user_id
where create_user_id is null
   or update_user_id is null;

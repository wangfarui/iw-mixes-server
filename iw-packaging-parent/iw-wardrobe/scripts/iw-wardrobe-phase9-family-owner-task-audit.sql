-- 家庭成员代维护衣物后，任务所属人和实际操作人需要分别审计。

alter table wardrobe_image_optimization_task
    add column requester_user_id int unsigned default 0 not null comment '实际任务发起用户id' after item_id;

update wardrobe_image_optimization_task
set requester_user_id = user_id
where requester_user_id = 0;

alter table wardrobe_image_optimization_attempt
    add column operator_user_id int unsigned default 0 not null comment '本次尝试发起用户id' after attempt_no;

update wardrobe_image_optimization_attempt
set operator_user_id = user_id
where operator_user_id = 0;

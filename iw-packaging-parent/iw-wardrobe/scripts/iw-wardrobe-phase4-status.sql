update wardrobe_item
set status = 2
where status in (3, 4);

alter table wardrobe_item
    modify column status tinyint default 1 not null comment '状态(1在穿 2闲置 5已淘汰)';

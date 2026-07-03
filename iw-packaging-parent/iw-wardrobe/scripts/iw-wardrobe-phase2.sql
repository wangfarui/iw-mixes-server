alter table wardrobe_item
    add column purchase_channel varchar(64) default '' not null comment '购买渠道' after material,
    add column storage_location varchar(64) default '' not null comment '存放位置' after purchase_channel,
    add column custom_tags varchar(255) default '' not null comment '自定义标签' after price;

alter table wardrobe_item
    modify column status tinyint default 1 not null comment '状态(1在穿 2闲置 3清洗中 4待维修 5已淘汰)';

alter table wardrobe_outfit
    add column custom_tags varchar(255) default '' not null comment '自定义标签' after style_tags;

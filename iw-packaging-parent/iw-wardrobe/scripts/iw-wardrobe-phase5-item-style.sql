alter table wardrobe_item
    add column item_style smallint unsigned default 0 not null comment '衣物款式' after category,
    add index idx_item_style (item_style);

alter table wardrobe_outfit_item
    add column item_style smallint unsigned default 0 not null comment '衣物款式快照' after category;

alter table wardrobe_wear_record_item
    add column item_style smallint unsigned default 0 not null comment '衣物款式快照' after category;

update wardrobe_item
set category = case category
                   when 3 then 1
                   when 4 then 3
                   when 5 then 6
                   when 6 then 9
                   when 8 then 11
                   else category
    end
where category in (3, 4, 5, 6, 8);

update wardrobe_outfit_item
set category = case category
                   when 3 then 1
                   when 4 then 3
                   when 5 then 6
                   when 6 then 9
                   when 8 then 11
                   else category
    end
where category in (3, 4, 5, 6, 8);

update wardrobe_wear_record_item
set category = case category
                   when 3 then 1
                   when 4 then 3
                   when 5 then 6
                   when 6 then 9
                   when 8 then 11
                   else category
    end
where category in (3, 4, 5, 6, 8);

alter table wardrobe_item
    modify column category tinyint default 0 not null comment '衣物品类(1上装 2下装 3连衣裙 4内衣 5袜子 6鞋履 7配饰 8帽子 9包袋 10首饰 11其他)';

alter table wardrobe_outfit_item
    modify column category tinyint default 0 not null comment '衣物品类快照';

alter table wardrobe_wear_record_item
    modify column category tinyint default 0 not null comment '衣物品类快照';

create table base_dict
(
    id          int unsigned auto_increment                 not null comment 'id',
    parent_id   int unsigned      default 0                 not null comment '父字典id',
    dict_type   smallint unsigned                           not null comment '字典类型(枚举)',
    dict_code   smallint unsigned default 0                 not null comment '字典code',
    dict_name   varchar(32)                                 not null comment '字典名称',
    dict_status tinyint           default 1                 not null comment '字典状态(0禁用 1启用)',
    sort        smallint unsigned default 1                 not null comment '排序',
    deleted     tinyint(1)        default 0                 not null comment '是否删除(true表示已删除, 默认false表示未删除)',
    create_time datetime          default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime          default CURRENT_TIMESTAMP not null comment '更新时间',
    user_id     int unsigned      default 0                 not null comment '用户id',
    primary key (id),
    key idx_dict_type (dict_type)
) comment '字典表';

# 字典表的用户id应该是非空且有实际用户id的，当 user_id == 0 时，表示该字典值为模板字典
alter table base_dict
    modify column user_id int unsigned not null comment '用户id';

create table base_dict_business_relation
(
    id            int unsigned auto_increment not null comment '主键id',
    business_type smallint unsigned           not null comment '业务类型(枚举code)',
    business_id   int unsigned                not null comment '业务id',
    dict_id       int unsigned                not null comment '字典id',
    primary key (id),
    key idx_business_column (business_type, business_id)
) comment '字典业务关联表';

## 初始字典模板数据
insert into base_dict (dict_type, dict_code, dict_name, sort, user_id)
values (3002, 0, '任意时间', 1, 0),
       (3002, 1, '早餐', 2, 0),
       (3002, 2, '午餐', 3, 0),
       (3002, 3, '晚餐', 4, 0),
       (3003, 1, '荤菜', 1, 0),
       (3003, 2, '素菜', 2, 0),
       (3003, 3, '荤素搭配', 3, 0),
       (3004, 1, '正常', 1, 0),
       (3004, 2, '禁用', 2, 0),
       (3004, 3, '售空', 3, 0),
       (4002, 1, '餐饮美食', 1, 0),
       (4002, 2, '日用百货', 2, 0),
       (4002, 3, '交通出行', 3, 0),
       (4002, 4, '充值缴费', 4, 0),
       (4002, 5, '生活服务', 5, 0),
       (4002, 6, '其他', 6, 0),
       (4003, 1, '支出', 1, 0),
       (4003, 2, '收入', 2, 0)
;
insert into base_dict (dict_type, dict_name, sort, user_id)
values (4001, '买菜', 1, 0),
       (4001, '外卖', 2, 0),
       (4001, '朴朴', 3, 0),
       (4001, '聚餐', 4, 0),
       (4001, '固定支出', 5, 0),
       (4001, '旅游', 6, 0)
;
insert into base_dict (dict_type, dict_name, sort, user_id)
values (4011, '工资', 1, 0),
       (4011, '返现', 2, 0),
       (4011, '退款', 3, 0),
       (4011, '奖金', 4, 0)
;

## 字典 - 应用账号 - 应用分类数据初始化
insert into base_dict (dict_type, dict_code, dict_name, sort, user_id)
values (2010, 0, '未分类', 1, 0);

## 字典 - 记账会员类型数据初始化
insert into base_dict (dict_type, dict_code, dict_name, sort, user_id)
values (4004, 1, '音乐', 1, 0),
       (4004, 2, '视频', 2, 0),
       (4004, 3, '购物', 3, 0),
       (4004, 4, '工具', 4, 0),
       (4004, 5, '生活', 5, 0),
       (4004, 6, '教育', 6, 0),
       (4004, 7, '云服务', 7, 0),
       (4004, 8, 'AI', 8, 0),
       (4004, 9, '其他', 9, 0)
;
insert into base_dict (dict_type, dict_code, dict_name, sort, user_id)
values (4005, 1, '按月', 1, 0),
       (4005, 2, '按年', 2, 0),
       (4005, 3, '按周', 3, 0),
       (4005, 4, '按天', 4, 0),
       (4005, 5, '一次性', 5, 0),
       (4005, 6, '自定义', 6, 0),
       (4006, 1, '天', 1, 0),
       (4006, 2, '周', 2, 0),
       (4006, 3, '月', 3, 0),
       (4006, 4, '年', 4, 0)
;

## 字典 - 衣柜数据初始化
insert into base_dict (dict_type, dict_code, dict_name, sort, user_id)
values (5002, 1, '上装', 1, 0),
       (5002, 2, '下装', 2, 0),
       (5002, 3, '连衣裙', 3, 0),
       (5002, 4, '内衣', 4, 0),
       (5002, 5, '袜子', 5, 0),
       (5002, 6, '鞋履', 6, 0),
       (5002, 7, '配饰', 7, 0),
       (5002, 8, '帽子', 8, 0),
       (5002, 9, '包袋', 9, 0),
       (5002, 10, '首饰', 10, 0),
       (5002, 11, '其他', 11, 0),
       (5003, 1, '黑色', 1, 0),
       (5003, 2, '白色', 2, 0),
       (5003, 3, '灰色', 3, 0),
       (5003, 4, '蓝色', 4, 0),
       (5003, 5, '绿色', 5, 0),
       (5003, 6, '红色', 6, 0),
       (5003, 7, '黄色', 7, 0),
       (5003, 8, '粉色', 8, 0),
       (5003, 9, '紫色', 9, 0),
       (5003, 10, '棕色', 10, 0),
       (5003, 11, '米色', 11, 0),
       (5003, 12, '卡其色', 12, 0),
       (5003, 13, '牛仔蓝', 13, 0),
       (5003, 14, '藏青色', 14, 0),
       (5003, 15, '彩色', 15, 0),
       (5004, 1, '日常', 1, 0),
       (5004, 2, '通勤', 2, 0),
       (5004, 3, '约会', 3, 0),
       (5004, 4, '运动', 4, 0),
       (5004, 5, '旅行', 5, 0),
       (5004, 6, '正式', 6, 0),
       (5004, 7, '居家', 7, 0),
       (5004, 8, '户外', 8, 0),
       (5004, 9, '聚会', 9, 0),
       (5005, 1, '休闲', 1, 0),
       (5005, 2, '简约', 2, 0),
       (5005, 3, '利落', 3, 0),
       (5005, 4, '甜美', 4, 0),
       (5005, 5, '街头', 5, 0),
       (5005, 6, '复古', 6, 0),
       (5005, 7, '户外', 7, 0),
       (5005, 8, '运动', 8, 0),
       (5005, 9, '通勤', 9, 0),
       (5005, 10, '优雅', 10, 0),
       (5005, 11, '中性', 11, 0),
       (5005, 12, '学院', 12, 0),
       (5005, 13, '度假', 13, 0)
;
insert into base_dict (parent_id, dict_type, dict_code, dict_name, sort, user_id)
select c.id, 5006, t.dict_code, t.dict_name, t.sort, 0
from (
         select 1 category_code, 101 dict_code, 'T恤' dict_name, 1 sort union all
         select 1, 102, '衬衫', 2 union all select 1, 103, 'Polo衫', 3 union all select 1, 104, '卫衣', 4 union all
         select 1, 105, '毛衣', 5 union all select 1, 106, '针织衫', 6 union all select 1, 107, '打底衫', 7 union all
         select 1, 108, '背心', 8 union all select 1, 109, '吊带', 9 union all select 1, 110, '抹胸', 10 union all
         select 1, 111, '雪纺衫', 11 union all select 1, 112, '马甲', 12 union all select 1, 113, '开衫', 13 union all
         select 1, 114, '防晒衣', 14 union all select 1, 115, '其他上装', 15 union all
         select 2, 201, '牛仔裤', 1 union all select 2, 202, '休闲裤', 2 union all select 2, 203, '西裤', 3 union all
         select 2, 204, '运动裤', 4 union all select 2, 205, '工装裤', 5 union all select 2, 206, '阔腿裤', 6 union all
         select 2, 207, '短裤', 7 union all select 2, 208, '半身裙', 8 union all select 2, 209, '短裙', 9 union all
         select 2, 210, '长裙', 10 union all select 2, 211, '打底裤', 11 union all select 2, 212, '瑜伽裤', 12 union all
         select 2, 213, '背带裤', 13 union all select 2, 214, '其他下装', 14 union all
         select 3, 301, '连衣裙', 1 union all select 3, 302, '衬衫裙', 2 union all select 3, 303, '针织裙', 3 union all
         select 3, 304, '吊带裙', 4 union all select 3, 305, '背心裙', 5 union all select 3, 306, '背带裙', 6 union all
         select 3, 307, '礼服裙', 7 union all select 3, 308, '旗袍', 8 union all select 3, 309, '连体裤', 9 union all
         select 3, 310, '套装裙', 10 union all select 3, 311, '其他连衣裙', 11 union all
         select 4, 401, '文胸', 1 union all select 4, 402, '内裤', 2 union all select 4, 403, '保暖内衣', 3 union all
         select 4, 404, '睡衣', 4 union all select 4, 405, '家居服', 5 union all select 4, 406, '塑身衣', 6 union all
         select 4, 407, '打底背心', 7 union all select 4, 408, '抹胸内衣', 8 union all select 4, 409, '泳衣', 9 union all
         select 4, 410, '其他内衣', 10 union all
         select 5, 501, '短袜', 1 union all select 5, 502, '中筒袜', 2 union all select 5, 503, '长筒袜', 3 union all
         select 5, 504, '船袜', 4 union all select 5, 505, '隐形袜', 5 union all select 5, 506, '堆堆袜', 6 union all
         select 5, 507, '连裤袜', 7 union all select 5, 508, '打底袜', 8 union all select 5, 509, '运动袜', 9 union all
         select 5, 510, '保暖袜', 10 union all select 5, 511, '其他袜子', 11 union all
         select 6, 601, '运动鞋', 1 union all select 6, 602, '休闲鞋', 2 union all select 6, 603, '板鞋', 3 union all
         select 6, 604, '帆布鞋', 4 union all select 6, 605, '皮鞋', 5 union all select 6, 606, '乐福鞋', 6 union all
         select 6, 607, '靴子', 7 union all select 6, 608, '短靴', 8 union all select 6, 609, '长靴', 9 union all
         select 6, 610, '凉鞋', 10 union all select 6, 611, '拖鞋', 11 union all select 6, 612, '高跟鞋', 12 union all
         select 6, 613, '雨鞋', 13 union all select 6, 614, '其他鞋履', 14 union all
         select 7, 701, '围巾', 1 union all select 7, 702, '披肩', 2 union all select 7, 703, '腰带', 3 union all
         select 7, 704, '手套', 4 union all select 7, 705, '领带', 5 union all select 7, 706, '领结', 6 union all
         select 7, 707, '丝巾', 7 union all select 7, 708, '发饰', 8 union all select 7, 709, '眼镜', 9 union all
         select 7, 710, '墨镜', 10 union all select 7, 711, '口罩', 11 union all select 7, 712, '其他配饰', 12 union all
         select 8, 801, '棒球帽', 1 union all select 8, 802, '渔夫帽', 2 union all select 8, 803, '贝雷帽', 3 union all
         select 8, 804, '毛线帽', 4 union all select 8, 805, '鸭舌帽', 5 union all select 8, 806, '遮阳帽', 6 union all
         select 8, 807, '礼帽', 7 union all select 8, 808, '草帽', 8 union all select 8, 809, '空顶帽', 9 union all
         select 8, 810, '其他帽子', 10 union all
         select 9, 901, '双肩包', 1 union all select 9, 902, '托特包', 2 union all select 9, 903, '斜挎包', 3 union all
         select 9, 904, '单肩包', 4 union all select 9, 905, '手提包', 5 union all select 9, 906, '腰包', 6 union all
         select 9, 907, '胸包', 7 union all select 9, 908, '钱包', 8 union all select 9, 909, '卡包', 9 union all
         select 9, 910, '化妆包', 10 union all select 9, 911, '旅行包', 11 union all select 9, 912, '其他包袋', 12 union all
         select 10, 1001, '项链', 1 union all select 10, 1002, '耳钉', 2 union all select 10, 1003, '耳环', 3 union all
         select 10, 1004, '戒指', 4 union all select 10, 1005, '手链', 5 union all select 10, 1006, '手镯', 6 union all
         select 10, 1007, '胸针', 7 union all select 10, 1008, '脚链', 8 union all select 10, 1009, '手表', 9 union all
         select 10, 1010, '其他首饰', 10 union all
         select 11, 1101, '其他款式', 1 union all select 11, 1102, '待分类', 2
     ) t
join base_dict c on c.user_id = 0 and c.dict_type = 5002 and c.dict_code = t.category_code
;

## 初始所有用户的字典数据
## 注意!!! 该脚本需要在 iw-auth-init.sql 之后执行
insert into base_dict(parent_id, dict_type, dict_code, dict_name, dict_status, sort, user_id)
select 0, bd.dict_type, bd.dict_code, bd.dict_name, bd.dict_status, bd.sort, au.id
from auth_user au
join base_dict bd on bd.user_id = 0
where bd.dict_type <> 5006
;
insert into base_dict(parent_id, dict_type, dict_code, dict_name, dict_status, sort, user_id)
select user_category.id, bd.dict_type, bd.dict_code, bd.dict_name, bd.dict_status, bd.sort, au.id
from auth_user au
join base_dict bd on bd.user_id = 0 and bd.dict_type = 5006
join base_dict template_category on template_category.id = bd.parent_id
join base_dict user_category on user_category.user_id = au.id
    and user_category.dict_type = template_category.dict_type
    and user_category.dict_code = template_category.dict_code
;

##  MQ消息消费记录表
create table base_mq_consume_records
(
    id           bigint unsigned auto_increment comment '消息消费记录id',
    service_name varchar(32)                           not null comment '服务名称',
    message_id   varchar(64)                           not null comment '消息id',
    version      varchar(16)                           not null comment '消息版本',
    topic        varchar(64)                           not null comment '消息topic',
    tag          varchar(64) default ''                not null comment '消息tag',
    body         text                                  not null comment '消息体',
    status       tinyint                               not null comment '消费状态(0待消费, 1消费成功, 2消费失败)',
    create_time  datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    user_id      int unsigned                          null comment '用户id',
    primary key (id),
    key idx_message_id (message_id)
) comment 'MQ消息消费记录表';

##  MQ消息生产记录表
create table base_mq_produce_records
(
    id           bigint unsigned auto_increment comment '消息生产记录id',
    service_name varchar(32)                           not null comment '服务名称',
    message_id   varchar(64)                           not null comment '消息id',
    version      varchar(16)                           not null comment '消息版本',
    topic        varchar(64)                           not null comment '消息topic',
    tag          varchar(64) default ''                not null comment '消息tag',
    body         text                                  not null comment '消息体',
    create_time  datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    user_id      int unsigned                          null comment '用户id',
    primary key (id)
) comment 'MQ消息生产记录表';

create table base_business_file (
    id int unsigned auto_increment not null comment 'id',
    business_type smallint not null comment '业务类型',
    business_id int unsigned not null comment '业务id',
    file_name varchar(128) not null comment '文件名称(带后缀)',
    file_url varchar(255) not null comment '文件路径',
    deleted tinyint(1) default 0 not null comment '是否删除(true表示已删除, 默认false表示未删除)',
    create_time datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime default CURRENT_TIMESTAMP not null comment '更新时间',
    user_id int unsigned default 0 not null comment '用户id',
    primary key (id),
    key idx_user_id (user_id)
) comment '业务文件关联表';

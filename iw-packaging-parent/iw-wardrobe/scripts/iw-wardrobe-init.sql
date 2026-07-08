create table wardrobe_item
(
    id             int unsigned auto_increment             not null comment 'id',
    item_name      varchar(64)   default ''                not null comment '衣物名称',
    item_image     varchar(255)  default ''                not null comment '衣物图片',
    category       tinyint       default 0                 not null comment '分类(1上装 2下装 3外套 4连衣/套装 5鞋 6包 7配饰 8其他)',
    color_name     varchar(32)   default ''                not null comment '颜色名称',
    color_hex      varchar(16)   default ''                not null comment '颜色hex',
    season_tags    varchar(64)   default ''                not null comment '季节标签',
    scene_tags     varchar(128)  default ''                not null comment '场景标签',
    style_tags     varchar(128)  default ''                not null comment '风格标签',
    brand          varchar(64)   default ''                not null comment '品牌',
    size           varchar(32)   default ''                not null comment '尺码',
    material       varchar(64)   default ''                not null comment '材质',
    purchase_channel varchar(64) default ''                not null comment '购买渠道',
    storage_location varchar(64) default ''                not null comment '存放位置',
    purchase_date  date                                   null comment '购买日期',
    price          decimal(10,2) default 0                 not null comment '价格',
    custom_tags    varchar(255)  default ''                not null comment '自定义标签',
    status         tinyint       default 1                 not null comment '状态(1在穿 2闲置 5已淘汰)',
    wear_count     int unsigned  default 0                 not null comment '穿着次数',
    last_wear_date date                                   null comment '最近穿着日期',
    remark         varchar(255)  default ''                not null comment '备注',
    deleted        tinyint(1)    default 0                 not null comment '是否删除',
    create_time    datetime      default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time    datetime      default CURRENT_TIMESTAMP not null comment '更新时间',
    user_id        int unsigned  default 0                 not null comment '用户id',
    primary key (id),
    key idx_user_status (user_id, status),
    key idx_category (category),
    key idx_last_wear_date (last_wear_date)
) comment '衣柜衣物表';

create table wardrobe_outfit
(
    id             int unsigned auto_increment             not null comment 'id',
    outfit_name    varchar(64)   default ''                not null comment '搭配名称',
    cover_image    varchar(255)  default ''                not null comment '搭配封面',
    season_tags    varchar(64)   default ''                not null comment '季节标签',
    scene_tags     varchar(128)  default ''                not null comment '场景标签',
    style_tags     varchar(128)  default ''                not null comment '风格标签',
    custom_tags    varchar(255)  default ''                not null comment '自定义标签',
    color_summary  varchar(128)  default ''                not null comment '颜色摘要',
    item_count     tinyint       default 0                 not null comment '衣物数量',
    wear_count     int unsigned  default 0                 not null comment '穿着次数',
    last_wear_date date                                   null comment '最近穿着日期',
    status         tinyint       default 1                 not null comment '状态(1正常 2归档)',
    remark         varchar(255)  default ''                not null comment '备注',
    deleted        tinyint(1)    default 0                 not null comment '是否删除',
    create_time    datetime      default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time    datetime      default CURRENT_TIMESTAMP not null comment '更新时间',
    user_id        int unsigned  default 0                 not null comment '用户id',
    primary key (id),
    key idx_user_status (user_id, status),
    key idx_last_wear_date (last_wear_date)
) comment '衣柜搭配表';

create table wardrobe_outfit_item
(
    id          int unsigned auto_increment             not null comment 'id',
    outfit_id   int unsigned                            not null comment '搭配id',
    item_id     int unsigned                            not null comment '衣物id',
    item_name   varchar(64)   default ''                not null comment '衣物名称快照',
    item_image  varchar(255)  default ''                not null comment '衣物图片快照',
    category    tinyint       default 0                 not null comment '衣物分类快照',
    sort        tinyint       default 0                 not null comment '排序',
    deleted     tinyint(1)    default 0                 not null comment '是否删除',
    create_time datetime      default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime      default CURRENT_TIMESTAMP not null comment '更新时间',
    user_id     int unsigned  default 0                 not null comment '用户id',
    primary key (id),
    key idx_outfit_id (outfit_id),
    key idx_item_id (item_id),
    key idx_user_id (user_id)
) comment '衣柜搭配衣物表';

create table wardrobe_wear_record
(
    id           int unsigned auto_increment             not null comment 'id',
    wear_date    date                                    not null comment '穿着日期',
    outfit_id    int unsigned  default 0                 not null comment '搭配id',
    outfit_name  varchar(64)   default ''                not null comment '搭配名称快照',
    scene_tags   varchar(128)  default ''                not null comment '场景标签',
    weather_text varchar(64)   default ''                not null comment '天气',
    mood_text    varchar(64)   default ''                not null comment '心情',
    record_type  tinyint       default 2                 not null comment '记录类型(1计划 2已穿)',
    item_count   tinyint       default 0                 not null comment '衣物数量',
    remark       varchar(255)  default ''                not null comment '备注',
    deleted      tinyint(1)    default 0                 not null comment '是否删除',
    create_time  datetime      default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time  datetime      default CURRENT_TIMESTAMP not null comment '更新时间',
    user_id      int unsigned  default 0                 not null comment '用户id',
    primary key (id),
    key idx_wear_date (wear_date),
    key idx_outfit_id (outfit_id),
    key idx_user_id (user_id)
) comment '衣柜穿着记录表';

create table wardrobe_wear_record_item
(
    id          int unsigned auto_increment             not null comment 'id',
    record_id   int unsigned                            not null comment '穿着记录id',
    item_id     int unsigned                            not null comment '衣物id',
    item_name   varchar(64)   default ''                not null comment '衣物名称快照',
    item_image  varchar(255)  default ''                not null comment '衣物图片快照',
    category    tinyint       default 0                 not null comment '衣物分类快照',
    sort        tinyint       default 0                 not null comment '排序',
    deleted     tinyint(1)    default 0                 not null comment '是否删除',
    create_time datetime      default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime      default CURRENT_TIMESTAMP not null comment '更新时间',
    user_id     int unsigned  default 0                 not null comment '用户id',
    primary key (id),
    key idx_record_id (record_id),
    key idx_item_id (item_id),
    key idx_user_id (user_id)
) comment '衣柜穿着记录衣物表';

create table ai_image_generate_record
(
    id                       int unsigned auto_increment             not null comment 'id',
    dedupe_key               char(64)                                not null comment '去重key',
    business_type            varchar(64)   default ''                not null comment '业务类型枚举',
    business_custom_category varchar(128)  default ''                not null comment '业务自定义分类',
    business_category        varchar(255)  default ''                not null comment '业务分类',
    business_id              varchar(64)   default ''                not null comment '业务id',
    source_image_url         varchar(512)  default ''                not null comment '源图片地址',
    prompt                   text                                   null comment '生成提示词',
    task_id                  varchar(64)   default ''                not null comment '业务任务id',
    external_task_id         varchar(128)  default ''                not null comment 'AI供应商任务id',
    status                   varchar(32)   default 'processing'      not null comment '状态(processing/success/failed)',
    result_image_url         varchar(512)  default ''                not null comment '生成图片地址',
    result_mime_type         varchar(64)   default ''                not null comment '生成图片MIME类型',
    revised_prompt           varchar(1024) default ''                not null comment '供应商优化后的提示词',
    error_message            varchar(512)  default ''                not null comment '错误信息',
    hit_count                int unsigned  default 1                 not null comment '命中次数',
    last_hit_time            datetime                                null comment '最近命中时间',
    deleted                  tinyint(1)    default 0                 not null comment '是否删除',
    create_time              datetime      default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time              datetime      default CURRENT_TIMESTAMP not null comment '更新时间',
    user_id                  int unsigned  default 0                 not null comment '用户id',
    primary key (id),
    unique key uk_dedupe_key (dedupe_key),
    key idx_business (business_type, business_id),
    key idx_status (status),
    key idx_user_id (user_id)
) comment 'AI图片生成记录表';

## 衣柜字典模板数据
## 先插入/刷新 user_id=0 的模板字典，再调用 /auth-service/dict/repairUserVisibleDictData 为历史用户补齐。

update base_dict bd
join (
         select 5002 dict_type, 1 dict_code, '上装' dict_name, 1 sort union all
         select 5002, 2, '下装', 2 union all select 5002, 3, '连衣裙', 3 union all select 5002, 4, '内衣', 4 union all
         select 5002, 5, '袜子', 5 union all select 5002, 6, '鞋履', 6 union all select 5002, 7, '配饰', 7 union all
         select 5002, 8, '帽子', 8 union all select 5002, 9, '包袋', 9 union all select 5002, 10, '首饰', 10 union all
         select 5002, 11, '其他', 11 union all
         select 5003, 1, '黑色', 1 union all select 5003, 2, '白色', 2 union all select 5003, 3, '灰色', 3 union all
         select 5003, 4, '蓝色', 4 union all select 5003, 5, '绿色', 5 union all select 5003, 6, '红色', 6 union all
         select 5003, 7, '黄色', 7 union all select 5003, 8, '粉色', 8 union all select 5003, 9, '紫色', 9 union all
         select 5003, 10, '棕色', 10 union all select 5003, 11, '米色', 11 union all select 5003, 12, '卡其色', 12 union all
         select 5003, 13, '牛仔蓝', 13 union all select 5003, 14, '藏青色', 14 union all select 5003, 15, '彩色', 15 union all
         select 5004, 1, '日常', 1 union all select 5004, 2, '通勤', 2 union all select 5004, 3, '约会', 3 union all
         select 5004, 4, '运动', 4 union all select 5004, 5, '旅行', 5 union all select 5004, 6, '正式', 6 union all
         select 5004, 7, '居家', 7 union all select 5004, 8, '户外', 8 union all select 5004, 9, '聚会', 9 union all
         select 5005, 1, '休闲', 1 union all select 5005, 2, '简约', 2 union all select 5005, 3, '利落', 3 union all
         select 5005, 4, '甜美', 4 union all select 5005, 5, '街头', 5 union all select 5005, 6, '复古', 6 union all
         select 5005, 7, '户外', 7 union all select 5005, 8, '运动', 8 union all select 5005, 9, '通勤', 9 union all
         select 5005, 10, '优雅', 10 union all select 5005, 11, '中性', 11 union all select 5005, 12, '学院', 12 union all
         select 5005, 13, '度假', 13
     ) t on bd.dict_type = t.dict_type and bd.dict_code = t.dict_code
set bd.dict_name = t.dict_name,
    bd.sort = t.sort
where bd.dict_type in (5002, 5003, 5004, 5005);

insert into base_dict (dict_type, dict_code, dict_name, sort, user_id)
select t.dict_type, t.dict_code, t.dict_name, t.sort, 0
from (
         select 5002 dict_type, 1 dict_code, '上装' dict_name, 1 sort union all
         select 5002, 2, '下装', 2 union all select 5002, 3, '连衣裙', 3 union all select 5002, 4, '内衣', 4 union all
         select 5002, 5, '袜子', 5 union all select 5002, 6, '鞋履', 6 union all select 5002, 7, '配饰', 7 union all
         select 5002, 8, '帽子', 8 union all select 5002, 9, '包袋', 9 union all select 5002, 10, '首饰', 10 union all
         select 5002, 11, '其他', 11 union all
         select 5003, 1, '黑色', 1 union all select 5003, 2, '白色', 2 union all select 5003, 3, '灰色', 3 union all
         select 5003, 4, '蓝色', 4 union all select 5003, 5, '绿色', 5 union all select 5003, 6, '红色', 6 union all
         select 5003, 7, '黄色', 7 union all select 5003, 8, '粉色', 8 union all select 5003, 9, '紫色', 9 union all
         select 5003, 10, '棕色', 10 union all select 5003, 11, '米色', 11 union all select 5003, 12, '卡其色', 12 union all
         select 5003, 13, '牛仔蓝', 13 union all select 5003, 14, '藏青色', 14 union all select 5003, 15, '彩色', 15 union all
         select 5004, 1, '日常', 1 union all select 5004, 2, '通勤', 2 union all select 5004, 3, '约会', 3 union all
         select 5004, 4, '运动', 4 union all select 5004, 5, '旅行', 5 union all select 5004, 6, '正式', 6 union all
         select 5004, 7, '居家', 7 union all select 5004, 8, '户外', 8 union all select 5004, 9, '聚会', 9 union all
         select 5005, 1, '休闲', 1 union all select 5005, 2, '简约', 2 union all select 5005, 3, '利落', 3 union all
         select 5005, 4, '甜美', 4 union all select 5005, 5, '街头', 5 union all select 5005, 6, '复古', 6 union all
         select 5005, 7, '户外', 7 union all select 5005, 8, '运动', 8 union all select 5005, 9, '通勤', 9 union all
         select 5005, 10, '优雅', 10 union all select 5005, 11, '中性', 11 union all select 5005, 12, '学院', 12 union all
         select 5005, 13, '度假', 13
     ) t
where not exists (
    select 1
    from base_dict bd
    where bd.user_id = 0
      and bd.dict_type = t.dict_type
      and bd.dict_code = t.dict_code
);

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
where not exists (
    select 1
    from base_dict bd
    where bd.user_id = 0
      and bd.dict_type = 5006
      and bd.dict_code = t.dict_code
);

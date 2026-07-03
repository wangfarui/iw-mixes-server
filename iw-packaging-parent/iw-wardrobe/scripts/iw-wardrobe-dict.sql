## 衣柜字典模板数据
## 先插入 user_id=0 的模板字典，再调用 /auth-service/dict/repairUserVisibleDictData 为历史用户补齐。

insert into base_dict (dict_type, dict_code, dict_name, sort, user_id)
select t.dict_type, t.dict_code, t.dict_name, t.sort, 0
from (
         select 5002 dict_type, 1 dict_code, '上装' dict_name, 1 sort union all
         select 5002, 2, '下装', 2 union all
         select 5002, 3, '外套', 3 union all
         select 5002, 4, '连衣/套装', 4 union all
         select 5002, 5, '鞋履', 5 union all
         select 5002, 6, '包袋', 6 union all
         select 5002, 7, '配饰', 7 union all
         select 5002, 8, '其他', 8 union all
         select 5003, 1, '黑色', 1 union all
         select 5003, 2, '白色', 2 union all
         select 5003, 3, '灰色', 3 union all
         select 5003, 4, '蓝色', 4 union all
         select 5003, 5, '绿色', 5 union all
         select 5003, 6, '红色', 6 union all
         select 5003, 7, '黄色', 7 union all
         select 5003, 8, '粉色', 8 union all
         select 5003, 9, '紫色', 9 union all
         select 5003, 10, '棕色', 10 union all
         select 5003, 11, '米色', 11 union all
         select 5003, 12, '彩色', 12 union all
         select 5004, 1, '日常', 1 union all
         select 5004, 2, '通勤', 2 union all
         select 5004, 3, '约会', 3 union all
         select 5004, 4, '运动', 4 union all
         select 5004, 5, '旅行', 5 union all
         select 5004, 6, '正式', 6 union all
         select 5004, 7, '居家', 7 union all
         select 5005, 1, '休闲', 1 union all
         select 5005, 2, '简约', 2 union all
         select 5005, 3, '利落', 3 union all
         select 5005, 4, '甜美', 4 union all
         select 5005, 5, '街头', 5 union all
         select 5005, 6, '复古', 6 union all
         select 5005, 7, '户外', 7
     ) t
where not exists (
    select 1
    from base_dict bd
    where bd.user_id = 0
      and bd.dict_type = t.dict_type
      and bd.dict_code = t.dict_code
);

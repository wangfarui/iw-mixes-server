package com.itwray.iw.auth.settings;

import com.itwray.iw.eat.model.enums.*;
import com.itwray.iw.bookkeeping.model.enums.*;
import com.itwray.iw.web.model.enums.BusinessConstantEnum;
import com.itwray.iw.web.exception.BusinessException;
import java.util.*;

/** Business enums are the sole source of supported codes and labels. */
public final class SystemOptionDefinitions {
    private SystemOptionDefinitions() { }
    public record Option(int code, String name, int sort) { }
    public record Definition(String domain, int type, String name, List<Option> options) { }
    private static Definition define(String domain,int type,String name,BusinessConstantEnum[] values) {
        List<Option> options=new ArrayList<>();
        for(int i=0;i<values.length;i++) options.add(new Option(values[i].getCode(),values[i].getName(),i+1));
        return new Definition(domain,type,name,List.copyOf(options));
    }
    public static final List<Definition> ALL=List.of(
        define("kitchen",3003,"菜品分类",DishesTypeEnum.values()),
        define("kitchen",3002,"用餐时间",MealTimeEnum.values()),
        define("kitchen",3004,"菜品状态",DishesStatusEnum.values()),
        define("bookkeeping",4003,"收支类型",RecordCategoryEnum.values()),
        define("bookkeeping",4005,"订阅计费周期",MembershipBillingCycleEnum.values()),
        define("bookkeeping",4006,"订阅周期单位",MembershipCycleUnitEnum.values()));
    public static boolean supports(Integer type) { return ALL.stream().anyMatch(d -> Objects.equals(d.type(),type)); }
    public static boolean retired(Integer type) { return supports(type)||List.of(1001,2001,3001,5001).contains(type==null?-1:type); }
    public static List<Definition> select(String domain,Integer type) {
        List<Definition> list=ALL.stream().filter(d -> (domain.equals("all")||d.domain().equals(domain))&&(type==null||d.type()==type)).toList();
        if(list.isEmpty() || (domain.equals("all")&&type!=null)) throw new BusinessException("不支持的业务选项");
        return list;
    }
}

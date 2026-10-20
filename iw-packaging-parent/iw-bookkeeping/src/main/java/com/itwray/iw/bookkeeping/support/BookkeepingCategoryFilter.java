package com.itwray.iw.bookkeeping.support;
import com.itwray.iw.web.exception.BusinessException;
import java.util.List;
/** Distinguishes real NULL categories from incomplete migration and literal names. */
public final class BookkeepingCategoryFilter {
    private BookkeepingCategoryFilter() { }
    public static void validate(List<String> names,String state) {
        if(state==null)return;
        if(!state.equals("UNCATEGORIZED")&&!state.equals("PENDING"))throw new BusinessException("分类筛选状态不正确");
        if(names!=null&&!names.isEmpty())throw new BusinessException("分类名称与分类状态不能同时筛选");
    }
}

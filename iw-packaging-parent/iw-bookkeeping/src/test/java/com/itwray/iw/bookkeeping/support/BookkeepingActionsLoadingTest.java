package com.itwray.iw.bookkeeping.support;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.itwray.iw.bookkeeping.dao.BookkeepingActionsDao;
import com.itwray.iw.bookkeeping.model.entity.BookkeepingActionsEntity;
import com.itwray.iw.bookkeeping.service.impl.BookkeepingActionsServiceImpl;
import com.itwray.iw.web.bookkeeping.BookkeepingDictionaryAccess;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import com.itwray.iw.web.utils.UserUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookkeepingActionsLoadingTest {
    @Test void listBatchesReferencesAndKeepsUnavailableActionsIsolated() {
        var dao=mock(BookkeepingActionsDao.class);
        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<BookkeepingActionsEntity> query=mock(LambdaQueryChainWrapper.class,invocation -> {
            if(List.of("eq","orderByAsc").contains(invocation.getMethod().getName())) return invocation.getMock();
            return RETURNS_DEFAULTS.answer(invocation);
        });
        when(dao.lambdaQuery()).thenReturn(query);
        when(query.list()).thenReturn(List.of(action(1,10,"20"),action(2,10,"20"),
                action(3,99,""),action(4,10,"broken"),action(5,20,"")));
        var access=mock(BookkeepingDictionaryAccess.class);
        var category=new BaseDictEntity(); category.setId(11); category.setDictType(4002); category.setDictName("改名后餐饮");
        var tag=new BaseDictEntity(); tag.setId(20); tag.setDictType(4001); tag.setDictName("外卖");
        when(access.resolveSuccessors(7,new TreeSet<>(List.of(10,20,99)))).thenReturn(Map.of(10,category,20,tag));
        var service=new BookkeepingActionsServiceImpl(dao);
        ReflectionTestUtils.setField(service,"dictionaries",access);
        UserUtils.setUserId(7);
        try {
            var result=service.list(1);
            assertEquals(5,result.size());
            assertTrue(result.get(0).getAvailable()); assertTrue(result.get(1).getAvailable());
            assertEquals(11,result.get(0).getRecordTypeId());
            assertEquals("改名后餐饮",result.get(0).getRecordTypeName());
            assertEquals("外卖",result.get(0).getRecordTagSnapshots().get(0).getName());
            assertFalse(result.get(2).getAvailable(),"失效分类不能被接受");
            assertFalse(result.get(3).getAvailable(),"单个损坏配置不影响其它快捷项");
            assertFalse(result.get(4).getAvailable(),"标签ID不能被当成分类ID");
            verify(access).resolveSuccessors(7,new TreeSet<>(List.of(10,20,99)));
            verifyNoMoreInteractions(access);
        } finally { UserUtils.removeUserId(); }
    }

    private BookkeepingActionsEntity action(int id,int category,String tags) {
        var action=new BookkeepingActionsEntity(); action.setId(id); action.setRecordCategory(1);
        action.setRecordTypeId(category); action.setRecordTags(tags); return action;
    }
}

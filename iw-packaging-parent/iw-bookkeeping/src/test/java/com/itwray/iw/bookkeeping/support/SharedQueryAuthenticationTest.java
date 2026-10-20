package com.itwray.iw.bookkeeping.support;

import com.itwray.iw.web.core.aop.SharedQueryScopeAspect;
import com.itwray.iw.web.core.mybatis.UserCurrentGroupProvider;
import com.itwray.iw.web.core.mybatis.UserSharedQueryPolicy;
import com.itwray.iw.web.utils.ApplicationContextHolder;
import com.itwray.iw.web.utils.UserCurrentGroupUtils;
import com.itwray.iw.web.utils.UserSharedQueryUtils;
import com.itwray.iw.web.utils.UserUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SharedQueryAuthenticationTest {
    @Test
    void resolvesLazyIdentityBeforeApplyingChildSharingPolicy() throws Throwable {
        GenericApplicationContext context = new GenericApplicationContext();
        UserCurrentGroupProvider provider = mock(UserCurrentGroupProvider.class);
        when(provider.querySharedQueryPolicy(44)).thenReturn(new UserSharedQueryPolicy(2, true));
        context.registerBean(UserCurrentGroupProvider.class, () -> provider);
        context.refresh();
        new ApplicationContextHolder().setApplicationContext(context);
        ReflectionTestUtils.setField(SharedQueryScopeAspect.class, "userCurrentGroupProviderResolved", false);
        ReflectionTestUtils.setField(SharedQueryScopeAspect.class, "userCurrentGroupProvider", null);
        try (MockedStatic<UserUtils> identity = mockStatic(UserUtils.class)) {
            identity.when(() -> UserUtils.getUserId(false)).thenReturn(null);
            identity.when(UserUtils::getUserId).thenReturn(44);
            ProceedingJoinPoint query = mock(ProceedingJoinPoint.class);
            when(query.getArgs()).thenReturn(new Object[0]);
            when(query.proceed()).thenAnswer(call -> {
                assertTrue(UserSharedQueryUtils.getUserSharedQueryOnlyMyself(), "Child restriction must precede the first mapper query");
                assertEquals(2, UserCurrentGroupUtils.getCurrentGroupId());
                return "filtered";
            });
            assertEquals("filtered", new SharedQueryScopeAspect().around(query));
            verify(provider).querySharedQueryPolicy(44);
            assertFalse(UserSharedQueryUtils.getUserSharedQueryOnlyMyself());
            assertNull(UserCurrentGroupUtils.getCurrentGroupId());
        } finally {
            UserSharedQueryUtils.clearContext();
            UserCurrentGroupUtils.removeCurrentGroupId();
            ReflectionTestUtils.setField(SharedQueryScopeAspect.class, "userCurrentGroupProviderResolved", false);
            ReflectionTestUtils.setField(SharedQueryScopeAspect.class, "userCurrentGroupProvider", null);
            ReflectionTestUtils.setField(ApplicationContextHolder.class, "applicationContext", null);
            context.close();
        }
    }
}

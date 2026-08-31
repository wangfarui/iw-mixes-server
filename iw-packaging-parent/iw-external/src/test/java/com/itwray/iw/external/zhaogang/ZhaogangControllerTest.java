package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.zhaogang.ZhaogangModels.TokenValue;
import com.itwray.iw.external.zhaogang.worklog.WorklogModule;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class ZhaogangControllerTest {

    @Test
    void tokenResponseIsMarkedAsNonCacheable() {
        ZhaogangSessionManager sessionManager = mock(ZhaogangSessionManager.class);
        ZhaogangWorkbenchService workbenchService = mock(ZhaogangWorkbenchService.class);
        ZhaogangCatalogService catalogService = mock(ZhaogangCatalogService.class);
        ZhaogangController controller = new ZhaogangController(sessionManager, workbenchService, catalogService,
                new ZhaogangProperties(), mock(WorklogModule.class));
        ZhaogangSession session = new ZhaogangSession("test-token", 183478L, "tester", "", "g-iijw5014");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(sessionManager.resolve(any(), any())).thenReturn(session);
        when(workbenchService.tokenValue(session)).thenReturn(new TokenValue("test-token"));

        controller.token(new MockHttpServletRequest(), response);

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store, max-age=0");
        assertThat(response.getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        verify(workbenchService).tokenValue(session);
    }

    @Test
    void sessionRebindsCookieAfterRepairingIdentity() {
        ZhaogangSessionManager sessionManager = mock(ZhaogangSessionManager.class);
        ZhaogangWorkbenchService workbenchService = mock(ZhaogangWorkbenchService.class);
        ZhaogangCatalogService catalogService = mock(ZhaogangCatalogService.class);
        ZhaogangController controller = new ZhaogangController(sessionManager, workbenchService, catalogService,
                new ZhaogangProperties(), mock(WorklogModule.class));
        ZhaogangSession corrupted = new ZhaogangSession("test-token", 9292850L, "??????(?????????)", "", "g-iijw5014");
        ZhaogangSession repaired = new ZhaogangSession("test-token", 9292850L, "步步(王发瑞)", "avatar", "g-iijw5014");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(sessionManager.resolve(request, response)).thenReturn(corrupted);
        when(workbenchService.repairSession(corrupted)).thenReturn(repaired);

        controller.session(request, response);

        verify(sessionManager).bind(response, repaired);
        verify(sessionManager, never()).bind(response, corrupted);
    }
}

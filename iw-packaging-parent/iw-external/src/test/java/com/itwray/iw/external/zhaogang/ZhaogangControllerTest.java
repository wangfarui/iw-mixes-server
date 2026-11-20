package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.zhaogang.ZhaogangModels.TokenValue;
import com.itwray.iw.external.zhaogang.k8s.K8sTokenService;
import com.itwray.iw.external.zhaogang.worklog.WorklogModule;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.RangeReport;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
    void rangeReportBindsDatesAndResolvesTheAuthenticatedContext() throws Exception {
        ZhaogangSessionManager sessions = mock(ZhaogangSessionManager.class);
        WorklogModule worklogs = mock(WorklogModule.class);
        ZhaogangProperties properties = new ZhaogangProperties();
        var controller = new ZhaogangController(sessions, mock(ZhaogangWorkbenchService.class),
                mock(ZhaogangCatalogService.class), properties, worklogs, mock(K8sTokenService.class));
        var session = new ZhaogangSession("fixture-token", 100L, "测试成员", "", "fixture", 10L);
        when(sessions.resolve(any(), any())).thenReturn(session);
        var context = new WorklogModule.Context("fixture-token", 100L, "测试成员", "", 10L,
                "fixture", properties.configuredTeamHost());
        var report = new RangeReport(
                "2026-10-28", "2026-11-04", "2026-11-04", null, null, null);
        when(worklogs.report(context, "2026-10-28", "2026-11-03", "WORKBENCH_TEAM", 1L, true))
                .thenReturn(report);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/external-service/api/zhaogang/worklogs/report")
                        .param("from", "2026-10-28").param("to", "2026-11-03")
                        .param("scope", "WORKBENCH_TEAM").param("workbenchTeamId", "1").param("refresh", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from")
                        .value("2026-10-28"))
                .andExpect(jsonPath("$.data.toExclusive")
                        .value("2026-11-04"));
        verify(worklogs).report(context, "2026-10-28", "2026-11-03", "WORKBENCH_TEAM", 1L, true);
    }

    @Test
    void tokenResponseIsMarkedAsNonCacheable() {
        ZhaogangSessionManager sessionManager = mock(ZhaogangSessionManager.class);
        ZhaogangWorkbenchService workbenchService = mock(ZhaogangWorkbenchService.class);
        ZhaogangCatalogService catalogService = mock(ZhaogangCatalogService.class);
        ZhaogangController controller = new ZhaogangController(sessionManager, workbenchService, catalogService,
                new ZhaogangProperties(), mock(WorklogModule.class), mock(K8sTokenService.class));
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
                new ZhaogangProperties(), mock(WorklogModule.class), mock(K8sTokenService.class));
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

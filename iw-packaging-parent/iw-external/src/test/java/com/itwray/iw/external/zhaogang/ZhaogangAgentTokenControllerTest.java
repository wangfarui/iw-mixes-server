package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.zhaogang.agent.ZhaogangAgentModels.TokenStatus;
import com.itwray.iw.external.zhaogang.agent.ZhaogangAgentCodingCredentialException;
import com.itwray.iw.external.zhaogang.agent.ZhaogangAgentTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ZhaogangAgentTokenControllerTest {

    @Test
    void tokenStatusIsNotCacheableAndUsesCookieSession() {
        ZhaogangSessionManager sessionManager = mock(ZhaogangSessionManager.class);
        ZhaogangAgentTokenService tokenService = mock(ZhaogangAgentTokenService.class);
        ZhaogangAgentTokenController controller = new ZhaogangAgentTokenController(sessionManager, tokenService);
        ZhaogangSession session = new ZhaogangSession("pat", 20L, "tester", "", "team", 10L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(sessionManager.resolve(request, response)).thenReturn(session);
        when(tokenService.status(session)).thenReturn(new TokenStatus(true, "zg_ag_abcd••••wxyz", "2026-11-03T10:00:00+08:00", null));

        controller.status(request, response);

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store, max-age=0");
        assertThat(response.getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        verify(tokenService).status(session);
    }

    @Test
    void createReturnsConflictWhenCodingCredentialBackupIsMissing() throws Exception {
        ZhaogangSessionManager sessionManager = mock(ZhaogangSessionManager.class);
        ZhaogangAgentTokenService tokenService = mock(ZhaogangAgentTokenService.class);
        ZhaogangAgentTokenController controller = new ZhaogangAgentTokenController(sessionManager, tokenService);
        ZhaogangSession session = new ZhaogangSession("pat", 20L, "tester", "", "team", 10L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ZhaogangExceptionHandler(sessionManager))
                .build();
        when(sessionManager.resolve(any(), any())).thenReturn(session);
        when(tokenService.create(session)).thenThrow(new ZhaogangAgentCodingCredentialException(
                "当前工作台账号尚未保存 CODING 令牌，请先在设置页重新绑定 CODING 令牌"));

        mvc.perform(post("/external-service/api/zhaogang/agent-token"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0"))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("重新绑定 CODING 令牌")));
    }
}

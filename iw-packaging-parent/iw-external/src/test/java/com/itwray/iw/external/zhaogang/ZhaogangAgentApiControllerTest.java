package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.zhaogang.ZhaogangModels.Build;
import com.itwray.iw.external.zhaogang.ZhaogangModels.Plan;
import com.itwray.iw.external.zhaogang.ZhaogangModels.PlanCatalog;
import com.itwray.iw.external.zhaogang.agent.ZhaogangAgentCodingCredentialException;
import com.itwray.iw.external.zhaogang.agent.ZhaogangAgentTokenException;
import com.itwray.iw.external.zhaogang.agent.ZhaogangAgentTokenService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ZhaogangAgentApiControllerTest {

    private static final ZhaogangSession SESSION = new ZhaogangSession("coding-pat-secret", 20L, "tester", "avatar",
            "team", 10L);

    private static final String PAT = "coding-pat-secret";
    private static final String AGENT_TOKEN = "zg_ag_raw_agent_token";

    @Test
    void validBearerTriggersOnlyPlanBranchAndEnvironmentAndDoesNotLeakCredentials() throws Exception {
        ZhaogangAgentTokenService tokenService = mock(ZhaogangAgentTokenService.class);
        ZhaogangWorkbenchService workbenchService = mock(ZhaogangWorkbenchService.class);
        ZhaogangCatalogService catalogService = mock(ZhaogangCatalogService.class);
        ZhaogangAgentApiController controller = new ZhaogangAgentApiController(tokenService, workbenchService,
                catalogService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ZhaogangExceptionHandler(mock(ZhaogangSessionManager.class)))
                .build();
        when(tokenService.authenticate("Bearer " + AGENT_TOKEN)).thenReturn(SESSION);
        Build build = new Build(2919L, "2919", "QUEUED", "", "release_20260821", "6866f42abc", "王发瑞",
                "", "2026-11-03T10:00:00+08:00", "test");
        when(workbenchService.triggerBuild(eq(SESSION), eq(450L), eq(6196835L), any(ZhaogangTriggerBuildDto.class)))
                .thenReturn(build);

        mvc.perform(post("/external-service/api/zhaogang/agent-api/build-plans/450/6196835/builds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + AGENT_TOKEN)
                        .contentType("application/json")
                        .content("""
                                {"branch":"release_20260821","environment":"test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(2919))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PAT))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(AGENT_TOKEN))));

        ArgumentCaptor<ZhaogangTriggerBuildDto> dto = ArgumentCaptor.forClass(ZhaogangTriggerBuildDto.class);
        verify(workbenchService).triggerBuild(eq(SESSION), eq(450L), eq(6196835L), dto.capture());
        assertThat(dto.getValue().getBranch()).isEqualTo("release_20260821");
        assertThat(dto.getValue().getEnvironment()).isEqualTo("test");
        verify(catalogService).updateLatestBuild(SESSION, 450L, 6196835L, build);
    }

    @Test
    void missingOrInvalidBearerReturnsBusinessUnauthorizedInsteadOfBadRequest() throws Exception {
        ZhaogangAgentTokenService tokenService = mock(ZhaogangAgentTokenService.class);
        ZhaogangAgentApiController controller = new ZhaogangAgentApiController(tokenService,
                mock(ZhaogangWorkbenchService.class), mock(ZhaogangCatalogService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ZhaogangExceptionHandler(mock(ZhaogangSessionManager.class)))
                .build();
        when(tokenService.authenticate(null)).thenThrow(new ZhaogangAgentTokenException("缺少工作台 Agent 令牌，请重新绑定"));
        when(tokenService.authenticate("Bearer zg_ag_stale"))
                .thenThrow(new ZhaogangAgentTokenException("工作台 Agent 令牌无效或已失效，请重新绑定"));

        mvc.perform(get("/external-service/api/zhaogang/agent-api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("缺少工作台 Agent 令牌，请重新绑定"));
        mvc.perform(get("/external-service/api/zhaogang/agent-api/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer zg_ag_stale"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void missingCodingCredentialReturnsConflictAndDoesNotAskToRebindAgentToken() throws Exception {
        ZhaogangAgentTokenService tokenService = mock(ZhaogangAgentTokenService.class);
        ZhaogangAgentApiController controller = new ZhaogangAgentApiController(tokenService,
                mock(ZhaogangWorkbenchService.class), mock(ZhaogangCatalogService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ZhaogangExceptionHandler(mock(ZhaogangSessionManager.class)))
                .build();
        when(tokenService.authenticate("Bearer " + AGENT_TOKEN))
                .thenThrow(new ZhaogangAgentCodingCredentialException(
                        "当前工作台账号尚未保存 CODING 令牌，请先在设置页重新绑定 CODING 令牌"));

        mvc.perform(get("/external-service/api/zhaogang/agent-api/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + AGENT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("重新绑定 CODING 令牌")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("重新绑定工作台 Agent 令牌"))));
    }

    @Test
    void buildPlanSearchUsesProjectAndPlanNames() throws Exception {
        ZhaogangAgentTokenService tokenService = mock(ZhaogangAgentTokenService.class);
        ZhaogangCatalogService catalogService = mock(ZhaogangCatalogService.class);
        ZhaogangAgentApiController controller = new ZhaogangAgentApiController(tokenService,
                mock(ZhaogangWorkbenchService.class), catalogService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(tokenService.authenticate("Bearer " + AGENT_TOKEN)).thenReturn(SESSION);
        Plan matching = new Plan(6196835L, 450L, "pmy-stock", "库存服务", "online.stock.service",
                "master", List.of("test"), true, null);
        Plan other = new Plan(6196836L, 451L, "pmy-order", "订单服务", "online.order.service",
                "master", List.of("test"), true, null);
        when(catalogService.catalog(SESSION)).thenReturn(new PlanCatalog(List.of(), List.of(matching, other),
                List.of(), "2026-11-03T10:00:00+08:00", false));

        mvc.perform(get("/external-service/api/zhaogang/agent-api/build-plans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + AGENT_TOKEN)
                        .param("keyword", "库存"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(6196835))
                .andExpect(jsonPath("$.data[0].projectName").value("pmy-stock"));
    }
}

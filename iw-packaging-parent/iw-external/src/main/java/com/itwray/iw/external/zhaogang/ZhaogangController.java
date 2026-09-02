package com.itwray.iw.external.zhaogang;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.zhaogang.ZhaogangModels.Branch;
import com.itwray.iw.external.zhaogang.ZhaogangModels.Build;
import com.itwray.iw.external.zhaogang.ZhaogangModels.Plan;
import com.itwray.iw.external.zhaogang.ZhaogangModels.PlanCatalog;
import com.itwray.iw.external.zhaogang.ZhaogangModels.PlanDetail;
import com.itwray.iw.external.zhaogang.ZhaogangModels.PlanPageSync;
import com.itwray.iw.external.zhaogang.ZhaogangModels.Project;
import com.itwray.iw.external.zhaogang.ZhaogangModels.SessionStatus;
import com.itwray.iw.external.zhaogang.ZhaogangModels.TokenValue;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Options;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Entries;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Absence;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Statistics;
import com.itwray.iw.external.zhaogang.worklog.WorklogModule;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.k8s.K8sTokenService;
import com.itwray.iw.external.zhaogang.ZhaogangModels.K8sTokenStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 找钢工作台公开接口，只依赖浏览器中已绑定的 CODING 会话。 */
@RestController
@RequestMapping("/external-service/api/zhaogang")
@Validated
@Tag(name = "找钢工作台")
public class ZhaogangController {

    private final ZhaogangSessionManager sessionManager;

    private final ZhaogangWorkbenchService workbenchService;

    private final ZhaogangCatalogService catalogService;

    private final ZhaogangProperties properties;

    private final WorklogModule worklogModule;

    private final CodingCredentialService credentials;

    private final K8sTokenService k8sTokens;

    public ZhaogangController(ZhaogangSessionManager sessionManager, ZhaogangWorkbenchService workbenchService,
                              ZhaogangCatalogService catalogService, ZhaogangProperties properties,
                              WorklogModule worklogModule) {
        this(sessionManager, workbenchService, catalogService, properties, worklogModule, null);
    }

    public ZhaogangController(ZhaogangSessionManager sessionManager, ZhaogangWorkbenchService workbenchService,
                              ZhaogangCatalogService catalogService, ZhaogangProperties properties,
                              WorklogModule worklogModule, CodingCredentialService credentials) {
        this(sessionManager, workbenchService, catalogService, properties, worklogModule, credentials, null);
    }

    @Autowired
    public ZhaogangController(ZhaogangSessionManager sessionManager, ZhaogangWorkbenchService workbenchService,
                              ZhaogangCatalogService catalogService, ZhaogangProperties properties,
                              WorklogModule worklogModule, CodingCredentialService credentials,
                              K8sTokenService k8sTokens) {
        this.sessionManager = sessionManager;
        this.workbenchService = workbenchService;
        this.catalogService = catalogService;
        this.properties = properties;
        this.worklogModule = worklogModule;
        this.credentials = credentials;
        this.k8sTokens = k8sTokens;
    }

    @PostMapping("/session/bind")
    @Operation(summary = "绑定 CODING 个人令牌")
    public GeneralResponse<SessionStatus> bind(@RequestHeader(HttpHeaders.AUTHORIZATION) @NotBlank String authorization,
                                               HttpServletResponse response) {
        String token = tokenFromAuthorization(authorization);
        ZhaogangSession session = workbenchService.bind(token);
        sessionManager.bind(response, session);
        return GeneralResponse.success(workbenchService.status(session));
    }

    @GetMapping("/session")
    @Operation(summary = "获取找钢工作台会话状态")
    public GeneralResponse<SessionStatus> session(HttpServletRequest request, HttpServletResponse response) {
        ZhaogangSession session = sessionManager.resolve(request, response);
        ZhaogangSession repaired = workbenchService.repairSession(session);
        if (!repaired.equals(session)) {
            sessionManager.bind(response, repaired);
        }
        return GeneralResponse.success(workbenchService.status(repaired));
    }

    @PostMapping("/session/token")
    @Operation(summary = "获取当前绑定令牌用于主动复制")
    public GeneralResponse<TokenValue> token(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        return GeneralResponse.success(workbenchService.tokenValue(sessionManager.resolve(request, response)));
    }

    @DeleteMapping("/session")
    @Operation(summary = "解除 CODING 个人令牌绑定")
    public GeneralResponse<Void> unbind(HttpServletRequest request, HttpServletResponse response) {
        try {
            ZhaogangSession session = sessionManager.resolve(request, response);
            catalogService.invalidate(session);
            if (credentials != null) {
                credentials.remove(session.teamId() == null ? 0 : session.teamId(), session.userId());
            }
        } catch (ZhaogangSessionException ignored) {
            // Cookie 已失效时仍允许执行解除绑定。
        }
        sessionManager.clear(response);
        return GeneralResponse.success();
    }

    @GetMapping("/k8s-tokens")
    @Operation(summary = "查询当前用户的 K8s Token 配置状态")
    public GeneralResponse<K8sTokenStatus> k8sTokenStatus(HttpServletRequest request, HttpServletResponse response) {
        ZhaogangSession session = sessionManager.resolve(request, response);
        if (k8sTokens == null) {
            return GeneralResponse.success(new K8sTokenStatus(List.of("test", "uat", "prd"),
                    java.util.Map.of("test", false, "uat", false, "prd", false)));
        }
        return GeneralResponse.success(new K8sTokenStatus(k8sTokens.environments(),
                k8sTokens.statuses(session.teamId() == null ? 0 : session.teamId(), session.userId())));
    }

    @PostMapping("/k8s-tokens")
    @Operation(summary = "保存当前用户的 K8s Token")
    public GeneralResponse<K8sTokenStatus> upsertK8sToken(@Valid @RequestBody com.itwray.iw.external.zhaogang.k8s.ZhaogangK8sTokenDto dto,
                                                          HttpServletRequest request, HttpServletResponse response) {
        ZhaogangSession session = sessionManager.resolve(request, response);
        if (k8sTokens == null) {
            throw new IllegalStateException("K8s Token 服务未初始化");
        }
        k8sTokens.upsert(session.teamId() == null ? 0 : session.teamId(), session.userId(), dto.getEnvironment(), dto.getToken());
        return k8sTokenStatus(request, response);
    }

    @GetMapping("/k8s-tokens/{environment}")
    @Operation(summary = "读取当前用户指定环境的 K8s Token")
    public GeneralResponse<TokenValue> k8sToken(@PathVariable String environment,
                                                HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        ZhaogangSession session = sessionManager.resolve(request, response);
        if (k8sTokens == null) {
            throw new IllegalStateException("K8s Token 服务未初始化");
        }
        return GeneralResponse.success(new TokenValue(k8sTokens.token(session.teamId() == null ? 0 : session.teamId(), session.userId(), environment)));
    }

    @DeleteMapping("/k8s-tokens/{environment}")
    @Operation(summary = "删除当前用户指定环境的 K8s Token")
    public GeneralResponse<K8sTokenStatus> deleteK8sToken(@PathVariable String environment,
                                                          HttpServletRequest request, HttpServletResponse response) {
        ZhaogangSession session = sessionManager.resolve(request, response);
        if (k8sTokens != null) {
            k8sTokens.delete(session.teamId() == null ? 0 : session.teamId(), session.userId(), environment);
        }
        return k8sTokenStatus(request, response);
    }

    @GetMapping("/build-plan-catalog")
    @Operation(summary = "查询缓存的构建计划目录")
    public GeneralResponse<PlanCatalog> buildPlanCatalog(HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(catalogService.catalog(sessionManager.resolve(request, response)));
    }

    @PostMapping("/build-plan-catalog/page-sync")
    @Operation(summary = "同步当前页构建计划的最近构建")
    public GeneralResponse<PlanPageSync> syncBuildPlanPage(@Valid @RequestBody ZhaogangPlanPageSyncDto dto,
                                                            HttpServletRequest request,
                                                            HttpServletResponse response) {
        return GeneralResponse.success(catalogService.syncPage(sessionManager.resolve(request, response), dto));
    }

    @GetMapping("/projects")
    @Operation(summary = "查询可访问项目")
    public GeneralResponse<List<Project>> projects(HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(workbenchService.projects(sessionManager.resolve(request, response)));
    }

    @GetMapping("/projects/{projectId}/build-plans")
    @Operation(summary = "查询项目构建计划")
    public GeneralResponse<List<Plan>> plans(@PathVariable @Min(1) long projectId,
                                              HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(workbenchService.plans(sessionManager.resolve(request, response), projectId));
    }

    @GetMapping("/projects/{projectId}/build-plans/{jobId}")
    @Operation(summary = "查询构建计划详情和最近构建记录")
    public GeneralResponse<PlanDetail> planDetail(@PathVariable @Min(1) long projectId, @PathVariable @Min(1) long jobId,
                                                   HttpServletRequest request, HttpServletResponse response) {
        ZhaogangSession session = sessionManager.resolve(request, response);
        PlanDetail detail = workbenchService.planDetail(session, projectId, jobId);
        if (!detail.builds().isEmpty()) {
            catalogService.updateLatestBuild(session, projectId, jobId, detail.builds().get(0));
        }
        return GeneralResponse.success(detail);
    }

    @GetMapping("/projects/{projectId}/build-plans/{jobId}/branches")
    @Operation(summary = "搜索构建计划关联仓库分支")
    public GeneralResponse<List<Branch>> branches(@PathVariable @Min(1) long projectId, @PathVariable @Min(1) long jobId,
                                                   @RequestParam(required = false) @Size(max = 100) String keyword,
                                                   HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(workbenchService.branches(sessionManager.resolve(request, response), projectId, jobId, keyword));
    }

    @PostMapping("/projects/{projectId}/build-plans/{jobId}/builds")
    @Operation(summary = "触发构建计划")
    public GeneralResponse<Build> triggerBuild(@PathVariable @Min(1) long projectId, @PathVariable @Min(1) long jobId,
                                               @Valid @RequestBody ZhaogangTriggerBuildDto dto,
                                               HttpServletRequest request, HttpServletResponse response) {
        ZhaogangSession session = sessionManager.resolve(request, response);
        Build build = workbenchService.triggerBuild(session, projectId, jobId, dto);
        catalogService.updateLatestBuild(session, projectId, jobId, build);
        return GeneralResponse.success(build);
    }

    @GetMapping("/worklog-options")
    @Operation(summary = "查询工时可选的工作台团队")
    public GeneralResponse<Options> worklogOptions(HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(worklogModule.options(worklogContext(sessionManager.resolve(request, response))));
    }

    @GetMapping("/worklogs/statistics")
    @Operation(summary = "查询月度工时统计")
    public GeneralResponse<Statistics> worklogStatistics(@RequestParam @NotBlank String month,
                                                          @RequestParam @NotBlank String scope,
                                                          @RequestParam(required = false) Long workbenchTeamId,
                                                          @RequestParam(defaultValue = "false") boolean refresh,
                                                          HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(worklogModule.statistics(worklogContext(sessionManager.resolve(request, response)),
                month, scope, workbenchTeamId, refresh));
    }

    @GetMapping("/worklogs/entries")
    @Operation(summary = "查询日期范围内的工时登记")
    public GeneralResponse<Entries> worklogEntries(@RequestParam @NotBlank String from,
                                                    @RequestParam @NotBlank String to,
                                                    @RequestParam @NotBlank String scope,
                                                    @RequestParam(required = false) Long workbenchTeamId,
                                                    @RequestParam(defaultValue = "false") boolean refresh,
                                                    HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(worklogModule.entries(worklogContext(sessionManager.resolve(request, response)),
                from, to, scope, workbenchTeamId, refresh));
    }

    @GetMapping("/worklogs/absences")
    @Operation(summary = "查询月份工作日缺勤统计")
    public GeneralResponse<Absence> worklogAbsences(@RequestParam @NotBlank String month,
                                                     @RequestParam @NotBlank String scope,
                                                     @RequestParam(required = false) Long workbenchTeamId,
                                                     @RequestParam(defaultValue = "false") boolean refresh,
                                                     HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(worklogModule.absences(worklogContext(sessionManager.resolve(request, response)),
                month, scope, workbenchTeamId, refresh));
    }

    private String tokenFromAuthorization(String authorization) {
        String token = authorization.trim();
        if (token.regionMatches(true, 0, "token ", 0, 6)) {
            token = token.substring(6).trim();
        } else if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }
        if (StringUtils.isBlank(token)) {
            throw new CodingOpenApiException("请粘贴 CODING 个人令牌");
        }
        return token;
    }

    private WorklogModule.Context worklogContext(ZhaogangSession session) {
        return new WorklogModule.Context(session.token(), session.userId(), session.userName(), session.avatar(),
                session.teamId() == null ? 0 : session.teamId(), session.team(), properties.configuredTeamHost());
    }
}

package com.itwray.iw.external.zhaogang;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.zhaogang.ZhaogangModels.Branch;
import com.itwray.iw.external.zhaogang.ZhaogangModels.Build;
import com.itwray.iw.external.zhaogang.ZhaogangModels.Plan;
import com.itwray.iw.external.zhaogang.ZhaogangModels.PlanCatalog;
import com.itwray.iw.external.zhaogang.ZhaogangModels.PlanDetail;
import com.itwray.iw.external.zhaogang.agent.ZhaogangAgentModels.Me;
import com.itwray.iw.external.zhaogang.agent.ZhaogangAgentTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 供胖猫云 Skill 调用的 Agent 构建接口，仅接受 Bearer 授权。 */
@RestController
@RequestMapping("/external-service/api/zhaogang/agent-api")
@Validated
@Tag(name = "找钢工作台 Agent API")
public class ZhaogangAgentApiController {

    private final ZhaogangAgentTokenService tokenService;

    private final ZhaogangWorkbenchService workbenchService;

    private final ZhaogangCatalogService catalogService;

    public ZhaogangAgentApiController(ZhaogangAgentTokenService tokenService,
                                      ZhaogangWorkbenchService workbenchService,
                                      ZhaogangCatalogService catalogService) {
        this.tokenService = tokenService;
        this.workbenchService = workbenchService;
        this.catalogService = catalogService;
    }

    @GetMapping("/me")
    @Operation(summary = "查询 Agent 绑定的工作台用户")
    public GeneralResponse<Me> me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                  String authorization) {
        ZhaogangSession session = session(authorization);
        return GeneralResponse.success(new Me(session.teamId(), session.userId(), session.userName(),
                session.avatar(), tokenService.statusFor(session.teamId(), session.userId())));
    }

    @GetMapping("/build-plans")
    @Operation(summary = "检索当前用户可访问的构建计划")
    public GeneralResponse<List<Plan>> plans(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                             String authorization,
                                             @RequestParam(required = false) @Size(max = 100) String keyword) {
        PlanCatalog catalog = catalogService.catalog(session(authorization));
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase();
        return GeneralResponse.success(catalog.plans().stream()
                .filter(plan -> normalized.isEmpty() || searchable(plan).contains(normalized))
                .toList());
    }

    @GetMapping("/build-plans/{projectId}/{jobId}")
    @Operation(summary = "查询构建计划详情")
    public GeneralResponse<PlanDetail> planDetail(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                                  String authorization,
                                                  @PathVariable @Min(1) long projectId,
                                                  @PathVariable @Min(1) long jobId) {
        ZhaogangSession session = session(authorization);
        PlanDetail detail = workbenchService.planDetail(session, projectId, jobId);
        if (!detail.builds().isEmpty()) {
            catalogService.updateLatestBuild(session, projectId, jobId, detail.builds().get(0));
        }
        return GeneralResponse.success(detail);
    }

    @GetMapping("/build-plans/{projectId}/{jobId}/branches")
    @Operation(summary = "检索构建计划关联分支")
    public GeneralResponse<List<Branch>> branches(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                                  String authorization,
                                                  @PathVariable @Min(1) long projectId,
                                                  @PathVariable @Min(1) long jobId,
                                                  @RequestParam(required = false) @Size(max = 100) String keyword) {
        return GeneralResponse.success(workbenchService.branches(session(authorization), projectId, jobId, keyword));
    }

    @PostMapping("/build-plans/{projectId}/{jobId}/builds")
    @Operation(summary = "触发构建计划")
    public GeneralResponse<Build> triggerBuild(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                               String authorization,
                                               @PathVariable @Min(1) long projectId,
                                               @PathVariable @Min(1) long jobId,
                                               @Valid @RequestBody ZhaogangTriggerBuildDto dto) {
        ZhaogangSession session = session(authorization);
        Build build = workbenchService.triggerBuild(session, projectId, jobId, dto);
        catalogService.updateLatestBuild(session, projectId, jobId, build);
        return GeneralResponse.success(build);
    }

    @GetMapping("/build-plans/{projectId}/{jobId}/builds/{buildId}")
    @Operation(summary = "查询指定构建状态")
    public GeneralResponse<Build> build(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                        String authorization,
                                        @PathVariable @Min(1) long projectId,
                                        @PathVariable @Min(1) long jobId,
                                        @PathVariable @Min(1) long buildId) {
        PlanDetail detail = workbenchService.planDetail(session(authorization), projectId, jobId);
        return GeneralResponse.success(detail.builds().stream()
                .filter(build -> build.id() == buildId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到指定构建记录")));
    }

    private ZhaogangSession session(String authorization) {
        return tokenService.authenticate(authorization);
    }

    private String searchable(Plan plan) {
        return (plan.projectName() + " " + plan.projectDisplayName() + " " + plan.name()).toLowerCase();
    }
}

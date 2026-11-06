package com.itwray.iw.external.zhaogang;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/external-service/api/zhaogang/code-stats")
@Validated
@Tag(name = "找钢工作台代码统计")
public class ZhaogangCodeStatsController {

    private final ZhaogangSessionManager sessionManager;
    private final CodeStatsModule module;
    private final ZhaogangProperties properties;

    public ZhaogangCodeStatsController(ZhaogangSessionManager sessionManager, CodeStatsModule module,
                                       ZhaogangProperties properties) {
        this.sessionManager = sessionManager;
        this.module = module;
        this.properties = properties;
    }

    @GetMapping("/options")
    @Operation(summary = "查询代码统计选项")
    public GeneralResponse<CodeStatsModels.Options> options(HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.options(context(request, response)));
    }

    @GetMapping("/statistics")
    @Operation(summary = "查询个人或团队代码统计")
    public GeneralResponse<Object> statistics(@RequestParam @NotBlank String from,
                                              @RequestParam @NotBlank String to,
                                              @RequestParam @NotBlank String scope,
                                              @RequestParam(required = false) Long workbenchTeamId,
                                              @RequestParam(defaultValue = "false") boolean refresh,
                                              HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.statistics(context(request, response), from, to, scope,
                workbenchTeamId, refresh));
    }

    @GetMapping("/member-statistics")
    @Operation(summary = "查询指定团队成员的代码时间序列")
    public GeneralResponse<CodeStatsModels.MemberStatistics> memberStatistics(
            @RequestParam @NotBlank String from, @RequestParam @NotBlank String to,
            @RequestParam @Min(1) long workbenchTeamId, @RequestParam @Min(1) long memberUserId,
            HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.memberStatistics(context(request, response), from, to,
                workbenchTeamId, memberUserId));
    }

    @GetMapping("/entries")
    @Operation(summary = "分页查询我的提交明细")
    public GeneralResponse<CodeStatsModels.EntryPage> entries(@RequestParam @NotBlank String from,
                                                              @RequestParam @NotBlank String to,
                                                              @RequestParam(required = false) Long depotId,
                                                              @RequestParam(required = false) @Size(max = 100) String keyword,
                                                              @RequestParam(defaultValue = "1") int pageNumber,
                                                              @RequestParam(defaultValue = "20") int pageSize,
                                                              @RequestParam(defaultValue = "false") boolean refresh,
                                                              HttpServletRequest request,
                                                              HttpServletResponse response) {
        return GeneralResponse.success(module.entries(context(request, response), from, to, depotId, keyword,
                pageNumber, pageSize, refresh));
    }

    @GetMapping("/scan")
    @Operation(summary = "查询代码扫描状态")
    public GeneralResponse<CodeStatsModels.ScanState> scan(@RequestParam @NotBlank String from,
                                                           @RequestParam @NotBlank String to,
                                                           @RequestParam @NotBlank String scope,
                                                           @RequestParam(required = false) Long workbenchTeamId,
                                                           HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.scan(context(request, response), from, to, scope, workbenchTeamId));
    }

    @PostMapping("/scan")
    @Operation(summary = "手动全量重扫代码")
    public GeneralResponse<CodeStatsModels.ScanTrigger> triggerScan(
            @Valid @RequestBody CodeStatsModels.ScanRequest command,
            HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.triggerScan(context(request, response), command));
    }

    @PostMapping("/scan/{jobId}/cancel")
    @Operation(summary = "取消代码扫描")
    public GeneralResponse<CodeStatsModels.ScanState> cancel(@PathVariable @Min(1) long jobId,
                                                             HttpServletRequest request,
                                                             HttpServletResponse response) {
        return GeneralResponse.success(module.cancel(context(request, response), jobId));
    }

    @GetMapping("/scan/{jobId}/failures")
    @Operation(summary = "查询扫描失败明细")
    public GeneralResponse<CodeStatsModels.Failures> failures(@PathVariable @Min(1) long jobId,
                                                              @RequestParam(defaultValue = "1") int pageNumber,
                                                              @RequestParam(defaultValue = "20") int pageSize,
                                                              HttpServletRequest request,
                                                              HttpServletResponse response) {
        return GeneralResponse.success(module.failures(context(request, response), jobId, pageNumber, pageSize));
    }

    @PostMapping("/scan/{jobId}/diff-retry")
    @Operation(summary = "定向重试指定的失败 diff")
    public GeneralResponse<CodeStatsModels.DiffRetryJob> retryDiffs(@PathVariable @Min(1) long jobId,
                                                                   @Valid @RequestBody CodeStatsModels.DiffRetryRequest command,
                                                                   HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.retryDiffs(context(request, response), jobId, command));
    }

    @GetMapping("/diff-retry/{jobId}")
    @Operation(summary = "查询 diff 重试结果")
    public GeneralResponse<CodeStatsModels.DiffRetryJob> diffRetryStatus(@PathVariable @Min(1) long jobId,
                                                                        HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.diffRetryStatus(context(request, response), jobId));
    }

    @GetMapping("/identities")
    @Operation(summary = "查询代码身份映射和未归属候选")
    public GeneralResponse<CodeStatsModels.IdentityPage> identities(@RequestParam @NotBlank String scope,
                                                                    @RequestParam(required = false) Long workbenchTeamId,
                                                                    @RequestParam(required = false) @Size(max = 100) String keyword,
                                                                    @RequestParam(defaultValue = "1") int pageNumber,
                                                                    @RequestParam(defaultValue = "20") int pageSize,
                                                                    HttpServletRequest request,
                                                                    HttpServletResponse response) {
        return GeneralResponse.success(module.identities(context(request, response), scope, workbenchTeamId,
                keyword, pageNumber, pageSize));
    }

    @PostMapping("/identities")
    @Operation(summary = "新增代码身份映射或忽略")
    public GeneralResponse<CodeStatsModels.IdentityMapping> createIdentity(
            @Valid @RequestBody CodeStatsModels.CreateIdentityRequest command,
            HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.createIdentity(context(request, response), command));
    }

    @PutMapping("/identities/{id}")
    @Operation(summary = "修改代码身份映射")
    public GeneralResponse<CodeStatsModels.IdentityMapping> updateIdentity(
            @PathVariable @Min(1) long id,
            @Valid @RequestBody CodeStatsModels.UpdateIdentityRequest command,
            HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.updateIdentity(context(request, response), id, command));
    }

    @DeleteMapping("/identities/{id}")
    @Operation(summary = "删除手动代码身份映射")
    public GeneralResponse<Void> deleteIdentity(@PathVariable @Min(1) long id, HttpServletRequest request,
                                                HttpServletResponse response) {
        module.deleteIdentity(context(request, response), id);
        return GeneralResponse.success();
    }

    private CodeStatsModule.Context context(HttpServletRequest request, HttpServletResponse response) {
        ZhaogangSession session = sessionManager.resolve(request, response);
        return new CodeStatsModule.Context(session.token(), session.userId(), session.userName(),
                session.avatar(), session.teamId() == null ? 0 : session.teamId(), session.team(),
                properties.configuredTeamHost());
    }
}

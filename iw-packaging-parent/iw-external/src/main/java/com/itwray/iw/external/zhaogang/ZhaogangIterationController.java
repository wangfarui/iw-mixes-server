package com.itwray.iw.external.zhaogang;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Actor;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.AddReleasePlanCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CodingIssueCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CreateChildIssueCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CreateCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IterationDetail;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IterationListItem;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IterationIssue;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IssueCreationOptions;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IssueWorklog;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CodingIssueType;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CodingSyncResult;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IterationQuery;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.PageResult;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.ReplaceMembersCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.ReleasePlan;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.RegisterWorklogCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Stage;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.StageCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.RemoveIssuesCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UpdateCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UpdateIssueCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UpdateIssueStatusCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.SelectionOption;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UserSnapshot;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModule;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.IterationTeamOption;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/external-service/api/zhaogang")
@Tag(name = "找钢工作台团队迭代")
public class ZhaogangIterationController {

    private final ZhaogangSessionManager sessionManager;
    private final TeamIterationModule module;
    private final ZhaogangProperties properties;

    public ZhaogangIterationController(ZhaogangSessionManager sessionManager, TeamIterationModule module,
                                       ZhaogangProperties properties) {
        this.sessionManager = sessionManager;
        this.module = module;
        this.properties = properties;
    }

    @GetMapping("/iterations")
    @Operation(summary = "分页查询当前用户参与的团队迭代")
    public GeneralResponse<PageResult<IterationListItem>> list(
            @RequestParam(required = false) Stage stage,
            @RequestParam(required = false) Long memberUserId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize,
            HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.list(actor(request, response),
                new IterationQuery(stage, memberUserId, keyword, pageNumber, pageSize)));
    }

    @PostMapping("/iterations")
    public GeneralResponse<IterationDetail> create(@RequestBody CreateCommand command,
                                                   HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.create(actor(request, response), command));
    }

    @GetMapping("/iterations/{iterationId}")
    public GeneralResponse<IterationDetail> detail(@PathVariable long iterationId,
                                                   HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.detail(actor(request, response), iterationId));
    }

    @PutMapping("/iterations/{iterationId}")
    public GeneralResponse<IterationDetail> update(@PathVariable long iterationId, @RequestBody UpdateCommand command,
                                                   HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.update(actor(request, response), iterationId, command));
    }

    @PostMapping("/iterations/{iterationId}/stage")
    public GeneralResponse<IterationDetail> transition(@PathVariable long iterationId, @RequestBody StageCommand command,
                                                       HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.transition(actor(request, response), iterationId, command));
    }

    @PutMapping("/iterations/{iterationId}/members")
    public GeneralResponse<IterationDetail> replaceMembers(@PathVariable long iterationId,
                                                           @RequestBody ReplaceMembersCommand command,
                                                           HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.replaceMembers(actor(request, response), iterationId, command));
    }

    @DeleteMapping("/iterations/{iterationId}")
    public GeneralResponse<Void> delete(@PathVariable long iterationId,
                                        HttpServletRequest request, HttpServletResponse response) {
        module.delete(actor(request, response), iterationId);
        return GeneralResponse.success();
    }

    @GetMapping("/iteration-team-members")
    public GeneralResponse<List<UserSnapshot>> teamMembers(@RequestParam(required = false) String keyword,
                                                           HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.teamMembers(actor(request, response), keyword));
    }

    @GetMapping("/iteration-member-options")
    public GeneralResponse<List<IterationTeamOption>> iterationMemberOptions(HttpServletRequest request,
                                                                              HttpServletResponse response) {
        return GeneralResponse.success(module.iterationMemberOptions(actor(request, response)));
    }

    @PostMapping("/iterations/{iterationId}/coding-issues")
    public GeneralResponse<IterationIssue> addCodingIssue(@PathVariable long iterationId,
                                                          @RequestBody CodingIssueCommand command,
                                                          HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.addCodingIssue(actor(request, response), iterationId, command));
    }

    @PostMapping("/iterations/{iterationId}/issues/{parentIssueId}/children")
    public GeneralResponse<IterationIssue> addChildIssue(@PathVariable long iterationId,
                                                         @PathVariable long parentIssueId,
                                                         @RequestBody CreateChildIssueCommand command,
                                                         HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.addChildIssue(actor(request, response), iterationId, parentIssueId,
                command));
    }

    @GetMapping("/iterations/{iterationId}/issues/{parentIssueId}/creation-options")
    public GeneralResponse<IssueCreationOptions> issueCreationOptions(@PathVariable long iterationId,
                                                                      @PathVariable long parentIssueId,
                                                                      @RequestParam CodingIssueType issueType,
                                                                      HttpServletRequest request,
                                                                      HttpServletResponse response) {
        return GeneralResponse.success(module.issueCreationOptions(actor(request, response), iterationId,
                parentIssueId, issueType));
    }

    @GetMapping("/iterations/{iterationId}/issues/{issueId}/edit-options")
    public GeneralResponse<IssueCreationOptions> issueEditOptions(@PathVariable long iterationId,
                                                                  @PathVariable long issueId,
                                                                  HttpServletRequest request,
                                                                  HttpServletResponse response) {
        return GeneralResponse.success(module.issueEditOptions(actor(request, response), iterationId, issueId));
    }

    @GetMapping("/iterations/{iterationId}/issues/{issueId}/status-options")
    public GeneralResponse<List<SelectionOption>> issueStatusOptions(@PathVariable long iterationId,
                                                                       @PathVariable long issueId,
                                                                       HttpServletRequest request,
                                                                       HttpServletResponse response) {
        return GeneralResponse.success(module.issueStatusOptions(actor(request, response), iterationId, issueId));
    }

    @PutMapping("/iterations/{iterationId}/issues/{issueId}")
    public GeneralResponse<IterationIssue> updateIssue(@PathVariable long iterationId,
                                                       @PathVariable long issueId,
                                                       @RequestBody UpdateIssueCommand command,
                                                       HttpServletRequest request,
                                                       HttpServletResponse response) {
        return GeneralResponse.success(module.updateIssue(actor(request, response), iterationId, issueId, command));
    }

    @PutMapping("/iterations/{iterationId}/issues/{issueId}/status")
    public GeneralResponse<IterationIssue> updateIssueStatus(@PathVariable long iterationId,
                                                              @PathVariable long issueId,
                                                              @RequestBody UpdateIssueStatusCommand command,
                                                              HttpServletRequest request,
                                                              HttpServletResponse response) {
        return GeneralResponse.success(module.updateIssueStatus(actor(request, response), iterationId, issueId,
                command));
    }

    @PostMapping("/iterations/{iterationId}/issues/{issueId}/sync")
    public GeneralResponse<IterationIssue> syncIssue(@PathVariable long iterationId, @PathVariable long issueId,
                                                     HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.syncIssue(actor(request, response), iterationId, issueId));
    }

    @PostMapping("/iterations/{iterationId}/sync-coding-issues")
    @Operation(summary = "批量从 CODING 同步迭代事项")
    public GeneralResponse<CodingSyncResult> syncCodingIssues(@PathVariable long iterationId,
                                                              HttpServletRequest request,
                                                              HttpServletResponse response) {
        return GeneralResponse.success(module.syncCodingIssues(actor(request, response), iterationId));
    }

    @PostMapping("/iterations/{iterationId}/issues/{issueId}/worklogs")
    public GeneralResponse<IssueWorklog> registerWorklog(@PathVariable long iterationId,
                                                         @PathVariable long issueId,
                                                         @RequestBody RegisterWorklogCommand command,
                                                         HttpServletRequest request,
                                                         HttpServletResponse response) {
        return GeneralResponse.success(module.registerWorklog(actor(request, response), iterationId, issueId,
                command));
    }

    @PostMapping("/iterations/{iterationId}/issues/{issueId}/worklogs/{worklogId}/retry")
    public GeneralResponse<IssueWorklog> retryWorklog(@PathVariable long iterationId,
                                                      @PathVariable long issueId,
                                                      @PathVariable long worklogId,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        return GeneralResponse.success(module.retryWorklog(actor(request, response), iterationId, issueId,
                worklogId));
    }

    @DeleteMapping("/iterations/{iterationId}/issues/{issueId}")
    public GeneralResponse<Void> removeIssue(@PathVariable long iterationId, @PathVariable long issueId,
                                             HttpServletRequest request, HttpServletResponse response) {
        module.removeIssue(actor(request, response), iterationId, issueId);
        return GeneralResponse.success();
    }

    @PostMapping("/iterations/{iterationId}/issues/batch-delete")
    @Operation(summary = "批量删除工作台迭代事项，不删除 CODING 数据")
    public GeneralResponse<Void> removeIssues(@PathVariable long iterationId,
                                              @RequestBody RemoveIssuesCommand command,
                                              HttpServletRequest request,
                                              HttpServletResponse response) {
        module.removeIssues(actor(request, response), iterationId, command);
        return GeneralResponse.success();
    }

    @PostMapping("/iterations/{iterationId}/release-plans")
    @Operation(summary = "为迭代添加发布构建计划")
    public GeneralResponse<ReleasePlan> addReleasePlan(@PathVariable long iterationId,
                                                       @RequestBody AddReleasePlanCommand command,
                                                       HttpServletRequest request,
                                                       HttpServletResponse response) {
        return GeneralResponse.success(module.addReleasePlan(actor(request, response), iterationId, command));
    }

    @DeleteMapping("/iterations/{iterationId}/release-plans/{releasePlanId}")
    @Operation(summary = "移除迭代发布构建计划")
    public GeneralResponse<Void> removeReleasePlan(@PathVariable long iterationId,
                                                   @PathVariable long releasePlanId,
                                                   HttpServletRequest request,
                                                   HttpServletResponse response) {
        module.removeReleasePlan(actor(request, response), iterationId, releasePlanId);
        return GeneralResponse.success();
    }

    private Actor actor(HttpServletRequest request, HttpServletResponse response) {
        ZhaogangSession session = sessionManager.resolve(request, response);
        return new Actor(session.userId(), session.userName(), session.avatar(), session.token(), session.team(),
                session.teamId() == null ? 0 : session.teamId(), properties.configuredTeamHost());
    }
}

package com.itwray.iw.external.zhaogang;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Actor;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.CreateCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.InvitationPreview;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.LeaveCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.RenameCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.ReorderCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.TeamDetail;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.TeamListItem;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.TransferAdministratorCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.VersionCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.List;

@RestController
@RequestMapping("/external-service/api/zhaogang")
@Tag(name = "找钢工作台团队")
public class ZhaogangTeamController {

    private final ZhaogangSessionManager sessionManager;
    private final ZhaogangProperties properties;
    private final WorkbenchTeamModule module;

    public ZhaogangTeamController(ZhaogangSessionManager sessionManager, ZhaogangProperties properties,
                                  WorkbenchTeamModule module) {
        this.sessionManager = sessionManager;
        this.properties = properties;
        this.module = module;
    }

    @GetMapping("/workbench-teams")
    @Operation(summary = "查询当前用户加入的团队")
    public GeneralResponse<List<TeamListItem>> list(HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.list(actor(request, response)));
    }

    @PutMapping("/workbench-teams/order")
    @Operation(summary = "调整当前用户的团队顺序")
    public GeneralResponse<List<TeamListItem>> reorder(@RequestBody ReorderCommand command,
                                                       HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.reorder(actor(request, response), command));
    }

    @PostMapping("/workbench-teams")
    @Operation(summary = "创建团队")
    public GeneralResponse<TeamDetail> create(@RequestBody CreateCommand command,
                                              HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.create(actor(request, response), command));
    }

    @GetMapping("/workbench-teams/{teamId}")
    @Operation(summary = "查询团队详情")
    public GeneralResponse<TeamDetail> detail(@PathVariable long teamId,
                                              HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.detail(actor(request, response), teamId));
    }

    @PatchMapping("/workbench-teams/{teamId}")
    @Operation(summary = "管理员重命名团队")
    public GeneralResponse<TeamDetail> rename(@PathVariable long teamId, @RequestBody RenameCommand command,
                                              HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.rename(actor(request, response), teamId, command));
    }

    @DeleteMapping("/workbench-teams/{teamId}/members/{userId}")
    @Operation(summary = "管理员移除普通成员")
    public GeneralResponse<TeamDetail> removeMember(@PathVariable long teamId, @PathVariable long userId,
                                                    @RequestBody VersionCommand command,
                                                    HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.removeMember(actor(request, response), teamId, userId, command));
    }

    @PostMapping("/workbench-teams/{teamId}/administrator/transfer")
    @Operation(summary = "转交团队管理员")
    public GeneralResponse<TeamDetail> transferAdministrator(
            @PathVariable long teamId, @RequestBody TransferAdministratorCommand command,
            HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.transferAdministrator(actor(request, response), teamId, command));
    }

    @PostMapping("/workbench-teams/{teamId}/leave")
    @Operation(summary = "退出团队")
    public GeneralResponse<Void> leave(@PathVariable long teamId, @RequestBody LeaveCommand command,
                                       HttpServletRequest request, HttpServletResponse response) {
        module.leave(actor(request, response), teamId, command);
        return GeneralResponse.success();
    }

    @PostMapping("/workbench-teams/{teamId}/dissolve")
    @Operation(summary = "管理员解散团队")
    public GeneralResponse<Void> dissolve(@PathVariable long teamId, @RequestBody VersionCommand command,
                                          HttpServletRequest request, HttpServletResponse response) {
        module.dissolve(actor(request, response), teamId, command);
        return GeneralResponse.success();
    }

    @GetMapping("/workbench-team-invitations/{inviteCode}")
    @Operation(summary = "预览团队邀请")
    public GeneralResponse<InvitationPreview> previewInvitation(
            @PathVariable String inviteCode, HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.previewInvitation(actor(request, response), inviteCode));
    }

    @PostMapping("/workbench-team-invitations/{inviteCode}/join")
    @Operation(summary = "加入团队")
    public GeneralResponse<TeamDetail> join(@PathVariable String inviteCode,
                                            HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(module.join(actor(request, response), inviteCode));
    }

    private Actor actor(HttpServletRequest request, HttpServletResponse response) {
        ZhaogangSession session = sessionManager.resolve(request, response);
        return new Actor(session.userId(), session.userName(), session.avatar(),
                session.teamId() == null ? 0 : session.teamId(), session.team(), properties.configuredTeamHost());
    }
}

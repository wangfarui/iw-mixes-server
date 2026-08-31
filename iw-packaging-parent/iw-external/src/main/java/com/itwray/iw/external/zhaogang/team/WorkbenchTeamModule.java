package com.itwray.iw.external.zhaogang.team;

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
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.WorklogTeamOption;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.WorklogTeamScope;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.IterationTeamOption;

import java.util.List;

public interface WorkbenchTeamModule {

    List<TeamListItem> list(Actor actor);

    List<TeamListItem> reorder(Actor actor, ReorderCommand command);

    TeamDetail create(Actor actor, CreateCommand command);

    TeamDetail detail(Actor actor, long teamId);

    TeamDetail rename(Actor actor, long teamId, RenameCommand command);

    TeamDetail removeMember(Actor actor, long teamId, long targetUserId, VersionCommand command);

    TeamDetail transferAdministrator(Actor actor, long teamId, TransferAdministratorCommand command);

    void leave(Actor actor, long teamId, LeaveCommand command);

    void dissolve(Actor actor, long teamId, VersionCommand command);

    InvitationPreview previewInvitation(Actor actor, String inviteCode);

    TeamDetail join(Actor actor, String inviteCode);

    List<WorklogTeamOption> worklogTeams(Actor actor);

    WorklogTeamScope resolveWorklogScope(Actor actor, long teamId);

    List<IterationTeamOption> iterationMemberOptions(Actor actor);
}

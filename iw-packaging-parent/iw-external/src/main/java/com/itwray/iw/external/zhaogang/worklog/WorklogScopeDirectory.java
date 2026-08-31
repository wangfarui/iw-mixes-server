package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Team;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamException;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Actor;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.WorklogTeamScope;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModule;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Options;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.TeamOption;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class WorklogScopeDirectory {

    private final WorkbenchTeamModule teams;

    private final CodingCredentialService credentials;

    WorklogScopeDirectory(WorkbenchTeamModule teams) {
        this(teams, null);
    }

    @Autowired
    WorklogScopeDirectory(WorkbenchTeamModule teams, CodingCredentialService credentials) {
        this.teams = teams;
        this.credentials = credentials;
    }

    Options options(WorklogModule.Context context) {
        try {
            List<TeamOption> options = teams.worklogTeams(actor(context)).stream()
                    .map(team -> new TeamOption(team.id(), team.name(), team.memberCount()))
                    .toList();
            return new Options(options);
        } catch (WorkbenchTeamException error) {
            throw new CodingOpenApiException(error.getMessage());
        }
    }

    Team selfTeam(WorklogModule.Context context) {
        return new Team(context.codingTeamId(), context.codingTeamKey(), context.codingTeamHost());
    }

    TeamSelection workbenchTeam(WorklogModule.Context context, long workbenchTeamId) {
        try {
            WorklogTeamScope scope = teams.resolveWorklogScope(actor(context), workbenchTeamId);
            List<WorklogModule.MemberCredential> members = scope.members().stream()
                    .map(member -> new WorklogModule.MemberCredential(member.userId(), member.userName(), member.avatar(),
                            credentials == null ? "" : credentials.token(context.codingTeamId(), member.userId()).orElse("")))
                    .toList();
            return new TeamSelection(new Team(scope.id(), scope.name(), scope.codingTeamHost()), members);
        } catch (WorkbenchTeamException error) {
            throw new CodingOpenApiException(error.getMessage());
        }
    }

    private Actor actor(WorklogModule.Context context) {
        return new Actor(context.userId(), context.userName(), context.avatar(), context.codingTeamId(),
                context.codingTeamKey(), context.codingTeamHost());
    }

    record TeamSelection(Team team, List<WorklogModule.MemberCredential> members) {
    }
}

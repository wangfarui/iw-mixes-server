package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Team;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamException;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Actor;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.StatisticsMember;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.TeamStatisticsCredential;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.WorklogTeamScope;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModule;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Options;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.TeamOption;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.User;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
class WorklogScopeDirectory {

    private final WorkbenchTeamModule teams;

    private final CodingCredentialService credentials;

    WorklogScopeDirectory(WorkbenchTeamModule teams, CodingCredentialService credentials) {
        this.teams = teams;
        this.credentials = credentials;
    }

    Options options(WorklogModule.Context context) {
        try {
            List<TeamOption> options = teams.worklogTeams(actor(context)).stream()
                    .map(team -> new TeamOption(team.id(), team.name(), team.members().size(),
                            team.members().stream().map(member ->
                                    new User(member.userId(), member.userName(), member.avatar())).toList()))
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
        WorklogTeamScope scope = requireWorkbenchTeam(context, workbenchTeamId);
        return new TeamSelection(new Team(scope.id(), scope.name(), scope.codingTeamHost()),
                preferredMemberCredentials(credentials, context.codingTeamId(), scope.members(), scope.managementCredential()));
    }

    WorklogModule.MemberCredential self(WorklogModule.Context context) {
        // Latest server binding wins over an old browser cookie. Never restore a removed credential from that cookie.
        return new WorklogModule.MemberCredential(context.userId(),context.userName(),context.avatar(),
                credentials.token(context.codingTeamId(),context.userId()).orElse(""));
    }

    static List<WorklogModule.MemberCredential> preferredMemberCredentials(CodingCredentialService credentials,long teamId,
            List<? extends StatisticsMember> members,TeamStatisticsCredential management) {
        Map<Long,String> tokens=credentials.tokens(teamId,members.stream().map(StatisticsMember::userId).toList());
        return members.stream().map(member->{
            String token=tokens.getOrDefault(member.userId(),"");
            boolean managed=token.isBlank();
            return new WorklogModule.MemberCredential(member.userId(),member.userName(),member.avatar(),
                    managed?management.token():token,managed);
        }).toList();
    }

    static List<WorklogModule.MemberCredential> memberCredentials(CodingCredentialService credentials, long codingTeamId,
            List<? extends StatisticsMember> members, TeamStatisticsCredential managementCredential) {
        Map<Long, String> tokens = credentials.tokens(codingTeamId, members.stream()
                .filter(member -> !member.managedStatistics()).map(StatisticsMember::userId).toList());
        return members.stream().map(member -> new WorklogModule.MemberCredential(
                member.userId(), member.userName(), member.avatar(), member.managedStatistics()
                ? managementCredential.token() : tokens.getOrDefault(member.userId(), ""),
                member.managedStatistics())).toList();
    }

    WorklogTeamScope requireWorkbenchTeam(WorklogModule.Context context, long workbenchTeamId) {
        try {
            return teams.resolveWorklogScope(actor(context), workbenchTeamId);
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

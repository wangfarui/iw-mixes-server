package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Scope;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamException;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Actor;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.TeamListItem;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.WorklogTeamScope;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModule;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class CodeStatsScopeDirectory {

    private final WorkbenchTeamModule teams;
    private final CodingCredentialService credentials;

    CodeStatsScopeDirectory(WorkbenchTeamModule teams, CodingCredentialService credentials) {
        this.teams = teams;
        this.credentials = credentials;
    }

    CodeStatsModule.ScopeInfo resolve(CodeStatsModule.Context context, Scope scope, Long workbenchTeamId) {
        if (scope == Scope.SELF) {
            return new CodeStatsModule.ScopeInfo(scope, context.userId(), context.codingTeamId(),
                    context.codingTeamKey(), context.codingTeamHost(), context.userName(),
                    List.of(new CodeStatsModule.MemberCredential(context.userId(), context.userName(),
                            context.avatar(), context.token())), false);
        }
        if (workbenchTeamId == null || workbenchTeamId <= 0) {
            throw new CodingOpenApiException("团队代码统计必须选择工作台团队");
        }
        Actor actor = actor(context);
        try {
            List<TeamListItem> visibleTeams = teams.list(actor);
            TeamListItem team = visibleTeams.stream().filter(item -> item.id() == workbenchTeamId).findFirst()
                    .orElseThrow(() -> new CodeStatsForbiddenException("你不是该工作台团队成员"));
            WorklogTeamScope teamScope = teams.resolveWorklogScope(actor, workbenchTeamId);
            List<CodeStatsModule.MemberCredential> members = teamScope.members().stream()
                    .map(member -> new CodeStatsModule.MemberCredential(member.userId(), member.userName(),
                            member.avatar(), credentials.token(context.codingTeamId(), member.userId()).orElse("")))
                    .toList();
            return new CodeStatsModule.ScopeInfo(scope, workbenchTeamId, context.codingTeamId(),
                    context.codingTeamKey(), StringUtils.defaultIfBlank(teamScope.codingTeamHost(),
                    context.codingTeamHost()), teamScope.name(), members, team.administrator());
        } catch (WorkbenchTeamException error) {
            throw new CodingOpenApiException(error.getMessage());
        }
    }

    List<TeamListItem> teams(CodeStatsModule.Context context) {
        try {
            return teams.list(actor(context));
        } catch (WorkbenchTeamException error) {
            throw new CodingOpenApiException(error.getMessage());
        }
    }

    private Actor actor(CodeStatsModule.Context context) {
        return new Actor(context.userId(), context.userName(), context.avatar(), context.codingTeamId(),
                context.codingTeamKey(), context.codingTeamHost());
    }
}

package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.ZhaogangCodeSyncTargetMapper;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.AutomaticSyncTarget;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Scope;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamException;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Actor;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.TeamDetail;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.TeamListItem;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModule;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
class CodeStatsScopeDirectory {

    private final WorkbenchTeamModule teams;
    private final CodingCredentialService credentials;
    private final ZhaogangCodeSyncTargetMapper targets;
    private final ZhaogangProperties properties;

    CodeStatsScopeDirectory(WorkbenchTeamModule teams, CodingCredentialService credentials,
                            ZhaogangCodeSyncTargetMapper targets, ZhaogangProperties properties) {
        this.teams = teams;
        this.credentials = credentials;
        this.targets = targets;
        this.properties = properties;
    }

    CodeStatsModule.ScopeInfo resolve(CodeStatsModule.Context context, Scope scope, Long workbenchTeamId) {
        if (scope == Scope.SELF) {
            return new CodeStatsModule.ScopeInfo(scope, context.userId(), context.codingTeamId(),
                    context.codingTeamKey(), context.codingTeamHost(), context.userName(),
                    List.of(new CodeStatsModule.MemberCredential(context.userId(), context.userName(),
                            context.avatar(), context.token())), false,
                    credentials.exists(context.codingTeamId(), context.userId()));
        }
        if (workbenchTeamId == null || workbenchTeamId <= 0) {
            throw new CodingOpenApiException("团队代码统计必须选择工作台团队");
        }
        Actor actor = actor(context);
        try {
            if (teams.list(actor).stream().noneMatch(item -> item.id() == workbenchTeamId)) {
                throw new CodeStatsForbiddenException("你不是该工作台团队成员");
            }
            TeamDetail team = teams.detail(actor, workbenchTeamId);
            List<CodeStatsModule.MemberCredential> members = team.members().stream()
                    .map(member -> new CodeStatsModule.MemberCredential(member.userId(), member.userName(),
                            member.avatar(), credentials.token(context.codingTeamId(), member.userId()).orElse(""),
                            member.codingRole()))
                    .toList();
            return new CodeStatsModule.ScopeInfo(scope, workbenchTeamId, context.codingTeamId(),
                    context.codingTeamKey(), StringUtils.defaultIfBlank(team.codingTeamHost(),
                    context.codingTeamHost()), team.name(), members, team.permissions().administrator());
        } catch (WorkbenchTeamException error) {
            throw new CodingOpenApiException(error.getMessage());
        }
    }

    List<AutomaticSyncTarget> automaticTargets() {
        return targets.selectAutomaticTargets();
    }

    Optional<CodeStatsModule.ScopeInfo> resolveAutomatic(AutomaticSyncTarget target) {
        if (target.scope() == Scope.SELF) {
            return credentials.token(target.codingTeamId(), target.scopeId()).map(token ->
                    new CodeStatsModule.ScopeInfo(Scope.SELF, target.scopeId(), target.codingTeamId(),
                            properties.getTeam(), properties.configuredTeamHost(), "",
                            List.of(new CodeStatsModule.MemberCredential(target.scopeId(), "", "", token)), false));
        }
        return teams.findCodeSyncTeam(target.codingTeamId(), target.scopeId())
                .map(team -> {
                    var members = team.members().stream()
                            .map(member -> new CodeStatsModule.MemberCredential(member.userId(),
                                    member.userName(), member.avatar(),
                                    credentials.token(target.codingTeamId(), member.userId()).orElse("")))
                            .toList();
                    return new CodeStatsModule.ScopeInfo(Scope.WORKBENCH_TEAM, target.scopeId(), target.codingTeamId(),
                            team.codingTeamKey(), team.codingTeamHost(), team.name(), members, false);
                });
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

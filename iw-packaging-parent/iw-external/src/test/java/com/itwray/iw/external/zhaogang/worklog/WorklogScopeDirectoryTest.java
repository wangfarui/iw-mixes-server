package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamException;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Actor;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.WorklogMember;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.WorklogTeamOption;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.WorklogTeamScope;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModule;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorklogScopeDirectoryTest {

    private final WorklogModule.Context context = new WorklogModule.Context("token", 100L, "管理员", "avatar",
            10L, "g-iijw5014", "https://g-iijw5014.coding.net");

    @Test
    void optionsComeFromWorkbenchTeams() {
        WorkbenchTeamModule teams = mock(WorkbenchTeamModule.class);
        when(teams.worklogTeams(actor())).thenReturn(List.of(
                new WorklogTeamOption(1L, "研发团队", 2),
                new WorklogTeamOption(2L, "产品团队", 3)));
        WorklogScopeDirectory directory = new WorklogScopeDirectory(teams);

        WorklogModels.Options options = directory.options(context);

        assertThat(options.teams()).extracting(WorklogModels.TeamOption::name)
                .containsExactly("研发团队", "产品团队");
        assertThat(options.teams()).extracting(WorklogModels.TeamOption::memberCount)
                .containsExactly(2, 3);
        verify(teams).worklogTeams(actor());
    }

    @Test
    void selectedTeamResolvesStoredWorkbenchMembers() {
        WorkbenchTeamModule teams = mock(WorkbenchTeamModule.class);
        when(teams.resolveWorklogScope(actor(), 1L)).thenReturn(new WorklogTeamScope(1L, "研发团队",
                "https://g-iijw5014.coding.net", List.of(
                new WorklogMember(100L, "管理员", "avatar-a"),
                new WorklogMember(200L, "成员", "avatar-b"))));
        WorklogScopeDirectory directory = new WorklogScopeDirectory(teams);

        WorklogScopeDirectory.TeamSelection selection = directory.workbenchTeam(context, 1L);

        assertThat(selection.team().id()).isEqualTo(1L);
        assertThat(selection.team().host()).isEqualTo("https://g-iijw5014.coding.net");
        assertThat(selection.members()).extracting(member -> member.userId())
                .containsExactly(100L, 200L);
        assertThat(selection.members()).allMatch(member -> member.token().isEmpty());
    }

    @Test
    void selectedTeamLoadsEachMemberCredentialByTeamAndUser() {
        WorkbenchTeamModule teams = mock(WorkbenchTeamModule.class);
        CodingCredentialService credentials = mock(CodingCredentialService.class);
        when(teams.resolveWorklogScope(actor(), 1L)).thenReturn(new WorklogTeamScope(1L, "研发团队",
                "https://g-iijw5014.coding.net", List.of(
                new WorklogMember(100L, "管理员", "avatar-a"),
                new WorklogMember(200L, "成员", "avatar-b"))));
        when(credentials.token(10L, 100L)).thenReturn(java.util.Optional.of("admin-token"));
        when(credentials.token(10L, 200L)).thenReturn(java.util.Optional.of("member-token"));
        WorklogScopeDirectory directory = new WorklogScopeDirectory(teams, credentials);

        WorklogScopeDirectory.TeamSelection selection = directory.workbenchTeam(context, 1L);

        assertThat(selection.members()).extracting(member -> member.token())
                .containsExactly("admin-token", "member-token");
        verify(credentials).token(10L, 100L);
        verify(credentials).token(10L, 200L);
    }

    @Test
    void teamMembershipErrorsUseWorklogApiException() {
        WorkbenchTeamModule teams = mock(WorkbenchTeamModule.class);
        when(teams.resolveWorklogScope(actor(), 1L))
                .thenThrow(new WorkbenchTeamException("团队不存在或当前用户不是团队成员"));
        WorklogScopeDirectory directory = new WorklogScopeDirectory(teams);

        assertThatThrownBy(() -> directory.workbenchTeam(context, 1L))
                .isInstanceOf(CodingOpenApiException.class)
                .hasMessage("团队不存在或当前用户不是团队成员");
    }

    private Actor actor() {
        return new Actor(100L, "管理员", "avatar", 10L, "g-iijw5014",
                "https://g-iijw5014.coding.net");
    }
}

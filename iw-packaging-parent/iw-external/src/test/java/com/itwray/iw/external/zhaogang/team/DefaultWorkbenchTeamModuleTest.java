package com.itwray.iw.external.zhaogang.team;

import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Actor;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.LeaveCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.RenameCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.ReorderCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.VersionCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamRepository.StoredTeam;
import com.itwray.iw.external.zhaogang.team.entity.WorkbenchTeamEntities.MemberEntity;
import com.itwray.iw.external.zhaogang.team.entity.WorkbenchTeamEntities.TeamEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultWorkbenchTeamModuleTest {

    private final Actor administrator = new Actor(100L, "管理员", "avatar-a", 10L,
            "g-iijw5014", "https://g-iijw5014.coding.net");
    private final Actor member = new Actor(200L, "成员", "avatar-b", 10L,
            "g-iijw5014", "https://g-iijw5014.coding.net");

    @Test
    void detailExposesSingleAdministratorWithoutMemberRoleState() {
        WorkbenchTeamRepository repository = mock(WorkbenchTeamRepository.class);
        StoredTeam stored = storedTeam();
        when(repository.findById(1L)).thenReturn(Optional.of(stored));
        DefaultWorkbenchTeamModule module = new DefaultWorkbenchTeamModule(repository);

        var detail = module.detail(administrator, 1L);

        assertThat(detail.permissions().administrator()).isTrue();
        assertThat(detail.members()).filteredOn(item -> item.administrator()).singleElement()
                .extracting(WorkbenchTeamModels.Member::userId).isEqualTo(100L);
    }

    @Test
    void ordinaryMemberCannotRenameOrDissolveTeam() {
        WorkbenchTeamRepository repository = mock(WorkbenchTeamRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(storedTeam()));
        DefaultWorkbenchTeamModule module = new DefaultWorkbenchTeamModule(repository);

        assertThatThrownBy(() -> module.rename(member, 1L, new RenameCommand(3, "新名称")))
                .isInstanceOf(WorkbenchTeamException.class).hasMessage("只有团队管理员可以执行此操作");
        assertThatThrownBy(() -> module.dissolve(member, 1L, new VersionCommand(3)))
                .isInstanceOf(WorkbenchTeamException.class).hasMessage("只有团队管理员可以执行此操作");
        verify(repository, never()).dissolve(1L, 3, member);
    }

    @Test
    void administratorMustChooseCurrentMemberBeforeLeaving() {
        WorkbenchTeamRepository repository = mock(WorkbenchTeamRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(storedTeam()));
        DefaultWorkbenchTeamModule module = new DefaultWorkbenchTeamModule(repository);

        assertThatThrownBy(() -> module.leave(administrator, 1L, new LeaveCommand(3, null)))
                .isInstanceOf(WorkbenchTeamException.class).hasMessage("管理员退出前必须选择继任管理员");

        module.leave(administrator, 1L, new LeaveCommand(3, 200L));

        verify(repository).leave(1L, 3, administrator, 200L);
    }

    @Test
    void soleAdministratorCannotLeaveAndMustDissolve() {
        WorkbenchTeamRepository repository = mock(WorkbenchTeamRepository.class);
        StoredTeam stored = storedTeam();
        StoredTeam soleAdministrator = new StoredTeam(stored.team(), List.of(stored.members().get(0)));
        when(repository.findById(1L)).thenReturn(Optional.of(soleAdministrator));
        DefaultWorkbenchTeamModule module = new DefaultWorkbenchTeamModule(repository);

        assertThatThrownBy(() -> module.leave(administrator, 1L, new LeaveCommand(3, 200L)))
                .isInstanceOf(WorkbenchTeamException.class).hasMessage("团队仅有管理员一人，请使用解散团队");
    }

    @Test
    void invitationCannotCrossCodingTeamBoundary() {
        WorkbenchTeamRepository repository = mock(WorkbenchTeamRepository.class);
        when(repository.findByInviteCode("invite-code")).thenReturn(Optional.of(storedTeam()));
        DefaultWorkbenchTeamModule module = new DefaultWorkbenchTeamModule(repository);
        Actor outsider = new Actor(300L, "外部成员", "", 11L,
                "another-team", "https://another-team.coding.net");

        assertThatThrownBy(() -> module.previewInvitation(outsider, "invite-code"))
                .isInstanceOf(WorkbenchTeamException.class).hasMessage("该邀请仅限指定 CODING 团队成员");
    }

    @Test
    void reorderPersistsCompleteCurrentMembershipOrder() {
        WorkbenchTeamRepository repository = mock(WorkbenchTeamRepository.class);
        StoredTeam first = storedTeam(1L, 10L, "研发团队");
        StoredTeam second = storedTeam(2L, 10L, "产品团队");
        when(repository.findByMember(100L)).thenReturn(List.of(first, second), List.of(second, first));
        DefaultWorkbenchTeamModule module = new DefaultWorkbenchTeamModule(repository);

        var reordered = module.reorder(administrator, new ReorderCommand(List.of(2L, 1L)));

        verify(repository).reorder(100L, List.of(2L, 1L));
        assertThat(reordered).extracting(WorkbenchTeamModels.TeamListItem::id).containsExactly(2L, 1L);
    }

    @Test
    void reorderRejectsIncompleteOrDuplicateTeamIds() {
        WorkbenchTeamRepository repository = mock(WorkbenchTeamRepository.class);
        when(repository.findByMember(100L)).thenReturn(List.of(
                storedTeam(1L, 10L, "研发团队"), storedTeam(2L, 10L, "产品团队")));
        DefaultWorkbenchTeamModule module = new DefaultWorkbenchTeamModule(repository);

        assertThatThrownBy(() -> module.reorder(administrator, new ReorderCommand(List.of(1L))))
                .isInstanceOf(WorkbenchTeamException.class).hasMessage("团队列表已变化，请刷新后重试");
        assertThatThrownBy(() -> module.reorder(administrator, new ReorderCommand(List.of(1L, 1L))))
                .isInstanceOf(WorkbenchTeamException.class).hasMessage("团队顺序不正确");
        verify(repository, never()).reorder(100L, List.of(1L));
    }

    @Test
    void worklogOptionsOnlyIncludeTeamsFromCurrentCodingTeam() {
        WorkbenchTeamRepository repository = mock(WorkbenchTeamRepository.class);
        when(repository.findByMember(100L)).thenReturn(List.of(
                storedTeam(2L, 10L, "产品团队"), storedTeam(), storedTeam(3L, 11L, "其他团队")));
        DefaultWorkbenchTeamModule module = new DefaultWorkbenchTeamModule(repository);

        var options = module.worklogTeams(administrator);

        assertThat(options).extracting(WorkbenchTeamModels.WorklogTeamOption::id).containsExactly(2L, 1L);
        assertThat(options).extracting(WorkbenchTeamModels.WorklogTeamOption::name)
                .containsExactly("产品团队", "研发团队");
        verify(repository).refreshProfile(administrator);
    }

    @Test
    void worklogScopeUsesStoredMemberProfiles() {
        WorkbenchTeamRepository repository = mock(WorkbenchTeamRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(storedTeam()));
        DefaultWorkbenchTeamModule module = new DefaultWorkbenchTeamModule(repository);

        var scope = module.resolveWorklogScope(administrator, 1L);

        assertThat(scope.id()).isEqualTo(1L);
        assertThat(scope.codingTeamHost()).isEqualTo("https://g-iijw5014.coding.net");
        assertThat(scope.members()).extracting(WorkbenchTeamModels.WorklogMember::userId)
                .containsExactly(100L, 200L);
        assertThat(scope.members()).extracting(WorkbenchTeamModels.WorklogMember::userName)
                .containsExactly("管理员", "成员");
        verify(repository).refreshProfile(administrator);
    }

    @Test
    void nonMemberCannotResolveWorklogScope() {
        WorkbenchTeamRepository repository = mock(WorkbenchTeamRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(storedTeam()));
        DefaultWorkbenchTeamModule module = new DefaultWorkbenchTeamModule(repository);
        Actor outsider = new Actor(300L, "同组织外部成员", "", 10L,
                "g-iijw5014", "https://g-iijw5014.coding.net");

        assertThatThrownBy(() -> module.resolveWorklogScope(outsider, 1L))
                .isInstanceOf(WorkbenchTeamException.class)
                .hasMessage("团队不存在或当前用户不是团队成员");
    }

    private StoredTeam storedTeam() {
        return storedTeam(1L, 10L, "研发团队");
    }

    private StoredTeam storedTeam(long teamId, long codingTeamId, String name) {
        TeamEntity team = new TeamEntity();
        team.setId(teamId);
        team.setRequestId("request-" + teamId);
        team.setName(name);
        team.setInviteCode("invite-code-" + teamId);
        team.setCodingTeamId(codingTeamId);
        team.setCodingTeamKey("g-iijw5014");
        team.setCodingTeamHost("https://g-iijw5014.coding.net");
        team.setCreatorUserId(100L);
        team.setAdministratorUserId(100L);
        team.setVersionNo(3);
        team.setCreateTime(LocalDateTime.now());
        team.setUpdateTime(LocalDateTime.now());
        return new StoredTeam(team, List.of(member(teamId, 100L, "管理员"), member(teamId, 200L, "成员")));
    }

    private MemberEntity member(long userId, String name) {
        return member(1L, userId, name);
    }

    private MemberEntity member(long teamId, long userId, String name) {
        MemberEntity member = new MemberEntity();
        member.setId(userId);
        member.setTeamId(teamId);
        member.setCodingUserId(userId);
        member.setUserName(name);
        member.setAvatar("");
        member.setSortNo((int) teamId);
        member.setCreateTime(LocalDateTime.now());
        return member;
    }
}

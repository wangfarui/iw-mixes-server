package com.itwray.iw.external.zhaogang.iteration;

import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Actor;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.AddReleasePlanCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CodingIssueCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CodingIssueType;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CreateChildIssueCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CreateCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IssueSource;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IssueSyncStatus;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.MemberInput;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Role;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.RegisterWorklogCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.RemoveIssuesCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UpdateIssueCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UpdateIssueStatusCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationRepository.ResolvedMember;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationRepository.StoredIteration;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationRepository.StoredMember;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.IterationEntity;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.IssueEntity;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.IssueWorklogEntity;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.MemberEntity;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.ReleasePlanEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultTeamIterationModuleTest {

    private final Actor actor = new Actor(100L, "creator", "avatar", "token", "g-iijw5014");

    @Test
    void addReleasePlanResolvesAccessibleCodingPlanAndPersistsSnapshot() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        TeamIterationReleasePlanResolver resolver = mock(TeamIterationReleasePlanResolver.class);
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of())));
        when(repository.findReleasePlan(1L, 11L, 22L)).thenReturn(Optional.empty());
        var resolved = new TeamIterationReleasePlanResolver.ResolvedReleasePlan(
                11L, "project-a", "项目 A", 22L, "sit-build", true);
        when(resolver.resolve(actor, 11L, 22L)).thenReturn(resolved);
        ReleasePlanEntity entity = releasePlan(31L, resolved);
        when(repository.addReleasePlan(1L, resolved, actor)).thenReturn(entity);
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser(), resolver);

        var releasePlan = module.addReleasePlan(actor, 1L, new AddReleasePlanCommand(11L, 22L));

        assertThat(releasePlan.projectDisplayName()).isEqualTo("项目 A");
        assertThat(releasePlan.planName()).isEqualTo("sit-build");
        assertThat(releasePlan.quickBuildSupported()).isTrue();
        verify(repository).addReleasePlan(1L, resolved, actor);
    }

    @Test
    void addReleasePlanRejectsExactDuplicateBeforeCallingCoding() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        TeamIterationReleasePlanResolver resolver = mock(TeamIterationReleasePlanResolver.class);
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of())));
        when(repository.findReleasePlan(1L, 11L, 22L)).thenReturn(Optional.of(new ReleasePlanEntity()));
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser(), resolver);

        assertThatThrownBy(() -> module.addReleasePlan(actor, 1L, new AddReleasePlanCommand(11L, 22L)))
                .isInstanceOf(TeamIterationException.class)
                .hasMessageContaining("已加入当前迭代");
        verifyNoInteractions(resolver);
    }

    @Test
    void createAllowsMembersWithoutRolesAndKeepsCreatorMembership() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        when(repository.findByRequestId("request-1")).thenReturn(Optional.empty());
        when(coding.teamDirectory("token")).thenReturn(new CodingOpenApiPort.TeamDirectory(List.of(
                new CodingOpenApiPort.Member(100L, "creator", "avatar", true, List.of()),
                new CodingOpenApiPort.Member(200L, "member", "member-avatar", true, List.of()))));
        when(repository.create(any(), any(), any())).thenAnswer(invocation -> stored(invocation.getArgument(2), List.of()));
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        var detail = module.create(actor, new CreateCommand("request-1", "迭代", null, null,
                LocalDate.of(2026, 8, 31), List.of(
                new MemberInput(100L, List.of()),
                new MemberInput(200L, List.of(Role.PRODUCT)))));

        assertThat(detail.members()).hasSize(2);
        assertThat(detail.members().get(0).roles()).isEmpty();
    }

    @Test
    void createWithOnlyCreatorDoesNotRequireTeamDirectoryPermission() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        when(repository.findByRequestId("request-self")).thenReturn(Optional.empty());
        when(repository.create(any(), any(), any())).thenAnswer(invocation -> stored(invocation.getArgument(2), List.of()));
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        var detail = module.create(actor, new CreateCommand("request-self", "个人迭代", null, null,
                LocalDate.of(2026, 8, 31), List.of(new MemberInput(100L, List.of()))));

        assertThat(detail.members()).hasSize(1);
        verifyNoInteractions(coding);
    }

    @Test
    void teamMemberSearchExplainsMissingDirectoryPermission() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        when(coding.teamDirectory("token"))
                .thenThrow(new com.itwray.iw.external.zhaogang.CodingOpenApiException("Forbidden", "permission denied"));
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        assertThatThrownBy(() -> module.teamMembers(actor, ""))
                .isInstanceOf(TeamIterationException.class)
                .hasMessageContaining("成员目录读取权限");
    }

    @Test
    void codingIssueBindingAcceptsDefectsAndChecksDuplicatesOnlyInsideCurrentIteration() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of())));
        when(repository.findIssue(anyLong(), anyString())).thenReturn(Optional.empty());
        CodingOpenApiPort.Issue defect = codingIssue(8123L, "DEFECT", "缺陷", "批量导入失败", false);
        when(coding.issue("token", "project-a", 8123L)).thenReturn(defect);
        IssueEntity entity = linkedIssue(9L, CodingIssueType.DEFECT, 8123L, "批量导入失败");
        when(repository.addCodingIssue(anyLong(), isNull(), anyString(), anyString(), anyString(), anyLong(),
                anyLong(), any(), anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(entity);
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        var issue = module.addCodingIssue(actor, 1L, new CodingIssueCommand(
                "https://g-iijw5014.coding.net/p/project-a/bug-tracking/issues/8123/detail"));

        assertThat(issue.issueType()).isEqualTo(CodingIssueType.DEFECT);
        assertThat(issue.title()).isEqualTo("批量导入失败");
        verify(repository).findIssue(eq(1L), anyString());
    }

    @Test
    void codingIssueBindingAcceptsTaskAsTopLevelIssue() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of())));
        when(repository.findIssue(anyLong(), anyString())).thenReturn(Optional.empty());
        when(coding.issue("token", "yuncunzheng", 4781L)).thenReturn(new CodingOpenApiPort.Issue(
                4781L, "TASK", "任务", 61L, "云服务日常支持", "云存证", false, 14781L,
                "开发中", "PROCESSING", BigDecimal.ZERO, "日常支持任务", 4700L, "yuncunzheng",
                "REQUIREMENT", "需求", null, null, null));
        IssueEntity entity = linkedIssue(9L, CodingIssueType.TASK, 4781L, "云服务日常支持");
        entity.setProjectName("yuncunzheng");
        when(repository.addCodingIssue(anyLong(), isNull(), anyString(), anyString(), anyString(), anyLong(),
                anyLong(), eq(CodingIssueType.TASK), eq("TASK"), anyLong(), eq("任务"), anyString(), any()))
                .thenReturn(entity);
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        var issue = module.addCodingIssue(actor, 1L, new CodingIssueCommand(
                "https://g-iijw5014.coding.net/p/yuncunzheng/assignments/issues/4781/detail"));

        assertThat(issue.issueType()).isEqualTo(CodingIssueType.TASK);
        assertThat(issue.parentId()).isNull();
        verify(repository).addCodingIssue(eq(1L), isNull(), anyString(), anyString(), eq("yuncunzheng"),
                anyLong(), eq(4781L), eq(CodingIssueType.TASK), eq("TASK"), anyLong(), eq("任务"),
                eq("云服务日常支持"), eq(actor));
    }

    @Test
    void codingTaskCannotBeLinkedAsChildIssue() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity requirement = linkedIssue(1L, CodingIssueType.REQUIREMENT, 7000L, "采购需求");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(requirement))));
        when(repository.findIssue(anyLong(), anyString())).thenReturn(Optional.empty());
        when(coding.issue("token", "yuncunzheng", 4781L))
                .thenReturn(codingIssue(4781L, "TASK", "任务", "云服务日常支持", false));
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        assertThatThrownBy(() -> module.addCodingIssue(actor, 1L, new CodingIssueCommand(
                "https://g-iijw5014.coding.net/p/yuncunzheng/assignments/issues/4781/detail", 1L)))
                .isInstanceOf(TeamIterationException.class)
                .hasMessage("任务只能作为顶层事项关联");
    }

    @Test
    void codingUserStoryWithParentCodeKeepsUserStoryType() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of())));
        when(repository.findIssue(anyLong(), anyString())).thenReturn(Optional.empty());
        when(coding.issue("token", "project-a", 8124L)).thenReturn(new CodingOpenApiPort.Issue(
                8124L, "REQUIREMENT", "用户故事", 12L, "故事", "项目A", true, 18124L,
                "开发中", "PROCESSING", BigDecimal.ZERO));
        IssueEntity entity = linkedIssue(10L, CodingIssueType.USER_STORY, 8124L, "故事");
        when(repository.addCodingIssue(anyLong(), isNull(), anyString(), anyString(), anyString(), anyLong(),
                anyLong(), eq(CodingIssueType.USER_STORY), anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(entity);

        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        var issue = module.addCodingIssue(actor, 1L, new CodingIssueCommand(
                "https://g-iijw5014.coding.net/p/project-a/user-stories/issues/8124/detail"));

        assertThat(issue.issueType()).isEqualTo(CodingIssueType.USER_STORY);
    }

    @Test
    void detailNormalizesLegacyCodingIssueRoutes() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity story = linkedIssue(1L, CodingIssueType.USER_STORY, 7781L, "用户故事");
        story.setCodingUrl("https://g-iijw5014.coding.net/p/project-a/user-stories/issues/7781/detail");
        IssueEntity subTask = linkedChild(2L, 1L, CodingIssueType.SUB_TASK, 7783L, "子工作项");
        subTask.setCodingUrl("https://g-iijw5014.coding.net/p/project-a/tasks/issues/7783/detail");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(story, subTask))));
        when(coding.issue("token", "project-a", 7781L))
                .thenReturn(codingIssue(7781L, "REQUIREMENT", "用户故事", "用户故事", false));
        when(coding.issue("token", "project-a", 7783L))
                .thenReturn(codingIssue(7783L, "SUB_TASK", "子工作项", "子工作项", true));
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        var detail = module.detail(actor, 1L);

        assertThat(detail.issues().get(0).url())
                .isEqualTo("https://g-iijw5014.coding.net/p/project-a/requirements/issues/7781/detail");
        assertThat(detail.issues().get(0).children().get(0).url())
                .isEqualTo("https://g-iijw5014.coding.net/p/project-a/subtasks/issues/7783/detail");
    }

    @Test
    void childIssueCanUseAnySupportedTypeWithoutCallingCoding() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity root = linkedIssue(1L, CodingIssueType.REQUIREMENT, 7000L, "采购需求");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(root))));
        IssueEntity child = localIssue(2L, 1L, CodingIssueType.USER_STORY, "人工用户故事");
        when(repository.addChildIssue(eq(1L), eq(1L), eq("project-a"), eq(CodingIssueType.USER_STORY),
                anyString(), anyString(), any(), anyString(), anyString(), any(), any(), any(), any(),
                eq(IssueSyncStatus.PENDING), any())).thenReturn(child);
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        var issue = module.addChildIssue(actor, 1L, 1L,
                new CreateChildIssueCommand(CodingIssueType.USER_STORY, "人工用户故事", "说明",
                        "基础服务组", "联调通过", null, null, null, null));

        assertThat(issue.source()).isEqualTo(IssueSource.WORKBENCH);
        assertThat(issue.syncStatus()).isEqualTo(IssueSyncStatus.PENDING);
        verifyNoInteractions(coding);
    }

    @Test
    void taskAllowsOnlySubTaskChildren() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity task = linkedIssue(1L, CodingIssueType.TASK, 4781L, "云服务日常支持");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(task))));
        IssueEntity subTask = localIssue(2L, 1L, CodingIssueType.SUB_TASK, "处理告警");
        when(repository.addChildIssue(eq(1L), eq(1L), eq("project-a"), eq(CodingIssueType.SUB_TASK),
                eq("子工作项"), eq("处理告警"), any(), any(), any(), eq(new BigDecimal("2")),
                eq("开发任务"), any(), any(), eq(IssueSyncStatus.PENDING), eq(actor))).thenReturn(subTask);
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        var created = module.addChildIssue(actor, 1L, 1L,
                new CreateChildIssueCommand(CodingIssueType.SUB_TASK, "处理告警", null,
                        null, null, new BigDecimal("2"), "开发任务", null, null));

        assertThat(created.issueType()).isEqualTo(CodingIssueType.SUB_TASK);
        assertThatThrownBy(() -> module.addChildIssue(actor, 1L, 1L,
                new CreateChildIssueCommand(CodingIssueType.USER_STORY, "用户故事", null,
                        "基础服务组", "上线完成", null, null, null, null)))
                .isInstanceOf(TeamIterationException.class)
                .hasMessageContaining("不允许新增");
    }

    @Test
    void taskCannotBeCreatedFromWorkbench() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity requirement = linkedIssue(1L, CodingIssueType.REQUIREMENT, 7000L, "采购需求");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(requirement))));
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        assertThatThrownBy(() -> module.addChildIssue(actor, 1L, 1L,
                new CreateChildIssueCommand(CodingIssueType.TASK, "人工任务", null)))
                .isInstanceOf(TeamIterationException.class)
                .hasMessage("任务只能从 CODING 关联");
    }

    @Test
    void taskAcceptsOnlyLinkedSubTaskChildren() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity task = linkedIssue(1L, CodingIssueType.TASK, 4781L, "云服务日常支持");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(task))));
        when(repository.findIssue(anyLong(), anyString())).thenReturn(Optional.empty());
        when(coding.issue("token", "project-a", 8001L))
                .thenReturn(codingIssue(8001L, "SUB_TASK", "子工作项", "处理告警", true));
        when(coding.issueWorklogs("token", "project-a", 8001L)).thenReturn(List.of());
        when(repository.addCodingIssue(eq(1L), eq(1L), anyString(), anyString(), eq("project-a"), anyLong(),
                eq(8001L), eq(CodingIssueType.SUB_TASK), anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(linkedChild(2L, 1L, CodingIssueType.SUB_TASK, 8001L, "处理告警"));
        when(coding.issue("token", "project-a", 8002L))
                .thenReturn(codingIssue(8002L, "REQUIREMENT", "用户故事", "错误层级故事", false));
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        var linked = module.addCodingIssue(actor, 1L, new CodingIssueCommand(
                "https://g-iijw5014.coding.net/p/project-a/tasks/issues/8001/detail", 1L));

        assertThat(linked.issueType()).isEqualTo(CodingIssueType.SUB_TASK);
        assertThatThrownBy(() -> module.addCodingIssue(actor, 1L, new CodingIssueCommand(
                "https://g-iijw5014.coding.net/p/project-a/user-stories/issues/8002/detail", 1L)))
                .isInstanceOf(TeamIterationException.class)
                .hasMessageContaining("不允许新增");
    }

    @Test
    void updateUserStoryPersistsOnlyUserStoryFields() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity story = localIssue(2L, 1L, CodingIssueType.USER_STORY, "原用户故事");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(story))));
        when(repository.updateIssue(eq(1L), eq(2L), any(), eq(actor))).thenAnswer(invocation -> {
            UpdateIssueCommand command = invocation.getArgument(2);
            story.setTitle(command.title());
            story.setDescription(command.description());
            story.setDevelopmentTeam(command.developmentTeam());
            story.setDefinitionOfDone(command.definitionOfDone());
            story.setEstimatedHours(command.estimatedHours());
            story.setTaskType(command.taskType());
            story.setOnlineBug(command.onlineBug());
            story.setBugPriority(command.bugPriority());
            return story;
        });
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        var updated = module.updateIssue(actor, 1L, 2L, new UpdateIssueCommand(
                "新用户故事", "更新说明", "基础平台组", "测试通过", new BigDecimal("8"),
                "开发", true, "高"));

        assertThat(updated.title()).isEqualTo("新用户故事");
        assertThat(updated.developmentTeam()).isEqualTo("基础平台组");
        assertThat(updated.definitionOfDone()).isEqualTo("测试通过");
        assertThat(updated.estimatedHours()).isNull();
        assertThat(updated.taskType()).isNull();
        assertThat(updated.onlineBug()).isNull();
        assertThat(updated.bugPriority()).isNull();
        verifyNoInteractions(coding);
    }

    @Test
    void updateDefectRequiresBugPriority() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity defect = localIssue(2L, 1L, CodingIssueType.DEFECT, "缺陷");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(defect))));
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        assertThatThrownBy(() -> module.updateIssue(actor, 1L, 2L,
                new UpdateIssueCommand("缺陷", null, null, null, null, null, false, null)))
                .isInstanceOf(TeamIterationException.class)
                .hasMessageContaining("Bug 优先级");
        verifyNoInteractions(coding);
    }

    @Test
    void editingLinkedIssueAutomaticallyUpdatesCoding() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity issue = linkedIssue(2L, CodingIssueType.REQUIREMENT, 8001L, "原需求");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(issue))));
        when(coding.modifyIssue(eq("token"), any(CodingOpenApiPort.ModifyIssueRequest.class)))
                .thenReturn(codingIssue(8001L, "REQUIREMENT", "需求", "更新后的需求", false));
        when(repository.updateIssue(eq(1L), eq(2L), any(), eq(actor))).thenAnswer(invocation -> {
            UpdateIssueCommand command = invocation.getArgument(2);
            issue.setTitle(command.title());
            issue.setDescription(command.description());
            return issue;
        });
        when(coding.issue("token", "project-a", 8001L))
                .thenReturn(codingIssue(8001L, "REQUIREMENT", "需求", "更新后的需求", false));
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        module.updateIssue(actor, 1L, 2L, new UpdateIssueCommand("更新后的需求", "更新说明",
                null, null, null, null, null, null));

        ArgumentCaptor<CodingOpenApiPort.ModifyIssueRequest> request =
                ArgumentCaptor.forClass(CodingOpenApiPort.ModifyIssueRequest.class);
        verify(coding).modifyIssue(eq("token"), request.capture());
        assertThat(request.getValue().issueCode()).isEqualTo(8001L);
        assertThat(request.getValue().name()).isEqualTo("更新后的需求");
        assertThat(request.getValue().description()).isEqualTo("更新说明");
    }

    @Test
    void changingLinkedIssueStatusUpdatesCoding() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity issue = linkedIssue(2L, CodingIssueType.REQUIREMENT, 8001L, "需求");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(issue))));
        when(coding.modifyIssue(eq("token"), any(CodingOpenApiPort.ModifyIssueRequest.class)))
                .thenReturn(codingIssue(8001L, "REQUIREMENT", "需求", "需求", false));
        when(coding.issue("token", "project-a", 8001L))
                .thenReturn(codingIssue(8001L, "REQUIREMENT", "需求", "需求", false));
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        module.updateIssueStatus(actor, 1L, 2L, new UpdateIssueStatusCommand(9L));

        ArgumentCaptor<CodingOpenApiPort.ModifyIssueRequest> request =
                ArgumentCaptor.forClass(CodingOpenApiPort.ModifyIssueRequest.class);
        verify(coding).modifyIssue(eq("token"), request.capture());
        assertThat(request.getValue().statusId()).isEqualTo(9L);
    }

    @Test
    void syncSubTaskUsesRequirementCodeAsParent() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity root = linkedIssue(1L, CodingIssueType.USER_STORY, 7000L, "采购故事");
        IssueEntity child = localIssue(2L, 1L, CodingIssueType.SUB_TASK, "实现接口");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(root, child))));
        when(coding.issueTypes("token", "project-a")).thenReturn(List.of(
                new CodingOpenApiPort.IssueType(31L, "子工作项", "SUB_TASK", true)));
        when(coding.issueFields("token", "project-a", "SUB_TASK", 31L))
                .thenReturn(List.of(selectField(201L, "任务类型", "开发", "development")));
        when(repository.claimIssueSync(1L, 2L, actor)).thenReturn(true);
        CodingOpenApiPort.Issue created = codingIssue(8001L, "SUB_TASK", "子工作项", "实现接口", true);
        when(coding.createIssue(eq("token"), any(CodingOpenApiPort.CreateIssueRequest.class))).thenReturn(created);
        IssueEntity synced = linkedChild(2L, 1L, CodingIssueType.SUB_TASK, 8001L, "实现接口");
        when(repository.markIssueSynced(eq(1L), eq(2L), anyString(), anyString(), anyLong(), eq(8001L),
                eq(CodingIssueType.SUB_TASK), anyString(), anyLong(), anyString(), anyString(), eq(7000L), any()))
                .thenReturn(synced);
        when(coding.issue("token", "project-a", 8001L)).thenReturn(created);
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        var issue = module.syncIssue(actor, 1L, 2L);

        assertThat(issue.syncStatus()).isEqualTo(IssueSyncStatus.SYNCED);
        ArgumentCaptor<CodingOpenApiPort.CreateIssueRequest> request =
                ArgumentCaptor.forClass(CodingOpenApiPort.CreateIssueRequest.class);
        verify(coding).createIssue(eq("token"), request.capture());
        assertThat(request.getValue().type()).isEqualTo("SUB_TASK");
        assertThat(request.getValue().parentCode()).isEqualTo(7000L);
        assertThat(request.getValue().assigneeId()).isEqualTo(actor.userId());
    }

    @Test
    void syncDefectUsesProjectWithoutParentCode() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity root = linkedIssue(1L, CodingIssueType.REQUIREMENT, 7000L, "采购需求");
        IssueEntity child = localIssue(2L, 1L, CodingIssueType.DEFECT, "价格计算错误");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(root, child))));
        when(coding.issueTypes("token", "project-a")).thenReturn(List.of(
                new CodingOpenApiPort.IssueType(41L, "缺陷", "DEFECT", true)));
        when(coding.issueFields("token", "project-a", "DEFECT", 41L)).thenReturn(defectFields());
        when(repository.claimIssueSync(1L, 2L, actor)).thenReturn(true);
        CodingOpenApiPort.Issue created = codingIssue(8002L, "DEFECT", "缺陷", "价格计算错误", false);
        when(coding.createIssue(eq("token"), any(CodingOpenApiPort.CreateIssueRequest.class))).thenReturn(created);
        IssueEntity synced = linkedChild(2L, 1L, CodingIssueType.DEFECT, 8002L, "价格计算错误");
        when(repository.markIssueSynced(eq(1L), eq(2L), anyString(), anyString(), anyLong(), eq(8002L),
                eq(CodingIssueType.DEFECT), anyString(), anyLong(), anyString(), anyString(), isNull(), any()))
                .thenReturn(synced);
        when(coding.issue("token", "project-a", 8002L)).thenReturn(created);
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        module.syncIssue(actor, 1L, 2L);

        ArgumentCaptor<CodingOpenApiPort.CreateIssueRequest> request =
                ArgumentCaptor.forClass(CodingOpenApiPort.CreateIssueRequest.class);
        verify(coding).createIssue(eq("token"), request.capture());
        assertThat(request.getValue().type()).isEqualTo("DEFECT");
        assertThat(request.getValue().parentCode()).isNull();
        assertThat(request.getValue().assigneeId()).isEqualTo(actor.userId());
    }

    @Test
    void transportFailureMarksSyncResultUnknown() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity root = linkedIssue(1L, CodingIssueType.REQUIREMENT, 7000L, "采购需求");
        IssueEntity child = localIssue(2L, 1L, CodingIssueType.SUB_TASK, "实现接口");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(root, child))));
        when(coding.issueTypes("token", "project-a")).thenReturn(List.of(
                new CodingOpenApiPort.IssueType(31L, "子工作项", "SUB_TASK", true)));
        when(coding.issueFields("token", "project-a", "SUB_TASK", 31L))
                .thenReturn(List.of(selectField(201L, "任务类型", "开发", "development")));
        when(repository.claimIssueSync(1L, 2L, actor)).thenReturn(true);
        when(coding.createIssue(eq("token"), any(CodingOpenApiPort.CreateIssueRequest.class)))
                .thenThrow(new com.itwray.iw.external.zhaogang.CodingOpenApiException(
                        "CODING 服务暂不可用", new IOException("timeout")));
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        assertThatThrownBy(() -> module.syncIssue(actor, 1L, 2L))
                .isInstanceOf(TeamIterationException.class)
                .hasMessageContaining("同步失败");
        verify(repository).markIssueSyncFailed(eq(1L), eq(2L), eq(IssueSyncStatus.UNKNOWN), anyString(),
                anyString(), eq(actor));
    }

    @Test
    void syncUserStoryUnderRequirementUsesRequirementTypeParentAndDynamicFields() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity root = linkedIssue(1L, CodingIssueType.REQUIREMENT, 7550L, "进项发票核销");
        IssueEntity story = localIssue(2L, 1L, CodingIssueType.USER_STORY, "核销异常处理故事");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(root, story))));
        when(coding.issueTypes("token", "project-a")).thenReturn(List.of(
                new CodingOpenApiPort.IssueType(11L, "用户故事", "REQUIREMENT", false)));
        when(coding.issueFields("token", "project-a", "REQUIREMENT", 11L)).thenReturn(List.of(
                selectField(101L, "开发团队", "基础服务组", "team-backend"),
                selectField(102L, "DoD", "联调通过", "dod-integration")));
        when(repository.claimIssueSync(1L, 2L, actor)).thenReturn(true);
        CodingOpenApiPort.Issue created = codingIssue(8003L, "REQUIREMENT", "用户故事",
                "核销异常处理故事", false);
        when(coding.createIssue(eq("token"), any(CodingOpenApiPort.CreateIssueRequest.class))).thenReturn(created);
        when(repository.markIssueSynced(eq(1L), eq(2L), anyString(), anyString(), anyLong(), eq(8003L),
                eq(CodingIssueType.USER_STORY), anyString(), anyLong(), anyString(), anyString(), eq(7550L), any()))
                .thenReturn(linkedChild(2L, 1L, CodingIssueType.USER_STORY, 8003L, "核销异常处理故事"));
        when(coding.issue("token", "project-a", 8003L)).thenReturn(created);
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        module.syncIssue(actor, 1L, 2L);

        ArgumentCaptor<CodingOpenApiPort.CreateIssueRequest> request =
                ArgumentCaptor.forClass(CodingOpenApiPort.CreateIssueRequest.class);
        verify(coding).createIssue(eq("token"), request.capture());
        assertThat(request.getValue().type()).isEqualTo("REQUIREMENT");
        assertThat(request.getValue().parentCode()).isEqualTo(7550L);
        assertThat(request.getValue().assigneeId()).isEqualTo(actor.userId());
        assertThat(request.getValue().customFieldValues()).extracting(CodingOpenApiPort.CustomFieldValue::content)
                .containsExactly("team-backend", "dod-integration");
    }

    @Test
    void syncCodingSnapshotMapsCustomOptionValuesToDisplayTitles() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity story = linkedIssue(2L, CodingIssueType.USER_STORY, 7778L, "用户故事");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(story))));
        when(coding.issue("token", "project-a", 7778L)).thenReturn(new CodingOpenApiPort.Issue(
                7778L, "REQUIREMENT", "用户故事", 11L, "用户故事", "项目A", false, 17778L,
                "开发中", "PROCESSING", BigDecimal.ZERO, "说明", null, null, null, null,
                "1056188", "1010715", null));
        when(coding.issueTypes("token", "project-a")).thenReturn(List.of(
                new CodingOpenApiPort.IssueType(11L, "用户故事", "REQUIREMENT", false)));
        when(coding.issueFields("token", "project-a", "REQUIREMENT", 11L)).thenReturn(List.of(
                selectField(101L, "开发团队", "基础服务组", "1056188"),
                selectField(102L, "DoD", "测试通过", "1010715")));
        IssueEntity synced = linkedChild(2L, 1L, CodingIssueType.USER_STORY, 7778L, "用户故事");
        when(repository.upsertCodingSnapshot(anyLong(), isNull(), anyString(), anyString(), anyString(), anyLong(),
                anyLong(), any(), anyString(), anyLong(), anyString(), anyString(), any(), any(), any(),
                any(), any(), any(), eq(actor))).thenReturn(synced);

        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        var result = module.syncCodingIssues(actor, 1L);

        assertThat(result.successCount()).isEqualTo(1);
        verify(repository).upsertCodingSnapshot(eq(1L), isNull(), anyString(), anyString(), eq("project-a"),
                eq(17778L), eq(7778L), eq(CodingIssueType.USER_STORY), eq("REQUIREMENT"), eq(11L), eq("用户故事"),
                eq("用户故事"), eq("说明"), eq("基础服务组"), eq("测试通过"), isNull(), isNull(), isNull(), eq(actor));
    }

    @Test
    void syncCodingSubTaskRefreshesExistingCodingWorklogSummary() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity subTask = linkedIssue(2L, CodingIssueType.SUB_TASK, 7780L, "数据来源修复");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(subTask))));
        when(coding.issue("token", "project-a", 7780L)).thenReturn(new CodingOpenApiPort.Issue(
                7780L, "SUB_TASK", "子工作项", 31L, "数据来源修复", "项目A", true, 17780L,
                "已完成", "COMPLETED", new BigDecimal("8")));
        when(coding.issueWorklogs("token", "project-a", 7780L)).thenReturn(List.of(
                new CodingOpenApiPort.IssueWorklog(9001L, new BigDecimal("8"), BigDecimal.ZERO,
                        1787711400000L, 1787711400000L, 1787711400000L)));
        when(repository.upsertCodingSnapshot(anyLong(), isNull(), anyString(), anyString(), anyString(), anyLong(),
                anyLong(), any(), anyString(), anyLong(), anyString(), anyString(), any(), any(), any(),
                any(), any(), any(), eq(actor))).thenReturn(subTask);
        when(repository.updateCodingWorklogSummary(eq(1L), eq(2L), eq(new BigDecimal("8")), eq(1), eq(actor)))
                .thenAnswer(invocation -> subTask);
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        var result = module.syncCodingIssues(actor, 1L);

        assertThat(result.successCount()).isEqualTo(1);
        verify(repository).updateCodingWorklogSummary(1L, 2L, new BigDecimal("8"), 1, actor);
    }

    @Test
    void linkingCodingChildUsesParentIdAndRejectsChildrenUnderSubTasks() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity requirement = linkedIssue(1L, CodingIssueType.REQUIREMENT, 7550L, "需求");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(requirement))));
        when(repository.findIssue(eq(1L), org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        when(coding.issue("token", "project-a", 8001L))
                .thenReturn(codingIssue(8001L, "SUB_TASK", "子工作项", "实现接口", true));
        when(repository.addCodingIssue(eq(1L), eq(1L), anyString(), anyString(), eq("project-a"), anyLong(),
                eq(8001L), eq(CodingIssueType.SUB_TASK), anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(linkedChild(2L, 1L, CodingIssueType.SUB_TASK, 8001L, "实现接口"));
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        var linked = module.addCodingIssue(actor, 1L, new CodingIssueCommand(
                "https://g-iijw5014.coding.net/p/project-a/tasks/issues/8001/detail", 1L));

        assertThat(linked.parentId()).isEqualTo(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(linkedChild(2L, 1L,
                CodingIssueType.SUB_TASK, 8001L, "实现接口")))));
        assertThatThrownBy(() -> module.addChildIssue(actor, 1L, 2L,
                new CreateChildIssueCommand(CodingIssueType.DEFECT, "缺陷", null,
                        null, null, null, null, false, "中")))
                .isInstanceOf(TeamIterationException.class)
                .hasMessageContaining("不允许新增");
    }

    @Test
    void linkingCodingSubTaskLoadsExistingCodingWorklogSummary() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity requirement = linkedIssue(1L, CodingIssueType.REQUIREMENT, 7550L, "需求");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(requirement))));
        when(repository.findIssue(eq(1L), anyString())).thenReturn(Optional.empty());
        when(coding.issue("token", "project-a", 7780L))
                .thenReturn(new CodingOpenApiPort.Issue(7780L, "SUB_TASK", "子工作项", 31L, "数据来源修复",
                        "项目A", true, 17780L, "已完成", "COMPLETED", new BigDecimal("8")));
        when(coding.issueWorklogs("token", "project-a", 7780L)).thenReturn(List.of(
                new CodingOpenApiPort.IssueWorklog(9001L, new BigDecimal("8"), BigDecimal.ZERO,
                        1787711400000L, 1787711400000L, 1787711400000L)));
        IssueEntity linked = linkedChild(2L, 1L, CodingIssueType.SUB_TASK, 7780L, "数据来源修复");
        when(repository.addCodingIssue(anyLong(), eq(1L), anyString(), anyString(), eq("project-a"), anyLong(),
                eq(7780L), eq(CodingIssueType.SUB_TASK), anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(linked);
        when(repository.updateCodingWorklogSummary(eq(1L), eq(2L), eq(new BigDecimal("8")), eq(1), eq(actor)))
                .thenAnswer(invocation -> {
                    linked.setCodingRecordedHours(invocation.getArgument(2));
                    linked.setCodingWorklogCount(invocation.getArgument(3));
                    return linked;
                });
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        var result = module.addCodingIssue(actor, 1L, new CodingIssueCommand(
                "https://g-iijw5014.coding.net/p/project-a/subtasks/issues/7780/detail", 1L));

        assertThat(result.recordedHours()).isEqualByComparingTo("8");
        assertThat(result.recordedWorklogCount()).isEqualTo(1);
        verify(repository).updateCodingWorklogSummary(1L, 2L, new BigDecimal("8"), 1, actor);
    }

    @Test
    void registeringWorklogOnLinkedSubTaskAutomaticallySyncsRemainingHours() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity subTask = linkedIssue(2L, CodingIssueType.SUB_TASK, 8001L, "实现接口");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(subTask))));
        IssueWorklogEntity pending = worklog(9L, IssueSyncStatus.PENDING);
        when(repository.addWorklog(eq(1L), eq(2L), eq(new BigDecimal("2.5")), any(),
                eq(IssueSyncStatus.PENDING), eq(actor))).thenReturn(pending);
        when(repository.claimWorklogSync(9L)).thenReturn(true);
        when(coding.issue("token", "project-a", 8001L)).thenReturn(new CodingOpenApiPort.Issue(8001L,
                "SUB_TASK", "子工作项", 31L, "实现接口", "项目A", true, 18001L, "开发中",
                "PROCESSING", new BigDecimal("8")));
        when(coding.issueWorklogs("token", "project-a", 8001L)).thenReturn(List.of());
        when(coding.createIssueWorkHours(eq("token"), eq("project-a"), eq(8001L),
                eq(new BigDecimal("2.5")), eq(new BigDecimal("5.5")), anyLong())).thenReturn("request-1");
        IssueWorklogEntity synced = worklog(9L, IssueSyncStatus.SYNCED);
        when(repository.markWorklogSynced(9L, "request-1")).thenReturn(synced);
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        var result = module.registerWorklog(actor, 1L, 2L,
                new RegisterWorklogCommand(new BigDecimal("2.5"), LocalDateTime.of(2026, 8, 26, 16, 30)));

        assertThat(result.syncStatus()).isEqualTo(IssueSyncStatus.SYNCED);
        verify(coding).createIssueWorkHours(eq("token"), eq("project-a"), eq(8001L),
                eq(new BigDecimal("2.5")), eq(new BigDecimal("5.5")), anyLong());
    }

    @Test
    void registeringWorklogBeforeSubTaskIsSyncedIsRejected() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity subTask = localIssue(2L, 1L, CodingIssueType.SUB_TASK, "待同步任务");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(subTask))));
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        assertThatThrownBy(() -> module.registerWorklog(actor, 1L, 2L,
                new RegisterWorklogCommand(new BigDecimal("2.5"), LocalDateTime.of(2026, 8, 26, 16, 30))))
                .isInstanceOf(TeamIterationException.class)
                .hasMessageContaining("关联 CODING");
        verifyNoInteractions(coding);
    }

    @Test
    void batchRemoveIssuesDeletesOnlyWorkbenchRecordsWithoutCallingCoding() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity root = linkedIssue(1L, CodingIssueType.REQUIREMENT, 7000L, "采购需求");
        IssueEntity child = linkedChild(2L, 1L, CodingIssueType.SUB_TASK, 7001L, "接口联调");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(root, child))));
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        module.removeIssues(actor, 1L, new RemoveIssuesCommand(List.of(1L, 2L, 1L)));

        verify(repository).removeIssueTrees(1L, List.of(1L, 2L), actor);
        verifyNoInteractions(coding);
    }

    @Test
    void batchRemoveIssuesRejectsUnknownIssueBeforeDeleting() {
        TeamIterationRepository repository = mock(TeamIterationRepository.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        IssueEntity root = linkedIssue(1L, CodingIssueType.REQUIREMENT, 7000L, "采购需求");
        when(repository.findById(1L)).thenReturn(Optional.of(stored(selfMember(), List.of(root))));
        DefaultTeamIterationModule module = new DefaultTeamIterationModule(repository, coding,
                new CodingIssueUrlParser());

        assertThatThrownBy(() -> module.removeIssues(actor, 1L, new RemoveIssuesCommand(List.of(1L, 99L))))
                .isInstanceOf(TeamIterationException.class)
                .hasMessage("部分事项不存在，请刷新后重试");

        verifyNoInteractions(coding);
    }

    private List<ResolvedMember> selfMember() {
        return List.of(new ResolvedMember(new TeamIterationModels.UserSnapshot(100L, "creator", "avatar"), List.of()));
    }

    private StoredIteration stored(List<ResolvedMember> resolvedMembers, List<IssueEntity> issues) {
        IterationEntity iteration = new IterationEntity();
        iteration.setId(1L);
        iteration.setRequestId("request-1");
        iteration.setTeamKey("g-iijw5014");
        iteration.setName("迭代");
        iteration.setStage(TeamIterationModels.Stage.NOT_STARTED.name());
        iteration.setPlannedReleaseDate(LocalDate.of(2026, 8, 31));
        iteration.setCreatorUserId(100L);
        iteration.setCreatorUserName("creator");
        iteration.setCreatorAvatar("avatar");
        iteration.setVersionNo(1);
        iteration.setCreateTime(LocalDateTime.now());
        iteration.setUpdateTime(LocalDateTime.now());
        List<StoredMember> members = resolvedMembers.stream().map(resolved -> {
            MemberEntity entity = new MemberEntity();
            entity.setId(resolved.user().userId());
            entity.setIterationId(1L);
            entity.setCodingUserId(resolved.user().userId());
            entity.setUserName(resolved.user().userName());
            entity.setAvatar(resolved.user().avatar());
            return new StoredMember(entity, resolved.roles());
        }).toList();
        return new StoredIteration(iteration, members, issues);
    }

    private ReleasePlanEntity releasePlan(long id,
                                          TeamIterationReleasePlanResolver.ResolvedReleasePlan resolved) {
        ReleasePlanEntity entity = new ReleasePlanEntity();
        entity.setId(id);
        entity.setIterationId(1L);
        entity.setCodingProjectId(resolved.projectId());
        entity.setCodingProjectName(resolved.projectName());
        entity.setProjectDisplayName(resolved.projectDisplayName());
        entity.setCodingPlanId(resolved.planId());
        entity.setPlanName(resolved.planName());
        entity.setQuickBuildSupported(resolved.quickBuildSupported());
        entity.setCreatorUserId(actor.userId());
        entity.setCreatorUserName(actor.userName());
        entity.setCreatorAvatar(actor.avatar());
        entity.setCreateTime(LocalDateTime.of(2026, 8, 27, 10, 0));
        return entity;
    }

    private IssueEntity linkedIssue(long id, CodingIssueType type, long code, String title) {
        IssueEntity entity = new IssueEntity();
        entity.setId(id);
        entity.setIterationId(1L);
        entity.setSource(IssueSource.CODING.name());
        entity.setCodingUrl("https://g-iijw5014.coding.net/p/project-a/issues/" + code + "/detail");
        entity.setProjectName("project-a");
        entity.setIssueId(code + 10000);
        entity.setIssueCode(code);
        entity.setIssueType(type.name());
        entity.setCodingSystemType(type.name());
        entity.setCodingIssueTypeId(1L);
        entity.setIssueTypeName(type.name());
        entity.setTitle(title);
        if (type == CodingIssueType.SUB_TASK) entity.setEstimatedHours(new BigDecimal("8"));
        entity.setSyncStatus(IssueSyncStatus.SYNCED.name());
        entity.setCreateTime(LocalDateTime.now());
        return entity;
    }

    private IssueEntity localIssue(long id, long parentId, CodingIssueType type, String title) {
        IssueEntity entity = new IssueEntity();
        entity.setId(id);
        entity.setIterationId(1L);
        entity.setParentId(parentId);
        entity.setSource(IssueSource.WORKBENCH.name());
        entity.setProjectName("project-a");
        entity.setIssueType(type.name());
        entity.setIssueTypeName(type.name());
        entity.setTitle(title);
        if (type == CodingIssueType.USER_STORY) {
            entity.setDevelopmentTeam("基础服务组");
            entity.setDefinitionOfDone("联调通过");
        } else if (type == CodingIssueType.SUB_TASK) {
            entity.setEstimatedHours(new java.math.BigDecimal("8"));
            entity.setTaskType("开发");
        } else if (type == CodingIssueType.DEFECT) {
            entity.setOnlineBug(false);
            entity.setBugPriority("中");
        }
        entity.setSyncStatus(IssueSyncStatus.PENDING.name());
        entity.setCreateTime(LocalDateTime.now());
        return entity;
    }

    private IssueEntity linkedChild(long id, long parentId, CodingIssueType type, long code, String title) {
        IssueEntity entity = localIssue(id, parentId, type, title);
        entity.setCodingUrl("https://g-iijw5014.coding.net/p/project-a/issues/" + code + "/detail");
        entity.setIssueId(code + 10000);
        entity.setIssueCode(code);
        entity.setSyncStatus(IssueSyncStatus.SYNCED.name());
        return entity;
    }

    private CodingOpenApiPort.Issue codingIssue(long code, String type, String typeName, String title,
                                                boolean subtask) {
        return new CodingOpenApiPort.Issue(code, type, typeName, title, "Project A", subtask,
                code + 10000, "开发中", "PROCESSING");
    }

    private CodingOpenApiPort.IssueField selectField(long id, String name, String title, String value) {
        return new CodingOpenApiPort.IssueField(id, name, "CUSTOM", "SELECT", true, false, "",
                List.of(new CodingOpenApiPort.IssueFieldOption(value, title)));
    }

    private IssueWorklogEntity worklog(long id, IssueSyncStatus status) {
        IssueWorklogEntity entity = new IssueWorklogEntity();
        entity.setId(id);
        entity.setIterationId(1L);
        entity.setIssueId(2L);
        entity.setSpendHours(new BigDecimal("2.5"));
        entity.setRegisteredAt(LocalDateTime.of(2026, 8, 26, 16, 30));
        entity.setSyncStatus(status.name());
        entity.setCreatorUserId(actor.userId());
        entity.setCreatorUserName(actor.userName());
        entity.setCreateTime(LocalDateTime.now());
        return entity;
    }

    private List<CodingOpenApiPort.IssueField> defectFields() {
        return List.of(
                selectField(301L, "是否线上Bug", "否", "false"),
                selectField(302L, "Bug严重性", "一般(C级)", "C"),
                selectField(303L, "操作系统", "Windows", "windows"),
                selectField(304L, "浏览器", "Chrome", "chrome"));
    }
}

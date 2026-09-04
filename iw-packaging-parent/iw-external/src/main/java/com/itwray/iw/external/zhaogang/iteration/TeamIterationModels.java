package com.itwray.iw.external.zhaogang.iteration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class TeamIterationModels {

    private TeamIterationModels() {
    }

    public enum Stage {
        NOT_STARTED,
        DEVELOPING,
        TESTING,
        RELEASED
    }

    public enum Role {
        PRODUCT,
        BACKEND,
        FRONTEND,
        QA
    }

    public enum CodingIssueType {
        REQUIREMENT,
        TASK,
        USER_STORY,
        SUB_TASK,
        DEFECT
    }

    public enum IssueSource {
        CODING,
        WORKBENCH
    }

    public enum IssueSyncStatus {
        NOT_REQUIRED,
        PENDING,
        SYNCING,
        SYNCED,
        FAILED,
        UNKNOWN
    }

    public record Actor(long userId, String userName, String avatar, String token, String teamKey,
                        long codingTeamId, String codingTeamHost) {
        public Actor(long userId, String userName, String avatar, String token, String teamKey) {
            this(userId, userName, avatar, token, teamKey, 0, null);
        }
    }

    public record UserSnapshot(long userId, String userName, String avatar) {
    }

    public record MemberInput(long teamId, long userId, List<Role> roles) {
        public MemberInput(long userId, List<Role> roles) {
            this(0, userId, roles);
        }
        public MemberInput {
            roles = roles == null ? List.of() : roles.stream().distinct().toList();
        }
    }

    public record TeamSnapshot(long id, String name) {
    }

    public record Member(long id, TeamSnapshot team, UserSnapshot user, List<Role> roles) {
        public Member(long id, UserSnapshot user, List<Role> roles) {
            this(id, new TeamSnapshot(0, ""), user, roles);
        }
        public Member {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    public record IterationQuery(Stage stage, Long memberUserId, String keyword, int pageNumber, int pageSize) {
    }

    public record CreateCommand(String requestId, String name, String version, Stage stage, LocalDate startDate,
                                LocalDate plannedReleaseDate, List<MemberInput> members) {
        public CreateCommand {
            stage = stage == null ? Stage.NOT_STARTED : stage;
            members = members == null ? List.of() : List.copyOf(members);
        }
    }

    public record UpdateCommand(int versionNo, String name, String version, Stage stage, LocalDate startDate,
                                LocalDate plannedReleaseDate) {
    }

    public record StageCommand(int versionNo, Stage targetStage) {
    }

    public record ReplaceMembersCommand(int versionNo, List<MemberInput> members) {
        public ReplaceMembersCommand {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }

    public record CodingIssueCommand(String url, Long parentIssueId) {
        public CodingIssueCommand(String url) {
            this(url, null);
        }
    }

    public record CreateChildIssueCommand(CodingIssueType issueType, String title, String description,
                                          String developmentTeam, String definitionOfDone,
                                          BigDecimal estimatedHours, String taskType,
                                          Boolean onlineBug, String bugPriority, Boolean syncToCoding) {
        public CreateChildIssueCommand(CodingIssueType issueType, String title, String description,
                                       String developmentTeam, String definitionOfDone,
                                       BigDecimal estimatedHours, String taskType,
                                       Boolean onlineBug, String bugPriority) {
            this(issueType, title, description, developmentTeam, definitionOfDone, estimatedHours, taskType,
                    onlineBug, bugPriority, null);
        }

        public CreateChildIssueCommand(CodingIssueType issueType, String title, String description) {
            this(issueType, title, description, null, null, null, null, null, null, null);
        }
    }

    public record UpdateIssueCommand(String title, String description, String developmentTeam,
                                     String definitionOfDone, BigDecimal estimatedHours, String taskType,
                                     Boolean onlineBug, String bugPriority) {
    }

    public record UpdateIssueStatusCommand(long statusId) {
    }

    public record RemoveIssuesCommand(List<Long> issueIds) {
        public RemoveIssuesCommand {
            issueIds = issueIds == null ? List.of() : issueIds.stream()
                    .filter(id -> id != null && id > 0)
                    .distinct()
                    .toList();
        }
    }

    public record SelectionOption(String value, String label) {
    }

    public record IssueCreationOptions(CodingIssueType issueType, List<SelectionOption> developmentTeams,
                                       List<SelectionOption> definitionsOfDone, List<SelectionOption> taskTypes,
                                       List<SelectionOption> bugPriorities) {
        public IssueCreationOptions {
            developmentTeams = developmentTeams == null ? List.of() : List.copyOf(developmentTeams);
            definitionsOfDone = definitionsOfDone == null ? List.of() : List.copyOf(definitionsOfDone);
            taskTypes = taskTypes == null ? List.of() : List.copyOf(taskTypes);
            bugPriorities = bugPriorities == null ? List.of() : List.copyOf(bugPriorities);
        }
    }

    public record RegisterWorklogCommand(BigDecimal spendHours, LocalDateTime registeredAt) {
    }

    public record IssueWorklog(long id, BigDecimal spendHours, LocalDateTime registeredAt,
                               IssueSyncStatus syncStatus, String syncMessage, LocalDateTime syncedAt,
                               UserSnapshot creator, LocalDateTime createdAt) {
    }

    public record CodingSyncFailure(long issueId, String title, String reason) {
    }

    public record CodingSyncResult(int successCount, int failureCount, List<CodingSyncFailure> failures) {
        public CodingSyncResult {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }

    public record Permissions(boolean canEdit, boolean canManageMembers, boolean canDelete) {
    }

    public record AddReleasePlanCommand(long projectId, long planId) {
    }

    public record ReleasePlan(long id, long projectId, String projectName, String projectDisplayName,
                              long planId, String planName, boolean quickBuildSupported,
                              UserSnapshot creator, LocalDateTime createdAt) {
    }

    public record IterationIssue(long id, Long parentId, IssueSource source, String url, String projectName,
                                 Long issueId, Long issueCode, CodingIssueType issueType, String issueTypeName,
                                 String title, String description, String statusName, boolean available,
                                 String warning, IssueSyncStatus syncStatus, String syncMessage,
                                 String developmentTeam, String definitionOfDone, BigDecimal estimatedHours,
                                 String taskType, Boolean onlineBug, String bugPriority,
                                 LocalDateTime syncedAt, LocalDateTime createdAt, List<IssueWorklog> worklogs,
                                 List<IterationIssue> children, BigDecimal recordedHours,
                                 Integer recordedWorklogCount, String assigneeName) {
        public IterationIssue {
            worklogs = worklogs == null ? List.of() : List.copyOf(worklogs);
            children = children == null ? List.of() : List.copyOf(children);
            recordedWorklogCount = recordedWorklogCount == null ? 0 : Math.max(0, recordedWorklogCount);
        }

        public IterationIssue(long id, Long parentId, IssueSource source, String url, String projectName,
                              Long issueId, Long issueCode, CodingIssueType issueType, String issueTypeName,
                              String title, String description, String statusName, boolean available,
                              String warning, IssueSyncStatus syncStatus, String syncMessage,
                              String developmentTeam, String definitionOfDone, BigDecimal estimatedHours,
                              String taskType, Boolean onlineBug, String bugPriority,
                              LocalDateTime syncedAt, LocalDateTime createdAt, List<IssueWorklog> worklogs,
                              List<IterationIssue> children, BigDecimal recordedHours,
                              Integer recordedWorklogCount) {
            this(id, parentId, source, url, projectName, issueId, issueCode, issueType, issueTypeName,
                    title, description, statusName, available, warning, syncStatus, syncMessage, developmentTeam,
                    definitionOfDone, estimatedHours, taskType, onlineBug, bugPriority, syncedAt, createdAt,
                    worklogs, children, recordedHours, recordedWorklogCount, null);
        }

        public IterationIssue(long id, Long parentId, IssueSource source, String url, String projectName,
                              Long issueId, Long issueCode, CodingIssueType issueType, String issueTypeName,
                              String title, String description, String statusName, boolean available,
                              String warning, IssueSyncStatus syncStatus, String syncMessage,
                              String developmentTeam, String definitionOfDone, BigDecimal estimatedHours,
                              String taskType, Boolean onlineBug, String bugPriority,
                              LocalDateTime syncedAt, LocalDateTime createdAt, List<IssueWorklog> worklogs,
                              List<IterationIssue> children) {
            this(id, parentId, source, url, projectName, issueId, issueCode, issueType, issueTypeName,
                    title, description, statusName, available, warning, syncStatus, syncMessage, developmentTeam,
                    definitionOfDone, estimatedHours, taskType, onlineBug, bugPriority, syncedAt, createdAt,
                    worklogs, children, null, null, null);
        }
    }

    public record IterationListItem(long id, String name, String version, Stage stage,
                                    LocalDate startDate, LocalDate plannedReleaseDate, UserSnapshot creator,
                                    List<Member> members, int issueCount, int versionNo,
                                    LocalDateTime createdAt, LocalDateTime updatedAt, Permissions permissions) {
    }

    public record IterationDetail(long id, String requestId, String name, String version, Stage stage,
                                  LocalDate startDate, LocalDate plannedReleaseDate, LocalDateTime releasedAt,
                                  UserSnapshot creator, List<Member> members, List<IterationIssue> issues,
                                  List<ReleasePlan> releasePlans,
                                  int versionNo, LocalDateTime createdAt, LocalDateTime updatedAt,
                                  Permissions permissions) {
        public IterationDetail {
            releasePlans = releasePlans == null ? List.of() : List.copyOf(releasePlans);
        }
    }

    public record PageResult<T>(List<T> items, long total, int pageNumber, int pageSize) {
        public PageResult {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}

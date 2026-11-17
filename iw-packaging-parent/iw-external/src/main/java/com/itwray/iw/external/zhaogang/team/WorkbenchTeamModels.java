package com.itwray.iw.external.zhaogang.team;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.itwray.iw.external.zhaogang.profile.WorkbenchProfile.CodingRole;

import java.time.LocalDateTime;
import java.util.List;

public final class WorkbenchTeamModels {

    private WorkbenchTeamModels() {
    }

    public record Actor(long userId, String userName, String avatar, long codingTeamId, String codingTeamKey,
                        String codingTeamHost) {
    }

    public record CreateCommand(String requestId, String name) {
    }

    public record RenameCommand(int versionNo, String name) {
    }

    public record UpdateMemberRoleCommand(int versionNo, @JsonProperty(required = true) CodingRole codingRole) {
    }

    public record UpdateMemberStatisticsCommand(int versionNo,
                                                @JsonProperty(required = true) Boolean ignoreTeamStatistics) {
    }

    public record UpdateMemberMobileCommand(int versionNo, @JsonProperty(required = true) String mobile) {
        @Override public String toString() { return "UpdateMemberMobileCommand[versionNo=" + versionNo + ", mobile=[redacted]]"; }
    }

    public record UpdateManagementTokenCommand(int versionNo, @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String token) {
        @Override
        public String toString() {
            return "UpdateManagementTokenCommand[versionNo=" + versionNo + "]";
        }
    }

    public record ManagementTokenStatus(boolean configured, Long ownerUserId, String ownerName, LocalDateTime verifiedAt) {
    }

    public record AddMembersCommand(int versionNo, long projectId, boolean matchTeamName, List<Long> userIds) {
    }

    public record MemberCandidate(long userId, String userName, String avatar) {
    }

    public record MemberCandidates(long projectId, String projectName, int versionNo, List<MemberCandidate> members) {
    }

    public record TransferAdministratorCommand(int versionNo, long successorUserId) {
    }

    public record LeaveCommand(int versionNo, Long successorUserId) {
    }

    public record VersionCommand(int versionNo) {
    }

    public record ReorderCommand(List<Long> teamIds) {
        public ReorderCommand {
            teamIds = teamIds == null ? List.of() : List.copyOf(teamIds);
        }
    }

    public record Member(long userId, String userName, String avatar, LocalDateTime joinedAt,
                         boolean administrator, CodingRole codingRole,
                         @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime lastUsedAt, String mobile,
                         boolean managedStatistics,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Boolean ignoreTeamStatistics) {
    }

    public record Permissions(boolean administrator, boolean canRename, boolean canRemoveMembers,
                              boolean canTransferAdministrator, boolean canLeave, boolean canDissolve,
                              boolean canManageStatistics) {
        public Permissions(boolean administrator, boolean canRename, boolean canRemoveMembers,
                           boolean canTransferAdministrator, boolean canLeave, boolean canDissolve) {
            this(administrator, canRename, canRemoveMembers, canTransferAdministrator, canLeave, canDissolve, false);
        }
    }

    public record TeamListItem(long id, String name, String codingTeamKey, int memberCount,
                               long administratorUserId, boolean administrator, int versionNo,
                               LocalDateTime updateTime) {
    }

    public record TeamDetail(long id, String name, String inviteCode, long codingTeamId, String codingTeamKey,
                             String codingTeamHost, long creatorUserId, long administratorUserId, int versionNo,
                             List<Member> members, Permissions permissions, LocalDateTime createTime,
                             LocalDateTime updateTime,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Long memberSourceProjectId) {
    }

    public record InvitationPreview(long teamId, String teamName, String codingTeamKey, int memberCount,
                                    boolean alreadyMember) {
    }

    public record WorklogTeamOption(long id, String name, List<WorklogMember> members) {
    }

    public interface StatisticsMember {
        long userId();
        String userName();
        String avatar();
        boolean managedStatistics();
    }

    public record TeamStatisticsCredential(@JsonIgnore String token) {
        public static final TeamStatisticsCredential EMPTY = new TeamStatisticsCredential("");

        @Override
        public String toString() {
            return "TeamStatisticsCredential[redacted]";
        }
    }

    public record WorklogMember(long userId, String userName, String avatar,
                                boolean managedStatistics) implements StatisticsMember {
        public WorklogMember(long userId, String userName, String avatar) {
            this(userId, userName, avatar, false);
        }
    }

    public record WorklogTeamScope(long id, String name, String codingTeamHost, List<WorklogMember> members,
                                   @JsonIgnore TeamStatisticsCredential managementCredential) {
        public WorklogTeamScope(long id, String name, String codingTeamHost, List<WorklogMember> members) {
            this(id, name, codingTeamHost, members, TeamStatisticsCredential.EMPTY);
        }
    }

    public record CodeSyncMember(long userId, String userName, String avatar, CodingRole codingRole, String mobile,
                                 boolean managedStatistics, boolean ignoreTeamStatistics) implements StatisticsMember {
        public CodeSyncMember(long userId, String userName, String avatar, CodingRole codingRole) {
            this(userId, userName, avatar, codingRole, null);
        }

        public CodeSyncMember(long userId, String userName, String avatar, CodingRole codingRole, String mobile) {
            this(userId, userName, avatar, codingRole, mobile, false, false);
        }
    }

    public record CodeSyncTeam(String name, String codingTeamKey, String codingTeamHost, List<CodeSyncMember> members,
                               @JsonIgnore TeamStatisticsCredential managementCredential, long administratorUserId) {
        public CodeSyncTeam(String name, String codingTeamKey, String codingTeamHost, List<CodeSyncMember> members) {
            this(name, codingTeamKey, codingTeamHost, members, TeamStatisticsCredential.EMPTY, 0);
        }
    }

    public record IterationMemberOption(long userId, String userName, String avatar) {
    }

    public record IterationTeamOption(long teamId, String teamName, List<IterationMemberOption> members) {
        public IterationTeamOption {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }
}

package com.itwray.iw.external.zhaogang.team;

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
                         boolean administrator) {
    }

    public record Permissions(boolean administrator, boolean canRename, boolean canRemoveMembers,
                              boolean canTransferAdministrator, boolean canLeave, boolean canDissolve) {
    }

    public record TeamListItem(long id, String name, String codingTeamKey, int memberCount,
                               long administratorUserId, boolean administrator, int versionNo,
                               LocalDateTime updateTime) {
    }

    public record TeamDetail(long id, String name, String inviteCode, long codingTeamId, String codingTeamKey,
                             String codingTeamHost, long creatorUserId, long administratorUserId, int versionNo,
                             List<Member> members, Permissions permissions, LocalDateTime createTime,
                             LocalDateTime updateTime) {
    }

    public record InvitationPreview(long teamId, String teamName, String codingTeamKey, int memberCount,
                                    boolean alreadyMember) {
    }

    public record WorklogTeamOption(long id, String name, int memberCount) {
    }

    public record WorklogMember(long userId, String userName, String avatar) {
    }

    public record WorklogTeamScope(long id, String name, String codingTeamHost, List<WorklogMember> members) {
    }

    public record IterationMemberOption(long userId, String userName, String avatar) {
    }

    public record IterationTeamOption(long teamId, String teamName, List<IterationMemberOption> members) {
        public IterationTeamOption {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }
}

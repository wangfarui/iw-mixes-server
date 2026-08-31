package com.itwray.iw.external.zhaogang.team;

import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Actor;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.CreateCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.InvitationPreview;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.LeaveCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Member;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Permissions;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.RenameCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.ReorderCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.TeamDetail;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.TeamListItem;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.TransferAdministratorCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.VersionCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.WorklogMember;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.WorklogTeamOption;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.WorklogTeamScope;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.IterationMemberOption;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.IterationTeamOption;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamRepository.StoredTeam;
import com.itwray.iw.external.zhaogang.team.entity.WorkbenchTeamEntities.MemberEntity;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;

@Service
class DefaultWorkbenchTeamModule implements WorkbenchTeamModule {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WorkbenchTeamRepository repository;

    DefaultWorkbenchTeamModule(WorkbenchTeamRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<TeamListItem> list(Actor actor) {
        validateActor(actor);
        repository.refreshProfile(actor);
        return repository.findByMember(actor.userId()).stream().map(stored -> toListItem(actor, stored)).toList();
    }

    @Override
    public List<TeamListItem> reorder(Actor actor, ReorderCommand command) {
        validateActor(actor);
        List<Long> teamIds = command == null ? List.of() : command.teamIds();
        if (teamIds.stream().anyMatch(teamId -> teamId == null || teamId <= 0)
                || new HashSet<>(teamIds).size() != teamIds.size()) {
            throw new WorkbenchTeamException("团队顺序不正确");
        }
        List<StoredTeam> current = repository.findByMember(actor.userId());
        if (current.size() != teamIds.size()
                || !current.stream().map(stored -> stored.team().getId()).collect(java.util.stream.Collectors.toSet())
                .equals(new HashSet<>(teamIds))) {
            throw new WorkbenchTeamException("团队列表已变化，请刷新后重试");
        }
        repository.reorder(actor.userId(), teamIds);
        return repository.findByMember(actor.userId()).stream().map(stored -> toListItem(actor, stored)).toList();
    }

    @Override
    public TeamDetail create(Actor actor, CreateCommand command) {
        validateActor(actor);
        if (command == null || StringUtils.isBlank(command.requestId()) || command.requestId().length() > 64) {
            throw new WorkbenchTeamException("创建请求标识不正确");
        }
        String name = normalizedName(command.name());
        StoredTeam existing = repository.findByRequestId(command.requestId()).orElse(null);
        if (existing != null) {
            if (existing.team().getCreatorUserId() != actor.userId()
                    || existing.team().getCodingTeamId() != actor.codingTeamId()) {
                throw new WorkbenchTeamException("创建请求标识已被使用");
            }
            return toDetail(actor, existing);
        }
        try {
            return toDetail(actor, repository.create(command.requestId(), name, inviteCode(), actor));
        } catch (RuntimeException error) {
            StoredTeam duplicate = repository.findByRequestId(command.requestId()).orElse(null);
            if (duplicate != null && duplicate.team().getCreatorUserId() == actor.userId()
                    && duplicate.team().getCodingTeamId() == actor.codingTeamId()) {
                return toDetail(actor, duplicate);
            }
            throw error;
        }
    }

    @Override
    public TeamDetail detail(Actor actor, long teamId) {
        validateActor(actor);
        repository.refreshProfile(actor);
        return toDetail(actor, requireMember(actor, require(teamId)));
    }

    @Override
    public TeamDetail rename(Actor actor, long teamId, RenameCommand command) {
        StoredTeam stored = requireAdministrator(actor, require(teamId));
        requireVersion(command == null ? 0 : command.versionNo());
        return toDetail(actor, repository.rename(teamId, command.versionNo(), actor, normalizedName(command.name())));
    }

    @Override
    public TeamDetail removeMember(Actor actor, long teamId, long targetUserId, VersionCommand command) {
        StoredTeam stored = requireAdministrator(actor, require(teamId));
        requireVersion(command == null ? 0 : command.versionNo());
        if (targetUserId <= 0 || targetUserId == stored.team().getAdministratorUserId()) {
            throw new WorkbenchTeamException("管理员不能通过移除成员操作退出团队");
        }
        requireMemberId(stored, targetUserId);
        return toDetail(actor, repository.removeMember(teamId, command.versionNo(), actor, targetUserId));
    }

    @Override
    public TeamDetail transferAdministrator(Actor actor, long teamId, TransferAdministratorCommand command) {
        StoredTeam stored = requireAdministrator(actor, require(teamId));
        requireVersion(command == null ? 0 : command.versionNo());
        long successorUserId = command.successorUserId();
        requireSuccessor(actor, stored, successorUserId);
        return toDetail(actor, repository.transferAdministrator(teamId, command.versionNo(), actor, successorUserId));
    }

    @Override
    public void leave(Actor actor, long teamId, LeaveCommand command) {
        StoredTeam stored = requireMember(actor, require(teamId));
        requireVersion(command == null ? 0 : command.versionNo());
        boolean administrator = stored.team().getAdministratorUserId() == actor.userId();
        if (!administrator) {
            if (command.successorUserId() != null) {
                throw new WorkbenchTeamException("普通成员退出不需要指定继任管理员");
            }
            repository.leave(teamId, command.versionNo(), actor, null);
            return;
        }
        if (stored.members().size() <= 1) {
            throw new WorkbenchTeamException("团队仅有管理员一人，请使用解散团队");
        }
        if (command.successorUserId() == null) {
            throw new WorkbenchTeamException("管理员退出前必须选择继任管理员");
        }
        requireSuccessor(actor, stored, command.successorUserId());
        repository.leave(teamId, command.versionNo(), actor, command.successorUserId());
    }

    @Override
    public void dissolve(Actor actor, long teamId, VersionCommand command) {
        requireAdministrator(actor, require(teamId));
        requireVersion(command == null ? 0 : command.versionNo());
        repository.dissolve(teamId, command.versionNo(), actor);
    }

    @Override
    public InvitationPreview previewInvitation(Actor actor, String inviteCode) {
        validateActor(actor);
        StoredTeam stored = requireInvitation(inviteCode);
        requireSameCodingTeam(actor, stored);
        return new InvitationPreview(stored.team().getId(), stored.team().getName(), stored.team().getCodingTeamKey(),
                stored.members().size(), containsMember(stored, actor.userId()));
    }

    @Override
    public TeamDetail join(Actor actor, String inviteCode) {
        validateActor(actor);
        StoredTeam stored = requireInvitation(inviteCode);
        requireSameCodingTeam(actor, stored);
        return toDetail(actor, repository.join(stored.team().getId(), actor));
    }

    @Override
    public List<WorklogTeamOption> worklogTeams(Actor actor) {
        validateActor(actor);
        repository.refreshProfile(actor);
        return repository.findByMember(actor.userId()).stream()
                .filter(stored -> stored.team().getCodingTeamId() == actor.codingTeamId())
                .map(stored -> new WorklogTeamOption(stored.team().getId(), stored.team().getName(),
                        stored.members().size()))
                .toList();
    }

    @Override
    public WorklogTeamScope resolveWorklogScope(Actor actor, long teamId) {
        validateActor(actor);
        repository.refreshProfile(actor);
        StoredTeam stored = requireMember(actor, require(teamId));
        List<WorklogMember> members = stored.members().stream()
                .map(member -> new WorklogMember(member.getCodingUserId(), member.getUserName(), member.getAvatar()))
                .toList();
        return new WorklogTeamScope(stored.team().getId(), stored.team().getName(),
                stored.team().getCodingTeamHost(), members);
    }

    @Override
    public List<IterationTeamOption> iterationMemberOptions(Actor actor) {
        validateActor(actor);
        repository.refreshProfile(actor);
        return repository.findByMember(actor.userId()).stream()
                .map(stored -> new IterationTeamOption(stored.team().getId(), stored.team().getName(),
                        stored.members().stream()
                                .map(member -> new IterationMemberOption(member.getCodingUserId(),
                                        member.getUserName(), member.getAvatar()))
                                .toList()))
                .toList();
    }

    private StoredTeam require(long teamId) {
        if (teamId <= 0) throw new WorkbenchTeamException("团队标识不正确");
        return repository.findById(teamId).orElseThrow(() -> new WorkbenchTeamException("团队不存在或已解散"));
    }

    private StoredTeam requireInvitation(String inviteCode) {
        if (StringUtils.isBlank(inviteCode) || inviteCode.length() > 64) {
            throw new WorkbenchTeamException("邀请链接无效");
        }
        return repository.findByInviteCode(inviteCode.trim())
                .orElseThrow(() -> new WorkbenchTeamException("邀请不存在或团队已解散"));
    }

    private StoredTeam requireMember(Actor actor, StoredTeam stored) {
        requireSameCodingTeam(actor, stored);
        if (!containsMember(stored, actor.userId())) {
            throw new WorkbenchTeamException("团队不存在或当前用户不是团队成员");
        }
        return stored;
    }

    private StoredTeam requireAdministrator(Actor actor, StoredTeam stored) {
        requireMember(actor, stored);
        if (stored.team().getAdministratorUserId() != actor.userId()) {
            throw new WorkbenchTeamException("只有团队管理员可以执行此操作");
        }
        return stored;
    }

    private void requireSuccessor(Actor actor, StoredTeam stored, long successorUserId) {
        if (successorUserId <= 0 || successorUserId == actor.userId()) {
            throw new WorkbenchTeamException("请选择另一名当前成员作为继任管理员");
        }
        requireMemberId(stored, successorUserId);
    }

    private void requireMemberId(StoredTeam stored, long userId) {
        if (!containsMember(stored, userId)) {
            throw new WorkbenchTeamException("所选用户不是当前团队成员");
        }
    }

    private void requireSameCodingTeam(Actor actor, StoredTeam stored) {
        if (stored.team().getCodingTeamId() != actor.codingTeamId()) {
            throw new WorkbenchTeamException("该邀请仅限指定 CODING 团队成员");
        }
    }

    private boolean containsMember(StoredTeam stored, long userId) {
        return stored.members().stream().anyMatch(member -> member.getCodingUserId() == userId);
    }

    private TeamListItem toListItem(Actor actor, StoredTeam stored) {
        return new TeamListItem(stored.team().getId(), stored.team().getName(), stored.team().getCodingTeamKey(),
                stored.members().size(), stored.team().getAdministratorUserId(),
                stored.team().getAdministratorUserId() == actor.userId(), stored.team().getVersionNo(),
                stored.team().getUpdateTime());
    }

    private TeamDetail toDetail(Actor actor, StoredTeam stored) {
        requireMember(actor, stored);
        long administratorUserId = stored.team().getAdministratorUserId();
        List<Member> members = stored.members().stream().map(member -> toMember(member, administratorUserId)).toList();
        boolean administrator = administratorUserId == actor.userId();
        Permissions permissions = new Permissions(administrator, administrator, administrator, administrator,
                !administrator || members.size() > 1, administrator);
        return new TeamDetail(stored.team().getId(), stored.team().getName(), stored.team().getInviteCode(),
                stored.team().getCodingTeamId(), stored.team().getCodingTeamKey(), stored.team().getCodingTeamHost(),
                stored.team().getCreatorUserId(), administratorUserId, stored.team().getVersionNo(), members,
                permissions, stored.team().getCreateTime(), stored.team().getUpdateTime());
    }

    private Member toMember(MemberEntity member, long administratorUserId) {
        return new Member(member.getCodingUserId(), member.getUserName(), member.getAvatar(), member.getCreateTime(),
                member.getCodingUserId() == administratorUserId);
    }

    private String normalizedName(String name) {
        String normalized = StringUtils.trimToEmpty(name);
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw new WorkbenchTeamException("团队名称不能为空且不能超过 64 个字符");
        }
        return normalized;
    }

    private void requireVersion(int versionNo) {
        if (versionNo <= 0) throw new WorkbenchTeamException("团队版本不正确，请刷新后重试");
    }

    private void validateActor(Actor actor) {
        if (actor == null || actor.userId() <= 0 || actor.codingTeamId() <= 0) {
            throw new WorkbenchTeamException("当前 CODING 身份不完整，请重新绑定令牌");
        }
    }

    private String inviteCode() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

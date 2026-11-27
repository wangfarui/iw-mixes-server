package com.itwray.iw.external.zhaogang.team;

import com.itwray.iw.common.utils.AESUtils;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.ProjectMember;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.ProjectReference;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.profile.WorkbenchProfile.MemberProfile;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.TeamStatisticsCredential;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Actor;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.AddMembersCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.ManagementTokenStatus;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.MemberCandidate;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.MemberCandidates;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.UpdateManagementTokenCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.CodeSyncMember;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.CodeSyncTeam;
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
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.UpdateMemberRoleCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.UpdateMemberMobileCommand;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.UpdateMemberStatisticsCommand;
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

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
class DefaultWorkbenchTeamModule implements WorkbenchTeamModule {

    private org.springframework.context.ApplicationEventPublisher events;

    @org.springframework.beans.factory.annotation.Autowired
    void useStatisticsEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }


    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_ADD_MEMBERS = 100;

    private final WorkbenchTeamRepository repository;

    private final ZhaogangProperties properties;
    private final CodingOpenApiPort coding;

    DefaultWorkbenchTeamModule(WorkbenchTeamRepository repository, ZhaogangProperties properties,
                               CodingOpenApiPort coding) {
        this.repository = repository;
        this.properties = properties;
        this.coding = coding;
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
    public ManagementTokenStatus managementTokenStatus(Actor actor, long teamId) {
        requireMaintainer(actor, teamId);
        ManagementTokenStatus status = repository.managementTokenStatus(teamId);
        return status == null ? new ManagementTokenStatus(false, null, null, null) : status;
    }

    @Override
    public TeamDetail updateManagementToken(Actor actor, long teamId, UpdateManagementTokenCommand command) {
        StoredTeam stored = requireMaintainer(actor, teamId);
        requireCurrentVersion(stored, command == null ? 0 : command.versionNo());
        String token = StringUtils.trimToEmpty(command.token());
        if (token.isEmpty() || token.length() > 512 || token.chars().anyMatch(Character::isWhitespace)) {
            throw new WorkbenchTeamException("请输入有效的 CODING PAT");
        }
        CodingOpenApiPort.CodingUserProfile owner;
        try {
            owner = coding.currentUserProfile(token);
        } catch (CodingOpenApiException error) {
            throw new WorkbenchTeamException("无法验证管理令牌，请检查令牌和用户信息读取权限");
        }
        if (owner.id() <= 0 || owner.teamId() != actor.codingTeamId()) {
            throw new WorkbenchTeamException("管理令牌必须属于当前 CODING 团队");
        }
        verifyStatisticsAccess(token, stored, owner.id());
        var status = new ManagementTokenStatus(true, owner.id(),
                StringUtils.left(StringUtils.defaultIfBlank(owner.name(), Long.toString(owner.id())), 128),
                LocalDateTime.now(ZONE));
        var detail = toDetail(actor, repository.saveManagementToken(teamId, actor, command.versionNo(),
                AESUtils.encryptAESGCM(managementTokenKey(), token), status));
        if (events != null) events.publishEvent(new com.itwray.iw.external.zhaogang.credential.StatisticsCredentialChanged(
                actor.codingTeamId(), owner.id(), teamId));
        return detail;
    }

    private void verifyStatisticsAccess(String token, StoredTeam stored, long ownerId) {
        long targetId = stored.members().stream().filter(member -> Boolean.TRUE.equals(member.getManagedStatistics()))
                .mapToLong(MemberEntity::getCodingUserId).filter(id -> id != ownerId).findFirst()
                .orElse(stored.team().getCreatorUserId());
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        try {
            var page = coding.worklogPage(token, now.minusDays(7).toInstant().toEpochMilli(),
                    now.toInstant().toEpochMilli(), targetId, 0, 1);
            if (page.items().stream().anyMatch(row -> row.userId() != targetId)) {
                throw new WorkbenchTeamException("CODING 返回的工时不属于所查成员，请检查令牌的工时读取权限");
            }
        } catch (CodingOpenApiException error) {
            throw new WorkbenchTeamException("管理令牌无法读取 CODING 工时，请检查项目协同权限");
        }
        try {
            coding.myDepots(token, 1, 1);
        } catch (CodingOpenApiException error) {
            throw new WorkbenchTeamException("管理令牌无法读取 CODING 代码仓库，请检查仓库读取权限");
        }
    }

    @Override
    public MemberCandidates memberCandidates(Actor actor, long teamId, boolean matchTeamName) {
        StoredTeam stored = requireMaintainer(actor, teamId);
        ManagementCredential credential = requireManagementToken(teamId);
        ProjectReference project = memberSourceProject(stored, credential, matchTeamName);
        return new MemberCandidates(project.id(), project.displayName(), stored.team().getVersionNo(),
                projectCandidates(stored, credential, project.id()));
    }

    @Override
    public TeamDetail addMembers(Actor actor, long teamId, AddMembersCommand command) {
        StoredTeam stored = requireMaintainer(actor, teamId);
        requireCurrentVersion(stored, command == null ? 0 : command.versionNo());
        List<Long> userIds = command.userIds();
        if (userIds == null || userIds.isEmpty() || userIds.size() > MAX_ADD_MEMBERS
                || userIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new WorkbenchTeamException("每次请选择 1 至 100 名 CODING 成员");
        }
        var requested = new HashSet<>(userIds);
        stored.members().forEach(member -> requested.remove(member.getCodingUserId()));
        if (requested.isEmpty()) return toDetail(actor, stored);

        ManagementCredential credential = requireManagementToken(teamId);
        ProjectReference project = memberSourceProject(stored, credential, command.matchTeamName());
        if (command.projectId() != project.id()) {
            throw new WorkbenchTeamException("来源 CODING 项目已变化，请刷新成员列表后重试");
        }
        var candidates = projectCandidates(stored, credential, project.id()).stream()
                .filter(member -> requested.contains(member.userId())).toList();
        if (candidates.size() != requested.size()) {
            throw new WorkbenchTeamException("部分成员已不在来源 CODING 项目或不可用，请重新选择");
        }
        return toDetail(actor, repository.addMembers(teamId, actor, command.versionNo(), project.id(), candidates));
    }

    private ProjectReference memberSourceProject(StoredTeam stored, ManagementCredential credential, boolean matchTeamName) {
        List<ProjectReference> projects;
        try {
            projects = coding.visibleProjects(credential.value().token(), credential.ownerUserId());
        } catch (CodingOpenApiException error) {
            if (!error.isPermissionDenied()) throw error;
            throw new WorkbenchTeamException("团队管理令牌无法读取项目列表，请检查项目信息读取权限");
        }
        Long projectId = stored.team().getMemberSourceProjectId();
        if (!matchTeamName && projectId != null) {
            return projects.stream().filter(project -> project.id() == projectId).findFirst()
                    .orElseThrow(() -> new WorkbenchTeamException("团队管理令牌无法访问已关联的 CODING 项目，可按当前团队名称重新匹配"));
        }

        String teamName = stored.team().getName();
        Map<Long, ProjectReference> matches = new LinkedHashMap<>();
        projects.stream().filter(project -> project.id() > 0
                        && (teamName.equals(project.displayName()) || teamName.equals(project.name())))
                .forEach(project -> matches.putIfAbsent(project.id(), project));
        if (matches.isEmpty()) {
            throw new WorkbenchTeamException("管理令牌的可见项目中未找到与 Dev Hub 团队同名的 CODING 项目");
        }
        if (matches.size() > 1) {
            throw new WorkbenchTeamException("存在多个同名 CODING 项目，请将 Dev Hub 团队改为目标项目的唯一标识后再试");
        }
        return matches.values().iterator().next();
    }

    private List<MemberCandidate> projectCandidates(StoredTeam stored, ManagementCredential credential, long projectId) {
        List<ProjectMember> members;
        try {
            members = coding.projectMembers(credential.value().token(), projectId);
        } catch (CodingOpenApiException error) {
            if (!error.isPermissionDenied()) throw error;
            throw new WorkbenchTeamException("团队管理令牌无法读取此 CODING 项目的成员，请检查项目成员读取权限");
        }
        var joined = stored.members().stream().map(MemberEntity::getCodingUserId).collect(Collectors.toSet());
        long codingTeamId = stored.team().getCodingTeamId();
        Map<Long, MemberCandidate> candidates = new LinkedHashMap<>();
        for (ProjectMember member : members) {
            if (member.userId() > 0 && member.teamId() == codingTeamId && member.active()
                    && !joined.contains(member.userId())) {
                candidates.putIfAbsent(member.userId(), new MemberCandidate(member.userId(),
                        StringUtils.left(StringUtils.defaultIfBlank(member.name(), Long.toString(member.userId())), 128),
                        StringUtils.left(member.avatar(), 512)));
            }
        }
        return List.copyOf(candidates.values());
    }

    @Override
    public TeamDetail rename(Actor actor, long teamId, RenameCommand command) {
        StoredTeam stored = requireAdministrator(actor, require(teamId));
        requireVersion(command == null ? 0 : command.versionNo());
        return toDetail(actor, repository.rename(teamId, command.versionNo(), actor, normalizedName(command.name())));
    }

    @Override
    public TeamDetail updateMemberMobile(Actor actor, long teamId, long targetUserId, UpdateMemberMobileCommand command) {
        StoredTeam stored = requireAdministrator(actor, require(teamId));
        requireVersion(command == null ? 0 : command.versionNo());
        requireMemberId(stored, targetUserId);
        String mobile = com.itwray.iw.external.zhaogang.profile.WorkbenchMobile.normalize(command.mobile());
        return toDetail(actor, repository.updateMemberMobile(teamId, actor, targetUserId,
                new UpdateMemberMobileCommand(command.versionNo(), mobile)));
    }

    @Override
    public TeamDetail updateMemberRole(Actor actor, long teamId, long targetUserId, UpdateMemberRoleCommand command) {
        StoredTeam stored = requireAdministrator(actor, require(teamId));
        requireVersion(command == null ? 0 : command.versionNo());
        requireMemberId(stored, targetUserId);
        return toDetail(actor, repository.updateMemberRole(teamId, actor, targetUserId, command));
    }

    @Override
    public TeamDetail updateMemberStatistics(Actor actor, long teamId, long targetUserId,
                                             UpdateMemberStatisticsCommand command) {
        StoredTeam stored = requireAdministrator(actor, require(teamId));
        requireCurrentVersion(stored, command == null ? 0 : command.versionNo());
        requireMemberId(stored, targetUserId);
        if (command.ignoreTeamStatistics() == null) {
            throw new WorkbenchTeamException("请选择是否忽略团队统计");
        }
        return toDetail(actor, repository.updateMemberStatistics(teamId, actor, targetUserId, command));
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
                        scopeMembers(stored)))
                .toList();
    }

    @Override
    public WorklogTeamScope resolveWorklogScope(Actor actor, long teamId) {
        validateActor(actor);
        repository.refreshProfile(actor);
        StoredTeam stored = requireMember(actor, require(teamId));
        return new WorklogTeamScope(stored.team().getId(), stored.team().getName(),
                stored.team().getCodingTeamHost(), scopeMembers(stored),
                managementCredential(teamId).map(ManagementCredential::value).orElse(TeamStatisticsCredential.EMPTY));
    }

    @Override
    public Optional<CodeSyncTeam> findWorklogSyncTeam(long codingTeamId, long teamId) {
        return findCodeSyncTeam(codingTeamId, teamId).map(team -> new CodeSyncTeam(team.name(),team.codingTeamKey(),
                team.codingTeamHost(),team.members(),managementCredential(teamId).map(ManagementCredential::value)
                .orElse(TeamStatisticsCredential.EMPTY),team.administratorUserId()));
    }

    @Override
    public Optional<CodeSyncTeam> findCodeSyncTeam(long codingTeamId, long teamId) {
        return repository.findById(teamId)
                .filter(stored -> stored.team().getCodingTeamId() == codingTeamId)
                .map(stored -> {
                    Map<Long, MemberProfile> profiles = memberProfiles(teamId, false);
                    List<CodeSyncMember> members = stored.members().stream().map(member -> {
                        MemberProfile profile = profiles.get(member.getCodingUserId());
                        return new CodeSyncMember(member.getCodingUserId(), member.getUserName(), member.getAvatar(),
                                profile == null ? null : profile.codingRole(), profile == null ? null : profile.mobile(),
                                Boolean.TRUE.equals(member.getManagedStatistics()),
                                Boolean.TRUE.equals(member.getIgnoreTeamStatistics()));
                    }).toList();
                    return new CodeSyncTeam(stored.team().getName(), stored.team().getCodingTeamKey(),
                            stored.team().getCodingTeamHost(), members, statisticsCredential(stored),
                            stored.team().getAdministratorUserId());
                });
    }

    private List<WorklogMember> scopeMembers(StoredTeam stored) {
        return stored.members().stream()
                .filter(member -> !Boolean.TRUE.equals(member.getIgnoreTeamStatistics()))
                .map(member -> new WorklogMember(member.getCodingUserId(), member.getUserName(), member.getAvatar(),
                        Boolean.TRUE.equals(member.getManagedStatistics())))
                .toList();
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

    private boolean canMaintain(Actor actor, StoredTeam team) {
        return actor.userId() == properties.getWorkbenchManagerUserId()
                && actor.userId() == team.team().getCreatorUserId();
    }

    private StoredTeam requireMaintainer(Actor actor, long teamId) {
        validateActor(actor);
        StoredTeam team = requireMember(actor, require(teamId));
        if (!canMaintain(actor, team)) {
            throw new WorkbenchTeamException("只有创建该团队的 Dev Hub 维护人可以执行此操作");
        }
        return team;
    }

    private void requireCurrentVersion(StoredTeam stored, int versionNo) {
        requireVersion(versionNo);
        if (stored.team().getVersionNo() != versionNo) {
            throw new WorkbenchTeamException("团队数据已变化，请刷新后重试");
        }
    }

    private ManagementCredential requireManagementToken(long teamId) {
        return managementCredential(teamId).orElseThrow(() -> new WorkbenchTeamException("请先配置该团队的管理令牌"));
    }

    private TeamStatisticsCredential statisticsCredential(StoredTeam stored) {
        return stored.members().stream().anyMatch(member -> Boolean.TRUE.equals(member.getManagedStatistics()))
                ? managementCredential(stored.team().getId()).map(ManagementCredential::value).orElse(TeamStatisticsCredential.EMPTY)
                : TeamStatisticsCredential.EMPTY;
    }

    private Optional<ManagementCredential> managementCredential(long teamId) {
        var stored = repository.managementTokenCredential(teamId);
        if (stored == null || StringUtils.isBlank(stored.ciphertext())) return Optional.empty();
        try {
            String token = decryptManagementToken(stored.ciphertext());
            return Optional.of(new ManagementCredential(stored.ownerUserId(), new TeamStatisticsCredential(token)));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private record ManagementCredential(long ownerUserId, TeamStatisticsCredential value) {
    }

    String decryptManagementToken(String ciphertext) {
        return AESUtils.decryptAESGCM(managementTokenKey(), ciphertext);
    }

    private SecretKey managementTokenKey() {
        return AESUtils.generateSecretKey(CodingCredentialService
                .fingerprint(properties.safeSessionKey() + ":team-statistics:v1").substring(0, 32));
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
        boolean administrator = administratorUserId == actor.userId();
        Map<Long, MemberProfile> profiles = memberProfiles(stored.team().getId(), administrator);
        List<Member> members = stored.members().stream()
                .map(member -> toMember(member, administratorUserId, administrator, profiles.get(member.getCodingUserId())))
                .toList();
        Permissions permissions = new Permissions(administrator, administrator, administrator, administrator,
                !administrator || members.size() > 1, administrator, canMaintain(actor, stored));
        return new TeamDetail(stored.team().getId(), stored.team().getName(), stored.team().getInviteCode(),
                stored.team().getCodingTeamId(), stored.team().getCodingTeamKey(), stored.team().getCodingTeamHost(),
                stored.team().getCreatorUserId(), administratorUserId, stored.team().getVersionNo(), members,
                permissions, stored.team().getCreateTime(), stored.team().getUpdateTime(), canMaintain(actor, stored) ? stored.team().getMemberSourceProjectId() : null);
    }

    private Map<Long, MemberProfile> memberProfiles(long teamId, boolean includeActivity) {
        return repository.findMemberProfiles(teamId, includeActivity).stream()
                .collect(Collectors.toMap(MemberProfile::userId, Function.identity()));
    }

    private Member toMember(MemberEntity member, long administratorUserId, boolean administratorView,
                            MemberProfile profile) {
        return new Member(member.getCodingUserId(), member.getUserName(), member.getAvatar(), member.getCreateTime(),
                member.getCodingUserId() == administratorUserId, profile == null ? null : profile.codingRole(),
                administratorView && profile != null ? profile.lastUsedAt() : null, profile == null ? null : profile.mobile(),
                Boolean.TRUE.equals(member.getManagedStatistics()),
                administratorView ? Boolean.TRUE.equals(member.getIgnoreTeamStatistics()) : null);
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

package com.itwray.iw.external.zhaogang.iteration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.itwray.iw.external.mapper.ZhaogangIterationIssueMapper;
import com.itwray.iw.external.mapper.ZhaogangIterationIssueWorklogMapper;
import com.itwray.iw.external.mapper.ZhaogangIterationMapper;
import com.itwray.iw.external.mapper.ZhaogangIterationMemberMapper;
import com.itwray.iw.external.mapper.ZhaogangIterationMemberRoleMapper;
import com.itwray.iw.external.mapper.ZhaogangIterationReleasePlanMapper;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Actor;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CodingIssueType;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CreateCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IssueSource;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IssueSyncStatus;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IterationQuery;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Role;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Stage;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UpdateCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UpdateIssueCommand;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.IterationEntity;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.IssueEntity;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.IssueWorklogEntity;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.MemberEntity;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.MemberRoleEntity;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.ReleasePlanEntity;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
class MybatisTeamIterationRepository implements TeamIterationRepository {

    private final ZhaogangIterationMapper iterationMapper;
    private final ZhaogangIterationMemberMapper memberMapper;
    private final ZhaogangIterationMemberRoleMapper roleMapper;
    private final ZhaogangIterationIssueMapper issueMapper;
    private final ZhaogangIterationIssueWorklogMapper worklogMapper;
    private final ZhaogangIterationReleasePlanMapper releasePlanMapper;

    MybatisTeamIterationRepository(ZhaogangIterationMapper iterationMapper,
                                   ZhaogangIterationMemberMapper memberMapper,
                                   ZhaogangIterationMemberRoleMapper roleMapper,
                                   ZhaogangIterationIssueMapper issueMapper,
                                   ZhaogangIterationIssueWorklogMapper worklogMapper,
                                   ZhaogangIterationReleasePlanMapper releasePlanMapper) {
        this.iterationMapper = iterationMapper;
        this.memberMapper = memberMapper;
        this.roleMapper = roleMapper;
        this.issueMapper = issueMapper;
        this.worklogMapper = worklogMapper;
        this.releasePlanMapper = releasePlanMapper;
    }

    @Override
    public Optional<StoredIteration> findByRequestId(String requestId) {
        IterationEntity entity = iterationMapper.selectOne(new LambdaQueryWrapper<IterationEntity>()
                .eq(IterationEntity::getRequestId, requestId));
        return Optional.ofNullable(entity).map(this::load);
    }

    @Override
    public Optional<StoredIteration> findById(long iterationId) {
        return Optional.ofNullable(iterationMapper.selectById(iterationId)).map(this::load);
    }

    @Override
    public StoredPage findPage(IterationQuery query, long currentUserId, String teamKey) {
        String keyword = StringUtils.trimToNull(query.keyword());
        long offset = (long) (query.pageNumber() - 1) * query.pageSize();
        String stage = query.stage() == null ? null : query.stage().name();
        List<StoredIteration> items = iterationMapper.selectBoardPage(teamKey, currentUserId, stage,
                query.memberUserId(), keyword, offset, query.pageSize()).stream().map(this::load).toList();
        long total = iterationMapper.countBoard(teamKey, currentUserId, stage, query.memberUserId(), keyword);
        return new StoredPage(items, total, query.pageNumber(), query.pageSize());
    }

    @Override
    @Transactional
    public StoredIteration create(CreateCommand command, Actor actor, List<ResolvedMember> members) {
        IterationEntity entity = new IterationEntity();
        entity.setRequestId(command.requestId());
        entity.setTeamKey(actor.teamKey());
        entity.setName(command.name());
        entity.setVersion(command.version());
        entity.setStage(command.stage().name());
        entity.setStartDate(command.startDate());
        entity.setPlannedReleaseDate(command.plannedReleaseDate());
        entity.setCreatorUserId(actor.userId());
        entity.setCreatorUserName(actor.userName());
        entity.setCreatorAvatar(actor.avatar());
        entity.setUpdaterUserId(actor.userId());
        entity.setUpdaterUserName(actor.userName());
        entity.setVersionNo(1);
        entity.setReleasedAt(command.stage() == Stage.RELEASED ? LocalDateTime.now() : null);
        iterationMapper.insert(entity);
        insertMembers(entity.getId(), members);
        return load(entity);
    }

    @Override
    @Transactional
    public StoredIteration update(long iterationId, UpdateCommand command, Actor actor) {
        int changed = iterationMapper.updateBasic(iterationId, command.versionNo(), command.name(), command.version(),
                command.stage().name(), command.startDate(), command.plannedReleaseDate(),
                actor.userId(), actor.userName());
        requireChanged(changed);
        return findById(iterationId).orElseThrow(() -> new TeamIterationException("迭代不存在"));
    }

    @Override
    @Transactional
    public StoredIteration updateStage(long iterationId, int versionNo, Stage stage, Actor actor) {
        LocalDateTime releasedAt = stage == Stage.RELEASED ? LocalDateTime.now() : null;
        requireChanged(iterationMapper.updateStage(iterationId, versionNo, stage.name(), releasedAt,
                actor.userId(), actor.userName()));
        return findById(iterationId).orElseThrow(() -> new TeamIterationException("迭代不存在"));
    }

    @Override
    @Transactional
    public StoredIteration replaceMembers(long iterationId, int versionNo, Actor actor, List<ResolvedMember> members) {
        requireChanged(iterationMapper.touchWithVersion(iterationId, versionNo, actor.userId(), actor.userName()));
        roleMapper.deleteByIterationId(iterationId);
        memberMapper.deleteByIterationId(iterationId);
        insertMembers(iterationId, members);
        return findById(iterationId).orElseThrow(() -> new TeamIterationException("迭代不存在"));
    }

    @Override
    public void softDelete(long iterationId, Actor actor) {
        if (iterationMapper.softDelete(iterationId, actor.userId(), actor.userName()) == 0) {
            throw new TeamIterationException("迭代不存在或已删除");
        }
    }

    @Override
    @Transactional
    public IssueEntity addCodingIssue(long iterationId, Long parentId, String url, String urlHash, String projectName,
                                      long issueId, long issueCode, CodingIssueType issueType,
                                      String codingSystemType, long codingIssueTypeId, String issueTypeName,
                                      String title, Actor actor) {
        IssueEntity entity = new IssueEntity();
        entity.setIterationId(iterationId);
        entity.setParentId(parentId);
        if (entity.getId() == null) entity.setSource(IssueSource.CODING.name());
        entity.setCodingUrl(url);
        entity.setUrlHash(urlHash);
        entity.setProjectName(projectName);
        entity.setIssueId(issueId);
        entity.setIssueCode(issueCode);
        entity.setIssueType(issueType.name());
        entity.setCodingSystemType(codingSystemType);
        entity.setCodingIssueTypeId(codingIssueTypeId > 0 ? codingIssueTypeId : null);
        entity.setIssueTypeName(issueTypeName);
        entity.setTitle(title);
        entity.setSyncStatus(IssueSyncStatus.SYNCED.name());
        entity.setSyncAttemptCount(0);
        entity.setSyncedAt(LocalDateTime.now());
        entity.setCreatorUserId(actor.userId());
        entity.setCreatorUserName(actor.userName());
        issueMapper.insert(entity);
        iterationMapper.touch(iterationId, actor.userId(), actor.userName());
        return entity;
    }

    @Override
    @Transactional
    public IssueEntity addChildIssue(long iterationId, long parentId, String projectName, CodingIssueType issueType,
                                     String issueTypeName, String title, String description, String developmentTeam,
                                     String definitionOfDone, BigDecimal estimatedHours, String taskType,
                                     Boolean onlineBug, String bugPriority, IssueSyncStatus syncStatus, Actor actor) {
        IssueEntity entity = new IssueEntity();
        entity.setIterationId(iterationId);
        entity.setParentId(parentId);
        entity.setSource(IssueSource.WORKBENCH.name());
        entity.setProjectName(projectName);
        entity.setIssueType(issueType.name());
        entity.setIssueTypeName(issueTypeName);
        entity.setTitle(title);
        entity.setDescription(description);
        entity.setDevelopmentTeam(developmentTeam);
        entity.setDefinitionOfDone(definitionOfDone);
        entity.setEstimatedHours(estimatedHours);
        entity.setTaskType(taskType);
        entity.setOnlineBug(onlineBug);
        entity.setBugPriority(bugPriority);
        entity.setSyncStatus(syncStatus.name());
        entity.setSyncAttemptCount(0);
        entity.setCreatorUserId(actor.userId());
        entity.setCreatorUserName(actor.userName());
        issueMapper.insert(entity);
        iterationMapper.touch(iterationId, actor.userId(), actor.userName());
        return entity;
    }

    @Override
    public Optional<IssueEntity> findIssue(long iterationId, String urlHash) {
        return Optional.ofNullable(issueMapper.selectOne(new LambdaQueryWrapper<IssueEntity>()
                .eq(IssueEntity::getIterationId, iterationId)
                .eq(IssueEntity::getUrlHash, urlHash)));
    }

    @Override
    public Optional<IssueEntity> findIssue(long iterationId, long issueId) {
        return Optional.ofNullable(issueMapper.selectOne(new LambdaQueryWrapper<IssueEntity>()
                .eq(IssueEntity::getIterationId, iterationId)
                .eq(IssueEntity::getId, issueId)));
    }

    @Override
    @Transactional
    public IssueEntity updateIssue(long iterationId, long issueId, UpdateIssueCommand command, Actor actor) {
        IssueEntity entity = findIssue(iterationId, issueId)
                .orElseThrow(() -> new TeamIterationException("事项不存在"));
        entity.setTitle(command.title());
        entity.setDescription(command.description());
        entity.setDevelopmentTeam(command.developmentTeam());
        entity.setDefinitionOfDone(command.definitionOfDone());
        entity.setEstimatedHours(command.estimatedHours());
        entity.setTaskType(command.taskType());
        entity.setOnlineBug(command.onlineBug());
        entity.setBugPriority(command.bugPriority());
        issueMapper.updateById(entity);
        iterationMapper.touch(iterationId, actor.userId(), actor.userName());
        return entity;
    }

    @Override
    @Transactional
    public boolean claimIssueSync(long iterationId, long issueId, Actor actor) {
        int changed = issueMapper.update(null, new LambdaUpdateWrapper<IssueEntity>()
                .eq(IssueEntity::getIterationId, iterationId)
                .eq(IssueEntity::getId, issueId)
                .in(IssueEntity::getSyncStatus, IssueSyncStatus.PENDING.name(), IssueSyncStatus.FAILED.name())
                .set(IssueEntity::getSyncStatus, IssueSyncStatus.SYNCING.name())
                .set(IssueEntity::getSyncMessage, null)
                .set(IssueEntity::getSyncErrorCode, null)
                .set(IssueEntity::getSyncStartedAt, LocalDateTime.now())
                .setSql("sync_attempt_count = sync_attempt_count + 1"));
        if (changed > 0) iterationMapper.touch(iterationId, actor.userId(), actor.userName());
        return changed > 0;
    }

    @Override
    @Transactional
    public IssueEntity markIssueSynced(long iterationId, long issueId, String url, String urlHash,
                                       long codingIssueId, long codingIssueCode, CodingIssueType issueType,
                                       String codingSystemType, long codingIssueTypeId, String issueTypeName,
                                       String title, Long codingParentCode, Actor actor) {
        IssueEntity entity = findIssue(iterationId, issueId)
                .orElseThrow(() -> new TeamIterationException("事项不存在"));
        entity.setCodingUrl(url);
        entity.setUrlHash(urlHash);
        entity.setIssueId(codingIssueId);
        entity.setIssueCode(codingIssueCode);
        entity.setIssueType(issueType.name());
        entity.setCodingSystemType(codingSystemType);
        entity.setCodingIssueTypeId(codingIssueTypeId > 0 ? codingIssueTypeId : null);
        entity.setIssueTypeName(issueTypeName);
        entity.setTitle(title);
        entity.setSyncStatus(IssueSyncStatus.SYNCED.name());
        entity.setSyncMessage(null);
        entity.setSyncErrorCode(null);
        entity.setSyncStartedAt(null);
        entity.setSyncedAt(LocalDateTime.now());
        entity.setCodingParentCode(codingParentCode);
        issueMapper.updateById(entity);
        iterationMapper.touch(iterationId, actor.userId(), actor.userName());
        return entity;
    }

    @Override
    @Transactional
    public IssueEntity upsertCodingSnapshot(long iterationId, Long parentId, String url, String urlHash,
                                             String projectName, long codingIssueId, long codingIssueCode,
                                             CodingIssueType issueType, String codingSystemType,
                                             long codingIssueTypeId, String issueTypeName, String title,
                                             String description, String developmentTeam, String definitionOfDone,
                                             BigDecimal estimatedHours, String taskType, Long codingParentCode,
                                             Actor actor) {
        IssueEntity entity = findIssue(iterationId, urlHash).orElseGet(IssueEntity::new);
        entity.setIterationId(iterationId);
        entity.setParentId(parentId);
        entity.setSource(IssueSource.CODING.name());
        entity.setCodingUrl(url);
        entity.setUrlHash(urlHash);
        entity.setProjectName(projectName);
        entity.setIssueId(codingIssueId);
        entity.setIssueCode(codingIssueCode);
        entity.setIssueType(issueType.name());
        entity.setCodingSystemType(codingSystemType);
        entity.setCodingIssueTypeId(codingIssueTypeId > 0 ? codingIssueTypeId : null);
        entity.setIssueTypeName(issueTypeName);
        entity.setTitle(title);
        entity.setDescription(description);
        entity.setDevelopmentTeam(developmentTeam);
        entity.setDefinitionOfDone(definitionOfDone);
        entity.setEstimatedHours(estimatedHours);
        entity.setTaskType(taskType);
        entity.setSyncStatus(IssueSyncStatus.SYNCED.name());
        entity.setSyncMessage(null);
        entity.setSyncErrorCode(null);
        entity.setCodingParentCode(codingParentCode);
        entity.setSyncedAt(LocalDateTime.now());
        if (entity.getId() == null) {
            entity.setCreatorUserId(actor.userId());
            entity.setCreatorUserName(actor.userName());
            entity.setSyncAttemptCount(0);
            issueMapper.insert(entity);
        } else {
            issueMapper.updateById(entity);
        }
        iterationMapper.touch(iterationId, actor.userId(), actor.userName());
        return entity;
    }

    @Override
    @Transactional
    public IssueEntity updateCodingWorklogSummary(long iterationId, long issueId, BigDecimal recordedHours,
                                                  int worklogCount, Actor actor) {
        IssueEntity entity = findIssue(iterationId, issueId)
                .orElseThrow(() -> new TeamIterationException("事项不存在"));
        entity.setCodingRecordedHours(recordedHours);
        entity.setCodingWorklogCount(Math.max(0, worklogCount));
        issueMapper.updateById(entity);
        iterationMapper.touch(iterationId, actor.userId(), actor.userName());
        return entity;
    }

    @Override
    @Transactional
    public void markIssueSyncFailed(long iterationId, long issueId, IssueSyncStatus status, String errorCode,
                                    String message, Actor actor) {
        if (status != IssueSyncStatus.FAILED && status != IssueSyncStatus.UNKNOWN) {
            throw new IllegalArgumentException("同步失败状态不正确");
        }
        IssueEntity entity = findIssue(iterationId, issueId)
                .orElseThrow(() -> new TeamIterationException("事项不存在"));
        entity.setSyncStatus(status.name());
        entity.setSyncMessage(StringUtils.abbreviate(message, 500));
        entity.setSyncErrorCode(StringUtils.abbreviate(errorCode, 128));
        entity.setSyncStartedAt(null);
        issueMapper.updateById(entity);
        iterationMapper.touch(iterationId, actor.userId(), actor.userName());
    }

    @Override
    @Transactional
    public IssueWorklogEntity addWorklog(long iterationId, long issueId, BigDecimal spendHours,
                                         LocalDateTime registeredAt, IssueSyncStatus syncStatus, Actor actor) {
        IssueWorklogEntity entity = new IssueWorklogEntity();
        entity.setIterationId(iterationId);
        entity.setIssueId(issueId);
        entity.setSpendHours(spendHours);
        entity.setRegisteredAt(registeredAt);
        entity.setSyncStatus(syncStatus.name());
        entity.setSyncAttemptCount(0);
        entity.setCreatorUserId(actor.userId());
        entity.setCreatorUserName(actor.userName());
        worklogMapper.insert(entity);
        iterationMapper.touch(iterationId, actor.userId(), actor.userName());
        return entity;
    }

    @Override
    public boolean claimWorklogSync(long worklogId) {
        return worklogMapper.update(null, new LambdaUpdateWrapper<IssueWorklogEntity>()
                .eq(IssueWorklogEntity::getId, worklogId)
                .in(IssueWorklogEntity::getSyncStatus, IssueSyncStatus.NOT_REQUIRED.name(),
                        IssueSyncStatus.PENDING.name(), IssueSyncStatus.FAILED.name())
                .set(IssueWorklogEntity::getSyncStatus, IssueSyncStatus.SYNCING.name())
                .set(IssueWorklogEntity::getSyncMessage, null)
                .set(IssueWorklogEntity::getSyncErrorCode, null)
                .set(IssueWorklogEntity::getSyncStartedAt, LocalDateTime.now())
                .setSql("sync_attempt_count = sync_attempt_count + 1")) > 0;
    }

    @Override
    public IssueWorklogEntity markWorklogSynced(long worklogId, String codingRequestId) {
        IssueWorklogEntity entity = Optional.ofNullable(worklogMapper.selectById(worklogId))
                .orElseThrow(() -> new TeamIterationException("工时记录不存在"));
        entity.setSyncStatus(IssueSyncStatus.SYNCED.name());
        entity.setSyncMessage(null);
        entity.setSyncErrorCode(null);
        entity.setSyncStartedAt(null);
        entity.setCodingRequestId(StringUtils.trimToNull(codingRequestId));
        entity.setSyncedAt(LocalDateTime.now());
        worklogMapper.updateById(entity);
        return entity;
    }

    @Override
    public IssueWorklogEntity markWorklogSyncFailed(long worklogId, IssueSyncStatus status, String errorCode,
                                                     String message) {
        IssueWorklogEntity entity = Optional.ofNullable(worklogMapper.selectById(worklogId))
                .orElseThrow(() -> new TeamIterationException("工时记录不存在"));
        entity.setSyncStatus(status.name());
        entity.setSyncMessage(StringUtils.abbreviate(message, 500));
        entity.setSyncErrorCode(StringUtils.abbreviate(errorCode, 128));
        entity.setSyncStartedAt(null);
        worklogMapper.updateById(entity);
        return entity;
    }

    @Override
    @Transactional
    public void removeIssueTrees(long iterationId, List<Long> issueIds, Actor actor) {
        List<IssueEntity> issues = issueMapper.selectList(new LambdaQueryWrapper<IssueEntity>()
                .eq(IssueEntity::getIterationId, iterationId));
        if (!new HashSet<>(issues.stream().map(IssueEntity::getId).toList()).containsAll(issueIds)) {
            throw new TeamIterationException("部分事项不存在，请刷新后重试");
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>(issueIds);
        List<Long> pendingIds = new ArrayList<>(ids);
        for (int index = 0; index < pendingIds.size(); index++) {
            long parentId = pendingIds.get(index);
            issues.stream().filter(item -> item.getParentId() != null && item.getParentId() == parentId)
                    .map(IssueEntity::getId).filter(ids::add).forEach(pendingIds::add);
        }
        List<Long> deleteIds = List.copyOf(ids);
        worklogMapper.deleteByIssueIds(iterationId, deleteIds);
        if (issueMapper.deleteOwned(iterationId, deleteIds) == 0) throw new TeamIterationException("事项不存在");
        iterationMapper.touch(iterationId, actor.userId(), actor.userName());
    }

    @Override
    public Optional<ReleasePlanEntity> findReleasePlan(long iterationId, long projectId, long planId) {
        return Optional.ofNullable(releasePlanMapper.selectOne(new LambdaQueryWrapper<ReleasePlanEntity>()
                .eq(ReleasePlanEntity::getIterationId, iterationId)
                .eq(ReleasePlanEntity::getCodingProjectId, projectId)
                .eq(ReleasePlanEntity::getCodingPlanId, planId)));
    }

    @Override
    @Transactional
    public ReleasePlanEntity addReleasePlan(long iterationId,
                                            TeamIterationReleasePlanResolver.ResolvedReleasePlan releasePlan,
                                            Actor actor) {
        ReleasePlanEntity entity = new ReleasePlanEntity();
        entity.setIterationId(iterationId);
        entity.setCodingProjectId(releasePlan.projectId());
        entity.setCodingProjectName(releasePlan.projectName());
        entity.setProjectDisplayName(releasePlan.projectDisplayName());
        entity.setCodingPlanId(releasePlan.planId());
        entity.setPlanName(releasePlan.planName());
        entity.setQuickBuildSupported(releasePlan.quickBuildSupported());
        entity.setCreatorUserId(actor.userId());
        entity.setCreatorUserName(actor.userName());
        entity.setCreatorAvatar(actor.avatar());
        releasePlanMapper.insert(entity);
        iterationMapper.touch(iterationId, actor.userId(), actor.userName());
        return entity;
    }

    @Override
    @Transactional
    public void removeReleasePlan(long iterationId, long releasePlanId, Actor actor) {
        if (releasePlanMapper.deleteOwned(iterationId, releasePlanId) == 0) {
            throw new TeamIterationException("发布计划不存在");
        }
        iterationMapper.touch(iterationId, actor.userId(), actor.userName());
    }

    private StoredIteration load(IterationEntity iteration) {
        List<MemberEntity> memberEntities = memberMapper.selectList(new LambdaQueryWrapper<MemberEntity>()
                .eq(MemberEntity::getIterationId, iteration.getId()).orderByAsc(MemberEntity::getId));
        List<Long> memberIds = memberEntities.stream().map(MemberEntity::getId).toList();
        Map<Long, List<Role>> roles = new HashMap<>();
        if (!memberIds.isEmpty()) {
            for (MemberRoleEntity role : roleMapper.selectList(new LambdaQueryWrapper<MemberRoleEntity>()
                    .in(MemberRoleEntity::getMemberId, memberIds).orderByAsc(MemberRoleEntity::getId))) {
                roles.computeIfAbsent(role.getMemberId(), ignored -> new ArrayList<>()).add(Role.valueOf(role.getRole()));
            }
        }
        List<StoredMember> members = memberEntities.stream()
                .map(member -> new StoredMember(member, roles.getOrDefault(member.getId(), List.of())))
                .toList();
        List<IssueEntity> issues = issueMapper.selectList(new LambdaQueryWrapper<IssueEntity>()
                .eq(IssueEntity::getIterationId, iteration.getId()).orderByAsc(IssueEntity::getId));
        List<IssueWorklogEntity> worklogs = worklogMapper.selectList(new LambdaQueryWrapper<IssueWorklogEntity>()
                .eq(IssueWorklogEntity::getIterationId, iteration.getId()).orderByAsc(IssueWorklogEntity::getId));
        List<ReleasePlanEntity> releasePlans = releasePlanMapper.selectList(new LambdaQueryWrapper<ReleasePlanEntity>()
                .eq(ReleasePlanEntity::getIterationId, iteration.getId()).orderByAsc(ReleasePlanEntity::getId));
        return new StoredIteration(iteration, members, issues, worklogs, releasePlans);
    }

    private void insertMembers(long iterationId, List<ResolvedMember> members) {
        for (ResolvedMember resolved : members) {
            MemberEntity member = new MemberEntity();
            member.setIterationId(iterationId);
            member.setWorkbenchTeamId(resolved.teamId() > 0 ? resolved.teamId() : null);
            member.setWorkbenchTeamName(StringUtils.trimToNull(resolved.teamName()));
            member.setCodingUserId(resolved.user().userId());
            member.setUserName(resolved.user().userName());
            member.setAvatar(resolved.user().avatar());
            memberMapper.insert(member);
            for (Role role : resolved.roles()) {
                MemberRoleEntity roleEntity = new MemberRoleEntity();
                roleEntity.setMemberId(member.getId());
                roleEntity.setRole(role.name());
                roleMapper.insert(roleEntity);
            }
        }
    }

    private void requireChanged(int changed) {
        if (changed == 0) throw new TeamIterationException("数据已被其他成员更新，请刷新后重试");
    }
}

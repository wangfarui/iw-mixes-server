package com.itwray.iw.external.zhaogang.iteration;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CreateIssueRequest;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CustomFieldValue;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.ModifyIssueRequest;
import com.itwray.iw.external.zhaogang.iteration.CodingIssueUrlParser.ParsedIssueUrl;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Actor;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.AddReleasePlanCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CodingIssueCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CodingIssueType;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CodingSyncFailure;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CodingSyncResult;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CreateChildIssueCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CreateCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IssueSource;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IssueSyncStatus;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IterationDetail;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IssueCreationOptions;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IssueWorklog;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IterationIssue;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IterationListItem;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IterationQuery;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Member;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.MemberInput;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.PageResult;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Permissions;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.ReplaceMembersCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.RegisterWorklogCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.ReleasePlan;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.RemoveIssuesCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.SelectionOption;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Stage;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.StageCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.TeamSnapshot;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UpdateCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UpdateIssueCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UpdateIssueStatusCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UserSnapshot;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationRepository.ResolvedMember;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationRepository.StoredIteration;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.IssueEntity;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.IssueWorklogEntity;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.ReleasePlanEntity;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.IterationMemberOption;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.IterationTeamOption;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModule;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
class DefaultTeamIterationModule implements TeamIterationModule {

    private final TeamIterationRepository repository;
    private final CodingOpenApiPort coding;
    private final CodingIssueUrlParser urlParser;
    private final WorkbenchTeamModule workbenchTeams;
    private final CodingIssueMetadataCatalog metadataCatalog;
    private final TeamIterationReleasePlanResolver releasePlanResolver;
    private final CodingIssueSnapshotLoader issueSnapshotLoader;

    DefaultTeamIterationModule(TeamIterationRepository repository, CodingOpenApiPort coding,
                               CodingIssueUrlParser urlParser) {
        this(repository, coding, urlParser, null, new CodingIssueMetadataCatalog(coding), null);
    }

    DefaultTeamIterationModule(TeamIterationRepository repository, CodingOpenApiPort coding,
                               CodingIssueUrlParser urlParser,
                               TeamIterationReleasePlanResolver releasePlanResolver) {
        this(repository, coding, urlParser, null, new CodingIssueMetadataCatalog(coding), releasePlanResolver);
    }

    DefaultTeamIterationModule(TeamIterationRepository repository, CodingOpenApiPort coding,
                               CodingIssueUrlParser urlParser, WorkbenchTeamModule workbenchTeams,
                               CodingIssueMetadataCatalog metadataCatalog,
                               TeamIterationReleasePlanResolver releasePlanResolver) {
        this(repository, coding, urlParser, workbenchTeams, metadataCatalog, releasePlanResolver, null);
    }

    @Autowired
    DefaultTeamIterationModule(TeamIterationRepository repository, CodingOpenApiPort coding,
                               CodingIssueUrlParser urlParser, WorkbenchTeamModule workbenchTeams,
                               CodingIssueMetadataCatalog metadataCatalog,
                               TeamIterationReleasePlanResolver releasePlanResolver,
                               CodingIssueSnapshotLoader issueSnapshotLoader) {
        this.repository = repository;
        this.coding = coding;
        this.urlParser = urlParser;
        this.workbenchTeams = workbenchTeams;
        this.metadataCatalog = metadataCatalog;
        this.releasePlanResolver = releasePlanResolver;
        this.issueSnapshotLoader = issueSnapshotLoader;
    }

    @Override
    public PageResult<IterationListItem> list(Actor actor, IterationQuery query) {
        validateQuery(query);
        TeamIterationRepository.StoredPage page = repository.findPage(query, actor.userId(), actor.teamKey());
        return new PageResult<>(page.items().stream().map(item -> toListItem(actor, item)).toList(),
                page.total(), page.pageNumber(), page.pageSize());
    }

    @Override
    public IterationDetail create(Actor actor, CreateCommand command) {
        validateCreate(command, actor.userId());
        StoredIteration existing = repository.findByRequestId(command.requestId()).orElse(null);
        if (existing != null) return authorizedDetail(actor, existing);
        List<ResolvedMember> members = resolveMembers(actor, command.members(), true);
        try {
            return toDetail(actor, repository.create(normalize(command), actor, members));
        } catch (RuntimeException error) {
            StoredIteration duplicate = repository.findByRequestId(command.requestId()).orElse(null);
            if (duplicate != null) return authorizedDetail(actor, duplicate);
            throw error;
        }
    }

    @Override
    public IterationDetail detail(Actor actor, long iterationId) {
        return authorizedDetail(actor, requireStored(iterationId));
    }

    @Override
    public IterationDetail update(Actor actor, long iterationId, UpdateCommand command) {
        StoredIteration stored = requireStored(iterationId);
        requireMember(actor, stored);
        UpdateCommand normalized = normalize(command, Stage.valueOf(stored.iteration().getStage()));
        validateBasic(normalized.name(), normalized.version(), normalized.stage(), normalized.startDate(),
                normalized.plannedReleaseDate());
        return toDetail(actor, repository.update(iterationId, normalized, actor));
    }

    @Override
    public IterationDetail transition(Actor actor, long iterationId, StageCommand command) {
        StoredIteration stored = requireStored(iterationId);
        requireMember(actor, stored);
        if (command == null || command.targetStage() == null) throw new TeamIterationException("请选择迭代状态");
        return toDetail(actor, repository.updateStage(iterationId, command.versionNo(), command.targetStage(), actor));
    }

    @Override
    public IterationDetail replaceMembers(Actor actor, long iterationId, ReplaceMembersCommand command) {
        StoredIteration stored = requireStored(iterationId);
        requireCreator(actor, stored);
        if (command == null) throw new TeamIterationException("成员信息不能为空");
        validateMembers(command.members(), stored.iteration().getCreatorUserId());
        List<ResolvedMember> members = resolveMembers(actor, command.members(), false);
        return toDetail(actor, repository.replaceMembers(iterationId, command.versionNo(), actor, members));
    }

    @Override
    public void delete(Actor actor, long iterationId) {
        StoredIteration stored = requireStored(iterationId);
        requireCreator(actor, stored);
        repository.softDelete(iterationId, actor);
    }

    @Override
    public List<UserSnapshot> teamMembers(Actor actor, String keyword) {
        if (workbenchTeams != null) {
            String normalized = StringUtils.trimToEmpty(keyword).toLowerCase(Locale.ROOT);
            return workbenchTeams.iterationMemberOptions(toWorkbenchActor(actor)).stream()
                    .flatMap(team -> team.members().stream())
                    .filter(member -> normalized.isEmpty()
                            || member.userName().toLowerCase(Locale.ROOT).contains(normalized))
                    .map(member -> new UserSnapshot(member.userId(), member.userName(), member.avatar()))
                    .distinct().sorted(Comparator.comparing(UserSnapshot::userName, String.CASE_INSENSITIVE_ORDER))
                    .limit(100).toList();
        }
        String normalized = StringUtils.trimToEmpty(keyword).toLowerCase(Locale.ROOT);
        return teamDirectory(actor).stream().filter(CodingOpenApiPort.Member::active)
                .filter(member -> normalized.isEmpty() || member.name().toLowerCase(Locale.ROOT).contains(normalized))
                .sorted(Comparator.comparing(CodingOpenApiPort.Member::name, String.CASE_INSENSITIVE_ORDER))
                .limit(100).map(member -> new UserSnapshot(member.id(), member.name(), member.avatar())).toList();
    }

    @Override
    public List<IterationTeamOption> iterationMemberOptions(Actor actor) {
        return workbenchTeams == null ? List.of() : workbenchTeams.iterationMemberOptions(toWorkbenchActor(actor));
    }

    @Override
    public IterationIssue addCodingIssue(Actor actor, long iterationId, CodingIssueCommand command) {
        StoredIteration stored = requireStored(iterationId);
        requireMember(actor, stored);
        if (command == null) throw new TeamIterationException("请输入 CODING 事项链接");
        IssueEntity parent = null;
        if (command.parentIssueId() != null) {
            parent = stored.issues().stream().filter(item -> item.getId().equals(command.parentIssueId())).findFirst()
                    .orElseThrow(() -> new TeamIterationException("父事项不存在"));
        }
        ParsedIssueUrl parsed = urlParser.parse(command.url(), actor.teamKey());
        String urlHash = issueHash(parsed.projectName(), parsed.issueCode());
        if (repository.findIssue(iterationId, urlHash).isPresent()) {
            throw new TeamIterationException("该 CODING 事项已关联到当前迭代");
        }
        CodingOpenApiPort.Issue issue = coding.issue(actor.token(), parsed.projectName(), parsed.issueCode());
        CodingIssueType type = supportedType(issue);
        if (type == CodingIssueType.TASK && parent != null) {
            throw new TeamIterationException("任务只能作为顶层事项关联");
        }
        CodingWorklogSummary worklogSummary = type == CodingIssueType.SUB_TASK
                ? codingWorklogSummary(actor, parsed.projectName(), parsed.issueCode()) : null;
        IssueEntity codingParent = null;
        if (type != CodingIssueType.TASK && issue.parentCode() != null && issue.parentCode() > 0) {
            codingParent = syncCodingSnapshot(actor, iterationId, parsed.projectName(), issue.parentCode(),
                    new HashMap<>(), new HashMap<>(), new HashSet<>());
        }
        IssueEntity effectiveParent = codingParent != null ? codingParent : parent;
        if (effectiveParent != null) validateChildType(CodingIssueType.valueOf(effectiveParent.getIssueType()), type);
        IssueEntity entity = repository.addCodingIssue(iterationId, effectiveParent == null ? null : effectiveParent.getId(),
                parsed.url(), urlHash, parsed.projectName(),
                issue.id(), issue.code(), type, issue.type(), issue.issueTypeId(), displayTypeName(issue, type),
                issue.title(), actor);
        entity = applyCodingWorklogSummary(actor, iterationId, entity, worklogSummary);
        return toIssue(actor, entity, Map.of(), Map.of(), new HashSet<>());
    }

    @Override
    public IterationIssue addChildIssue(Actor actor, long iterationId, long parentIssueId,
                                        CreateChildIssueCommand command) {
        StoredIteration stored = requireStored(iterationId);
        requireMember(actor, stored);
        IssueEntity parent = stored.issues().stream().filter(item -> item.getId() == parentIssueId).findFirst()
                .orElseThrow(() -> new TeamIterationException("父事项不存在"));
        if (command != null && command.issueType() == CodingIssueType.TASK) {
            throw new TeamIterationException("任务只能从 CODING 关联");
        }
        validateChild(command);
        validateChildType(CodingIssueType.valueOf(parent.getIssueType()), command.issueType());
        IssueSyncStatus syncStatus = command.issueType() == CodingIssueType.REQUIREMENT
                ? IssueSyncStatus.NOT_REQUIRED : IssueSyncStatus.PENDING;
        IssueEntity entity = repository.addChildIssue(iterationId, parentIssueId, parent.getProjectName(),
                command.issueType(), typeName(command.issueType()), command.title().trim(),
                StringUtils.trimToNull(command.description()), StringUtils.trimToNull(command.developmentTeam()),
                StringUtils.trimToNull(command.definitionOfDone()), command.estimatedHours(),
                StringUtils.trimToNull(command.taskType()), command.onlineBug(),
                StringUtils.trimToNull(command.bugPriority()), syncStatus, actor);
        if (Boolean.TRUE.equals(command.syncToCoding()) && syncStatus == IssueSyncStatus.PENDING) {
            try {
                return performIssueSync(actor, iterationId, entity, stored.issues(), stored.worklogs(), true);
            } catch (TeamIterationException error) {
                return autoSyncFailure(actor, iterationId, entity, IssueSyncStatus.FAILED,
                        "AUTO_SYNC_FAILED", error.getMessage());
            }
        }
        return toIssue(actor, entity, Map.of(), Map.of(), new HashSet<>());
    }

    @Override
    public IssueCreationOptions issueCreationOptions(Actor actor, long iterationId, long parentIssueId,
                                                     CodingIssueType issueType) {
        StoredIteration stored = requireStored(iterationId);
        requireMember(actor, stored);
        IssueEntity parent = stored.issues().stream().filter(item -> item.getId() == parentIssueId).findFirst()
                .orElseThrow(() -> new TeamIterationException("父事项不存在"));
        if (issueType == null) throw new TeamIterationException("请选择事项类型");
        validateChildType(CodingIssueType.valueOf(parent.getIssueType()), issueType);
        if (issueType == CodingIssueType.REQUIREMENT) {
            return new IssueCreationOptions(issueType, List.of(), List.of(), List.of(), List.of());
        }
        if (StringUtils.isBlank(parent.getProjectName())) {
            throw new TeamIterationException("父事项没有可用的 CODING 项目信息");
        }
        return metadataCatalog.creationOptions(actor.token(), parent.getProjectName(), issueType);
    }

    @Override
    public IssueCreationOptions issueEditOptions(Actor actor, long iterationId, long issueId) {
        StoredIteration stored = requireStored(iterationId);
        requireMember(actor, stored);
        IssueEntity issue = stored.issues().stream().filter(item -> item.getId() == issueId).findFirst()
                .orElseThrow(() -> new TeamIterationException("事项不存在"));
        CodingIssueType type = CodingIssueType.valueOf(issue.getIssueType());
        if (type == CodingIssueType.REQUIREMENT || type == CodingIssueType.TASK) {
            return new IssueCreationOptions(type, List.of(), List.of(), List.of(), List.of());
        }
        if (StringUtils.isBlank(issue.getProjectName())) {
            throw new TeamIterationException("事项没有可用的 CODING 项目信息");
        }
        return metadataCatalog.creationOptions(actor.token(), issue.getProjectName(), type);
    }

    @Override
    public List<SelectionOption> issueStatusOptions(Actor actor, long iterationId, long issueId) {
        StoredIteration stored = requireStored(iterationId);
        requireMember(actor, stored);
        IssueEntity issue = stored.issues().stream().filter(item -> item.getId() == issueId).findFirst()
                .orElseThrow(() -> new TeamIterationException("事项不存在"));
        requireCodingBacked(issue, "只有已关联或已同步的 CODING 事项可以修改状态");
        try {
            String issueType = StringUtils.defaultIfBlank(issue.getCodingSystemType(), issue.getIssueType());
            return coding.issueStatuses(actor.token(), issue.getProjectName(), issueType,
                    issue.getCodingIssueTypeId() == null ? 0 : issue.getCodingIssueTypeId()).stream()
                    .map(status -> new SelectionOption(Long.toString(status.id()), status.name())).toList();
        } catch (CodingOpenApiException error) {
            throw codingUpdateException(error, "读取 CODING 事项状态失败，请稍后重试");
        }
    }

    @Override
    public IterationIssue updateIssue(Actor actor, long iterationId, long issueId, UpdateIssueCommand command) {
        StoredIteration stored = requireStored(iterationId);
        requireMember(actor, stored);
        IssueEntity issue = stored.issues().stream().filter(item -> item.getId() == issueId).findFirst()
                .orElseThrow(() -> new TeamIterationException("事项不存在"));
        if (IssueSyncStatus.SYNCING.name().equals(issue.getSyncStatus())) {
            throw new TeamIterationException("事项正在同步，暂时不能编辑");
        }
        CodingIssueType type = CodingIssueType.valueOf(issue.getIssueType());
        validateIssueUpdate(type, command);
        UpdateIssueCommand normalized = normalize(type, command);
        if (isCodingBacked(issue)) {
            modifyCodingIssue(actor, issue, type, normalized);
        }
        IssueEntity updated = repository.updateIssue(iterationId, issueId, normalized, actor);
        return toIssue(actor, updated, Map.of(), worklogsByIssue(stored.worklogs()), new HashSet<>());
    }

    @Override
    public IterationIssue updateIssueStatus(Actor actor, long iterationId, long issueId,
                                            UpdateIssueStatusCommand command) {
        StoredIteration stored = requireStored(iterationId);
        requireMember(actor, stored);
        IssueEntity issue = stored.issues().stream().filter(item -> item.getId() == issueId).findFirst()
                .orElseThrow(() -> new TeamIterationException("事项不存在"));
        requireCodingBacked(issue, "只有已关联或已同步的 CODING 事项可以修改状态");
        if (command == null || command.statusId() <= 0) throw new TeamIterationException("请选择 CODING 状态");
        try {
            coding.modifyIssue(actor.token(), new ModifyIssueRequest(issue.getProjectName(), issue.getIssueCode(),
                    null, null, command.statusId(), null, null, List.of()));
        } catch (CodingOpenApiException error) {
            throw codingUpdateException(error, "CODING 状态同步失败，请稍后重试");
        }
        return toIssue(actor, issue, Map.of(), worklogsByIssue(stored.worklogs()), new HashSet<>());
    }

    @Override
    public IterationIssue syncIssue(Actor actor, long iterationId, long issueId) {
        StoredIteration stored = requireStored(iterationId);
        requireMember(actor, stored);
        Map<Long, IssueEntity> issues = stored.issues().stream()
                .collect(java.util.stream.Collectors.toMap(IssueEntity::getId, item -> item));
        IssueEntity entity = issues.get(issueId);
        if (entity == null) throw new TeamIterationException("事项不存在");
        if (!IssueSource.WORKBENCH.name().equals(entity.getSource())) {
            throw new TeamIterationException("已关联的 CODING 事项不需要同步");
        }
        if (IssueSyncStatus.SYNCED.name().equals(entity.getSyncStatus())) {
            throw new TeamIterationException("该事项已经同步到 CODING");
        }
        if (IssueSyncStatus.SYNCING.name().equals(entity.getSyncStatus())) {
            throw new TeamIterationException("该事项正在同步，请勿重复提交");
        }
        if (IssueSyncStatus.UNKNOWN.name().equals(entity.getSyncStatus())) {
            throw new TeamIterationException("上次同步结果不确定，请先在 CODING 核对后再处理");
        }
        CodingIssueType type = CodingIssueType.valueOf(entity.getIssueType());
        if (type == CodingIssueType.REQUIREMENT) throw new TeamIterationException("需求不允许同步到 CODING");
        if (type == CodingIssueType.TASK) throw new TeamIterationException("任务只能从 CODING 关联");
        return performIssueSync(actor, iterationId, entity, stored.issues(), stored.worklogs(), false);
    }

    private IterationIssue performIssueSync(Actor actor, long iterationId, IssueEntity entity,
                                            List<IssueEntity> issues, List<IssueWorklogEntity> worklogs,
                                            boolean returnFailure) {
        CodingIssueType type = CodingIssueType.valueOf(entity.getIssueType());
        validateSyncFields(entity, type);
        IssueEntity codingParent = codingParentFor(type, entity, issues);
        Long parentCode = type == CodingIssueType.DEFECT ? null : codingParent.getIssueCode();
        CodingIssueMetadataCatalog.IssueMetadata metadata;
        try {
            metadata = metadataCatalog.metadata(actor.token(), codingParent.getProjectName(), type);
        } catch (CodingOpenApiException error) {
            throw new TeamIterationException(error.isPermissionDenied()
                    ? error.permissionMessage()
                    : "CODING 项目事项配置读取失败，请稍后重试", error);
        }
        if (type == CodingIssueType.USER_STORY) {
            metadataCatalog.validateUserStoryParent(metadata, codingParent.getCodingIssueTypeId());
        }
        CreateIssueRequest request = createIssueRequest(entity, type, codingParent.getProjectName(),
                parentCode, actor.userId(), metadata);
        if (!repository.claimIssueSync(iterationId, entity.getId(), actor)) {
            throw new TeamIterationException("该事项的同步状态已变化，请刷新后重试");
        }
        try {
            CodingOpenApiPort.Issue created = coding.createIssue(actor.token(), request);
            CodingIssueType actualType = supportedType(created);
            String url = codingIssueUrl(actor.teamKey(), codingParent.getProjectName(), actualType, created.code());
            IssueEntity updated = repository.markIssueSynced(iterationId, entity.getId(), url,
                    issueHash(codingParent.getProjectName(), created.code()), created.id(), created.code(), actualType,
                    created.type(), created.issueTypeId(), displayTypeName(created, actualType), created.title(),
                    parentCode, actor);
            return toIssue(actor, updated, Map.of(), worklogsByIssue(worklogs), new HashSet<>());
        } catch (CodingOpenApiException error) {
            String message = error.isPermissionDenied()
                    ? error.permissionMessage()
                    : "CODING 事项同步失败：" + StringUtils.defaultIfBlank(error.getMessage(), "请稍后重试");
            IssueSyncStatus failureStatus = error.isTransportFailure()
                    ? IssueSyncStatus.UNKNOWN : IssueSyncStatus.FAILED;
            repository.markIssueSyncFailed(iterationId, entity.getId(), failureStatus, error.code(), message, actor);
            if (returnFailure) {
                entity.setSyncStatus(failureStatus.name());
                entity.setSyncMessage(message);
                entity.setSyncErrorCode(error.code());
                entity.setSyncStartedAt(null);
                return toIssue(actor, entity, Map.of(), Map.of(), new HashSet<>());
            }
            throw new TeamIterationException(message, error);
        }
    }

    private IterationIssue autoSyncFailure(Actor actor, long iterationId, IssueEntity entity,
                                           IssueSyncStatus status, String errorCode, String message) {
        IssueEntity latest = repository.findIssue(iterationId, entity.getId()).orElse(entity);
        IssueSyncStatus latestStatus = syncStatus(latest);
        if (latestStatus != IssueSyncStatus.PENDING && latestStatus != IssueSyncStatus.FAILED) {
            return toIssue(actor, latest, Map.of(), Map.of(), new HashSet<>());
        }
        String failureMessage = StringUtils.defaultIfBlank(message, "自动同步 CODING 失败，请稍后重试");
        repository.markIssueSyncFailed(iterationId, latest.getId(), status, errorCode, failureMessage, actor);
        latest.setSyncStatus(status.name());
        latest.setSyncMessage(failureMessage);
        latest.setSyncErrorCode(errorCode);
        latest.setSyncStartedAt(null);
        return toIssue(actor, latest, Map.of(), Map.of(), new HashSet<>());
    }

    @Override
    public CodingSyncResult syncCodingIssues(Actor actor, long iterationId) {
        StoredIteration stored = requireStored(iterationId);
        requireMember(actor, stored);
        List<IssueEntity> linked = stored.issues().stream()
                .filter(item -> item.getIssueCode() != null && item.getIssueCode() > 0
                        && StringUtils.isNotBlank(item.getProjectName()))
                .toList();
        Map<String, CodingOpenApiPort.Issue> snapshots = new HashMap<>();
        Map<String, IssueEntity> syncedEntities = new HashMap<>();
        List<CodingSyncFailure> failures = new ArrayList<>();
        int success = 0;
        for (IssueEntity entity : linked) {
            try {
                CodingIssueType type = CodingIssueType.valueOf(entity.getIssueType());
                if (type == CodingIssueType.DEFECT) {
                    throw new TeamIterationException("迭代暂不支持缺陷事项同步");
                }
                syncCodingSnapshot(actor, iterationId, entity.getProjectName(), entity.getIssueCode(),
                        snapshots, syncedEntities, new HashSet<>());
                success++;
            } catch (RuntimeException error) {
                String reason = error instanceof CodingOpenApiException codingError
                        && codingError.isPermissionDenied() ? codingError.permissionMessage()
                        : StringUtils.defaultIfBlank(error.getMessage(), "同步失败");
                failures.add(new CodingSyncFailure(entity.getId(), StringUtils.defaultIfBlank(entity.getTitle(),
                        "#" + entity.getIssueCode()), reason));
            }
        }
        return new CodingSyncResult(success, failures.size(), failures);
    }

    private IssueEntity syncCodingSnapshot(Actor actor, long iterationId, String projectName, long issueCode,
                                           Map<String, CodingOpenApiPort.Issue> snapshots,
                                           Map<String, IssueEntity> syncedEntities, Set<String> path) {
        String key = projectName + ":" + issueCode;
        IssueEntity existing = syncedEntities.get(key);
        if (existing != null) return existing;
        if (!path.add(key)) throw new TeamIterationException("CODING 父级关系存在循环");
        CodingOpenApiPort.Issue issue = snapshots.computeIfAbsent(key,
                ignored -> coding.issue(actor.token(), projectName, issueCode));
        CodingIssueType type = supportedType(issue);
        IssueEntity parent = null;
        Long parentCode = type == CodingIssueType.TASK ? null : issue.parentCode();
        if (parentCode != null && parentCode > 0) {
            String parentProject = StringUtils.defaultIfBlank(issue.parentProjectName(), projectName);
            parent = syncCodingSnapshot(actor, iterationId, parentProject, parentCode, snapshots, syncedEntities,
                    new HashSet<>(path));
        }
        if (parent != null) validateChildType(CodingIssueType.valueOf(parent.getIssueType()), type);
        if (type == CodingIssueType.DEFECT) throw new TeamIterationException("迭代暂不支持缺陷事项同步");
        String normalizedProject = StringUtils.defaultIfBlank(projectName, issue.projectDisplayName());
        long normalizedCode = issue.code() > 0 ? issue.code() : issueCode;
        long normalizedIssueId = issue.id() > 0 ? issue.id() : normalizedCode;
        String url = codingIssueUrl(actor.teamKey(), normalizedProject, type, normalizedCode);
        CodingWorklogSummary worklogSummary = type == CodingIssueType.SUB_TASK
                ? codingWorklogSummary(actor, normalizedProject, normalizedCode) : null;
        String developmentTeam = type == CodingIssueType.USER_STORY
                ? metadataCatalog.displayValue(actor.token(), normalizedProject, type,
                issue.developmentTeam(), "开发团队") : null;
        String definitionOfDone = type == CodingIssueType.USER_STORY
                ? metadataCatalog.displayValue(actor.token(), normalizedProject, type,
                issue.definitionOfDone(), "DoD", "DOD", "Definition of Done") : null;
        String taskType = type == CodingIssueType.SUB_TASK
                ? metadataCatalog.displayValue(actor.token(), normalizedProject, type,
                issue.taskType(), "任务类型") : null;
        IssueEntity result = repository.upsertCodingSnapshot(iterationId, parent == null ? null : parent.getId(), url,
                issueHash(normalizedProject, normalizedCode), normalizedProject, normalizedIssueId, normalizedCode, type,
                issue.type(), issue.issueTypeId(), displayTypeName(issue, type), issue.title(), issue.description(),
                developmentTeam, definitionOfDone,
                type == CodingIssueType.SUB_TASK ? issue.workingHours() : null, taskType, parentCode, actor);
        result = applyCodingWorklogSummary(actor, iterationId, result, worklogSummary);
        syncedEntities.put(key, result);
        return result;
    }

    private IssueEntity applyCodingWorklogSummary(Actor actor, long iterationId, IssueEntity entity,
                                                  CodingWorklogSummary summary) {
        if (summary == null || entity == null || entity.getId() == null) return entity;
        entity.setCodingRecordedHours(summary.recordedHours());
        entity.setCodingWorklogCount(summary.worklogCount());
        IssueEntity updated = repository.updateCodingWorklogSummary(iterationId, entity.getId(),
                summary.recordedHours(), summary.worklogCount(), actor);
        return updated == null ? entity : updated;
    }

    private CodingWorklogSummary codingWorklogSummary(Actor actor, String projectName, long issueCode) {
        List<CodingOpenApiPort.IssueWorklog> worklogs = coding.issueWorklogs(actor.token(), projectName, issueCode);
        BigDecimal total = worklogs.stream().map(CodingOpenApiPort.IssueWorklog::spendHours)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CodingWorklogSummary(total, worklogs.size());
    }

    @Override
    public IssueWorklog registerWorklog(Actor actor, long iterationId, long issueId,
                                        RegisterWorklogCommand command) {
        StoredIteration stored = requireStored(iterationId);
        requireMember(actor, stored);
        IssueEntity issue = stored.issues().stream().filter(item -> item.getId() == issueId).findFirst()
                .orElseThrow(() -> new TeamIterationException("事项不存在"));
        validateWorklog(issue, command);
        IssueSyncStatus status = issue.getIssueCode() == null ? IssueSyncStatus.NOT_REQUIRED : IssueSyncStatus.PENDING;
        IssueWorklogEntity worklog = repository.addWorklog(iterationId, issueId, command.spendHours(),
                command.registeredAt(), status, actor);
        if (issue.getIssueCode() != null && issue.getIssueCode() > 0) {
            worklog = syncWorklog(actor, issue, worklog);
        }
        return toWorklog(worklog);
    }

    @Override
    public IssueWorklog retryWorklog(Actor actor, long iterationId, long issueId, long worklogId) {
        StoredIteration stored = requireStored(iterationId);
        requireMember(actor, stored);
        IssueEntity issue = stored.issues().stream().filter(item -> item.getId() == issueId).findFirst()
                .orElseThrow(() -> new TeamIterationException("事项不存在"));
        IssueWorklogEntity worklog = stored.worklogs().stream()
                .filter(item -> item.getId() == worklogId && item.getIssueId() == issueId).findFirst()
                .orElseThrow(() -> new TeamIterationException("工时记录不存在"));
        if (issue.getIssueCode() == null || issue.getIssueCode() <= 0) {
            throw new TeamIterationException("该子工作项尚未关联 CODING，无法同步工时");
        }
        if (IssueSyncStatus.UNKNOWN.name().equals(worklog.getSyncStatus())) {
            throw new TeamIterationException("该工时同步结果不确定，请先在 CODING 核对");
        }
        return toWorklog(syncWorklog(actor, issue, worklog));
    }

    @Override
    public void removeIssue(Actor actor, long iterationId, long issueId) {
        removeIssues(actor, iterationId, new RemoveIssuesCommand(List.of(issueId)));
    }

    @Override
    public void removeIssues(Actor actor, long iterationId, RemoveIssuesCommand command) {
        StoredIteration stored = requireStored(iterationId);
        requireMember(actor, stored);
        if (command == null || command.issueIds().isEmpty()) {
            throw new TeamIterationException("请选择要删除的迭代事项");
        }
        Set<Long> existingIssueIds = new HashSet<>(stored.issues().stream().map(IssueEntity::getId).toList());
        if (!existingIssueIds.containsAll(command.issueIds())) {
            throw new TeamIterationException("部分事项不存在，请刷新后重试");
        }
        repository.removeIssueTrees(iterationId, command.issueIds(), actor);
    }

    @Override
    public ReleasePlan addReleasePlan(Actor actor, long iterationId, AddReleasePlanCommand command) {
        StoredIteration stored = requireStored(iterationId);
        requireMember(actor, stored);
        if (command == null || command.projectId() <= 0 || command.planId() <= 0) {
            throw new TeamIterationException("请选择项目和构建计划");
        }
        if (repository.findReleasePlan(iterationId, command.projectId(), command.planId()).isPresent()) {
            throw new TeamIterationException("该项目和构建计划已加入当前迭代");
        }
        if (releasePlanResolver == null) throw new TeamIterationException("发布计划解析服务不可用");
        TeamIterationReleasePlanResolver.ResolvedReleasePlan resolved =
                releasePlanResolver.resolve(actor, command.projectId(), command.planId());
        try {
            return toReleasePlan(repository.addReleasePlan(iterationId, resolved, actor));
        } catch (RuntimeException error) {
            if (repository.findReleasePlan(iterationId, command.projectId(), command.planId()).isPresent()) {
                throw new TeamIterationException("该项目和构建计划已加入当前迭代", error);
            }
            throw error;
        }
    }

    @Override
    public void removeReleasePlan(Actor actor, long iterationId, long releasePlanId) {
        StoredIteration stored = requireStored(iterationId);
        requireMember(actor, stored);
        repository.removeReleasePlan(iterationId, releasePlanId, actor);
    }

    private IterationDetail authorizedDetail(Actor actor, StoredIteration stored) {
        requireMember(actor, stored);
        return toDetail(actor, stored);
    }

    private StoredIteration requireStored(long iterationId) {
        return repository.findById(iterationId).orElseThrow(() -> new TeamIterationException("迭代不存在"));
    }

    private void requireMember(Actor actor, StoredIteration stored) {
        boolean member = stored.members().stream().anyMatch(item -> item.member().getCodingUserId() == actor.userId());
        if (!member) throw new TeamIterationException("迭代不存在或当前用户不是迭代成员");
    }

    private void requireCreator(Actor actor, StoredIteration stored) {
        if (stored.iteration().getCreatorUserId() != actor.userId()) {
            throw new TeamIterationException("只有创建人可以维护迭代成员或删除迭代");
        }
    }

    private List<ResolvedMember> resolveMembers(Actor actor, List<MemberInput> inputs, boolean creating) {
        if (workbenchTeams != null) {
            Map<Long, IterationTeamOption> teams = iterationMemberOptions(actor).stream()
                    .collect(java.util.stream.Collectors.toMap(IterationTeamOption::teamId, item -> item,
                            (left, right) -> left, LinkedHashMap::new));
            List<ResolvedMember> result = new ArrayList<>();
            for (MemberInput input : inputs) {
                IterationTeamOption team = teams.get(input.teamId());
                if (team == null) throw new TeamIterationException("所选工作台团队不存在或当前用户未加入");
                IterationMemberOption member = team.members().stream()
                        .filter(item -> item.userId() == input.userId()).findFirst().orElse(null);
                if (member == null) throw new TeamIterationException("所选成员不属于对应工作台团队");
                result.add(new ResolvedMember(team.teamId(), team.teamName(),
                        new UserSnapshot(member.userId(), member.userName(), member.avatar()), input.roles()));
            }
            if (creating && result.stream().noneMatch(member -> member.user().userId() == actor.userId())) {
                throw new TeamIterationException("创建人必须加入迭代成员");
            }
            return result;
        }
        Map<Long, CodingOpenApiPort.Member> directory = new LinkedHashMap<>();
        if (inputs.stream().anyMatch(input -> input.userId() != actor.userId())) {
            teamDirectory(actor).stream().filter(CodingOpenApiPort.Member::active)
                    .forEach(member -> directory.put(member.id(), member));
        }
        List<ResolvedMember> result = new ArrayList<>();
        for (MemberInput input : inputs) {
            UserSnapshot user;
            if (input.userId() == actor.userId()) {
                user = new UserSnapshot(actor.userId(), actor.userName(), actor.avatar());
            } else {
                CodingOpenApiPort.Member member = directory.get(input.userId());
                if (member == null) throw new TeamIterationException("所选成员不在当前 CODING 团队成员目录中");
                user = new UserSnapshot(member.id(), member.name(), member.avatar());
            }
            result.add(ResolvedMember.from(user, input));
        }
        if (creating && result.stream().noneMatch(member -> member.user().userId() == actor.userId())) {
            throw new TeamIterationException("创建人必须加入迭代成员");
        }
        return result;
    }

    private List<CodingOpenApiPort.Member> teamDirectory(Actor actor) {
        try {
            return coding.teamDirectory(actor.token()).members();
        } catch (CodingOpenApiException error) {
            if (error.isPermissionDenied()) {
                String message = error.requiredPermissions().isEmpty()
                        ? "当前令牌缺少团队成员目录读取权限，请开通“团队信息（只读）”和“团队成员（只读）”"
                        : error.permissionMessage();
                throw new TeamIterationException(message, error);
            }
            throw new TeamIterationException("CODING 团队成员目录读取失败，请稍后重试", error);
        }
    }

    private IterationListItem toListItem(Actor actor, StoredIteration stored) {
        return new IterationListItem(stored.iteration().getId(), stored.iteration().getName(),
                stored.iteration().getVersion(), Stage.valueOf(stored.iteration().getStage()),
                stored.iteration().getStartDate(), stored.iteration().getPlannedReleaseDate(), creator(stored),
                members(stored), stored.issues().size(), stored.iteration().getVersionNo(),
                stored.iteration().getCreateTime(), stored.iteration().getUpdateTime(), permissions(actor, stored));
    }

    private IterationDetail toDetail(Actor actor, StoredIteration stored) {
        return new IterationDetail(stored.iteration().getId(), stored.iteration().getRequestId(),
                stored.iteration().getName(), stored.iteration().getVersion(),
                Stage.valueOf(stored.iteration().getStage()), stored.iteration().getStartDate(),
                stored.iteration().getPlannedReleaseDate(), stored.iteration().getReleasedAt(), creator(stored),
                members(stored), issueTree(actor, stored.issues(), stored.worklogs()),
                stored.releasePlans().stream().map(this::toReleasePlan).toList(), stored.iteration().getVersionNo(),
                stored.iteration().getCreateTime(), stored.iteration().getUpdateTime(), permissions(actor, stored));
    }

    private ReleasePlan toReleasePlan(ReleasePlanEntity entity) {
        return new ReleasePlan(entity.getId(), entity.getCodingProjectId(), entity.getCodingProjectName(),
                entity.getProjectDisplayName(), entity.getCodingPlanId(), entity.getPlanName(),
                Boolean.TRUE.equals(entity.getQuickBuildSupported()),
                new UserSnapshot(entity.getCreatorUserId(), entity.getCreatorUserName(), entity.getCreatorAvatar()),
                entity.getCreateTime());
    }

    private List<IterationIssue> issueTree(Actor actor, List<IssueEntity> entities,
                                           List<IssueWorklogEntity> worklogs) {
        Map<Long, IssueEntity> byId = entities.stream()
                .collect(java.util.stream.Collectors.toMap(IssueEntity::getId, item -> item));
        Map<Long, List<IssueEntity>> children = new HashMap<>();
        List<IssueEntity> roots = new ArrayList<>();
        for (IssueEntity entity : entities) {
            if (entity.getParentId() == null || !byId.containsKey(entity.getParentId())) roots.add(entity);
            else children.computeIfAbsent(entity.getParentId(), ignored -> new ArrayList<>()).add(entity);
        }
        Map<Long, List<IssueWorklogEntity>> byIssue = worklogsByIssue(worklogs);
        Map<CodingIssueSnapshotLoader.IssueKey, CodingIssueSnapshotLoader.Lookup> snapshots = issueSnapshots(actor, entities);
        return roots.stream().map(entity -> toIssue(actor, entity, children, byIssue, snapshots, new HashSet<>())).toList();
    }

    private IterationIssue toIssue(Actor actor, IssueEntity entity, Map<Long, List<IssueEntity>> children,
                                   Map<Long, List<IssueWorklogEntity>> worklogs, Set<Long> path) {
        Map<CodingIssueSnapshotLoader.IssueKey, CodingIssueSnapshotLoader.Lookup> snapshots = issueSnapshots(actor, List.of(entity));
        return toIssue(actor, entity, children, worklogs, snapshots, path);
    }

    private IterationIssue toIssue(Actor actor, IssueEntity entity, Map<Long, List<IssueEntity>> children,
                                   Map<Long, List<IssueWorklogEntity>> worklogs,
                                   Map<CodingIssueSnapshotLoader.IssueKey, CodingIssueSnapshotLoader.Lookup> snapshots,
                                   Set<Long> path) {
        if (!path.add(entity.getId())) {
            return snapshotIssue(actor, entity, false, "事项层级存在循环", worklogs.get(entity.getId()), List.of());
        }
        List<IterationIssue> childViews = children.getOrDefault(entity.getId(), List.of()).stream()
                .map(child -> toIssue(actor, child, children, worklogs, snapshots, new HashSet<>(path))).toList();
        if (entity.getIssueCode() == null || entity.getIssueCode() <= 0) {
            return snapshotIssue(actor, entity, true, null, worklogs.get(entity.getId()), childViews);
        }
        CodingIssueSnapshotLoader.Lookup lookup = snapshots.get(new CodingIssueSnapshotLoader.IssueKey(
                entity.getProjectName(), entity.getIssueCode()));
        if (lookup == null || lookup.issue() == null) {
            RuntimeException error = lookup == null ? null : lookup.error();
            return snapshotIssue(actor, entity, false,
                    error instanceof CodingOpenApiException codingError && codingError.isPermissionDenied()
                            ? codingError.permissionMessage() : "当前令牌无法读取该 CODING 事项",
                    worklogs.get(entity.getId()), childViews);
        }
        try {
            CodingOpenApiPort.Issue issue = lookup.issue();
            CodingIssueType type = supportedType(issue);
            String url = codingIssueUrl(actor.teamKey(), entity.getProjectName(), type, issue.code());
            return new IterationIssue(entity.getId(), entity.getParentId(), source(entity), url,
                    entity.getProjectName(), issue.id(), issue.code(), type, displayTypeName(issue, type), issue.title(),
                    entity.getDescription(), StringUtils.defaultIfBlank(issue.statusName(), "未知"), true, null,
                    syncStatus(entity), entity.getSyncMessage(), entity.getDevelopmentTeam(),
                    entity.getDefinitionOfDone(), entity.getEstimatedHours() == null
                    ? issue.workingHours() : entity.getEstimatedHours(), entity.getTaskType(), entity.getOnlineBug(),
                    entity.getBugPriority(), entity.getSyncedAt(), entity.getCreateTime(),
                    worklogs.getOrDefault(entity.getId(), List.of()).stream().map(this::toWorklog).toList(), childViews,
                    recordedHours(entity, worklogs.get(entity.getId())), recordedWorklogCount(entity, worklogs.get(entity.getId())),
                    issue.assigneeName());
        } catch (CodingOpenApiException error) {
            return snapshotIssue(actor, entity, false, error.isPermissionDenied()
                            ? error.permissionMessage() : "当前令牌无法读取该 CODING 事项",
                    worklogs.get(entity.getId()), childViews);
        } catch (TeamIterationException error) {
            return snapshotIssue(actor, entity, false, "当前令牌无法读取该 CODING 事项",
                    worklogs.get(entity.getId()), childViews);
        }
    }

    private Map<CodingIssueSnapshotLoader.IssueKey, CodingIssueSnapshotLoader.Lookup> issueSnapshots(
            Actor actor, List<IssueEntity> entities) {
        List<CodingIssueSnapshotLoader.IssueKey> keys = entities.stream()
                .filter(entity -> entity.getIssueCode() != null && entity.getIssueCode() > 0)
                .map(entity -> new CodingIssueSnapshotLoader.IssueKey(entity.getProjectName(), entity.getIssueCode()))
                .distinct().toList();
        if (keys.isEmpty()) return Map.of();
        if (issueSnapshotLoader != null) return issueSnapshotLoader.load(actor.token(), keys);
        Map<CodingIssueSnapshotLoader.IssueKey, CodingIssueSnapshotLoader.Lookup> result = new LinkedHashMap<>();
        for (CodingIssueSnapshotLoader.IssueKey key : keys) {
            try {
                result.put(key, new CodingIssueSnapshotLoader.Lookup(
                        coding.issue(actor.token(), key.projectName(), key.issueCode()), null));
            } catch (RuntimeException error) {
                result.put(key, new CodingIssueSnapshotLoader.Lookup(null, error));
            }
        }
        return result;
    }

    private IterationIssue snapshotIssue(Actor actor, IssueEntity entity, boolean available, String warning,
                                         List<IssueWorklogEntity> worklogs, List<IterationIssue> children) {
        String title = !available && source(entity) == IssueSource.CODING ? "" : entity.getTitle();
        String url = entity.getIssueCode() == null || entity.getIssueCode() <= 0
                ? entity.getCodingUrl()
                : codingIssueUrl(actor.teamKey(), entity.getProjectName(),
                CodingIssueType.valueOf(entity.getIssueType()), entity.getIssueCode());
        return new IterationIssue(entity.getId(), entity.getParentId(), source(entity), url,
                entity.getProjectName(), entity.getIssueId(), entity.getIssueCode(),
                CodingIssueType.valueOf(entity.getIssueType()), entity.getIssueTypeName(), title,
                entity.getDescription(), "", available, warning, syncStatus(entity), entity.getSyncMessage(),
                entity.getDevelopmentTeam(), entity.getDefinitionOfDone(), entity.getEstimatedHours(),
                entity.getTaskType(), entity.getOnlineBug(), entity.getBugPriority(), entity.getSyncedAt(),
                entity.getCreateTime(), worklogs == null ? List.of() : worklogs.stream().map(this::toWorklog).toList(),
                children, recordedHours(entity, worklogs), recordedWorklogCount(entity, worklogs));
    }

    private IssueSource source(IssueEntity entity) {
        return IssueSource.valueOf(StringUtils.defaultIfBlank(entity.getSource(), IssueSource.CODING.name()));
    }

    private IssueSyncStatus syncStatus(IssueEntity entity) {
        return IssueSyncStatus.valueOf(StringUtils.defaultIfBlank(entity.getSyncStatus(),
                source(entity) == IssueSource.CODING ? IssueSyncStatus.SYNCED.name() : IssueSyncStatus.PENDING.name()));
    }

    private IssueEntity codingParentFor(CodingIssueType childType, IssueEntity start, List<IssueEntity> entities) {
        Map<Long, IssueEntity> issues = entities.stream()
                .collect(java.util.stream.Collectors.toMap(IssueEntity::getId, item -> item));
        if (childType == CodingIssueType.USER_STORY) {
            IssueEntity directParent = start.getParentId() == null ? null : issues.get(start.getParentId());
            if (directParent != null && CodingIssueType.REQUIREMENT.name().equals(directParent.getIssueType())
                    && directParent.getIssueCode() != null && directParent.getIssueCode() > 0) {
                return directParent;
            }
            throw new TeamIterationException("同步用户故事需要直接上级需求已关联 CODING");
        }
        IssueEntity current = start;
        Set<Long> visited = new HashSet<>();
        while (current != null) {
            if (!visited.add(current.getId())) throw new TeamIterationException("事项层级存在循环，无法同步");
            CodingIssueType currentType = CodingIssueType.valueOf(current.getIssueType());
            boolean allowed = currentType == CodingIssueType.REQUIREMENT || currentType == CodingIssueType.TASK
                    || currentType == CodingIssueType.USER_STORY;
            if (allowed && current.getIssueCode() != null && current.getIssueCode() > 0) return current;
            current = current.getParentId() == null ? null : issues.get(current.getParentId());
        }
        throw new TeamIterationException("同步需要上级需求、任务或用户故事已关联 CODING");
    }

    private CreateIssueRequest createIssueRequest(IssueEntity entity, CodingIssueType type, String projectName,
                                                   Long parentCode, long assigneeId,
                                                   CodingIssueMetadataCatalog.IssueMetadata metadata) {
        List<CustomFieldValue> customFields = new ArrayList<>();
        String priority = "0";
        long issueTypeId = metadata.targetType().id();
        if (type == CodingIssueType.USER_STORY) {
            addCustom(customFields, metadataCatalog.customValue(metadata, entity.getDevelopmentTeam(), "开发团队"));
            addCustom(customFields, metadataCatalog.customValue(metadata, entity.getDefinitionOfDone(),
                    "DoD", "DOD", "Definition of Done"));
        } else if (type == CodingIssueType.SUB_TASK) {
            if (metadataCatalog.findField(metadata, "任务类型") != null) {
                addCustom(customFields, metadataCatalog.customValue(metadata, entity.getTaskType(), "任务类型"));
            } else {
                issueTypeId = metadataCatalog.taskIssueTypeId(metadata, entity.getTaskType());
            }
        } else if (type == CodingIssueType.DEFECT) {
            addCustom(customFields, metadataCatalog.customValue(metadata,
                    Boolean.TRUE.equals(entity.getOnlineBug()) ? "是" : "否", "是否线上Bug", "是否线上 Bug"));
            if (metadataCatalog.findField(metadata, "Bug优先级", "Bug 优先级") != null) {
                addCustom(customFields, metadataCatalog.customValue(metadata, entity.getBugPriority(),
                        "Bug优先级", "Bug 优先级"));
            } else {
                priority = bugPriority(entity.getBugPriority());
            }
            addCustom(customFields, metadataCatalog.customValue(metadata, "一般(C级)",
                    "Bug严重性", "Bug 严重性", "严重性"));
            addCustom(customFields, metadataCatalog.customValue(metadata, "Windows", "操作系统"));
            addCustom(customFields, metadataCatalog.customValue(metadata, "Chrome", "浏览器"));
        }
        return new CreateIssueRequest(projectName, metadata.systemType(), issueTypeId, parentCode,
                entity.getTitle(), entity.getDescription(), priority, assigneeId,
                type == CodingIssueType.SUB_TASK ? entity.getEstimatedHours() : null, customFields);
    }

    private void addCustom(List<CustomFieldValue> values, CustomFieldValue value) {
        if (value != null) values.add(value);
    }

    private String bugPriority(String value) {
        return switch (StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT)) {
            case "中", "medium", "1" -> "1";
            case "高", "high", "2" -> "2";
            case "紧急", "urgent", "3" -> "3";
            default -> "0";
        };
    }

    private IssueWorklogEntity syncWorklog(Actor actor, IssueEntity issue, IssueWorklogEntity worklog) {
        if (!repository.claimWorklogSync(worklog.getId())) {
            throw new TeamIterationException("该工时记录的同步状态已变化，请刷新后重试");
        }
        try {
            CodingOpenApiPort.Issue codingIssue = coding.issue(actor.token(), issue.getProjectName(),
                    issue.getIssueCode());
            BigDecimal currentRemaining = coding.issueWorklogs(actor.token(), issue.getProjectName(),
                            issue.getIssueCode()).stream()
                    .max(Comparator.comparingLong(CodingOpenApiPort.IssueWorklog::updatedAt)
                    .thenComparingLong(CodingOpenApiPort.IssueWorklog::createdAt)
                            .thenComparingLong(CodingOpenApiPort.IssueWorklog::id))
                    .map(CodingOpenApiPort.IssueWorklog::remainingHours)
                    .orElse(codingIssue.workingHours().signum() > 0 ? codingIssue.workingHours()
                            : issue.getEstimatedHours() == null ? BigDecimal.ZERO : issue.getEstimatedHours());
            BigDecimal remaining = currentRemaining.subtract(worklog.getSpendHours()).max(BigDecimal.ZERO);
            long startAt = worklog.getRegisteredAt().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            String requestId = coding.createIssueWorkHours(actor.token(), issue.getProjectName(), issue.getIssueCode(),
                    worklog.getSpendHours(), remaining, startAt);
            return repository.markWorklogSynced(worklog.getId(), requestId);
        } catch (CodingOpenApiException error) {
            IssueSyncStatus status = error.isTransportFailure() ? IssueSyncStatus.UNKNOWN : IssueSyncStatus.FAILED;
            String message = error.isPermissionDenied()
                    ? error.permissionMessage()
                    : "CODING 工时同步失败：" + StringUtils.defaultIfBlank(error.getMessage(), "请稍后重试");
            repository.markWorklogSyncFailed(worklog.getId(), status, error.code(), message);
            throw new TeamIterationException(message, error);
        }
    }

    private void modifyCodingIssue(Actor actor, IssueEntity issue, CodingIssueType type,
                                   UpdateIssueCommand command) {
        List<CustomFieldValue> customFields = new ArrayList<>();
        String priority = null;
        BigDecimal workingHours = null;
        if (type != CodingIssueType.REQUIREMENT && type != CodingIssueType.TASK) {
            CodingIssueMetadataCatalog.IssueMetadata metadata;
            try {
                metadata = metadataCatalog.metadata(actor.token(), issue.getProjectName(), type);
            } catch (CodingOpenApiException error) {
                throw codingUpdateException(error, "CODING 项目事项配置读取失败，请稍后重试");
            }
            if (type == CodingIssueType.USER_STORY) {
                addCustom(customFields, metadataCatalog.customValue(metadata, command.developmentTeam(), "开发团队"));
                addCustom(customFields, metadataCatalog.customValue(metadata, command.definitionOfDone(),
                        "DoD", "DOD", "Definition of Done"));
            } else if (type == CodingIssueType.SUB_TASK) {
                workingHours = command.estimatedHours();
                if (metadataCatalog.findField(metadata, "任务类型") != null) {
                    addCustom(customFields, metadataCatalog.customValue(metadata, command.taskType(), "任务类型"));
                } else if (!StringUtils.equals(StringUtils.trimToEmpty(issue.getTaskType()),
                        StringUtils.trimToEmpty(command.taskType()))) {
                    throw new TeamIterationException("该 CODING 项目的任务类型由事项类型决定，同步后不支持修改");
                }
            } else if (type == CodingIssueType.DEFECT) {
                addCustom(customFields, metadataCatalog.customValue(metadata,
                        Boolean.TRUE.equals(command.onlineBug()) ? "是" : "否", "是否线上Bug", "是否线上 Bug"));
                if (metadataCatalog.findField(metadata, "Bug优先级", "Bug 优先级") != null) {
                    addCustom(customFields, metadataCatalog.customValue(metadata, command.bugPriority(),
                            "Bug优先级", "Bug 优先级"));
                } else {
                    priority = bugPriority(command.bugPriority());
                }
            }
        }
        try {
            coding.modifyIssue(actor.token(), new ModifyIssueRequest(issue.getProjectName(), issue.getIssueCode(),
                    command.title(), command.description(), null, priority, workingHours, customFields));
        } catch (CodingOpenApiException error) {
            throw codingUpdateException(error, "CODING 事项同步失败，请稍后重试");
        }
    }

    private boolean isCodingBacked(IssueEntity issue) {
        return issue.getIssueCode() != null && issue.getIssueCode() > 0
                && (source(issue) == IssueSource.CODING || syncStatus(issue) == IssueSyncStatus.SYNCED);
    }

    private void requireCodingBacked(IssueEntity issue, String message) {
        if (!isCodingBacked(issue)) throw new TeamIterationException(message);
    }

    private TeamIterationException codingUpdateException(CodingOpenApiException error, String fallback) {
        return new TeamIterationException(error.isPermissionDenied()
                ? error.permissionMessage()
                : fallback + "：" + StringUtils.defaultIfBlank(error.getMessage(), "请稍后重试"), error);
    }

    private Map<Long, List<IssueWorklogEntity>> worklogsByIssue(List<IssueWorklogEntity> worklogs) {
        Map<Long, List<IssueWorklogEntity>> result = new HashMap<>();
        for (IssueWorklogEntity worklog : worklogs) {
            result.computeIfAbsent(worklog.getIssueId(), ignored -> new ArrayList<>()).add(worklog);
        }
        return result;
    }

    private BigDecimal recordedHours(IssueEntity entity, List<IssueWorklogEntity> localWorklogs) {
        BigDecimal localTotal = localWorklogs == null ? BigDecimal.ZERO : localWorklogs.stream()
                .map(IssueWorklogEntity::getSpendHours).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal codingTotal = entity.getCodingRecordedHours();
        return codingTotal == null ? localTotal : codingTotal.max(localTotal);
    }

    private int recordedWorklogCount(IssueEntity entity, List<IssueWorklogEntity> localWorklogs) {
        int localCount = localWorklogs == null ? 0 : localWorklogs.size();
        return Math.max(entity.getCodingWorklogCount() == null ? 0 : entity.getCodingWorklogCount(), localCount);
    }

    private IssueWorklog toWorklog(IssueWorklogEntity entity) {
        return new IssueWorklog(entity.getId(), entity.getSpendHours(), entity.getRegisteredAt(),
                IssueSyncStatus.valueOf(entity.getSyncStatus()), entity.getSyncMessage(), entity.getSyncedAt(),
                new UserSnapshot(entity.getCreatorUserId(), entity.getCreatorUserName(), ""), entity.getCreateTime());
    }

    private record CodingWorklogSummary(BigDecimal recordedHours, int worklogCount) {
    }

    private CodingIssueType supportedType(CodingOpenApiPort.Issue issue) {
        String typeName = StringUtils.trimToEmpty(issue.typeName());
        String systemType = StringUtils.trimToEmpty(issue.type()).replace('-', '_').toUpperCase(Locale.ROOT);
        if (typeName.contains("用户故事") || "USER_STORY".equals(systemType) || "USERSTORY".equals(systemType)) {
            return CodingIssueType.USER_STORY;
        }
        if (typeName.contains("需求") || "REQUIREMENT".equals(systemType)) return CodingIssueType.REQUIREMENT;
        if (issue.subtask() || typeName.contains("子工作项") || typeName.contains("子任务")
                || "SUB_TASK".equals(systemType) || "WORK_ITEM".equals(systemType)) return CodingIssueType.SUB_TASK;
        if (typeName.equals("任务") || "TASK".equals(systemType) || "ASSIGNMENT".equals(systemType)) {
            return CodingIssueType.TASK;
        }
        if (typeName.contains("缺陷") || "DEFECT".equals(systemType) || "BUG".equals(systemType)) {
            return CodingIssueType.DEFECT;
        }
        String displayType = StringUtils.defaultIfBlank(typeName,
                StringUtils.defaultIfBlank(systemType, "未知类型"));
        throw new TeamIterationException("#" + issue.code() + " 事项类型为“" + displayType + "”，暂不支持关联。");
    }

    private String displayTypeName(CodingOpenApiPort.Issue issue, CodingIssueType type) {
        return StringUtils.defaultIfBlank(issue.typeName(), typeName(type));
    }

    private String typeName(CodingIssueType type) {
        return switch (type) {
            case REQUIREMENT -> "需求";
            case TASK -> "任务";
            case USER_STORY -> "用户故事";
            case SUB_TASK -> "子工作项";
            case DEFECT -> "缺陷";
        };
    }

    private String codingIssueUrl(String teamKey, String projectName, CodingIssueType type, long issueCode) {
        String route = switch (type) {
            case REQUIREMENT -> "requirements";
            case TASK -> "assignments";
            case USER_STORY -> "requirements";
            case SUB_TASK -> "subtasks";
            case DEFECT -> "bug-tracking";
        };
        String encodedProject = URLEncoder.encode(projectName, StandardCharsets.UTF_8).replace("+", "%20");
        return "https://" + teamKey + ".coding.net/p/" + encodedProject + "/" + route + "/issues/"
                + issueCode + "/detail";
    }

    private UserSnapshot creator(StoredIteration stored) {
        return new UserSnapshot(stored.iteration().getCreatorUserId(), stored.iteration().getCreatorUserName(),
                stored.iteration().getCreatorAvatar());
    }

    private List<Member> members(StoredIteration stored) {
        return stored.members().stream().map(item -> new Member(item.member().getId(),
                new TeamSnapshot(item.member().getWorkbenchTeamId() == null ? 0 : item.member().getWorkbenchTeamId(),
                        StringUtils.defaultString(item.member().getWorkbenchTeamName())),
                new UserSnapshot(item.member().getCodingUserId(), item.member().getUserName(), item.member().getAvatar()),
                item.roles())).toList();
    }

    private Permissions permissions(Actor actor, StoredIteration stored) {
        boolean member = stored.members().stream().anyMatch(item -> item.member().getCodingUserId() == actor.userId());
        boolean creator = stored.iteration().getCreatorUserId() == actor.userId();
        return new Permissions(member, creator, creator);
    }

    private void validateQuery(IterationQuery query) {
        if (query == null || query.pageNumber() < 1 || query.pageSize() < 1 || query.pageSize() > 50) {
            throw new TeamIterationException("分页参数不正确");
        }
        if (StringUtils.length(query.keyword()) > 128) throw new TeamIterationException("标题筛选不能超过 128 个字符");
    }

    private void validateCreate(CreateCommand command, long creatorUserId) {
        if (command == null || StringUtils.isBlank(command.requestId()) || command.requestId().length() > 64) {
            throw new TeamIterationException("创建请求标识不正确");
        }
        validateBasic(command.name(), command.version(), command.stage(), command.startDate(),
                command.plannedReleaseDate());
        validateMembers(command.members(), creatorUserId);
    }

    private void validateMembers(List<MemberInput> members, long creatorUserId) {
        if (members == null || members.isEmpty()) throw new TeamIterationException("请至少选择一名迭代成员");
        if (members.stream().map(MemberInput::userId).distinct().count() != members.size()) {
            throw new TeamIterationException("迭代成员不能重复");
        }
        if (members.stream().noneMatch(member -> member.userId() == creatorUserId)) {
            throw new TeamIterationException("创建人不能从迭代成员中移除");
        }
        if (members.stream().anyMatch(member -> member.userId() <= 0)) {
            throw new TeamIterationException("迭代成员不正确");
        }
        if (workbenchTeams != null && members.stream().anyMatch(member -> member.teamId() <= 0)) {
            throw new TeamIterationException("请选择每位成员所属的工作台团队");
        }
    }

    private void validateBasic(String name, String version, Stage stage, java.time.LocalDate startDate,
                               java.time.LocalDate plannedReleaseDate) {
        if (StringUtils.isBlank(name) || name.trim().length() > 128) {
            throw new TeamIterationException("迭代标题不能为空且不能超过 128 个字符");
        }
        if (StringUtils.length(version) > 64) throw new TeamIterationException("版本不能超过 64 个字符");
        if (stage == null) throw new TeamIterationException("请选择迭代状态");
        if (startDate != null && plannedReleaseDate != null && plannedReleaseDate.isBefore(startDate)) {
            throw new TeamIterationException("计划上线日期不能早于开始日期");
        }
    }

    private void validateChild(CreateChildIssueCommand command) {
        if (command == null || command.issueType() == null) throw new TeamIterationException("请选择事项类型");
        if (StringUtils.isBlank(command.title()) || command.title().trim().length() > 256) {
            throw new TeamIterationException("事项标题不能为空且不能超过 256 个字符");
        }
        if (StringUtils.length(command.description()) > 4000) {
            throw new TeamIterationException("事项描述不能超过 4000 个字符");
        }
        switch (command.issueType()) {
            case USER_STORY -> {
                if (StringUtils.isBlank(command.developmentTeam())) {
                    throw new TeamIterationException("用户故事必须维护开发团队");
                }
                if (StringUtils.isBlank(command.definitionOfDone())) {
                    throw new TeamIterationException("用户故事必须维护 DoD");
                }
            }
            case SUB_TASK -> {
                if (command.estimatedHours() == null || command.estimatedHours().signum() <= 0
                        || command.estimatedHours().compareTo(new BigDecimal("10000")) >= 0
                        || command.estimatedHours().scale() > 2) {
                    throw new TeamIterationException("预估工时必须为小于 10000、最多两位小数的小时数");
                }
                if (StringUtils.isBlank(command.taskType())) {
                    throw new TeamIterationException("子工作项必须维护任务类型");
                }
            }
            case DEFECT -> {
                if (command.onlineBug() == null) throw new TeamIterationException("请选择是否线上 Bug");
                if (StringUtils.isBlank(command.bugPriority())) {
                    throw new TeamIterationException("缺陷必须维护 Bug 优先级");
                }
            }
            case REQUIREMENT, TASK -> {
            }
        }
    }

    private void validateChildType(CodingIssueType parent, CodingIssueType child) {
        boolean allowed = switch (parent) {
            case REQUIREMENT -> child != null && child != CodingIssueType.TASK;
            case TASK -> child == CodingIssueType.SUB_TASK;
            case USER_STORY -> child == CodingIssueType.SUB_TASK || child == CodingIssueType.DEFECT;
            case SUB_TASK, DEFECT -> false;
        };
        if (!allowed) {
            throw new TeamIterationException("该事项类型下不允许新增所选子事项");
        }
    }

    private void validateWorklog(IssueEntity issue, RegisterWorklogCommand command) {
        if (CodingIssueType.valueOf(issue.getIssueType()) != CodingIssueType.SUB_TASK) {
            throw new TeamIterationException("只有子工作项可以登记工时");
        }
        requireCodingBacked(issue, "子工作项尚未关联 CODING 或完成同步，不可登记工时");
        if (command == null || command.spendHours() == null || command.spendHours().signum() <= 0
                || command.spendHours().compareTo(new BigDecimal("10000")) >= 0
                || command.spendHours().scale() > 2) {
            throw new TeamIterationException("使用工时必须为小于 10000、最多两位小数的小时数");
        }
        if (command.registeredAt() == null) throw new TeamIterationException("请选择登记时间");
    }

    private void validateSyncFields(IssueEntity entity, CodingIssueType type) {
        CreateChildIssueCommand command = new CreateChildIssueCommand(type, entity.getTitle(), entity.getDescription(),
                entity.getDevelopmentTeam(), entity.getDefinitionOfDone(), entity.getEstimatedHours(),
                entity.getTaskType(), entity.getOnlineBug(), entity.getBugPriority());
        validateChild(command);
    }

    private void validateIssueUpdate(CodingIssueType type, UpdateIssueCommand command) {
        if (command == null) throw new TeamIterationException("事项信息不能为空");
        validateChild(new CreateChildIssueCommand(type, command.title(), command.description(),
                command.developmentTeam(), command.definitionOfDone(), command.estimatedHours(),
                command.taskType(), command.onlineBug(), command.bugPriority()));
    }

    private com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Actor toWorkbenchActor(Actor actor) {
        return new com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Actor(actor.userId(), actor.userName(),
                actor.avatar(), actor.codingTeamId(), actor.teamKey(), actor.codingTeamHost());
    }

    private CreateCommand normalize(CreateCommand command) {
        return new CreateCommand(command.requestId(), command.name().trim(), StringUtils.trimToNull(command.version()),
                command.stage(),
                command.startDate(), command.plannedReleaseDate(), command.members());
    }

    private UpdateCommand normalize(UpdateCommand command, Stage currentStage) {
        return new UpdateCommand(command.versionNo(), command.name().trim(),
                StringUtils.trimToNull(command.version()), command.stage() == null ? currentStage : command.stage(),
                command.startDate(), command.plannedReleaseDate());
    }

    private UpdateIssueCommand normalize(CodingIssueType type, UpdateIssueCommand command) {
        return new UpdateIssueCommand(command.title().trim(), StringUtils.trim(command.description()),
                type == CodingIssueType.USER_STORY ? StringUtils.trimToNull(command.developmentTeam()) : null,
                type == CodingIssueType.USER_STORY ? StringUtils.trimToNull(command.definitionOfDone()) : null,
                type == CodingIssueType.SUB_TASK ? command.estimatedHours() : null,
                type == CodingIssueType.SUB_TASK ? StringUtils.trimToNull(command.taskType()) : null,
                type == CodingIssueType.DEFECT ? command.onlineBug() : null,
                type == CodingIssueType.DEFECT ? StringUtils.trimToNull(command.bugPriority()) : null);
    }

    private String issueHash(String projectName, long issueCode) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((projectName + ":" + issueCode).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("无法生成 CODING 事项摘要", error);
        }
    }
}

package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.ZhaogangCodeCommitMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeScanJobMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeScanRepoMapper;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Page;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.RepoFailure;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.ScanMonth;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.ScanProgress;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.ScanRequest;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.ScanState;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.ScanTrigger;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Scope;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.SkippedMonth;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.CommitEntity;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanJobEntity;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanRepoEntity;
import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
class CodeScanService implements CodeBackfillTrigger {

    private static final Logger log = LoggerFactory.getLogger(CodeScanService.class);

    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_INTERRUPTED = "INTERRUPTED";
    private static final String STATUS_NOT_STARTED = "NOT_STARTED";
    private static final String DIFF_RETRY_COOLDOWN = "diff-retry";

    private final ZhaogangCodeScanJobMapper jobMapper;
    private final ZhaogangCodeScanRepoMapper repoMapper;
    private final ZhaogangCodeCommitMapper commitMapper;
    private final CodeStatsViewCache cache;
    private final CodeScanWorker worker;
    private final CodeIdentityResolver identities;
    private final ZhaogangProperties properties;
    private final Clock clock;
    private final ThreadPoolExecutor executor;

    @Autowired
    CodeScanService(ZhaogangCodeScanJobMapper jobMapper, ZhaogangCodeScanRepoMapper repoMapper,
                    ZhaogangCodeCommitMapper commitMapper, CodeStatsViewCache cache, CodeScanWorker worker,
                    ZhaogangProperties properties, CodeIdentityResolver identities) {
        this(jobMapper, repoMapper, commitMapper, cache, worker, properties, identities, Clock.system(CodeStatsSupport.CHINA_ZONE));
    }

    CodeScanService(ZhaogangCodeScanJobMapper jobMapper, ZhaogangCodeScanRepoMapper repoMapper,
                    ZhaogangCodeCommitMapper commitMapper, CodeStatsViewCache cache, CodeScanWorker worker,
                    ZhaogangProperties properties, CodeIdentityResolver identities, Clock clock) {
        this.jobMapper = jobMapper;
        this.repoMapper = repoMapper;
        this.commitMapper = commitMapper;
        this.cache = cache;
        this.worker = worker;
        this.identities = identities;
        this.properties = properties;
        this.clock = clock;
        int concurrency = Math.max(1, properties.getCodeScanConcurrency());
        this.executor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.getCodeScanQueueCapacity())),
                daemonThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    ScanState scanState(CodeStatsModule.ScopeInfo scope, CodeRange range) {
        List<ScanMonth> months = new ArrayList<>();
        for (YearMonth month : range.months()) {
            months.add(scanMonth(scope, month, range));
        }
        boolean anyAvailable = months.stream().anyMatch(month -> month.retryAfterSeconds() == 0
                && !STATUS_RUNNING.equals(month.status()) && !STATUS_QUEUED.equals(month.status()));
        long retryAfter = anyAvailable ? 0 : months.stream().mapToLong(ScanMonth::retryAfterSeconds)
                .filter(value -> value > 0).min().orElse(0);
        return new ScanState(scope.scope().name(), scope.scope() == Scope.WORKBENCH_TEAM ? scope.scopeId() : null,
                range.view(), aggregateStatus(months), months, retryAfter, syncInfo(scope));
    }

    private CodeStatsModels.SyncInfo syncInfo(CodeStatsModule.ScopeInfo scope) {
        ScanJobEntity latest = jobMapper.selectLatestSync(scope.codingTeamId(), scope.scope().name(), scope.scopeId());
        boolean enabled = properties.isCodeSyncEnabled() && scope.automaticSyncEligible() && hasToken(scope);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime next = now.toLocalDate().atStartOfDay().plusHours((now.getHour() / 3 + 1) * 3L);
        String nextSyncAt = enabled ? CodeStatsSupport.format(next) : null;
        if (latest == null) {
            return new CodeStatsModels.SyncInfo(enabled, properties.getCodeSyncFrom(), null, null, nextSyncAt, "NOT_STARTED", 0);
        }
        return new CodeStatsModels.SyncInfo(enabled, latest.getSyncFrom().toString(),
                latest.getSyncTo().toString(), CodeStatsSupport.format(latest.getFinishedAt()),
                nextSyncAt, latest.getStatus(), manualSyncRetryAfter(latest, now), latest.getId(),
                value(latest.getFailedRepos()), incompleteDiffs(scope, latest.getSyncFrom(), latest.getSyncTo()));
    }

    boolean dataReady(ScanState scan) {
        return !scan.months().isEmpty()
                && scan.months().stream().allMatch(month -> month.lastSuccessfulAt() != null);
    }

    boolean refreshing(ScanState scan) {
        return scan.months().stream().anyMatch(month -> (STATUS_QUEUED.equals(month.status())
                || STATUS_RUNNING.equals(month.status()))
                && (("SYNC".equals(month.mode()) || "INCREMENTAL".equals(month.mode())) || "DIFF_BACKFILL".equals(month.mode())));
    }

    /** 查询侧保留兼容入口，但禁止发起同步。 */
    void autoRefresh(CodeStatsModule.ScopeInfo scope, CodeRange range, long viewerId) {
        // 由持续同步调度器负责，切换月份是纯查询。
    }

    ScanTrigger triggerSync(CodeStatsModule.ScopeInfo scope, CodeRange displayRange, long requestedBy) {
        if (!hasToken(scope)) throw new CodingOpenApiException("没有可用的 CODING 令牌，无法同步");
        ScanJobEntity latest = jobMapper.selectLatestSync(scope.codingTeamId(), scope.scope().name(), scope.scopeId());
        ScanJobEntity active = jobMapper.selectByActiveKey(activeKey(scope, ""));
        if (active != null) return new ScanTrigger(false, "ALREADY_RUNNING", List.of(), 0, scanState(scope, displayRange));
        long wait = manualSyncRetryAfter(latest, LocalDateTime.now(clock));
        boolean identityChanged = latest != null && !java.util.Objects.equals(latest.getIdentitySignature(), identities.signature(scope));
        if (wait > 0 && !identityChanged)
            return new ScanTrigger(false, "COOLDOWN", List.of(), wait, scanState(scope, displayRange));
        ScanJobEntity job = createAndSubmit(scope, YearMonth.now(clock), "SYNC", "MANUAL", requestedBy, syncFrom(latest));
        String reason = job != null ? "STARTED"
                : jobMapper.selectByActiveKey(activeKey(scope, "")) == null ? "QUEUE_FULL" : "ALREADY_RUNNING";
        return new ScanTrigger(job != null, reason, List.of(), 0,
                scanState(scope, displayRange));
    }

    private long manualSyncRetryAfter(ScanJobEntity latest, LocalDateTime now) {
        if (latest == null || !"MANUAL".equals(latest.getRequestedMode()) || latest.getFinishedAt() == null
                || STATUS_CANCELLED.equals(latest.getStatus()) || STATUS_INTERRUPTED.equals(latest.getStatus())) return 0;
        return Math.max(0, Duration.between(now,
                latest.getFinishedAt().plusSeconds(Math.max(0, properties.getCodeUserCooldownSeconds()))).getSeconds());
    }

    private LocalDate syncFrom(ScanJobEntity latest) {
        LocalDate configured = LocalDate.parse(properties.getCodeSyncFrom());
        return latest != null && latest.getSyncFrom() != null && latest.getSyncFrom().isBefore(configured)
                ? latest.getSyncFrom() : configured;
    }

    boolean hasScheduledSyncCapacity() {
        return executor.getActiveCount() < executor.getMaximumPoolSize() && executor.getQueue().isEmpty();
    }

    /** 活动任务结束或扫描线程空闲后，调度器继续处理暂缓的范围。 */
    boolean scheduledSync(CodeStatsModule.ScopeInfo scope, LocalDateTime scheduledAt) {
        var actor = scope.members().stream().filter(member -> StringUtils.isNotBlank(member.token())).findFirst();
        if (actor.isEmpty()) return true;
        if (jobMapper.selectByActiveKey(activeKey(scope, "")) != null || !hasScheduledSyncCapacity()) return false;
        ScanJobEntity latest = jobMapper.selectLatestSync(scope.codingTeamId(), scope.scope().name(), scope.scopeId());
        if (latest != null && latest.getFinishedAt() != null && !latest.getFinishedAt().isBefore(scheduledAt)
                && !STATUS_INTERRUPTED.equals(latest.getStatus()) && !STATUS_CANCELLED.equals(latest.getStatus())
                && !"QUEUE_FULL".equals(latest.getErrorCode())) {
            return true;
        }
        return createAndSubmit(scope, YearMonth.now(clock), "SYNC", "AUTO", actor.get().userId(), syncFrom(latest)) != null;
    }

    ScanTrigger trigger(CodeStatsModule.ScopeInfo scope, ScanRequest request, CodeRange range, long requestedBy) {
        if (!hasToken(scope)) {
            throw new CodingOpenApiException(scope.scope() == Scope.SELF
                    ? "当前会话没有可用的 CODING 令牌，请重新绑定"
                    : "团队成员都还没有绑定 CODING 令牌，无法扫描代码仓库");
        }
        List<SkippedMonth> skipped = new ArrayList<>();
        int started = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        String scopeKey = cache.scopeKey(scope);
        for (YearMonth month : range.months()) {
            ScanJobEntity latest = jobMapper.selectLatest(scope.codingTeamId(), scope.scope().name(),
                    scope.scopeId(), month.toString());
            if (latest != null && (STATUS_QUEUED.equals(latest.getStatus()) || STATUS_RUNNING.equals(latest.getStatus()))) {
                skipped.add(new SkippedMonth(month.toString(), "ALREADY_RUNNING", 0));
                continue;
            }
            long cooldown = retryAfterSeconds(latest, month, now);
            if (cooldown > 0) {
                skipped.add(new SkippedMonth(month.toString(), "COOLDOWN", cooldown));
                continue;
            }
            if (cache.userCooldownActive(scopeKey, requestedBy, month.toString())) {
                skipped.add(new SkippedMonth(month.toString(), "USER_COOLDOWN",
                        Math.max(1, properties.getCodeUserCooldownSeconds())));
                continue;
            }
            ScanJobEntity job = createAndSubmit(scope, month, "FULL", "MANUAL", requestedBy);
            if (job == null) {
                boolean racing = jobMapper.selectByActiveKey(activeKey(scope, month.toString())) != null;
                skipped.add(new SkippedMonth(month.toString(), racing ? "ALREADY_RUNNING" : "QUEUE_FULL", 0));
                continue;
            }
            started++;
            cache.markUserCooldown(scopeKey, requestedBy, month.toString());
        }
        ScanState state = scanState(scope, range);
        if (started > 0) {
            return new ScanTrigger(true, "STARTED", skipped, 0, state);
        }
        String reason = skipped.stream().anyMatch(item -> "ALREADY_RUNNING".equals(item.reason()))
                ? "ALREADY_RUNNING" : skipped.stream().anyMatch(item -> "USER_COOLDOWN".equals(item.reason()))
                ? "USER_COOLDOWN" : skipped.stream().anyMatch(item -> "QUEUE_FULL".equals(item.reason()))
                ? "QUEUE_FULL" : skipped.isEmpty() ? "SKIPPED" : "COOLDOWN";
        long retryAfter = skipped.stream().mapToLong(SkippedMonth::retryAfterSeconds).filter(value -> value > 0)
                .min().orElse(0);
        return new ScanTrigger(false, reason, skipped, retryAfter, state);
    }

    void cancel(CodeStatsModule.ScopeInfo scope, long jobId, long userId, boolean administrator) {
        ScanJobEntity job = jobMapper.selectById(jobId);
        if (job == null || job.getCodingTeamId() != scope.codingTeamId()
                || !job.getScopeType().equals(scope.scope().name()) || job.getScopeId() != scope.scopeId()) {
            throw new CodingOpenApiException("扫描任务不存在");
        }
        if (!administrator && job.getRequestedBy() != null && job.getRequestedBy() != userId) {
            throw new CodeStatsForbiddenException("只有发起人或团队管理员可以取消该任务");
        }
        if (!STATUS_QUEUED.equals(job.getStatus()) && !STATUS_RUNNING.equals(job.getStatus())) {
            throw new CodingOpenApiException("扫描任务已经结束");
        }
        cache.markCancelled(jobId);
        jobMapper.finishActive(jobId, STATUS_CANCELLED, "CANCELLED", "扫描已取消", LocalDateTime.now(clock));
        cache.bumpScanVersion(cache.scopeKey(scope), job.getMonth());
    }

    ZhaogangCodeScanJobMapper jobMapper() {
        return jobMapper;
    }

    ZhaogangCodeScanRepoMapper repoMapper() {
        return repoMapper;
    }

    CodeStatsModels.Failures failures(CodeStatsModule.ScopeInfo scope, long jobId, int pageNumber, int pageSize) {
        ScanJobEntity job = jobMapper.selectById(jobId);
        if (job == null || job.getCodingTeamId() != scope.codingTeamId()
                || !job.getScopeType().equals(scope.scope().name()) || job.getScopeId() != scope.scopeId()) {
            throw new CodingOpenApiException("扫描任务不存在");
        }
        List<RepoFailure> repoFailures = repoMapper.selectFailures(jobId).stream()
                .map(this::toRepoFailure).toList();
        boolean continuous = continuous(job);
        LocalDate fromDate = continuous ? job.getSyncFrom() : YearMonth.parse(job.getMonth()).atDay(1);
        LocalDate toDate = continuous ? job.getSyncTo() : YearMonth.parse(job.getMonth()).atEndOfMonth();
        List<CommitEntity> diffRows = commitMapper.selectDiffFailures(scope.codingTeamId(), scope.scope().name(),
                scope.scopeId(), fromDate, toDate);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        int safePage = Math.max(1, pageNumber);
        int from = Math.min(diffRows.size(), (safePage - 1) * safeSize);
        int to = Math.min(diffRows.size(), from + safeSize);
        List<CodeStatsModels.DiffFailure> diffFailures = diffRows.subList(from, to).stream()
                .map(this::toDiffFailure)
                .toList();
        return new CodeStatsModels.Failures(repoFailures, diffFailures,
                new Page(safePage, safeSize, diffRows.size(),
                        diffRows.isEmpty() ? 0 : (diffRows.size() + safeSize - 1) / safeSize), fromDate.toString(), toDate.toString());
    }

    private CodeStatsModels.DiffFailure toDiffFailure(CommitEntity row) {
        CodeDiffFailure failure = CodeDiffFailure.fromStatus(row.getDiffStatus());
        boolean legacyLimit = "TOO_LARGE".equals(row.getDiffStatus());
        String reasonCode = failure != null ? failure.name() : legacyLimit ? "TOO_LARGE" : "REQUEST_FAILED";
        String reason = failure != null ? failure.reason : legacyLimit ? "diff 超过保护上限" : "diff 读取失败";
        return new CodeStatsModels.DiffFailure(row.getProjectName(), row.getDepotName(), row.getSha(),
                row.getAuthorName(), reasonCode, reason + "，未统计行数", CodeStatsSupport.format(row.getCommitAt()), row.getDepotId());
    }

    CodeStatsModels.DiffRetryJob retryDiffs(CodeStatsModule.ScopeInfo scope, ScanJobEntity source,
                                           CodeStatsModels.DiffRetryRequest request, long requestedBy) {
        if (scope.scope() == Scope.WORKBENCH_TEAM && !scope.administrator()) {
            throw new CodeStatsForbiddenException("只有团队管理员可以重试团队 diff");
        }
        if (source.getFinishedAt() == null || CodeScanWorker.DIFF_RETRY.equals(source.getScanMode())) {
            throw new CodingOpenApiException("请从已结束的同步任务重试");
        }
        if (!hasToken(scope)) throw new CodingOpenApiException("没有可用的 CODING 令牌，无法重试");
        String scopeKey = cache.scopeKey(scope);
        if (jobMapper.selectByActiveKey(activeKey(scope, "")) != null) {
            throw new CodingOpenApiException("当前范围有任务正在处理，请稍后重试");
        }
        if (cache.userCooldownActive(scopeKey, requestedBy, DIFF_RETRY_COOLDOWN)) {
            throw new CodingOpenApiException("重试冷却中，请稍后再试");
        }
        YearMonth sourceMonth = YearMonth.parse(source.getMonth());
        LocalDate from = continuous(source) ? source.getSyncFrom() : sourceMonth.atDay(1);
        LocalDate to = continuous(source) ? source.getSyncTo() : sourceMonth.atEndOfMonth();
        Set<CodeStatsModels.DiffRetryTarget> targets = Set.copyOf(request.targets());
        List<CommitEntity> matches = commitMapper.selectDiffFailures(scope.codingTeamId(), scope.scope().name(),
                scope.scopeId(), from, to).stream()
                .filter(row -> targets.contains(new CodeStatsModels.DiffRetryTarget(row.getDepotId(), row.getSha())))
                .toList();
        if (matches.isEmpty() || matches.size() != targets.size()) {
            throw new CodingOpenApiException("所选失败记录已变化，请刷新明细后重试");
        }
        LocalDate first = matches.stream().map(CommitEntity::getCommitDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate last = matches.stream().map(CommitEntity::getCommitDate).max(LocalDate::compareTo).orElseThrow();
        var selection = new DiffRetrySelection(first, last, matches.stream().map(CommitEntity::getId).toList());
        ScanJobEntity job = createAndSubmit(scope, YearMonth.from(last), CodeScanWorker.DIFF_RETRY, "MANUAL",
                requestedBy, null, selection);
        if (job == null) throw new CodingOpenApiException("当前范围有任务正在处理或队列已满，请稍后重试");
        cache.markUserCooldown(scopeKey, requestedBy, DIFF_RETRY_COOLDOWN);
        return new CodeStatsModels.DiffRetryJob(job.getId(), STATUS_QUEUED, selection.commitIds().size(), 0, 0, null);
    }

    CodeStatsModels.DiffRetryJob diffRetryStatus(ScanJobEntity job) {
        if (!CodeScanWorker.DIFF_RETRY.equals(job.getScanMode())) {
            throw new CodingOpenApiException("diff 重试任务不存在");
        }
        return new CodeStatsModels.DiffRetryJob(job.getId(), job.getStatus(), value(job.getTotalDiffs()),
                value(job.getDoneDiffs()), value(job.getFailedDiffs()), job.getErrorMessage());
    }

    private record DiffRetrySelection(LocalDate from, LocalDate to, List<Long> commitIds) {
    }

    @Override
    public void enqueueBackfill(CodeStatsModule.ScopeInfo scope, String month, long requestedBy) {
        ScanJobEntity active = jobMapper.selectByActiveKey(activeKey(scope, month));
        if (active != null) {
            cache.markBackfillPending(cache.scopeKey(scope), "continuous");
            return;
        }
        ScanJobEntity subscription=jobMapper.selectLatestSync(scope.codingTeamId(),scope.scope().name(),scope.scopeId());
        if(subscription!=null) createAndSubmit(scope,YearMonth.now(clock),"SYNC","CONTINUOUS",requestedBy,subscription.getSyncFrom());

    }

    private ScanJobEntity createAndSubmit(CodeStatsModule.ScopeInfo scope, YearMonth month, String mode,
                                          String requestedMode, long requestedBy) {
        return createAndSubmit(scope, month, mode, requestedMode, requestedBy, null);
    }

    private ScanJobEntity createAndSubmit(CodeStatsModule.ScopeInfo scope, YearMonth month, String mode,
                                          String requestedMode, long requestedBy, java.time.LocalDate syncFrom) {
        return createAndSubmit(scope, month, mode, requestedMode, requestedBy, syncFrom, null);
    }

    private ScanJobEntity createAndSubmit(CodeStatsModule.ScopeInfo scope, YearMonth month, String mode,
                                         String requestedMode, long requestedBy, LocalDate syncFrom,
                                         DiffRetrySelection retry) {
        ScanJobEntity job = new ScanJobEntity();
        if ("SYNC".equals(mode)) {
            job.setSyncFrom(syncFrom == null ? java.time.LocalDate.parse(properties.getCodeSyncFrom()) : syncFrom);
            job.setSyncTo(java.time.LocalDate.now(clock));
            job.setSyncTeamKey(scope.teamKey()); job.setSyncTeamHost(scope.teamHost());
        }
        if (retry != null) {
            job.setSyncFrom(retry.from());
            job.setSyncTo(retry.to());
        }
        job.setCodingTeamId(scope.codingTeamId());
        job.setScopeType(scope.scope().name());
        job.setScopeId(scope.scopeId());
        job.setMonth(month.toString());
        job.setScanMode("SYNC".equals(mode) ? "SYNC_V2" : mode);
        job.setStatus(STATUS_QUEUED);
        job.setStage(retry != null || "DIFF_BACKFILL".equals(mode) ? "DIFFS" : "DISCOVERING");
        job.setActiveKey(activeKey(scope, month.toString()));
        job.setTotalRepos(0);
        job.setDoneRepos(0);
        job.setTotalBranches(0);
        job.setDoneBranches(0);
        job.setTotalCommits(0);
        job.setDoneCommits(0);
        job.setTotalDiffs(retry == null ? 0 : retry.commitIds().size());
        job.setDoneDiffs(0);
        job.setFailedRepos(0);
        job.setFailedDiffs(0);
        job.setTruncated(false);
        job.setRequestedBy(requestedBy);
        job.setRequestedMode(requestedMode);
        job.setIdentitySignature(identities.signature(scope));
        try {
            jobMapper.insert(job);
        } catch (DuplicateKeyException e) {
            return null;
        }
        cache.bumpScanVersion(cache.scopeKey(scope), month.toString());
        CodeScanWorker.ScanInput input = new CodeScanWorker.ScanInput(job, scope, month, mode, requestedBy,
                java.util.Map.of(), "", retry == null ? List.of() : retry.commitIds());
        try {
            executor.execute(() -> {
                try {
                    worker.run(input);
                } finally {
                    afterJobFinished(scope, month);
                }
            });
        } catch (RejectedExecutionException e) {
            jobMapper.finishActive(job.getId(), STATUS_FAILED, "QUEUE_FULL", "扫描队列已满，请稍后重试",
                    LocalDateTime.now(clock));
            cache.bumpScanVersion(cache.scopeKey(scope), month.toString());
            return null;
        }
        return job;
    }

    private void afterJobFinished(CodeStatsModule.ScopeInfo scope, YearMonth month) {
        if (!cache.backfillPending(cache.scopeKey(scope), "continuous")) return;
        ScanJobEntity active = jobMapper.selectByActiveKey(activeKey(scope, month.toString()));
        if (active != null) return;
        ScanJobEntity subscription=jobMapper.selectLatestSync(scope.codingTeamId(),scope.scope().name(),scope.scopeId());
        if(subscription==null) return;
        ScanJobEntity created = createAndSubmit(scope,YearMonth.now(clock),"SYNC","CONTINUOUS",subscription.getRequestedBy(),subscription.getSyncFrom());
        if (created != null) cache.clearBackfillPending(cache.scopeKey(scope), "continuous");
    }

    @Scheduled(fixedDelay = 60_000)
    void recoverInterrupted() {
        LocalDateTime cutoff = LocalDateTime.now(clock)
                .minusSeconds(Math.max(60, properties.getCodeScanHeartbeatTimeoutSeconds()));
        List<ScanJobEntity> stale = jobMapper.selectStaleActive(cutoff);
        for (ScanJobEntity job : stale) {
            jobMapper.finishActive(job.getId(), STATUS_INTERRUPTED, "SCAN_INTERRUPTED",
                    "扫描已中断，请重新发起", LocalDateTime.now(clock));
            cache.bumpScanVersion(job.getCodingTeamId() + ":" + job.getScopeType() + ":" + job.getScopeId(),
                    job.getMonth());
            log.warn("zhaogang code scan interrupted, jobId={}", job.getId());
        }
    }

    @Scheduled(cron = "0 20 3 * * *")
    void cleanupExpired() {
        LocalDateTime cutoff = LocalDateTime.now(clock)
                .minusMonths(Math.max(1, properties.getCodeScanJobRetentionMonths()));
        try {
            List<Long> expired=jobMapper.selectExpiredJobIds(cutoff);
            for(int i=0;i<expired.size();i+=200) {
                List<Long> batch=expired.subList(i,Math.min(expired.size(),i+200));
                repoMapper.deleteJobIds(batch);
                jobMapper.deleteBatchIds(batch);
            }
            String commitCutoff = YearMonth.now(clock)
                    .minusMonths(Math.max(1, properties.getCodeStatsRetentionMonths())).toString();
            commitMapper.deleteBeforeMonth(commitCutoff);
        } catch (RuntimeException e) {
            log.warn("zhaogang code stats cleanup failed: {}", e.getClass().getSimpleName());
        }
    }

    private ScanMonth scanMonth(CodeStatsModule.ScopeInfo scope, YearMonth month, CodeRange range) {
        ScanJobEntity latest = jobMapper.selectLatest(scope.codingTeamId(), scope.scope().name(),
                scope.scopeId(), month.toString());
        ScanJobEntity successful = jobMapper.selectLatestSuccessful(scope.codingTeamId(), scope.scope().name(),
                scope.scopeId(), month.toString());
        LocalDateTime now = LocalDateTime.now(clock);
        if (latest == null) {
            return new ScanMonth(month.toString(), null, null, STATUS_NOT_STARTED, null,
                    new ScanProgress(0, 0, 0, 0, 0, 0, 0, 0), null, null, null, null, 0, null);
        }
        long retryAfter = retryAfterSeconds(latest, month, now);
        LocalDateTime cooldownUntil = cooldownUntil(latest, month);
        String status = latest.getStatus();
        String error = latest.getErrorMessage();
        // A year-wide job may be partial solely due to a different month's diff.
        // Preserve metadata failures, missing PATs, truncation and unfinished jobs conservatively.
        boolean completeMetadata = value(latest.getFailedRepos()) == 0 && !Boolean.TRUE.equals(latest.getTruncated())
                && scope.members().stream().noneMatch(member -> org.apache.commons.lang3.StringUtils.isBlank(member.token()));
        if (continuous(latest) && completeMetadata && (STATUS_COMPLETED.equals(status)
                || (STATUS_PARTIAL.equals(status) && "PARTIAL_SCAN".equals(latest.getErrorCode())
                    && value(latest.getFailedDiffs()) > 0))) {
            LocalDate fromDate = range.from().isAfter(month.atDay(1)) ? range.from() : month.atDay(1);
            LocalDate toDate = range.to().isBefore(month.atEndOfMonth()) ? range.to() : month.atEndOfMonth();
            long incomplete = incompleteDiffs(scope, fromDate, toDate);
            status = incomplete == 0 ? STATUS_COMPLETED : STATUS_PARTIAL;
            error = incomplete == 0 ? null : "当前查询范围有 " + incomplete + " 条提交的代码行未完整统计";
        }
        return new ScanMonth(month.toString(), latest.getId(), continuous(latest) ? "SYNC" : latest.getScanMode(), status,
                latest.getStage(), progress(latest), CodeStatsSupport.format(latest.getStartedAt()),
                CodeStatsSupport.format(latest.getFinishedAt()), successful == null ? null
                : CodeStatsSupport.format(successful.getFinishedAt()),
                CodeStatsSupport.format(cooldownUntil), retryAfter, error);
    }

    private boolean continuous(ScanJobEntity job) {
        return job.getScanMode() != null && job.getSyncFrom() != null && job.getSyncTo() != null
                && java.util.Set.of("SYNC", "SYNC_V2", "SNAPSHOT_V1").contains(job.getScanMode());
    }

    private long incompleteDiffs(CodeStatsModule.ScopeInfo scope, LocalDate from, LocalDate to) {
        if (from == null || to == null) return 0;
        return commitMapper.countIncompleteDiffs(scope.codingTeamId(), scope.scope().name(), scope.scopeId(), from, to);
    }

    private ScanProgress progress(ScanJobEntity job) {
        return new ScanProgress(value(job.getDoneRepos()), value(job.getTotalRepos()),
                value(job.getDoneBranches()), value(job.getTotalBranches()), value(job.getDoneCommits()),
                value(job.getTotalCommits()), value(job.getDoneDiffs()), value(job.getTotalDiffs()));
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private long retryAfterSeconds(ScanJobEntity job, YearMonth month, LocalDateTime now) {
        LocalDateTime until = cooldownUntil(job, month);
        if (until == null) return 0;
        return Math.max(0, Duration.between(now, until).getSeconds());
    }

    private LocalDateTime cooldownUntil(ScanJobEntity job, YearMonth month) {
        if (job == null || job.getFinishedAt() == null) return null;
        if (STATUS_COMPLETED.equals(job.getStatus())) {
            boolean current = month.equals(YearMonth.now(clock));
            return job.getFinishedAt().plusSeconds(current ? Math.max(0, properties.getCodeCurrentMonthCooldownSeconds())
                    : Math.max(0, properties.getCodeHistoricalCooldownSeconds()));
        }
        if (STATUS_PARTIAL.equals(job.getStatus()) || STATUS_FAILED.equals(job.getStatus())
                || STATUS_INTERRUPTED.equals(job.getStatus()) || STATUS_CANCELLED.equals(job.getStatus())) {
            return job.getFinishedAt().plusSeconds(Math.max(0, properties.getCodeFailedRetrySeconds()));
        }
        return null;
    }

    private String aggregateStatus(List<ScanMonth> months) {
        if (months.stream().anyMatch(month -> STATUS_FAILED.equals(month.status()))) return STATUS_FAILED;
        if (months.stream().anyMatch(month -> STATUS_RUNNING.equals(month.status())
                || STATUS_QUEUED.equals(month.status()))) return STATUS_RUNNING;
        if (months.stream().anyMatch(month -> STATUS_INTERRUPTED.equals(month.status())
                || STATUS_CANCELLED.equals(month.status()))) return STATUS_INTERRUPTED;
        if (months.stream().anyMatch(month -> STATUS_PARTIAL.equals(month.status()))) return STATUS_PARTIAL;
        if (months.stream().allMatch(month -> STATUS_COMPLETED.equals(month.status()))) return STATUS_COMPLETED;
        return STATUS_NOT_STARTED;
    }

    private boolean hasToken(CodeStatsModule.ScopeInfo scope) {
        return scope.members().stream().anyMatch(member -> StringUtils.isNotBlank(member.token()));
    }

    private String activeKey(CodeStatsModule.ScopeInfo scope, String month) {
        return scope.codingTeamId() + ":" + scope.scope().name() + ":" + scope.scopeId();
    }

    private RepoFailure toRepoFailure(ScanRepoEntity repo) {
        return new RepoFailure(repo.getProjectName(), repo.getProjectDisplayName(), repo.getDepotName(),
                StringUtils.defaultIfBlank(repo.getErrorCode(), "REQUEST_FAILED"),
                StringUtils.defaultIfBlank(repo.getErrorMessage(), "仓库读取失败"));
    }

    private static java.util.concurrent.ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "zhaogang-code-scan-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}

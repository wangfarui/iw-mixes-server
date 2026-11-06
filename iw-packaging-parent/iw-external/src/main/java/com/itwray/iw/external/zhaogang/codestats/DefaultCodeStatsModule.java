package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.CreateIdentityRequest;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.EntryPage;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Failures;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.IdentityMapping;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.IdentityPage;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.MemberStatistics;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Options;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.RescanPolicy;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.ScanRequest;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.ScanState;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.ScanTrigger;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Scope;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.SelfStatistics;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.TeamOption;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.TeamStatistics;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.UpdateIdentityRequest;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanJobEntity;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
class DefaultCodeStatsModule implements CodeStatsModule {

    private final CodeStatsScopeDirectory directory;
    private final CodeScanService scanService;
    private final CodeStatsQueryService queryService;
    private final CodeIdentityService identityService;
    private final CodeStatsViewCache cache;
    private final ZhaogangProperties properties;
    private final Clock clock;

    @Autowired
    DefaultCodeStatsModule(CodeStatsScopeDirectory directory, CodeScanService scanService,
                           CodeStatsQueryService queryService, CodeIdentityService identityService,
                           CodeStatsViewCache cache, ZhaogangProperties properties) {
        this(directory, scanService, queryService, identityService, cache, properties,
                Clock.system(CodeStatsSupport.CHINA_ZONE));
    }

    DefaultCodeStatsModule(CodeStatsScopeDirectory directory, CodeScanService scanService,
                           CodeStatsQueryService queryService, CodeIdentityService identityService,
                           CodeStatsViewCache cache, ZhaogangProperties properties, Clock clock) {
        this.directory = directory;
        this.scanService = scanService;
        this.queryService = queryService;
        this.identityService = identityService;
        this.cache = cache;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Options options(Context context) {
        List<TeamOption> teams = directory.teams(context).stream()
                .map(team -> new TeamOption(team.id(), team.name(), team.memberCount(), team.administrator()))
                .toList();
        identityService.ensureDefaults(directory.resolve(context, Scope.SELF, null));
        int identityCount = identityService.countMyIdentities(context.codingTeamId(), context.userId());
        RescanPolicy policy = new RescanPolicy(properties.getCodeCurrentMonthCooldownSeconds(),
                properties.getCodeHistoricalCooldownSeconds(), properties.getCodeFailedRetrySeconds(),
                properties.getCodeUserCooldownSeconds());
        return new Options(teams, policy, identityCount,
                ZonedDateTime.now(clock).withZoneSameInstant(CodeStatsSupport.CHINA_ZONE)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }

    @Override
    public Object statistics(Context context, String from, String to, String scopeValue, Long workbenchTeamId,
                             boolean refresh) {
        Scope scope = parseScope(scopeValue);
        ScopeInfo info = directory.resolve(context, scope, workbenchTeamId);
        CodeRange range = CodeRange.parse(from, to, properties.getCodeStatsRetentionMonths(), clock);
        // 查询不调度上游同步。历史回补和增量由显式操作、定时任务负责。
        ScanState scan = scanService.scanState(info, range);
        boolean dataReady = scanService.dataReady(scan);
        boolean refreshing = scanService.refreshing(scan);
        boolean active = scan.months().stream().anyMatch(month ->
                "QUEUED".equals(month.status()) || "RUNNING".equals(month.status()));
        if (!dataReady || active) {
            return scope == Scope.SELF
                    ? queryService.selfStatistics(info, range, scan, refreshing, dataReady)
                    : queryService.teamStatistics(info, range, scan, refreshing, dataReady);
        }
        String key = statisticsKey(info, range);
        if (!refresh) {
            if (scope == Scope.SELF) {
                Optional<SelfStatistics> cached = cache.get(key, SelfStatistics.class);
                if (cached.isPresent()) return cached.get();
            } else {
                Optional<TeamStatistics> cached = cache.get(key, TeamStatistics.class);
                if (cached.isPresent()) return cached.get();
            }
        }
        Object result = scope == Scope.SELF
                ? queryService.selfStatistics(info, range, scan, refreshing, true)
                : queryService.teamStatistics(info, range, scan, refreshing, true);
        cache.put(key, result);
        return result;
    }

    @Override
    public MemberStatistics memberStatistics(Context context, String from, String to, long workbenchTeamId,
                                             long memberUserId) {
        ScopeInfo info = directory.resolve(context, Scope.WORKBENCH_TEAM, workbenchTeamId);
        CodeRange range = CodeRange.parse(from, to, properties.getCodeStatsRetentionMonths(), clock);
        String key = "member:" + cache.scopeKey(info) + ":" + memberUserId + ":" + range.from() + ":" + range.to()
                + ":" + versions(info, range);
        Optional<MemberStatistics> cached = cache.get(key, MemberStatistics.class);
        if (cached.isPresent()) return cached.get();
        MemberStatistics result = queryService.memberStatistics(info, range, memberUserId);
        cache.put(key, result);
        return result;
    }

    @Override
    public EntryPage entries(Context context, String from, String to, Long depotId, String keyword, int pageNumber,
                             int pageSize, boolean refresh) {
        ScopeInfo info = directory.resolve(context, Scope.SELF, null);
        CodeRange range = CodeRange.parse(from, to, properties.getCodeStatsRetentionMonths(), clock);
        String safeKeyword = CodeStatsSupport.truncate(StringUtils.trimToEmpty(keyword), 100);
        int safePage = Math.max(1, pageNumber);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        String key = "entries:" + cache.scopeKey(info) + ":" + range.from() + ":" + range.to() + ":"
                + (depotId == null ? "" : depotId) + ":" + safeKeyword + ":" + safePage + ":" + safeSize + ":"
                + versions(info, range);
        ScanState scan = scanService.scanState(info, range);
        boolean stable = scanService.dataReady(scan) && scan.months().stream().noneMatch(month ->
                "RUNNING".equals(month.status()) || "QUEUED".equals(month.status()));
        if (!refresh && stable) {
            Optional<EntryPage> cached = cache.get(key, EntryPage.class);
            if (cached.isPresent()) return cached.get();
        }
        EntryPage result = queryService.entries(info, range, depotId, safeKeyword, safePage, safeSize);
        if (stable) cache.put(key, result);
        return result;
    }

    @Override
    public ScanState scan(Context context, String from, String to, String scopeValue, Long workbenchTeamId) {
        Scope scope = parseScope(scopeValue);
        ScopeInfo info = directory.resolve(context, scope, workbenchTeamId);
        return scanService.scanState(info, CodeRange.parse(from, to, properties.getCodeStatsRetentionMonths(), clock));
    }

    @Override
    public ScanTrigger triggerScan(Context context, ScanRequest request) {
        Scope scope = parseScope(request.scope());
        ScopeInfo info = directory.resolve(context, scope, request.workbenchTeamId());
        CodeRange range = CodeRange.parse(request.from(), request.to(),
                properties.getCodeStatsRetentionMonths(), clock);
        return "SYNC".equalsIgnoreCase(request.mode())
                ? scanService.triggerSync(info, range, context.userId())
                : scanService.trigger(info, request, range, context.userId());
    }

    @Override
    public ScanState cancel(Context context, long jobId) {
        ScanJobEntity job = requireJob(jobId);
        ScopeInfo info = resolveJobScope(context, job);
        scanService.cancel(info, jobId, context.userId(), info.administrator());
        return scanService.scanState(info, monthRange(job.getMonth()));
    }

    @Override
    public Failures failures(Context context, long jobId, int pageNumber, int pageSize) {
        ScanJobEntity job = requireJob(jobId);
        ScopeInfo info = resolveJobScope(context, job);
        return scanService.failures(info, jobId, pageNumber, pageSize);
    }

    @Override
    public CodeStatsModels.DiffRetryJob retryDiffs(Context context, long sourceJobId,
                                                  CodeStatsModels.DiffRetryRequest request) {
        ScanJobEntity source = requireJob(sourceJobId);
        return scanService.retryDiffs(resolveJobScope(context, source), source, request, context.userId());
    }

    @Override
    public CodeStatsModels.DiffRetryJob diffRetryStatus(Context context, long jobId) {
        ScanJobEntity job = requireJob(jobId);
        resolveJobScope(context, job);
        return scanService.diffRetryStatus(job);
    }

    @Override
    public IdentityPage identities(Context context, String scope, Long workbenchTeamId, String keyword,
                                   int pageNumber, int pageSize) {
        return identityService.identities(context, scope, workbenchTeamId, keyword, pageNumber, pageSize);
    }

    @Override
    public IdentityMapping createIdentity(Context context, CreateIdentityRequest request) {
        return identityService.create(context, request);
    }

    @Override
    public IdentityMapping updateIdentity(Context context, long identityId, UpdateIdentityRequest request) {
        return identityService.update(context, identityId, request);
    }

    @Override
    public void deleteIdentity(Context context, long identityId) {
        identityService.delete(context, identityId);
    }

    private ScanJobEntity requireJob(long jobId) {
        ScanJobEntity job = scanService.jobMapper().selectById(jobId);
        if (job == null) throw new CodingOpenApiException("扫描任务不存在");
        return job;
    }

    private ScopeInfo resolveJobScope(Context context, ScanJobEntity job) {
        if (job.getCodingTeamId() != context.codingTeamId()) {
            throw new CodeStatsForbiddenException("无权查看其他 CODING 团队的扫描任务");
        }
        Scope scope = parseScope(job.getScopeType());
        if (scope == Scope.SELF && job.getScopeId() != context.userId()) {
            throw new CodingOpenApiException("无权查看其他成员的扫描任务");
        }
        return directory.resolve(context, scope, scope == Scope.WORKBENCH_TEAM ? job.getScopeId() : null);
    }

    private CodeRange monthRange(String month) {
        YearMonth yearMonth = YearMonth.parse(month);
        LocalDate today = LocalDate.now(clock);
        LocalDate to = yearMonth.atEndOfMonth().isAfter(today) ? today : yearMonth.atEndOfMonth();
        return new CodeRange(yearMonth.atDay(1), to, List.of(yearMonth));
    }

    private String statisticsKey(ScopeInfo info, CodeRange range) {
        return "statistics:" + cache.scopeKey(info) + ":" + range.from() + ":" + range.to() + ":"
                + versions(info, range);
    }

    private String versions(ScopeInfo info, CodeRange range) {
        String scopeKey = cache.scopeKey(info);
        StringBuilder builder = new StringBuilder("range-status-v3-" + CodeLinePolicy.CACHE_VERSION).append('-')
                .append(identityService.signature(info)).append('-')
                .append(cache.identityVersion(scopeKey)).append('-').append(cache.scanVersion(scopeKey, "diff-retry"));
        for (YearMonth month : range.months()) {
            builder.append('-').append(cache.scanVersion(scopeKey, month.toString()));
        }
        ScanJobEntity sync = scanService.jobMapper().selectLatestSync(info.codingTeamId(), info.scope().name(), info.scopeId());
        if (sync != null) builder.append("-sync-").append(sync.getId()).append('-').append(sync.getStatus());
        builder.append("-members-").append(info.members().stream().map(member -> String.valueOf(member.userId()))
                .sorted().reduce((left, right) -> left + "," + right).orElse(""));
        return builder.toString();
    }

    private Scope parseScope(String value) {
        try {
            return Scope.valueOf(StringUtils.trimToEmpty(value).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CodingOpenApiException("代码统计范围只支持 SELF 或 WORKBENCH_TEAM");
        }
    }
}

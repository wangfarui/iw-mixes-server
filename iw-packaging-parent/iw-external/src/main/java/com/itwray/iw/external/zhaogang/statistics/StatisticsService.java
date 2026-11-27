package com.itwray.iw.external.zhaogang.statistics;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.ZhaogangSession;
import com.itwray.iw.external.zhaogang.ZhaogangSessionException;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsSupport;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;
import java.util.Objects;

import static com.itwray.iw.external.zhaogang.statistics.StatisticsModels.*;
import static com.itwray.iw.external.zhaogang.statistics.StatisticsRepository.*;

@Service
class StatisticsService {
    static final long REFRESH_MS = 60_000;
    private static final String FORMULA = "v3";

    record Query(LocalDate from, LocalDate to, RangeKind rangeKind, Metric worklogMetric, Metric codeMetric,
                 Metric sortMetric, SortOrder sortOrder, String keyword, String role,
                 int pageNumber, int pageSize, boolean compare, String snapshotId, boolean refresh) {}
    private record State(Revision revision, Stored stored, Task task) {}

    private final StatisticsRepository repository;
    private final ZhaogangProperties properties;
    private final Clock clock;

    @Autowired
    StatisticsService(StatisticsRepository repository, ZhaogangProperties properties) {
        this(repository, properties, Clock.system(CodeStatsSupport.CHINA_ZONE));
    }

    StatisticsService(StatisticsRepository repository, ZhaogangProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    Report report(ZhaogangSession session, Query query) {
        if (session.teamId() == null || session.teamId() <= 0 || session.userId() == null || session.userId() <= 0) {
            throw new ZhaogangSessionException("当前 CODING 身份不完整，请重新绑定个人令牌");
        }
        if (query.worklogMetric.code() || !query.codeMetric.code()) {
            throw new CodingOpenApiException("工时榜或代码榜的指标不正确");
        }
        long org = session.teamId();
        long user = session.userId();
        LocalDate today = today();
        RangeKind kind = query.rangeKind == null ? query.from == null && query.to == null ? RangeKind.THIS_MONTH : RangeKind.CUSTOM
                : query.rangeKind;
        var period = StatisticsPeriod.resolve(kind, query.from, query.to, query.compare, today);
        String policy = policy(today);
        long now = clock.millis();
        String fingerprint = CodingCredentialService.fingerprint(session.token());
        var read = repository.read(() -> {
            requireAuthorized(org, user, fingerprint);
            return state(org, period, true);
        });
        Stored stored = applicable(read, policy) ? read.stored : null;
        Task task = read.task;
        String sourceVersion = read.revision.fingerprint(period.months(), policy);
        boolean stale = stored != null && (stored.generatedAt() + REFRESH_MS <= now
                || !stored.sourceVersion().equals(sourceVersion));
        if ((stored == null || stale || query.refresh) && !active(task)
                && (task == null || !"FAILED".equals(task.state()) || query.refresh
                || task.visibility() < read.revision.visibility() || !sourceVersion.equals(task.sourceVersion()))) {
            task = repository.enqueue(org, period.key(), read.revision.visibility(), sourceVersion, preset(period, today) ? 0 : 10,
                    now, task == null || stored == null || query.refresh ? now : stored.generatedAt(), query.refresh);
        }

        // If permission changed while a writer/queue was contending, do not send the previously
        // read payload. Each response is linearized at this durable authorization check.
        if (stored != null) {
            long visibility = stored.visibility();
            boolean applicable = Boolean.TRUE.equals(repository.read(() -> {
                requireAuthorized(org, user, fingerprint);
                return repository.revision(org, java.util.List.of(), false).visibility() == visibility;
            }));
            if (!applicable) {
                stored = null;
                stale = false;
            }
        }

        String status = task == null ? "INITIALIZING" : task.state();
        String error = task == null ? null : task.safeError();
        if (stored == null && task == null) {
            status = "FAILED";
            error = "当前统计任务较多，请稍后重试";
        } else if (stored == null && ("QUEUED".equals(status) || "IDLE".equals(status))) status = "INITIALIZING";
        Refresh refresh = new Refresh(status, stored != null && (stale || !"IDLE".equals(status)),
                task == null || "IDLE".equals(status) ? null : task.jobId(),
                stored == null ? null : Instant.ofEpochMilli(stored.generatedAt()), error);
        if (stored == null) return new Report(period.range(), period.comparison(), null, false, refresh, null, null, null, null, null, null);
        var payload = repository.decode(stored);
        int page = query.snapshotId != null && !Objects.equals(query.snapshotId, stored.snapshotId()) ? 1 : query.pageNumber;
        String keyword = query.keyword == null ? "" : query.keyword.strip().toLowerCase(Locale.ROOT);
        return new Report(period.range(), period.comparison(), stored.snapshotId(), true, refresh, payload.population(), payload.summary(),
                StatisticsCalculator.ranking(payload, user, query.worklogMetric), StatisticsCalculator.ranking(payload, user, query.codeMetric),
                StatisticsCalculator.members(payload, user, query.sortMetric, query.sortOrder, keyword, query.role, page, query.pageSize),
                payload.dataStatus());
    }

    void preparePresets(long org, LocalDate today) {
        String policy = policy(today);
        for (RangeKind kind : StatisticsPeriod.PRESETS) {
            var period = StatisticsPeriod.resolve(kind, null, null, true, today);
            State state = repository.read(() -> state(org, period, false));
            String sourceVersion = state.revision.fingerprint(period.months(), policy);
            if (active(state.task) || (state.task != null && "FAILED".equals(state.task.state())
                    && state.task.visibility() >= state.revision.visibility()
                    && sourceVersion.equals(state.task.sourceVersion()))) continue;
            if (!applicable(state, policy) || !state.stored.sourceVersion().equals(sourceVersion)) {
                long now = clock.millis();
                repository.enqueue(org, period.key(), state.revision.visibility(), sourceVersion, 0, now,
                        state.stored == null ? now : state.stored.generatedAt(), false);
            }
        }
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    LocalDate retention(LocalDate today) {
        return YearMonth.from(today).minusMonths(Math.max(1, properties.getCodeStatsRetentionMonths()) - 1L).atDay(1);
    }

    String policy(LocalDate today) {
        return FORMULA + ":" + retention(today);
    }

    Clock clock() {
        return clock;
    }

    private State state(long org, StatisticsPeriod period, boolean payload) {
        return new State(repository.revision(org, period.months(), false), repository.stored(org, period.key(), payload),
                repository.task(org, period.key(), false));
    }

    private boolean applicable(State state, String policy) {
        return state.stored != null && state.stored.visibility() == state.revision.visibility() && policy.equals(state.stored.policy());
    }

    private boolean active(Task task) {
        return task != null && ("QUEUED".equals(task.state()) || "RUNNING".equals(task.state()));
    }

    private boolean preset(StatisticsPeriod period, LocalDate today) {
        return StatisticsPeriod.PRESETS.stream()
                .anyMatch(kind -> period.equals(StatisticsPeriod.resolve(kind, null, null, true, today)));
    }

    private void requireAuthorized(long org, long user, String fingerprint) {
        if (!repository.authorized(org, user, fingerprint)) {
            throw new ZhaogangSessionException("CODING 凭证已变更，请重新绑定个人令牌");
        }
    }
}

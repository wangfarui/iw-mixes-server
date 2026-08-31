package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.zhaogang.ZhaogangModels.Build;
import com.itwray.iw.external.zhaogang.ZhaogangModels.Plan;
import com.itwray.iw.external.zhaogang.ZhaogangModels.PlanCatalog;
import com.itwray.iw.external.zhaogang.ZhaogangModels.PlanPageSync;
import com.itwray.iw.external.zhaogang.ZhaogangModels.Project;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按 CODING 用户缓存构建目录。目录元数据与当前页最近构建分开同步，CODING 仍是唯一事实来源。
 */
@Service
class ZhaogangCatalogService {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SYNC_TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final CodingOpenApiPort coding;
    private final ZhaogangProperties properties;
    private final Map<Long, CatalogEntry> entries = new ConcurrentHashMap<>();
    private final ExecutorService metadataExecutor;
    private final ExecutorService codingExecutor;

    ZhaogangCatalogService(CodingOpenApiPort coding, ZhaogangProperties properties) {
        this.coding = coding;
        this.properties = properties;
        this.metadataExecutor = Executors.newSingleThreadExecutor(threadFactory("zhaogang-catalog-metadata-"));
        this.codingExecutor = Executors.newFixedThreadPool(Math.max(1, properties.getCatalogConcurrency()),
                threadFactory("zhaogang-coding-request-"));
    }

    PlanCatalog catalog(ZhaogangSession session) {
        CatalogEntry entry = entry(session);
        entry.touch();
        CatalogSnapshot snapshot = entry.snapshot;
        if (snapshot == null) {
            snapshot = await(refreshMetadata(entry));
        }
        return view(entry, snapshot);
    }

    PlanPageSync syncPage(ZhaogangSession session, ZhaogangPlanPageSyncDto dto) {
        CatalogEntry entry = entry(session);
        entry.touch();
        if (entry.snapshot == null) {
            await(refreshMetadata(entry));
        }

        List<PlanRef> requested = dto.getPlans().stream()
                .map(plan -> new PlanRef(plan.getProjectId(), plan.getJobId()))
                .distinct()
                .toList();
        List<CompletableFuture<PlanSyncResult>> futures = requested.stream()
                .map(plan -> syncPlan(entry, plan, dto.isForce()))
                .toList();
        List<PlanSyncResult> results = futures.stream().map(this::join).toList();

        CatalogSnapshot snapshot = entry.snapshot;
        Map<String, Plan> plansByKey = snapshot.plans().stream()
                .collect(Collectors.toMap(this::planKey, Function.identity()));
        List<Plan> plans = requested.stream()
                .map(plan -> plansByKey.get(planKey(plan.projectId(), plan.jobId())))
                .filter(java.util.Objects::nonNull)
                .toList();
        Set<Long> failures = new LinkedHashSet<>(snapshot.failedProjectIds());
        results.stream().filter(result -> !result.success()).map(PlanSyncResult::projectId).forEach(failures::add);
        return new PlanPageSync(plans, failures.stream().sorted().toList(), pageSyncedAt(entry, requested));
    }

    void updateLatestBuild(ZhaogangSession session, long projectId, long jobId, Build build) {
        CatalogEntry entry = matchingEntry(session);
        if (entry == null || entry.snapshot == null || build == null) {
            return;
        }
        Instant now = Instant.now();
        synchronized (entry) {
            replaceBuild(entry, projectId, jobId, build);
            String key = planKey(projectId, jobId);
            entry.lastBuildAttemptAt.put(key, now);
            entry.lastBuildSuccessAt.put(key, now);
            entry.failedBuildKeys.remove(key);
        }
    }

    void invalidate(ZhaogangSession session) {
        if (session != null && session.userId() != null) {
            entries.computeIfPresent(session.userId(), (ignored, entry) -> entry.matches(session) ? null : entry);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    void maintainCatalogs() {
        Instant now = Instant.now();
        Duration activeWindow = Duration.ofMinutes(Math.max(1, properties.getCatalogActiveMinutes()));
        Duration retention = Duration.ofMinutes(Math.max(properties.getCatalogActiveMinutes(),
                properties.getCatalogRetentionMinutes()));
        Duration refreshInterval = refreshInterval();
        entries.forEach((userId, entry) -> {
            Duration idle = Duration.between(entry.lastAccessedAt, now);
            if (idle.compareTo(retention) >= 0) {
                entries.remove(userId, entry);
                return;
            }
            if (entry.snapshot != null && idle.compareTo(activeWindow) < 0
                    && Duration.between(entry.lastMetadataAttemptAt, now).compareTo(refreshInterval) >= 0) {
                refreshMetadata(entry);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        metadataExecutor.shutdownNow();
        codingExecutor.shutdownNow();
    }

    private CatalogEntry entry(ZhaogangSession session) {
        if (session == null || session.userId() == null || session.userId() <= 0) {
            throw new ZhaogangSessionException("CODING 会话用户信息不完整");
        }
        return entries.compute(session.userId(), (ignored, current) -> current != null && current.matches(session)
                ? current : new CatalogEntry(session));
    }

    private CatalogEntry matchingEntry(ZhaogangSession session) {
        if (session == null || session.userId() == null) {
            return null;
        }
        CatalogEntry entry = entries.get(session.userId());
        return entry != null && entry.matches(session) ? entry : null;
    }

    private CompletableFuture<CatalogSnapshot> refreshMetadata(CatalogEntry entry) {
        synchronized (entry) {
            if (entry.metadataRefresh != null) {
                return entry.metadataRefresh;
            }
            CompletableFuture<CatalogSnapshot> future = new CompletableFuture<>();
            entry.metadataRefresh = future;
            entry.metadataRefreshing = true;
            entry.lastMetadataAttemptAt = Instant.now();
            metadataExecutor.execute(() -> {
                try {
                    CatalogSnapshot fresh = synchronizeMetadata(entry);
                    synchronized (entry) {
                        fresh = preserveLatestBuilds(fresh, entry.snapshot);
                        entry.snapshot = fresh;
                        entry.metadataRefreshing = false;
                    }
                    future.complete(fresh);
                } catch (RuntimeException e) {
                    synchronized (entry) {
                        entry.metadataRefreshing = false;
                    }
                    future.completeExceptionally(e);
                } finally {
                    clearMetadataRefresh(entry, future);
                }
            });
            return future;
        }
    }

    private CatalogSnapshot synchronizeMetadata(CatalogEntry entry) {
        ZhaogangSession session = entry.session;
        List<CodingProject> codingProjects = coding.projects(session.token(), session.userId());
        List<Project> projects = codingProjects.stream()
                .map(project -> new Project(project.id(), project.name(), project.displayName()))
                .sorted(Comparator.comparing(Project::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        Map<String, Plan> previousPlans = entry.snapshot == null ? Map.of() : entry.snapshot.plans().stream()
                .collect(Collectors.toMap(this::planKey, Function.identity()));
        Set<Long> failedProjectIds = ConcurrentHashMap.newKeySet();

        List<ProjectPlans> projectPlans = parallel(codingProjects, project -> {
            try {
                return new ProjectPlans(project, coding.plans(session.token(), project.id()), false);
            } catch (CodingOpenApiException error) {
                if (error.isPermissionDenied()) throw error;
                failedProjectIds.add(project.id());
                return new ProjectPlans(project, List.of(), true);
            } catch (RuntimeException e) {
                failedProjectIds.add(project.id());
                return new ProjectPlans(project, List.of(), true);
            }
        });

        List<Plan> plans = new ArrayList<>();
        projectPlans.stream().filter(result -> !result.failed()).forEach(result -> result.plans().stream()
                .map(plan -> toPlan(plan, result.project(), null)).forEach(plans::add));
        projectPlans.stream().filter(ProjectPlans::failed).forEach(result -> previousPlans.values().stream()
                .filter(plan -> plan.projectId() == result.project().id()).forEach(plans::add));
        plans.sort(Comparator.comparing(Plan::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Plan::projectDisplayName, String.CASE_INSENSITIVE_ORDER));
        return new CatalogSnapshot(projects, List.copyOf(plans), failedProjectIds.stream().sorted().toList());
    }

    private CompletableFuture<PlanSyncResult> syncPlan(CatalogEntry entry, PlanRef plan, boolean force) {
        String key = planKey(plan.projectId(), plan.jobId());
        synchronized (entry) {
            if (entry.snapshot.plans().stream().noneMatch(item -> planKey(item).equals(key))) {
                return CompletableFuture.completedFuture(new PlanSyncResult(plan.projectId(), false));
            }
            CompletableFuture<PlanSyncResult> inFlight = entry.buildSyncs.get(key);
            if (inFlight != null) {
                return inFlight;
            }
            Instant now = Instant.now();
            Instant lastAttempt = entry.lastBuildAttemptAt.get(key);
            if (!force && lastAttempt != null
                    && Duration.between(lastAttempt, now).compareTo(refreshInterval()) < 0) {
                return CompletableFuture.completedFuture(
                        new PlanSyncResult(plan.projectId(), !entry.failedBuildKeys.contains(key)));
            }

            entry.lastBuildAttemptAt.put(key, now);
            CompletableFuture<PlanSyncResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    CodingBuild codingBuild = coding.latestBuild(entry.session.token(), plan.projectId(), plan.jobId());
                    synchronized (entry) {
                        replaceBuild(entry, plan.projectId(), plan.jobId(), toBuild(codingBuild));
                        entry.lastBuildSuccessAt.put(key, Instant.now());
                        entry.failedBuildKeys.remove(key);
                    }
                    return new PlanSyncResult(plan.projectId(), true);
                } catch (CodingOpenApiException error) {
                    if (error.isPermissionDenied()) throw error;
                    entry.failedBuildKeys.add(key);
                    return new PlanSyncResult(plan.projectId(), false);
                } catch (RuntimeException e) {
                    entry.failedBuildKeys.add(key);
                    return new PlanSyncResult(plan.projectId(), false);
                }
            }, codingExecutor);
            entry.buildSyncs.put(key, future);
            future.whenComplete((ignored, error) -> entry.buildSyncs.remove(key, future));
            return future;
        }
    }

    private void replaceBuild(CatalogEntry entry, long projectId, long jobId, Build build) {
        CatalogSnapshot snapshot = entry.snapshot;
        List<Plan> plans = snapshot.plans().stream()
                .map(plan -> plan.projectId() == projectId && plan.id() == jobId ? copyWithBuild(plan, build) : plan)
                .toList();
        entry.snapshot = new CatalogSnapshot(snapshot.projects(), plans, snapshot.failedProjectIds());
    }

    private CatalogSnapshot preserveLatestBuilds(CatalogSnapshot fresh, CatalogSnapshot current) {
        if (current == null) {
            return fresh;
        }
        Map<String, Build> builds = current.plans().stream()
                .filter(plan -> plan.latestBuild() != null)
                .collect(Collectors.toMap(this::planKey, Plan::latestBuild));
        List<Plan> plans = fresh.plans().stream()
                .map(plan -> builds.containsKey(planKey(plan)) ? copyWithBuild(plan, builds.get(planKey(plan))) : plan)
                .toList();
        return new CatalogSnapshot(fresh.projects(), plans, fresh.failedProjectIds());
    }

    private void clearMetadataRefresh(CatalogEntry entry, CompletableFuture<CatalogSnapshot> future) {
        synchronized (entry) {
            if (entry.metadataRefresh == future) {
                entry.metadataRefresh = null;
            }
        }
    }

    private <T, R> List<R> parallel(List<T> values, Function<T, R> task) {
        List<CompletableFuture<R>> futures = values.stream()
                .map(value -> CompletableFuture.supplyAsync(() -> task.apply(value), codingExecutor))
                .toList();
        return futures.stream().map(this::join).toList();
    }

    private <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw error;
        }
    }

    private CatalogSnapshot await(CompletableFuture<CatalogSnapshot> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new CodingOpenApiException("构建计划目录同步失败", e.getCause());
        }
    }

    private PlanCatalog view(CatalogEntry entry, CatalogSnapshot snapshot) {
        return new PlanCatalog(snapshot.projects(), snapshot.plans(), snapshot.failedProjectIds(),
                latestSyncedAt(entry), entry.metadataRefreshing);
    }

    private String latestSyncedAt(CatalogEntry entry) {
        return entry.lastBuildSuccessAt.values().stream().max(Comparator.naturalOrder())
                .map(this::formatSyncTime).orElse("");
    }

    private String pageSyncedAt(CatalogEntry entry, List<PlanRef> plans) {
        if (plans.isEmpty()) {
            return "";
        }
        List<Instant> times = plans.stream()
                .map(plan -> entry.lastBuildSuccessAt.get(planKey(plan.projectId(), plan.jobId())))
                .toList();
        if (times.stream().anyMatch(java.util.Objects::isNull)) {
            return "";
        }
        return times.stream().min(Comparator.naturalOrder()).map(this::formatSyncTime).orElse("");
    }

    private String formatSyncTime(Instant time) {
        return SYNC_TIME_FORMATTER.format(time.atZone(CHINA_ZONE));
    }

    private Duration refreshInterval() {
        return Duration.ofMinutes(Math.max(1, properties.getCatalogRefreshMinutes()));
    }

    private Plan toPlan(CodingPlan plan, CodingProject project, Build latestBuild) {
        return new Plan(plan.id(), project.id(), project.name(), project.displayName(), plan.name(), plan.defaultBranch(),
                plan.environments(), plan.quickBuildSupported(), latestBuild);
    }

    private Build toBuild(CodingBuild build) {
        if (build == null) {
            return null;
        }
        return new Build(build.id(), build.number(), build.status(), build.statusDetail(), build.branch(), build.commit(),
                build.triggerUser(), build.duration(), build.startedAt());
    }

    private Plan copyWithBuild(Plan plan, Build build) {
        return new Plan(plan.id(), plan.projectId(), plan.projectName(), plan.projectDisplayName(), plan.name(),
                plan.defaultBranch(), plan.environments(), plan.quickBuildSupported(), build);
    }

    private String planKey(Plan plan) {
        return planKey(plan.projectId(), plan.id());
    }

    private String planKey(long projectId, long jobId) {
        return projectId + ":" + jobId;
    }

    private ThreadFactory threadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class CatalogEntry {

        private final ZhaogangSession session;
        private final Map<String, Instant> lastBuildSuccessAt = new ConcurrentHashMap<>();
        private final Map<String, Instant> lastBuildAttemptAt = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<PlanSyncResult>> buildSyncs = new ConcurrentHashMap<>();
        private final Set<String> failedBuildKeys = ConcurrentHashMap.newKeySet();
        private volatile CatalogSnapshot snapshot;
        private volatile CompletableFuture<CatalogSnapshot> metadataRefresh;
        private volatile boolean metadataRefreshing;
        private volatile Instant lastAccessedAt = Instant.now();
        private volatile Instant lastMetadataAttemptAt = Instant.EPOCH;

        private CatalogEntry(ZhaogangSession session) {
            this.session = session;
        }

        private boolean matches(ZhaogangSession other) {
            return session.userId().equals(other.userId()) && session.token().equals(other.token());
        }

        private void touch() {
            lastAccessedAt = Instant.now();
        }
    }

    private record CatalogSnapshot(List<Project> projects, List<Plan> plans, List<Long> failedProjectIds) {
    }

    private record ProjectPlans(CodingProject project, List<CodingPlan> plans, boolean failed) {
    }

    private record PlanRef(long projectId, long jobId) {
    }

    private record PlanSyncResult(long projectId, boolean success) {
    }
}

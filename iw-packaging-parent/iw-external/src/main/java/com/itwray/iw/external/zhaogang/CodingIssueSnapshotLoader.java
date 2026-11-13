package com.itwray.iw.external.zhaogang;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 批量查验事项，按 PAT 隔离缓存和并发。 */
@Component
public class CodingIssueSnapshotLoader implements AutoCloseable {

    private static final int MAX_CACHE_ENTRIES = 5000;

    public record IssueKey(String projectName, long issueCode) {
    }

    public record Lookup(CodingOpenApiPort.Issue issue, RuntimeException error) {
        public boolean missing() {
            return error instanceof CodingOpenApiException codingError
                    && "InvalidParameterValue".equals(codingError.code())
                    && "issue_not_exist".equals(codingError.getMessage());
        }
    }

    public record CheckedIssue(Lookup lookup, Instant checkedAt) {
    }

    private record CacheKey(String tokenFingerprint, IssueKey issueKey) {
    }

    private record Batch(String token, String fingerprint, Iterator<IssueKey> issues) {
    }

    private final CodingOpenApiPort coding;
    private final Clock clock;
    private final Duration issueTtl;
    private final Duration worklogTtl;
    private final ExecutorService executor;
    private final int threads;
    private final int perToken;
    private final Map<CacheKey, CheckedIssue> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<CacheKey, CompletableFuture<CheckedIssue>> inFlight = new HashMap<>();
    private final Map<String, ArrayDeque<Runnable>> waiting = new LinkedHashMap<>();
    private final Map<String, Integer> activeByToken = new HashMap<>();
    private int active;
    private int queued;
    private boolean stopped;

    @Autowired
    public CodingIssueSnapshotLoader(CodingOpenApiPort coding, ZhaogangProperties properties) {
        this(coding, properties, Clock.systemUTC());
    }

    CodingIssueSnapshotLoader(CodingOpenApiPort coding, ZhaogangProperties properties, Clock clock) {
        this.coding = coding;
        this.clock = clock;
        this.issueTtl = Duration.ofSeconds(Math.max(0, properties.getCodingIssueCacheSeconds()));
        this.worklogTtl = Duration.ofSeconds(Math.max(0, properties.getWorklogIssueDeletionDelaySeconds()));
        this.threads = Math.max(1, properties.getCodingIssueExecutorConcurrency());
        this.perToken = Math.max(1, properties.getCodingConcurrencyPerToken());
        this.executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "zhaogang-coding-issue");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Map<IssueKey, Lookup> load(String token, List<IssueKey> requested) {
        Map<IssueKey, Lookup> result = new LinkedHashMap<>();
        load(Map.of(token, requested), false, issueTtl, false).get(token)
                .forEach((key, checked) -> result.put(key, checked.lookup()));
        return result;
    }

    public Map<String, Map<IssueKey, CheckedIssue>> loadForWorklogs(
            Map<String, ? extends Collection<IssueKey>> requested, boolean refresh) {
        return load(requested, refresh, worklogTtl, true);
    }

    public synchronized CheckedIssue cachedWorklogIssue(String token, IssueKey issue) {
        CheckedIssue checked = cache.get(new CacheKey(CodingRequestLimiter.fingerprint(token), issue));
        return checked != null && clock.instant().isBefore(checked.checkedAt().plus(worklogTtl))
                ? checked : null;
    }

    private Map<String, Map<IssueKey, CheckedIssue>> load(
            Map<String, ? extends Collection<IssueKey>> requested, boolean refresh,
            Duration maxAge, boolean reuseMissing) {
        Map<String, Map<IssueKey, CompletableFuture<CheckedIssue>>> futures = new LinkedHashMap<>();
        var batches = new ArrayDeque<Batch>();
        requested.forEach((token, keys) -> {
            futures.put(token, new LinkedHashMap<>());
            if (!keys.isEmpty()) {
                batches.addLast(new Batch(token, CodingRequestLimiter.fingerprint(token), keys.iterator()));
            }
        });
        while (!batches.isEmpty()) {
            Batch batch = batches.removeFirst();
            IssueKey issue = batch.issues().next();
            futures.get(batch.token()).computeIfAbsent(issue, key -> submit(batch.token(),
                    new CacheKey(batch.fingerprint(), key), refresh, maxAge, reuseMissing));
            if (batch.issues().hasNext()) batches.addLast(batch);
        }

        Map<String, Map<IssueKey, CheckedIssue>> result = new LinkedHashMap<>();
        futures.forEach((token, batch) -> {
            Map<IssueKey, CheckedIssue> checks = new LinkedHashMap<>();
            batch.forEach((key, future) -> checks.put(key, future.join()));
            result.put(token, checks);
        });
        return result;
    }

    private synchronized CompletableFuture<CheckedIssue> submit(String token, CacheKey key, boolean refresh,
                                                                Duration maxAge, boolean reuseMissing) {
        while (!stopped) {
            CheckedIssue cached = cache.get(key);
            if (!refresh && !maxAge.isZero() && cached != null && (reuseMissing || !cached.lookup().missing())
                    && clock.instant().isBefore(cached.checkedAt().plus(maxAge))) {
                return CompletableFuture.completedFuture(cached);
            }
            CompletableFuture<CheckedIssue> running = inFlight.get(key);
            if (running != null) return running;
            if ((long) queued < (long) threads * 8) {
                var future = new CompletableFuture<CheckedIssue>();
                inFlight.put(key, future);
                waiting.computeIfAbsent(key.tokenFingerprint(), ignored -> new ArrayDeque<>())
                        .addLast(() -> fetch(token, key, future));
                queued++;
                dispatch();
                return future;
            }
            try {
                wait();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CodingOpenApiException("CODING 请求已取消", error);
            }
        }
        throw new IllegalStateException("CODING 事项查询已停止");
    }

    // 仅给有空闲配额的 PAT 分配线程，每次调度后轮换到队尾。
    private void dispatch() {
        while (!stopped && active < threads) {
            var next = waiting.entrySet().stream()
                    .filter(entry -> activeByToken.getOrDefault(entry.getKey(), 0) < perToken)
                    .findFirst().orElse(null);
            if (next == null) return;
            String fingerprint = next.getKey();
            var tasks = waiting.remove(fingerprint);
            Runnable task = tasks.removeFirst();
            if (!tasks.isEmpty()) waiting.put(fingerprint, tasks);
            queued--;
            active++;
            activeByToken.merge(fingerprint, 1, Integer::sum);
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    synchronized (this) {
                        active--;
                        activeByToken.computeIfPresent(fingerprint, (key, count) -> count == 1 ? null : count - 1);
                        dispatch();
                        notifyAll();
                    }
                }
            });
        }
    }

    private void fetch(String token, CacheKey key, CompletableFuture<CheckedIssue> future) {
        Instant checkedAt = clock.instant();
        Lookup lookup;
        try {
            lookup = new Lookup(coding.issue(token, key.issueKey().projectName(), key.issueKey().issueCode()), null);
        } catch (RuntimeException error) {
            lookup = new Lookup(null, error);
        } catch (Error error) {
            synchronized (this) {
                inFlight.remove(key, future);
            }
            future.completeExceptionally(error);
            return;
        }
        CheckedIssue checked = new CheckedIssue(lookup, checkedAt);
        synchronized (this) {
            if (inFlight.remove(key, future)) {
                if (lookup.issue() != null || lookup.missing()) {
                    cache.put(key, checked);
                    if (cache.size() > MAX_CACHE_ENTRIES) cache.remove(cache.keySet().iterator().next());
                } else {
                    cache.remove(key);
                }
            }
        }
        future.complete(checked);
    }

    public synchronized void invalidate(String projectName, long issueCode) {
        IssueKey issue = new IssueKey(projectName, issueCode);
        cache.keySet().removeIf(key -> key.issueKey().equals(issue));
        inFlight.keySet().removeIf(key -> key.issueKey().equals(issue));
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        stopped = true;
        waiting.clear();
        var error = new IllegalStateException("CODING 事项查询已停止");
        inFlight.values().forEach(future -> future.completeExceptionally(error));
        inFlight.clear();
        executor.shutdownNow();
        notifyAll();
    }
}

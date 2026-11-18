package com.itwray.iw.external.zhaogang;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.TimeUnit;

/** 批量查验事项，按 PAT 隔离缓存和并发。 */
@Slf4j
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

    private static final class TokenQueue {
        private final Map<CompletableFuture<CheckedIssue>, Runnable> foreground = new LinkedHashMap<>();
        private final Map<CompletableFuture<CheckedIssue>, Runnable> background = new LinkedHashMap<>();

        void promote(CompletableFuture<CheckedIssue> future) {
            Runnable queued = background.remove(future);
            if (queued != null) foreground.put(future, queued);
        }
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
    private final Map<String, TokenQueue> waiting = new LinkedHashMap<>();
    private final Map<String, Integer> activeByToken = new HashMap<>();
    private int active;
    private int queued;
    private boolean stopped;
    private int foregroundStarts;

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

    /** Offers one lookup without blocking an HTTP caller on a large background batch. */
    public synchronized CompletableFuture<CheckedIssue> offerWorklogIssue(
            String token, IssueKey issue, boolean refresh, boolean foreground) {
        if (stopped) return CompletableFuture.failedFuture(new IllegalStateException("CODING 事项查询已停止"));
        var key = new CacheKey(CodingRequestLimiter.fingerprint(token), issue);
        return offer(token, key, refresh, worklogTtl, true, foreground);
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
            var future = offer(token, key, refresh, maxAge, reuseMissing, !reuseMissing);
            if (future != null) return future;
            try {
                wait();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CodingOpenApiException("CODING 请求已取消", error);
            }
        }
        throw new IllegalStateException("CODING 事项查询已停止");
    }

    private CompletableFuture<CheckedIssue> offer(String token, CacheKey key, boolean refresh, Duration maxAge,
                                                   boolean reuseMissing, boolean foreground) {
        CheckedIssue cached = cache.get(key);
        if (!refresh && !maxAge.isZero() && cached != null && (reuseMissing || !cached.lookup().missing())
                && clock.instant().isBefore(cached.checkedAt().plus(maxAge))) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<CheckedIssue> running = inFlight.get(key);
        if (running != null) {
            TokenQueue queue = waiting.get(key.tokenFingerprint());
            if (foreground && queue != null) {
                queue.promote(running);
                dispatch();
            }
            return running;
        }
        // Leave queue capacity for interactive requests even during a large monthly check.
        long capacity = (long) threads * (foreground ? 16 : 8);
        if (queued >= capacity) return null;
        var future = new CompletableFuture<CheckedIssue>();
        inFlight.put(key, future);
        var queue = waiting.computeIfAbsent(key.tokenFingerprint(), ignored -> new TokenQueue());
        var requests = foreground ? queue.foreground : queue.background;
        long queuedAt = System.nanoTime();
        requests.put(future, () -> {
            log.debug("CODING 事项调度 queueMs={}", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queuedAt));
            fetch(token, key, future);
        });
        queued++;
        dispatch();
        return future;
    }

    private Map.Entry<String, TokenQueue> nextToken(boolean foreground) {
        for (var entry : waiting.entrySet()) {
            var queue = foreground ? entry.getValue().foreground : entry.getValue().background;
            if (!queue.isEmpty() && activeByToken.getOrDefault(entry.getKey(), 0) < perToken) return entry;
        }
        return null;
    }

    // Rotate eligible PATs and let background checks keep progressing during sustained interactive traffic.
    private void dispatch() {
        while (!stopped && active < threads) {
            boolean foreground = foregroundStarts < 8;
            var next = nextToken(foreground);
            if (next == null) {
                foreground = !foreground;
                next = nextToken(foreground);
                if (next == null) return;
            }
            foregroundStarts = foreground ? Math.min(foregroundStarts + 1, 8) : 0;
            String fingerprint = next.getKey();
            var queue = waiting.remove(fingerprint);
            var requests = foreground ? queue.foreground : queue.background;
            var iterator = requests.values().iterator();
            Runnable request = iterator.next();
            iterator.remove();
            if (!queue.foreground.isEmpty() || !queue.background.isEmpty()) waiting.put(fingerprint, queue);
            queued--;
            active++;
            activeByToken.merge(fingerprint, 1, Integer::sum);
            executor.execute(() -> {
                try {
                    request.run();
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

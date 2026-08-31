package com.itwray.iw.external.zhaogang;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** 批量读取 CODING 事项，负责请求内去重、PAT 隔离缓存和进行中请求合并。 */
@Component
public class CodingIssueSnapshotLoader {

    public record IssueKey(String projectName, long issueCode) {
    }

    public record Lookup(CodingOpenApiPort.Issue issue, RuntimeException error) {
    }

    private record CacheKey(String tokenFingerprint, IssueKey issueKey) {
    }

    private record CachedIssue(CodingOpenApiPort.Issue issue, Instant expiresAt) {
    }

    private final CodingOpenApiPort coding;
    private final Duration cacheTtl;
    private final ExecutorService executor;
    private final Map<CacheKey, CachedIssue> cache = new ConcurrentHashMap<>();
    private final Map<CacheKey, CompletableFuture<Lookup>> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public CodingIssueSnapshotLoader(CodingOpenApiPort coding, ZhaogangProperties properties) {
        this.coding = coding;
        this.cacheTtl = Duration.ofSeconds(Math.max(0, properties.getCodingIssueCacheSeconds()));
        int threads = Math.max(1, properties.getCodingIssueExecutorConcurrency());
        this.executor = Executors.newFixedThreadPool(threads, daemonThreadFactory());
    }

    public Map<IssueKey, Lookup> load(String token, List<IssueKey> requested) {
        String tokenFingerprint = CodingRequestLimiter.fingerprint(token);
        Map<IssueKey, CompletableFuture<Lookup>> futures = new LinkedHashMap<>();
        for (IssueKey issueKey : requested) {
            CacheKey cacheKey = new CacheKey(tokenFingerprint, issueKey);
            CachedIssue cached = cached(cacheKey);
            if (cached != null) {
                futures.put(issueKey, CompletableFuture.completedFuture(new Lookup(cached.issue(), null)));
                continue;
            }
            futures.put(issueKey, inFlight.computeIfAbsent(cacheKey, key -> {
                CompletableFuture<Lookup> future = CompletableFuture.supplyAsync(
                        () -> fetch(token, issueKey), executor);
                future.whenComplete((ignored, error) -> inFlight.remove(key, future));
                return future;
            }));
        }
        Map<IssueKey, Lookup> result = new LinkedHashMap<>();
        futures.forEach((key, future) -> result.put(key, join(future)));
        trimCache();
        return result;
    }

    private Lookup fetch(String token, IssueKey issueKey) {
        try {
            CodingOpenApiPort.Issue issue = coding.issue(token, issueKey.projectName(), issueKey.issueCode());
            if (issue != null && !cacheTtl.isZero()) {
                cache.put(new CacheKey(CodingRequestLimiter.fingerprint(token), issueKey),
                        new CachedIssue(issue, Instant.now().plus(cacheTtl)));
            }
            return new Lookup(issue, null);
        } catch (RuntimeException error) {
            return new Lookup(null, error);
        }
    }

    private CachedIssue cached(CacheKey key) {
        CachedIssue cached = cache.get(key);
        if (cached == null) return null;
        if (cached.expiresAt().isBefore(Instant.now())) {
            cache.remove(key, cached);
            return null;
        }
        return cached;
    }

    private void trimCache() {
        if (cache.size() <= 5000) return;
        Instant now = Instant.now();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private Lookup join(CompletableFuture<Lookup> future) {
        try {
            return future.join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause();
            return new Lookup(null, cause instanceof RuntimeException runtime ? runtime : error);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "zhaogang-coding-issue-");
            thread.setDaemon(true);
            return thread;
        };
    }
}

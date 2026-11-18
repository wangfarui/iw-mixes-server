package com.itwray.iw.external.zhaogang;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 按 CODING PAT 隔离并发和请求速率，避免不同用户互相阻塞。 */
@Component
public class CodingRequestLimiter {

    private static final long QPS_WINDOW_NANOS = Duration.ofSeconds(1).toNanos();
    private static final long ENTRY_RETENTION_NANOS = Duration.ofMinutes(30).toNanos();
    private static final int MAX_ENTRIES = 2048;
    static final String ISSUE_ACTION = "DescribeIssue";
    static final String WORKLOG_ACTION = "DescribeAllProjectsIssueWorkLogList";
    private static final int TEAM_QPS_LIMIT = 30;

    private final int concurrencyPerToken;
    private final int issueQpsPerToken;
    private final Map<String, TokenGate> gates = new ConcurrentHashMap<>();
    private final Object rateLock = new Object();
    // This client targets the single CODING team configured by iw.external.zhaogang.api-url.
    private final RateWindow issueWindow;
    private final RateWindow worklogWindow;
    private final RateWindow commitWindow;
    private final RateWindow diffWindow;

    public CodingRequestLimiter(ZhaogangProperties properties) {
        concurrencyPerToken = Math.max(1, properties.getCodingConcurrencyPerToken());
        issueQpsPerToken = Math.max(0, properties.getCodingIssueQpsPerToken());
        issueWindow = new RateWindow(teamBudget(properties.getCodingIssueQpsPerTeam()), true);
        worklogWindow = new RateWindow(teamBudget(properties.getCodingWorklogQpsPerTeam()), true);
        commitWindow = new RateWindow(Math.max(0, properties.getCodeCommitQps()), false);
        diffWindow = new RateWindow(Math.max(0, properties.getCodeDiffQps()), false);
    }

    public void acquire(String token, String action) throws InterruptedException {
        acquire(token, action, Long.MAX_VALUE);
    }

    void acquire(String token, String action, long deadline) throws InterruptedException {
        TokenGate gate = gate(token);
        RateWindow teamWindow = actionWindow(action);
        RateWindow tokenWindow = ISSUE_ACTION.equals(action) ? gate.issueWindow : RateWindow.NONE;
        while (true) {
            if (deadline == Long.MAX_VALUE) {
                gate.semaphore.acquire();
            } else if (!gate.semaphore.tryAcquire(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                throw admissionTimeout(action);
            }
            long wait;
            synchronized (rateLock) {
                long now = System.nanoTime();
                wait = Math.max(teamWindow.waitNanos(now), tokenWindow.waitNanos(now));
                if (wait == 0 && (deadline == Long.MAX_VALUE || deadline - now > 0)) {
                    teamWindow.record(now);
                    tokenWindow.record(now);
                    return;
                }
            }
            gate.semaphore.release();
            if (deadline != Long.MAX_VALUE && wait >= deadline - System.nanoTime()) throw admissionTimeout(action);
            TimeUnit.NANOSECONDS.sleep(wait);
        }
    }

    void defer(String action, long millis) {
        synchronized (rateLock) {
            actionWindow(action).defer(System.nanoTime(), millis);
        }
    }

    private CodingOpenApiException admissionTimeout(String action) {
        return new CodingOpenApiException(action, "CODING_ADMISSION_TIMEOUT", "CODING 请求排队超时，请稍后重试");
    }

    private static int teamBudget(int configuredQps) {
        return Math.max(1, Math.min(TEAM_QPS_LIMIT, configuredQps));
    }

    private RateWindow actionWindow(String action) {
        return switch (action) {
            case ISSUE_ACTION -> issueWindow;
            case WORKLOG_ACTION -> worklogWindow;
            case "DescribeGitCommitsInPage" -> commitWindow;
            case "DescribeGitCommitDiff" -> diffWindow;
            default -> RateWindow.NONE;
        };
    }

    public void release(String token) {
        TokenGate gate = gates.get(fingerprint(token));
        if (gate != null) gate.semaphore.release();
    }

    private TokenGate gate(String token) {
        String key = fingerprint(token);
        TokenGate gate = gates.computeIfAbsent(key, ignored -> new TokenGate(concurrencyPerToken, issueQpsPerToken));
        gate.lastAccessNanos = System.nanoTime();
        if (gates.size() > MAX_ENTRIES) {
            long cutoff = System.nanoTime() - ENTRY_RETENTION_NANOS;
            gates.entrySet().removeIf(entry -> entry.getValue().lastAccessNanos < cutoff
                    && entry.getValue().semaphore.availablePermits() == concurrencyPerToken);
        }
        return gate;
    }

    static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static final class TokenGate {
        private final Semaphore semaphore;
        private final RateWindow issueWindow;
        private volatile long lastAccessNanos = System.nanoTime();

        private TokenGate(int permits, int qps) {
            semaphore = new Semaphore(permits, true);
            issueWindow = new RateWindow(qps, false);
        }
    }

    /** Called under rateLock so a request is counted only after all limits are available. */
    private static final class RateWindow {
        private static final RateWindow NONE = new RateWindow(0, false);
        private final ArrayDeque<Long> requestTimes = new ArrayDeque<>();
        private final int limit;
        private final long spacing;
        private long nextStart = System.nanoTime();
        private long cooldownUntil = nextStart;
        private long reducedUntil = nextStart;

        private RateWindow(int limit, boolean paced) {
            this.limit = limit;
            spacing = paced ? (QPS_WINDOW_NANOS + limit - 1) / limit : 0;
        }

        private long waitNanos(long now) {
            if (limit <= 0) return 0;
            while (!requestTimes.isEmpty() && now - requestTimes.peekFirst() >= QPS_WINDOW_NANOS) {
                requestTimes.removeFirst();
            }
            long windowWait = requestTimes.size() < limit ? 0 : QPS_WINDOW_NANOS - (now - requestTimes.peekFirst());
            return Math.max(windowWait, Math.max(nextStart - now, cooldownUntil - now));
        }

        private void record(long now) {
            if (limit <= 0) return;
            requestTimes.addLast(now);
            nextStart = now + (reducedUntil - now > 0 ? spacing * 2 : spacing);
        }

        private void defer(long now, long millis) {
            if (limit <= 0) return;
            long delay = TimeUnit.MILLISECONDS.toNanos(Math.min(millis, TimeUnit.DAYS.toMillis(1)));
            cooldownUntil = now + Math.max(delay, cooldownUntil - now);
            reducedUntil = cooldownUntil + TimeUnit.SECONDS.toNanos(30);
        }
    }
}

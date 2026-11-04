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

/** 按 CODING PAT 隔离并发和请求速率，避免不同用户互相阻塞。 */
@Component
class CodingRequestLimiter {

    private static final long QPS_WINDOW_NANOS = Duration.ofSeconds(1).toNanos();
    private static final long ENTRY_RETENTION_NANOS = Duration.ofMinutes(30).toNanos();
    private static final int MAX_ENTRIES = 2048;

    private final int concurrencyPerToken;
    private final int issueQpsPerToken;
    private final Map<String, TokenGate> gates = new ConcurrentHashMap<>();
    private final RateWindow commitWindow;
    private final RateWindow diffWindow;

    CodingRequestLimiter(ZhaogangProperties properties) {
        concurrencyPerToken = Math.max(1, properties.getCodingConcurrencyPerToken());
        issueQpsPerToken = Math.max(0, properties.getCodingIssueQpsPerToken());
        commitWindow = new RateWindow(Math.max(0, properties.getCodeCommitQps()));
        diffWindow = new RateWindow(Math.max(0, properties.getCodeDiffQps()));
    }

    void acquire(String token, String action) throws InterruptedException {
        TokenGate gate = gate(token);
        gate.semaphore.acquire();
        try {
            if ("DescribeIssue".equals(action)) gate.acquireRate();
            globalWindow(action).acquire();
        } catch (InterruptedException error) {
            gate.semaphore.release();
            throw error;
        }
    }

    private RateWindow globalWindow(String action) {
        if ("DescribeGitCommitsInPage".equals(action)) return commitWindow;
        if ("DescribeGitCommitDiff".equals(action)) return diffWindow;
        return RateWindow.NONE;
    }

    void release(String token) {
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
        private final RateWindow rateWindow;
        private volatile long lastAccessNanos = System.nanoTime();

        private TokenGate(int permits, int qps) {
            semaphore = new Semaphore(permits, true);
            rateWindow = new RateWindow(qps);
        }

        private void acquireRate() throws InterruptedException {
            rateWindow.acquire();
        }
    }

    /** 固定 1 秒窗口限速；limit<=0 表示不限制。 */
    private static final class RateWindow {
        private static final RateWindow NONE = new RateWindow(0);
        private final Object rateLock = new Object();
        private final ArrayDeque<Long> requestTimes = new ArrayDeque<>();
        private volatile int limit;

        private RateWindow(int limit) {
            this.limit = limit;
        }

        private void acquire() throws InterruptedException {
            int qps = limit;
            if (qps <= 0) return;
            while (true) {
                long waitNanos;
                synchronized (rateLock) {
                    long now = System.nanoTime();
                    while (!requestTimes.isEmpty()
                            && now - requestTimes.peekFirst() >= QPS_WINDOW_NANOS) {
                        requestTimes.removeFirst();
                    }
                    if (requestTimes.size() < qps) {
                        requestTimes.addLast(now);
                        return;
                    }
                    waitNanos = QPS_WINDOW_NANOS - (now - requestTimes.peekFirst());
                }
                long millis = Math.max(1, Math.min(1000, (waitNanos + 999_999L) / 1_000_000L));
                Thread.sleep(millis);
            }
        }
    }
}

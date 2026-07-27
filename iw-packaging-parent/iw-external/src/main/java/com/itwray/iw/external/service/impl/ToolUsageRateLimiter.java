package com.itwray.iw.external.service.impl;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具使用记录公开接口的轻量固定窗口限流器。
 *
 * @author wray
 * @since 2026/7/27
 */
@Component
public class ToolUsageRateLimiter {

    private static final int MAX_REQUESTS_PER_MINUTE = 120;
    private static final int MAX_WINDOWS = 4096;
    private static final long WINDOW_MILLIS = 60_000L;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public boolean tryAcquire(String clientIp) {
        long now = Instant.now().toEpochMilli();
        if (windows.size() > MAX_WINDOWS) {
            windows.entrySet().removeIf(entry -> entry.getValue().startMillis + WINDOW_MILLIS <= now);
        }
        boolean[] accepted = new boolean[1];
        windows.compute(clientIp, (key, window) -> {
            if (window == null || window.startMillis + WINDOW_MILLIS <= now) {
                accepted[0] = true;
                return new Window(now, 1);
            }
            if (window.requests >= MAX_REQUESTS_PER_MINUTE) {
                return window;
            }
            accepted[0] = true;
            return new Window(window.startMillis, window.requests + 1);
        });
        return accepted[0];
    }

    private record Window(long startMillis, int requests) {
    }
}

package com.itwray.iw.external.service.impl;

import com.itwray.iw.external.service.ToolUsageRateGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 工具使用记录公开接口的轻量固定窗口限流器。
 *
 * @author wray
 * @since 2026/7/27
 */
@Component
public class ToolUsageRateGuardImpl implements ToolUsageRateGuard {

    private static final int MAX_REQUESTS_PER_MINUTE = 120;
    private static final int MAX_WINDOWS = 4096;
    private static final long WINDOW_MILLIS = 60_000L;

    private final Map<String, Window> windows = new HashMap<>();

    @Override
    public synchronized boolean tryAcquire(HttpServletRequest request) {
        long now = Instant.now().toEpochMilli();
        cleanupExpiredWindows(now);
        String clientKey = resolveClientKey(request);
        Window window = windows.get(clientKey);
        if (window == null) {
            if (windows.size() >= MAX_WINDOWS) {
                return false;
            }
            windows.put(clientKey, new Window(now, 1));
            return true;
        }
        if (window.requests >= MAX_REQUESTS_PER_MINUTE) {
            return false;
        }
        windows.put(clientKey, new Window(window.startMillis, window.requests + 1));
        return true;
    }

    private void cleanupExpiredWindows(long now) {
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().startMillis + WINDOW_MILLIS <= now) {
                iterator.remove();
            }
        }
    }

    private String resolveClientKey(HttpServletRequest request) {
        String remoteAddress = StringUtils.defaultIfBlank(request.getRemoteAddr(), "unknown");
        if (!isLocalProxy(remoteAddress)) {
            return remoteAddress;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.isBlank(forwardedFor)) {
            return remoteAddress;
        }
        return StringUtils.trimToEmpty(forwardedFor.split(",")[0]);
    }

    private boolean isLocalProxy(String remoteAddress) {
        return "127.0.0.1".equals(remoteAddress)
                || "::1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress);
    }

    private record Window(long startMillis, int requests) {
    }
}

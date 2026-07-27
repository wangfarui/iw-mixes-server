package com.itwray.iw.external.service;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 工具使用记录公开接口的访问频率保护。
 *
 * @author wray
 * @since 2026/7/27
 */
public interface ToolUsageRateGuard {

    boolean tryAcquire(HttpServletRequest request);
}

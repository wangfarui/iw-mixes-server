package com.itwray.iw.external.core;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 限制匿名工具使用记录接口的请求体大小。
 *
 * @author wray
 * @since 2026/7/27
 */
@Component
public class ToolUsageRequestSizeFilter extends OncePerRequestFilter {

    private static final String RECORD_PATH = "/external-service/api/tools/usage/record";
    private static final long MAX_REQUEST_BODY_BYTES = 1024L;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RECORD_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength < 0 || contentLength > MAX_REQUEST_BODY_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "请求体过大");
            return;
        }
        filterChain.doFilter(request, response);
    }
}

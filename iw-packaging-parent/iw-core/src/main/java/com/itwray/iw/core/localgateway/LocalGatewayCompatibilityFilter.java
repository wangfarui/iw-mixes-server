package com.itwray.iw.core.localgateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps old gateway-prefixed frontend requests usable when running iw-core locally.
 *
 * @author wray
 * @since 2026/6/29
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalGatewayCompatibilityFilter extends OncePerRequestFilter {

    private static final List<String> CORE_PREFIXES = List.of(
            "/auth-service",
            "/bookkeeping-service",
            "/eat-service",
            "/points-service"
    );

    private static final String EXTERNAL_PREFIX = "/external-service";

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "content-length",
            "expect",
            "host",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade"
    );

    private final LocalGatewayProperties properties;

    private final HttpClient httpClient;

    public LocalGatewayCompatibilityFilter(LocalGatewayProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(Math.max(properties.getTimeoutSeconds(), 1)))
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestUri = request.getRequestURI();
        String coreTarget = resolveCoreTarget(requestUri);
        if (coreTarget != null) {
            request.getRequestDispatcher(coreTarget).forward(request, response);
            return;
        }

        if (isExternalRequest(requestUri)) {
            proxyExternal(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveCoreTarget(String requestUri) {
        for (String prefix : CORE_PREFIXES) {
            if (requestUri.equals(prefix)) {
                return "/";
            }
            if (requestUri.startsWith(prefix + "/")) {
                return requestUri.substring(prefix.length());
            }
        }
        return null;
    }

    private boolean isExternalRequest(String requestUri) {
        return requestUri.equals(EXTERNAL_PREFIX) || requestUri.startsWith(EXTERNAL_PREFIX + "/");
    }

    private void proxyExternal(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isWebSocketUpgrade(request)) {
            response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED,
                    "Local Java compatibility gateway does not proxy WebSocket. Use local Nginx for /external-service/wb/**.");
            return;
        }

        try {
            HttpRequest proxyRequest = buildProxyRequest(request);
            HttpResponse<byte[]> proxyResponse = httpClient.send(proxyRequest, HttpResponse.BodyHandlers.ofByteArray());
            writeProxyResponse(response, proxyResponse);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "External service proxy was interrupted.");
        } catch (IllegalArgumentException ex) {
            log.warn("Failed to build local external proxy request", ex);
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Invalid external service proxy request.");
        } catch (Exception ex) {
            log.warn("Failed to proxy local external request", ex);
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "External service is unavailable.");
        }
    }

    private HttpRequest buildProxyRequest(HttpServletRequest request) throws IOException {
        URI targetUri = buildExternalTargetUri(request);
        byte[] body = request.getInputStream().readAllBytes();
        HttpRequest.BodyPublisher bodyPublisher = body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);

        HttpRequest.Builder builder = HttpRequest.newBuilder(targetUri)
                .timeout(Duration.ofSeconds(Math.max(properties.getTimeoutSeconds(), 1)))
                .method(request.getMethod(), bodyPublisher);

        Collections.list(request.getHeaderNames()).forEach(headerName -> copyRequestHeader(request, builder, headerName));
        return builder.build();
    }

    private URI buildExternalTargetUri(HttpServletRequest request) {
        StringBuilder target = new StringBuilder(trimTrailingSlash(properties.getExternalBaseUrl()))
                .append(request.getRequestURI());
        if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
            target.append('?').append(request.getQueryString());
        }
        return URI.create(target.toString());
    }

    private void copyRequestHeader(HttpServletRequest request, HttpRequest.Builder builder, String headerName) {
        if (shouldSkipHeader(headerName)) {
            return;
        }
        Collections.list(request.getHeaders(headerName)).forEach(headerValue -> builder.header(headerName, headerValue));
    }

    private void writeProxyResponse(HttpServletResponse response, HttpResponse<byte[]> proxyResponse) throws IOException {
        response.setStatus(proxyResponse.statusCode());
        proxyResponse.headers().map().forEach((headerName, headerValues) -> {
            if (!shouldSkipHeader(headerName)) {
                headerValues.forEach(headerValue -> response.addHeader(headerName, headerValue));
            }
        });
        response.getOutputStream().write(proxyResponse.body());
    }

    private boolean shouldSkipHeader(String headerName) {
        return HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase(Locale.ROOT));
    }

    private boolean isWebSocketUpgrade(HttpServletRequest request) {
        String upgrade = request.getHeader("Upgrade");
        return upgrade != null && "websocket".equalsIgnoreCase(upgrade);
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://127.0.0.1:18006";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}

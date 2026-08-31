package com.itwray.iw.external.zhaogang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.common.utils.AESUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * 保持两个无状态 Cookie：短期会话 Cookie 失效后，可由长期令牌 Cookie 自动恢复。
 */
@Component
class ZhaogangSessionManager {

    private static final String PAYLOAD_PREFIX = "v2:";

    private static final String SESSION_COOKIE = "zhaogang_session";

    private static final String TOKEN_COOKIE = "zhaogang_token";

    private final ZhaogangProperties properties;

    private final ObjectMapper objectMapper;

    ZhaogangSessionManager(ZhaogangProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    ZhaogangSession resolve(HttpServletRequest request, HttpServletResponse response) {
        Optional<ZhaogangSession> shortSession = readCookie(request, SESSION_COOKIE);
        if (shortSession.isPresent()) {
            renewSessionCookie(response, shortSession.get());
            return shortSession.get();
        }
        Optional<ZhaogangSession> tokenSession = readCookie(request, TOKEN_COOKIE);
        if (tokenSession.isPresent()) {
            renewSessionCookie(response, tokenSession.get());
            return tokenSession.get();
        }
        throw new ZhaogangSessionException("请先绑定 CODING 个人令牌");
    }

    void bind(HttpServletResponse response, ZhaogangSession session) {
        addCookie(response, SESSION_COOKIE, session, properties.getSessionDays());
        addCookie(response, TOKEN_COOKIE, session, properties.getTokenDays());
    }

    void clear(HttpServletResponse response) {
        clearCookie(response, SESSION_COOKIE);
        clearCookie(response, TOKEN_COOKIE);
    }

    private void renewSessionCookie(HttpServletResponse response, ZhaogangSession session) {
        addCookie(response, SESSION_COOKIE, session, properties.getSessionDays());
    }

    private Optional<ZhaogangSession> readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> name.equals(cookie.getName()))
                .findFirst()
                .flatMap(cookie -> decrypt(cookie.getValue()));
    }

    private Optional<ZhaogangSession> decrypt(String value) {
        try {
            String encoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            String encrypted = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String payload = AESUtils.decryptAESGCM(secretKey(), encrypted);
            String json = payload.startsWith(PAYLOAD_PREFIX)
                    ? new String(Base64.getUrlDecoder().decode(payload.substring(PAYLOAD_PREFIX.length())),
                    StandardCharsets.UTF_8)
                    : payload;
            ZhaogangSession session = objectMapper.readValue(json, ZhaogangSession.class);
            return session.token() == null || session.token().isBlank() ? Optional.empty() : Optional.of(session);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private void addCookie(HttpServletResponse response, String name, ZhaogangSession session, int days) {
        try {
            String json = objectMapper.writeValueAsString(session);
            String payload = PAYLOAD_PREFIX + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
            String encrypted = AESUtils.encryptAESGCM(secretKey(), payload);
            String value = URLEncoder.encode(Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(encrypted.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            ResponseCookie cookie = ResponseCookie.from(name, value)
                    .path("/external-service/api/zhaogang")
                    .httpOnly(true)
                    .secure(properties.isProduction())
                    .sameSite("Lax")
                    .maxAge(Duration.ofDays(Math.max(days, 1)))
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        } catch (Exception e) {
            throw new IllegalStateException("找钢工作台会话保存失败", e);
        }
    }

    private void clearCookie(HttpServletResponse response, String name) {
        ResponseCookie cookie = ResponseCookie.from(name, "")
                .path("/external-service/api/zhaogang")
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private SecretKey secretKey() {
        return AESUtils.generateSecretKey(properties.safeSessionKey());
    }
}

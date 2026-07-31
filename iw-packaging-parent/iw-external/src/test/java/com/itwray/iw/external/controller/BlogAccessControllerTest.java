package com.itwray.iw.external.controller;

import cn.hutool.crypto.digest.BCrypt;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.config.BlogAccessProperties;
import com.itwray.iw.external.service.BlogAccessService;
import com.itwray.iw.external.service.impl.BlogAccessRateLimiter;
import com.itwray.iw.external.service.impl.BlogAccessServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BlogAccessControllerTest {

    private static final String VERIFY_PATH = "/external-service/api/blog/access/verify";
    private static final String ALLOWED_ORIGIN = "https://blog.itwray.com";
    private static final String REQUEST_BODY = """
            {
              "postId": "2026/02/24/monthly-202602",
              "path": "/2026/02/24/monthly-202602/",
              "scope": "life",
              "password": "%s"
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        BlogAccessProperties properties = new BlogAccessProperties();
        properties.setAllowedOrigins(ALLOWED_ORIGIN);
        properties.setTokenSecret("test-token-secret-with-enough-randomness");

        BlogAccessProperties.ScopeProperties scopeProperties = new BlogAccessProperties.ScopeProperties();
        scopeProperties.setPasswordHash(BCrypt.hashpw("open-sesame"));
        scopeProperties.setKey(testAesKey());
        properties.getScope().put("life", scopeProperties);

        BlogAccessService service = new BlogAccessServiceImpl(properties, new BlogAccessRateLimiter(properties));
        mockMvc = MockMvcBuilders.standaloneSetup(new BlogAccessController(service, properties)).build();
    }

    @Test
    void allowedOriginAndCorrectPasswordReturnsSuccessWithoutCorsHeaders() throws Exception {
        mockMvc.perform(post(VERIFY_PATH)
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY.formatted("open-sesame")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().exists(HttpHeaders.EXPIRES))
                .andExpect(noCorsHeaders())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.access").value("scope"))
                .andExpect(jsonPath("$.scope").value("life"))
                .andExpect(jsonPath("$.postId").value("2026/02/24/monthly-202602"))
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.key").value(testAesKey()))
                .andExpect(successJsonStructure());
    }

    @Test
    void allowedOriginAndWrongPasswordReturnsUnauthorizedWithoutCorsHeaders() throws Exception {
        mockMvc.perform(post(VERIFY_PATH)
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY.formatted("wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().exists(HttpHeaders.EXPIRES))
                .andExpect(noCorsHeaders())
                .andExpect(content().json("""
                        {"ok":false,"message":"访问密码不正确"}
                        """, true));
    }

    @Test
    void disallowedOriginReturnsForbiddenWithoutCorsHeaders() throws Exception {
        mockMvc.perform(post(VERIFY_PATH)
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY.formatted("open-sesame")))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().exists(HttpHeaders.EXPIRES))
                .andExpect(noCorsHeaders())
                .andExpect(content().json("""
                        {"ok":false,"message":"访问密码不正确"}
                        """, true));
    }

    private ResultMatcher noCorsHeaders() {
        return result -> {
            assertFalse(result.getResponse().getHeaderNames().stream()
                    .anyMatch(headerName -> headerName.regionMatches(true, 0, "Access-Control-", 0,
                            "Access-Control-".length())));
            assertFalse(result.getResponse().getHeaders(HttpHeaders.VARY).stream()
                    .flatMap(value -> Arrays.stream(value.split(",")))
                    .map(String::trim)
                    .anyMatch(HttpHeaders.ORIGIN::equalsIgnoreCase));
        };
    }

    private ResultMatcher successJsonStructure() {
        return result -> {
            Map<String, Object> body = objectMapper.readValue(
                    result.getResponse().getContentAsByteArray(), new TypeReference<>() {
                    });
            assertEquals(Set.of("ok", "access", "scope", "postId", "token", "expiresAt", "key"), body.keySet());
        };
    }

    private String testAesKey() {
        byte[] keyBytes = "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes);
    }
}

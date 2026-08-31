package com.itwray.iw.external.zhaogang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.common.utils.AESUtils;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ZhaogangSessionManagerTest {

    @Test
    void cookiePayloadUsesAsciiEnvelopeForNonAsciiIdentity() {
        ZhaogangProperties properties = new ZhaogangProperties();
        ZhaogangSessionManager manager = new ZhaogangSessionManager(properties, new ObjectMapper());
        MockHttpServletResponse response = new MockHttpServletResponse();
        ZhaogangSession expected = new ZhaogangSession("test-token", 9292850L, "步步(王发瑞)", "", "g-iijw5014");

        manager.bind(response, expected);

        String cookieValue = cookieValue(response.getHeaders(HttpHeaders.SET_COOKIE).get(0));
        String encrypted = new String(Base64.getUrlDecoder().decode(
                URLDecoder.decode(cookieValue, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        String payload = AESUtils.decryptAESGCM(AESUtils.generateSecretKey(properties.safeSessionKey()), encrypted);
        assertThat(payload).startsWith("v2:").containsOnlyOnce("v2:").matches("[\\x00-\\x7F]+");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("zhaogang_session", cookieValue));
        assertThat(manager.resolve(request, new MockHttpServletResponse())).isEqualTo(expected);
    }

    @Test
    void readsLegacyRawJsonCookie() throws Exception {
        ZhaogangProperties properties = new ZhaogangProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        ZhaogangSessionManager manager = new ZhaogangSessionManager(properties, objectMapper);
        ZhaogangSession expected = new ZhaogangSession("test-token", 9292850L, "tester", "", "g-iijw5014");
        String encrypted = AESUtils.encryptAESGCM(AESUtils.generateSecretKey(properties.safeSessionKey()),
                objectMapper.writeValueAsString(expected));
        String cookieValue = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(encrypted.getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("zhaogang_session", cookieValue));

        assertThat(manager.resolve(request, new MockHttpServletResponse())).isEqualTo(expected);
    }

    private String cookieValue(String setCookie) {
        int valueStart = setCookie.indexOf('=') + 1;
        int valueEnd = setCookie.indexOf(';', valueStart);
        return setCookie.substring(valueStart, valueEnd);
    }
}

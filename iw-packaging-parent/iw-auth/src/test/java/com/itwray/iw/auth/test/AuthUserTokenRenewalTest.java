package com.itwray.iw.auth.test;

import com.itwray.iw.auth.controller.AuthenticationController;
import com.itwray.iw.auth.dao.AuthUserDao;
import com.itwray.iw.auth.model.AuthRedisKeyEnum;
import com.itwray.iw.auth.service.impl.AuthUserServiceImpl;
import com.itwray.iw.auth.service.impl.BaseDictServiceImpl;
import com.itwray.iw.starter.redis.RedisUtil;
import com.itwray.iw.web.client.AuthenticationClient;
import com.itwray.iw.web.dao.BaseDictDao;
import com.itwray.iw.web.exception.AuthorizedException;
import com.itwray.iw.web.utils.UserUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthUserTokenRenewalTest {

    private static final long TOKEN_TTL = AuthRedisKeyEnum.USER_TOKEN_KEY.getExpireTime();

    private final Map<String, Object> values = new HashMap<>();
    private final Map<String, Long> deadlines = new HashMap<>();
    private final AuthUserDao userDao = new AuthUserDao();
    private final AuthenticationController authentication = new AuthenticationController(new AuthUserServiceImpl(userDao));
    private long now;
    private RedisTemplate<String, Object> redis;
    private Object previousRedis;
    private Object previousAuthClient;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        previousAuthClient = ReflectionTestUtils.getField(UserUtils.class, "authenticationClient");
        previousRedis = ReflectionTestUtils.getField(RedisUtil.class, "redisTemplate");
        redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        SetOperations<String, Object> setOps = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);
        doAnswer(call -> {
            String key = call.getArgument(0);
            values.put(key, call.getArgument(1));
            deadlines.put(key, now + (Long) call.getArgument(2));
            return null;
        }).when(valueOps).set(anyString(), any(), anyLong(), eq(TimeUnit.SECONDS));
        when(setOps.add(anyString(), anyString())).thenAnswer(call -> {
            String key = call.getArgument(0);
            values.putIfAbsent(key, Boolean.TRUE);
            deadlines.putIfAbsent(key, Long.MAX_VALUE);
            return 1L;
        });
        when(redis.hasKey(anyString())).thenAnswer(call -> exists(call.getArgument(0)));
        when(valueOps.get(anyString())).thenAnswer(call -> {
            String key = call.getArgument(0);
            return exists(key) ? values.get(key) : null;
        });
        when(redis.expire(anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenAnswer(call -> {
            String key = call.getArgument(0);
            if (!exists(key)) {
                return false;
            }
            deadlines.put(key, now + (Long) call.getArgument(1));
            return true;
        });
        RedisUtil.setRedisTemplate(redis);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(RedisUtil.class, "redisTemplate", previousRedis);
        UserUtils.clearContext();
        ReflectionTestUtils.setField(UserUtils.class, "authenticationClient", previousAuthClient);
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void normalIdentityLookupKeepsAnActiveTokenValidPastItsOriginalDeadline() {
        String token = userDao.genericUserToken(42);
        now = TOKEN_TTL - 60;

        assertEquals(42, authentication.getUserIdByToken(token));
        assertEquals(now + TOKEN_TTL, deadlines.get(tokenKey(token)));

        now = TOKEN_TTL + 60;
        assertEquals(42, authentication.getUserIdByToken(token));
    }

    @Test
    void dictVersionPollingRenewsThroughTheSharedIdentityLookup() {
        String token = userDao.genericUserToken(42);
        AuthenticationClient client = mock(AuthenticationClient.class);
        when(client.getUserIdByToken(token)).thenAnswer(call -> authentication.getUserIdByToken(token));
        ReflectionTestUtils.setField(UserUtils.class, "authenticationClient", client);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dict/version");
        request.addHeader("iwtoken", token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        String versionKey = AuthRedisKeyEnum.USER_DICT_VERSION.getKey(42);
        values.put(versionKey, 7L);
        deadlines.put(versionKey, Long.MAX_VALUE);

        now = 30 * 60;
        new BaseDictServiceImpl(mock(BaseDictDao.class)).getDictVersion();

        verify(client).getUserIdByToken(token);
        assertEquals(now + TOKEN_TTL, deadlines.get(tokenKey(token)));
        assertEquals(now + TOKEN_TTL, deadlines.get(AuthRedisKeyEnum.USER_TOKEN_SET_KEY.getKey(42)));
    }

    @Test
    void activityOnOneDeviceDoesNotRenewOrInvalidateAnotherDevicesToken() {
        String activeToken = userDao.genericUserToken(42);
        String idleToken = userDao.genericUserToken(42);
        assertNotEquals(activeToken, idleToken);
        now = TOKEN_TTL - 60;

        assertEquals(42, authentication.getUserIdByToken(activeToken));
        assertTrue(exists(tokenKey(idleToken)));
        assertEquals(TOKEN_TTL, deadlines.get(tokenKey(idleToken)));

        now = TOKEN_TTL + 60;
        assertEquals(42, authentication.getUserIdByToken(activeToken));
        assertThrows(AuthorizedException.class, () -> authentication.getUserIdByToken(idleToken));
    }

    @Test
    void expiredAndRemovedTokensCannotBeRevivedByIdentityLookup() {
        String token = userDao.genericUserToken(42);
        clearInvocations(redis);
        now = TOKEN_TTL;

        assertThrows(AuthorizedException.class, () -> authentication.getUserIdByToken(token));
        assertThrows(AuthorizedException.class, () -> authentication.getUserIdByToken("removed-session"));
        verify(redis, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    private boolean exists(String key) {
        return values.containsKey(key) && deadlines.get(key) > now;
    }

    private String tokenKey(String token) {
        return AuthRedisKeyEnum.USER_TOKEN_KEY.getKey(token);
    }
}

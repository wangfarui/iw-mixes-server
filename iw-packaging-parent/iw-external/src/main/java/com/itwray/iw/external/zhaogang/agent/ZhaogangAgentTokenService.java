package com.itwray.iw.external.zhaogang.agent;

import com.itwray.iw.external.mapper.ZhaogangAgentTokenMapper;
import com.itwray.iw.external.zhaogang.ZhaogangSession;
import com.itwray.iw.external.zhaogang.agent.entity.ZhaogangAgentTokenEntity;
import com.itwray.iw.external.zhaogang.agent.ZhaogangAgentModels.TokenCreated;
import com.itwray.iw.external.zhaogang.agent.ZhaogangAgentModels.TokenStatus;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;

/** 工作台 Agent 令牌的生成、展示状态与 Bearer 鉴权。 */
@Service
public class ZhaogangAgentTokenService {

    static final String TOKEN_PREFIX = "zg_ag_";

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final ZhaogangAgentTokenMapper mapper;

    private final CodingCredentialService credentials;

    private final SecureRandom random = new SecureRandom();

    public ZhaogangAgentTokenService(ZhaogangAgentTokenMapper mapper, CodingCredentialService credentials) {
        this.mapper = mapper;
        this.credentials = credentials;
    }

    public TokenStatus status(ZhaogangSession session) {
        requireIdentity(session);
        ZhaogangAgentTokenEntity entity = mapper.find(session.teamId(), session.userId());
        return status(entity);
    }

    public TokenCreated create(ZhaogangSession session) {
        requireIdentity(session);
        if (!credentials.exists(session.teamId(), session.userId())) {
            throw new ZhaogangAgentCodingCredentialException(
                    "当前工作台账号尚未保存 CODING 令牌，请先在设置页重新绑定 CODING 令牌");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hint = mask(token);
        mapper.upsert(session.teamId(), session.userId(), session.userName(), session.avatar(), digest(token), hint);
        return new TokenCreated(token, new TokenStatus(true, hint, formatted(LocalDateTime.now()), null));
    }

    public void delete(ZhaogangSession session) {
        requireIdentity(session);
        mapper.delete(session.teamId(), session.userId());
    }

    public ZhaogangSession authenticate(String authorization) {
        String token = bearerToken(authorization);
        ZhaogangAgentTokenEntity entity = mapper.findByHash(digest(token));
        if (entity == null || entity.getCodingTeamId() == null || entity.getCodingTeamId() <= 0
                || entity.getCodingUserId() == null || entity.getCodingUserId() <= 0) {
            throw new ZhaogangAgentTokenException("工作台 Agent 令牌无效或已失效，请重新绑定");
        }
        String codingToken = credentials.token(entity.getCodingTeamId(), entity.getCodingUserId())
                .orElseThrow(() -> new ZhaogangAgentCodingCredentialException(
                        "当前工作台账号尚未保存 CODING 令牌，请先在设置页重新绑定 CODING 令牌"));
        mapper.touch(entity.getCodingTeamId(), entity.getCodingUserId(), LocalDateTime.now());
        return new ZhaogangSession(codingToken, entity.getCodingUserId(), entity.getUserName(), entity.getAvatar(),
                "", entity.getCodingTeamId());
    }

    public TokenStatus statusFor(long teamId, long userId) {
        return status(mapper.find(teamId, userId));
    }

    public static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    static String mask(String token) {
        if (StringUtils.isBlank(token)) {
            return "";
        }
        return StringUtils.left(token, Math.min(12, token.length())) + "••••"
                + StringUtils.right(token, Math.min(4, token.length()));
    }

    private String bearerToken(String authorization) {
        if (StringUtils.isBlank(authorization)) {
            throw new ZhaogangAgentTokenException("缺少工作台 Agent 令牌，请重新绑定");
        }
        String token = authorization.trim();
        if (!token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ZhaogangAgentTokenException("工作台 Agent 令牌格式无效，请重新绑定");
        }
        token = token.substring(7).trim();
        if (!token.startsWith(TOKEN_PREFIX) || token.length() > 128) {
            throw new ZhaogangAgentTokenException("工作台 Agent 令牌无效或已失效，请重新绑定");
        }
        return token;
    }

    private TokenStatus status(ZhaogangAgentTokenEntity entity) {
        if (entity == null) {
            return new TokenStatus(false, "", null, null);
        }
        return new TokenStatus(true, entity.getTokenHint(), formatted(entity.getCreatedAt()),
                formatted(entity.getLastUsedAt()));
    }

    private String formatted(LocalDateTime value) {
        return value == null ? null : TIME_FORMATTER.format(value.atZone(CHINA_ZONE));
    }

    private void requireIdentity(ZhaogangSession session) {
        if (session == null || session.teamId() == null || session.teamId() <= 0
                || session.userId() == null || session.userId() <= 0) {
            throw new IllegalArgumentException("当前工作台账号信息不完整，请重新绑定 CODING 令牌");
        }
    }
}

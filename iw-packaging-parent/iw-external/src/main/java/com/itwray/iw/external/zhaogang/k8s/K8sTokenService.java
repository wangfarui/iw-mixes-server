package com.itwray.iw.external.zhaogang.k8s;

import com.itwray.iw.external.mapper.ZhaogangK8sTokenMapper;
import com.itwray.iw.external.zhaogang.ZhaogangModels.K8sTokenDisplay;
import com.itwray.iw.external.zhaogang.ZhaogangModels.K8sTokenSource;
import com.itwray.iw.external.zhaogang.ZhaogangModels.K8sTokenStatus;
import com.itwray.iw.external.zhaogang.ZhaogangModels.K8sTokenValue;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.k8s.entity.K8sTokenEntity;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class K8sTokenService {

    private final ZhaogangK8sTokenMapper mapper;
    private final ZhaogangProperties properties;

    public K8sTokenService(ZhaogangK8sTokenMapper mapper, ZhaogangProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public K8sTokenStatus statuses(long teamId, long userId) {
        Map<String, Boolean> configured = new LinkedHashMap<>();
        Map<String, K8sTokenDisplay> credentials = new LinkedHashMap<>();
        List<K8sTokenEntity> entities = teamId <= 0 || userId <= 0
                ? List.of() : mapper.findAll(teamId, userId);
        for (K8sTokenEnvironment environment : K8sTokenEnvironment.values()) {
            String code = environment.code();
            String personalToken = entities.stream().filter(item -> code.equals(item.getEnvironment()))
                    .map(K8sTokenEntity::getTokenPlaintext).filter(StringUtils::isNotBlank).findFirst().orElse(null);
            EffectiveToken token = resolve(teamId, userId, code, personalToken);
            configured.put(code, token.source() != K8sTokenSource.NONE);
            credentials.put(code, new K8sTokenDisplay(token.source(), mask(token.value()), token.revision(),
                    teamId > 0 && userId > 0 && defaultToken(code) != null));
        }
        return new K8sTokenStatus(environments(), configured, credentials);
    }

    public K8sTokenValue token(long teamId, long userId, String environment) {
        K8sTokenEnvironment parsed = K8sTokenEnvironment.parse(environment);
        if (teamId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("Dev Hub 会话信息不完整");
        }
        K8sTokenEntity entity = mapper.find(teamId, userId, parsed.code());
        EffectiveToken token = resolve(teamId, userId, parsed.code(), entity == null ? null : entity.getTokenPlaintext());
        if (token.source() == K8sTokenSource.NONE) {
            throw new IllegalArgumentException("当前环境尚未配置 K8s Token");
        }
        return new K8sTokenValue(token.value(), token.revision());
    }

    private EffectiveToken resolve(long teamId, long userId, String environment, String personalToken) {
        if (teamId > 0 && userId > 0) {
            if (StringUtils.isNotBlank(personalToken)) {
                return new EffectiveToken(personalToken, K8sTokenSource.PERSONAL,
                        revision(teamId, userId, environment, K8sTokenSource.PERSONAL, personalToken));
            }
            String defaultToken = defaultToken(environment);
            if (defaultToken != null) {
                return new EffectiveToken(defaultToken, K8sTokenSource.DEFAULT,
                        revision(teamId, userId, environment, K8sTokenSource.DEFAULT, defaultToken));
            }
        }
        return new EffectiveToken(null, K8sTokenSource.NONE, null);
    }

    private String defaultToken(String environment) {
        return StringUtils.stripToNull(properties.getK8sDefaultTokens().get(environment));
    }

    private String revision(long teamId, long userId, String environment, K8sTokenSource source, String token) {
        // Opaque browser-side cache key. Scope it to the user even when they share the default Token.
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.safeSessionKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String input = "k8s-token:" + teamId + ":" + userId + ":" + environment + ":" + source + ":" + token;
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法生成 K8s Token 版本", e);
        }
    }

    private String mask(String token) {
        if (token == null) {
            return null;
        }
        return token.length() <= 6 ? "***" : StringUtils.left(token, 3) + "***" + StringUtils.right(token, 3);
    }

    public void upsert(long teamId, long userId, String environment, String token) {
        K8sTokenEnvironment parsed = K8sTokenEnvironment.parse(environment);
        String normalized = token == null ? "" : token.trim();
        if (teamId <= 0 || userId <= 0 || normalized.isBlank()) {
            throw new IllegalArgumentException("K8s Token 信息不完整");
        }
        mapper.upsert(teamId, userId, parsed.code(), normalized);
    }

    public void delete(long teamId, long userId, String environment) {
        K8sTokenEnvironment parsed = K8sTokenEnvironment.parse(environment);
        if (teamId > 0 && userId > 0) {
            mapper.delete(teamId, userId, parsed.code());
        }
    }

    private List<String> environments() {
        return Arrays.stream(K8sTokenEnvironment.values()).map(K8sTokenEnvironment::code).toList();
    }

    private record EffectiveToken(String value, K8sTokenSource source, String revision) {
        @Override
        public String toString() {
            return "EffectiveToken[source=" + source + ", revision=" + revision + "]";
        }
    }
}

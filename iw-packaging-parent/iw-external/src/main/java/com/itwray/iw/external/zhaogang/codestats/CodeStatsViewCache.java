package com.itwray.iw.external.zhaogang.codestats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
class CodeStatsViewCache {

    private static final String PREFIX = "zhaogang:code:v1:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ZhaogangProperties properties;

    CodeStatsViewCache(StringRedisTemplate redis, ObjectMapper objectMapper, ZhaogangProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    String scopeKey(CodeStatsModule.ScopeInfo scope) {
        return scope.codingTeamId() + ":" + scope.scope().name() + ":" + scope.scopeId();
    }

    <T> Optional<T> get(String key, Class<T> type) {
        try {
            String value = redis.opsForValue().get(PREFIX + key);
            return value == null ? Optional.empty() : Optional.of(objectMapper.readValue(value, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    void put(String key, Object value) {
        try {
            redis.opsForValue().set(PREFIX + key, objectMapper.writeValueAsString(value),
                    Duration.ofSeconds(Math.max(1, properties.getCodeScanCacheSeconds())));
        } catch (Exception ignored) {
            // Redis 不可用时仍返回实时查询结果。
        }
    }

    /** 可丢弃的分支/月提交快照：只保存规范化提交（邮箱哈希），不包含 PAT 或原始邮箱。 */
    void putBranchSnapshot(String key, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024) return;
            redis.opsForValue().set(PREFIX + key, json, Duration.ofDays(7));
        } catch (Exception ignored) {
            // 缓存不可用只增加下次扫描成本，不影响本次结果。
        }
    }

    long identityVersion(String scopeKey) {
        return counter(PREFIX + "identity-version:" + scopeKey);
    }

    void bumpIdentityVersion(String scopeKey) {
        try {
            redis.opsForValue().increment(PREFIX + "identity-version:" + scopeKey);
        } catch (RuntimeException ignored) {
            // 缓存版本不可用时不阻断身份写入。
        }
    }

    long scanVersion(String scopeKey, String month) {
        return counter(PREFIX + "scan-version:" + scopeKey + ":" + month);
    }

    void bumpScanVersion(String scopeKey, String month) {
        try {
            redis.opsForValue().increment(PREFIX + "scan-version:" + scopeKey + ":" + month);
        } catch (RuntimeException ignored) {
            // 缓存版本不可用时不阻断扫描完成。
        }
    }

    boolean userCooldownActive(String scopeKey, long userId, String month) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(PREFIX + "scan:user:" + scopeKey + ":" + userId + ":" + month));
        } catch (RuntimeException e) {
            return false;
        }
    }

    void markUserCooldown(String scopeKey, long userId, String month) {
        try {
            redis.opsForValue().set(PREFIX + "scan:user:" + scopeKey + ":" + userId + ":" + month, "1",
                    Duration.ofSeconds(Math.max(0, properties.getCodeUserCooldownSeconds())));
        } catch (RuntimeException ignored) {
            // 冷却标记不可用时仅丧失单用户频率限制。
        }
    }

    void markCancelled(long jobId) {
        try {
            redis.opsForValue().set(PREFIX + "scan:cancel:" + jobId, "1", Duration.ofHours(1));
        } catch (RuntimeException ignored) {
            // 取消失败由调用方处理。
        }
    }

    boolean cancelled(long jobId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(PREFIX + "scan:cancel:" + jobId));
        } catch (RuntimeException e) {
            return false;
        }
    }

    Optional<CachedProfile> profile(String tokenFingerprint) {
        return get("profile:" + tokenFingerprint, CachedProfile.class);
    }

    void putProfile(String tokenFingerprint, CachedProfile profile) {
        try {
            redis.opsForValue().set(PREFIX + "profile:" + tokenFingerprint,
                    objectMapper.writeValueAsString(profile),
                    Duration.ofHours(Math.max(1, properties.getCodeProfileCacheHours())));
        } catch (Exception ignored) {
            // profile 缓存失败不影响扫描。
        }
    }

    void markBackfillPending(String scopeKey, String month) {
        try {
            redis.opsForValue().set(PREFIX + "diff-backfill-pending:" + scopeKey + ":" + month, "1",
                    Duration.ofDays(7));
        } catch (RuntimeException ignored) {
            // 不阻断身份映射保存。
        }
    }

    boolean backfillPending(String scopeKey, String month) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(PREFIX + "diff-backfill-pending:" + scopeKey + ":" + month));
        } catch (RuntimeException e) {
            return false;
        }
    }

    void clearBackfillPending(String scopeKey, String month) {
        try {
            redis.delete(PREFIX + "diff-backfill-pending:" + scopeKey + ":" + month);
        } catch (RuntimeException ignored) {
            // 清理失败不影响主链路。
        }
    }

    private long counter(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0L : Long.parseLong(value);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    record CachedProfile(long userId, String name, String avatar, String emailHash, long teamId,
                         long cachedAtMillis) {
    }
}

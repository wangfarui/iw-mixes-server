package com.itwray.iw.external.zhaogang.worklog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Entries;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.RangeReport;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.EntriesSnapshot;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.MonthlySnapshot;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Scope;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
class WorklogViewCache {

    private static final String KEY_PREFIX = "zhaogang:worklog:v9:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ZhaogangProperties properties;
    private final Map<String, CompletableFuture<SnapshotLoad<MonthlySnapshot>>> monthlyLoads = new ConcurrentHashMap<>();

    private final Map<String, CompletableFuture<SnapshotLoad<RangeReport>>> rangeLoads = new ConcurrentHashMap<>();

    WorklogViewCache(StringRedisTemplate redis, ObjectMapper objectMapper, ZhaogangProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    MonthlySnapshot loadMonth(String key, boolean refresh, Supplier<MonthlySnapshot> loader) {
        return loadSnapshot(key, refresh, loader, MonthlySnapshot.class, monthlyLoads);
    }

    RangeReport loadRange(String key, boolean refresh, Supplier<RangeReport> loader) {
        return loadSnapshot(key, refresh, loader, RangeReport.class, rangeLoads);
    }

    private <T> T loadSnapshot(String key, boolean refresh, Supplier<T> loader, Class<T> type,
                               Map<String, CompletableFuture<SnapshotLoad<T>>> loads) {
        while (true) {
            CompletableFuture<SnapshotLoad<T>> flight = new CompletableFuture<>();
            CompletableFuture<SnapshotLoad<T>> running = loads.putIfAbsent(key, flight);
            if (running != null) {
                SnapshotLoad<T> result = awaitSnapshot(running);
                if (!refresh || result.refreshed()) return result.snapshot();
                // 普通查询可能复用了事项缓存，手动刷新需要重新查验。
                continue;
            }

            SnapshotLoad<T> result;
            try {
                Optional<T> cached = refresh ? Optional.empty() : getByKey(key, type);
                if (cached.isPresent()) {
                    result = new SnapshotLoad<>(cached.get(), false);
                } else {
                    T snapshot = loader.get();
                    putByKey(key, snapshot);
                    result = new SnapshotLoad<>(snapshot, refresh);
                }
            } catch (RuntimeException | Error e) {
                loads.remove(key, flight);
                flight.completeExceptionally(e);
                throw e;
            }
            loads.remove(key, flight);
            flight.complete(result);
            return result.snapshot();
        }
    }

    private <T> SnapshotLoad<T> awaitSnapshot(CompletableFuture<SnapshotLoad<T>> running) {
        try {
            return running.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            if (e.getCause() instanceof Error cause) throw cause;
            throw e;
        }
    }

    Optional<Entries> getEntries(String key) {
        return getByKey(key, Entries.class);
    }

    EntriesSnapshot loadEntriesSnapshot(String key, boolean refresh, Supplier<EntriesSnapshot> loader) {
        if (!refresh) {
            Optional<EntriesSnapshot> cached = getByKey(key, EntriesSnapshot.class);
            if (cached.isPresent()) return cached.get();
        }
        EntriesSnapshot snapshot = loader.get();
        putByKey(key, snapshot);
        return snapshot;
    }

    private <T> Optional<T> getByKey(String key, Class<T> type) {
        try {
            String value = redis.opsForValue().get(key);
            if (value == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(value, type));
        } catch (JsonProcessingException e) {
            try {
                redis.delete(key);
            } catch (RuntimeException ignored) {
                // 缓存清理失败不影响实时查询。
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    void putEntries(String key, Entries entries) {
        // 有待补齐详情的响应可以先展示，后台结束后重读时使用已查到的事项。
        if (entries.issueCheck() == null || entries.issueCheck().checkId() == null && !entries.issueCheck().incomplete()) {
            putByKey(key, entries);
        }
    }

    private void putByKey(String key, Object result) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(result),
                    Duration.ofSeconds(Math.max(1, properties.getWorklogCacheSeconds())));
        } catch (JsonProcessingException | RuntimeException ignored) {
            // Redis 短暂不可用时仍返回实时查询结果。
        }
    }

    String monthlyKey(WorklogModule.Context context, Scope scope, long scopeId, YearMonth month,
                      LocalDate today, int calendarVersion, Map<Long, Set<LocalDate>> leaveDates,
                      List<WorklogModule.MemberCredential> members, long deletionRevision) {
        String leaveMaterial = leaveDates.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue().stream().sorted()
                        .map(LocalDate::toString).collect(Collectors.joining(",")))
                .collect(Collectors.joining("|"));
        return KEY_PREFIX + "monthly:" + context.codingTeamId() + ":" + context.userId() + ":"
                + CodingCredentialService.fingerprint(context.token()) + ":" + memberFingerprint(members)
                + ":" + scope.name() + ":" + scopeId + ":" + month
                + ":deleted-" + deletionRevision + ":as-of-" + today + ":calendar-" + calendarVersion + ":leave-" + digest(leaveMaterial);
    }

    String rangeKey(WorklogModule.Context context, Scope scope, long scopeId, LocalDate from, LocalDate to,
                    LocalDate today, String calendarVersions, Map<Long, Set<LocalDate>> leaveDates,
                    List<WorklogModule.MemberCredential> members, long deletionRevision) {
        // Reuse credential/member/leave/deletion isolation and add both inclusive bounds and every calendar version.
        return monthlyKey(context, scope, scopeId, YearMonth.from(from), today, 0, leaveDates, members, deletionRevision)
                + ":range-v1:" + from + ":" + to + ":calendars-" + digest(calendarVersions);
    }

    String entriesKey(WorklogModule.Context context, Scope scope, long scopeId, LocalDate from, LocalDate to,
                      List<WorklogModule.MemberCredential> members, long deletionRevision) {
        return KEY_PREFIX + "entries:" + context.codingTeamId() + ":" + context.userId() + ":"
                + CodingCredentialService.fingerprint(context.token()) + ":" + memberFingerprint(members) + ":"
                + scope.name() + ":" + scopeId + ":" + from + ":" + to + ":deleted-" + deletionRevision;
    }

    private String memberFingerprint(List<WorklogModule.MemberCredential> members) {
        String material = members.stream()
                .sorted(java.util.Comparator.comparingLong(WorklogModule.MemberCredential::userId))
                .map(member -> member.userId() + ":" + member.managedStatistics() + ":" + (member.token() == null || member.token().isBlank()
                        ? "missing" : CodingCredentialService.fingerprint(member.token())))
                .collect(Collectors.joining("|"));
        return digest(material);
    }

    private String digest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", e);
        }
    }
    private record SnapshotLoad<T>(T snapshot, boolean refreshed) {
    }
}

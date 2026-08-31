package com.itwray.iw.external.zhaogang.worklog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Entries;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Absence;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Scope;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Statistics;
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
import java.util.Optional;
import java.util.stream.Collectors;

@Component
class WorklogViewCache {

    private static final String KEY_PREFIX = "zhaogang:worklog:v2:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ZhaogangProperties properties;

    WorklogViewCache(StringRedisTemplate redis, ObjectMapper objectMapper, ZhaogangProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    Optional<Statistics> getStatistics(WorklogModule.Context context, Scope scope, long scopeId, YearMonth month,
                                       int calendarVersion, List<WorklogModule.MemberCredential> members) {
        return getStatistics(context, scope, scopeId, month, calendarVersion, 0, members);
    }

    Optional<Statistics> getStatistics(WorklogModule.Context context, Scope scope, long scopeId, YearMonth month,
                                       int calendarVersion, int leaveVersion, List<WorklogModule.MemberCredential> members) {
        return getByKey(statisticsKey(context, scope, scopeId, month, calendarVersion, leaveVersion, members), Statistics.class);
    }

    Optional<Entries> getEntries(WorklogModule.Context context, Scope scope, long scopeId, LocalDate from, LocalDate to,
                                 List<WorklogModule.MemberCredential> members) {
        return getByKey(entriesKey(context, scope, scopeId, from, to, members), Entries.class);
    }

    Optional<Absence> getAbsences(WorklogModule.Context context, Scope scope, long scopeId, YearMonth month,
                                  int calendarVersion, List<WorklogModule.MemberCredential> members) {
        return getAbsences(context, scope, scopeId, month, calendarVersion, 0, members);
    }

    Optional<Absence> getAbsences(WorklogModule.Context context, Scope scope, long scopeId, YearMonth month,
                                  int calendarVersion, int leaveVersion, List<WorklogModule.MemberCredential> members) {
        return getByKey(absencesKey(context, scope, scopeId, month, calendarVersion, leaveVersion, members), Absence.class);
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

    void putStatistics(WorklogModule.Context context, Scope scope, long scopeId, YearMonth month,
                       int calendarVersion, List<WorklogModule.MemberCredential> members, Statistics statistics) {
        putStatistics(context, scope, scopeId, month, calendarVersion, 0, members, statistics);
    }

    void putStatistics(WorklogModule.Context context, Scope scope, long scopeId, YearMonth month,
                       int calendarVersion, int leaveVersion, List<WorklogModule.MemberCredential> members,
                       Statistics statistics) {
        putByKey(statisticsKey(context, scope, scopeId, month, calendarVersion, leaveVersion, members), statistics);
    }

    void putEntries(WorklogModule.Context context, Scope scope, long scopeId, LocalDate from, LocalDate to,
                    List<WorklogModule.MemberCredential> members, Entries entries) {
        putByKey(entriesKey(context, scope, scopeId, from, to, members), entries);
    }

    void putAbsences(WorklogModule.Context context, Scope scope, long scopeId, YearMonth month,
                     int calendarVersion, List<WorklogModule.MemberCredential> members, Absence absence) {
        putAbsences(context, scope, scopeId, month, calendarVersion, 0, members, absence);
    }

    void putAbsences(WorklogModule.Context context, Scope scope, long scopeId, YearMonth month,
                     int calendarVersion, int leaveVersion, List<WorklogModule.MemberCredential> members,
                     Absence absence) {
        putByKey(absencesKey(context, scope, scopeId, month, calendarVersion, leaveVersion, members), absence);
    }

    private void putByKey(String key, Object valueObject) {
        try {
            String value = objectMapper.writeValueAsString(valueObject);
            redis.opsForValue().set(key, value,
                    Duration.ofSeconds(Math.max(1, properties.getWorklogCacheSeconds())));
        } catch (JsonProcessingException | RuntimeException ignored) {
            // Redis 短暂不可用时仍返回实时查询结果，避免影响工时主链路。
        }
    }

    String statisticsKey(WorklogModule.Context context, Scope scope, long scopeId, YearMonth month,
                         int calendarVersion, List<WorklogModule.MemberCredential> members) {
        return statisticsKey(context, scope, scopeId, month, calendarVersion, 0, members);
    }

    String statisticsKey(WorklogModule.Context context, Scope scope, long scopeId, YearMonth month,
                         int calendarVersion, int leaveVersion, List<WorklogModule.MemberCredential> members) {
        return KEY_PREFIX + "statistics:" + context.userId() + ":" + memberFingerprint(members) + ":"
                + scope.name() + ":" + scopeId + ":" + month + ":calendar-" + calendarVersion
                + ":leave-" + leaveVersion;
    }

    String entriesKey(WorklogModule.Context context, Scope scope, long scopeId, LocalDate from, LocalDate to,
                      List<WorklogModule.MemberCredential> members) {
        return KEY_PREFIX + "entries:" + context.userId() + ":" + memberFingerprint(members) + ":"
                + scope.name() + ":" + scopeId + ":" + from + ":" + to;
    }

    String absencesKey(WorklogModule.Context context, Scope scope, long scopeId, YearMonth month,
                       int calendarVersion, List<WorklogModule.MemberCredential> members) {
        return absencesKey(context, scope, scopeId, month, calendarVersion, 0, members);
    }

    String absencesKey(WorklogModule.Context context, Scope scope, long scopeId, YearMonth month,
                       int calendarVersion, int leaveVersion, List<WorklogModule.MemberCredential> members) {
        return KEY_PREFIX + "absences:" + context.userId() + ":" + memberFingerprint(members) + ":"
                + scope.name() + ":" + scopeId + ":" + month + ":calendar-" + calendarVersion
                + ":leave-" + leaveVersion;
    }

    private String memberFingerprint(List<WorklogModule.MemberCredential> members) {
        String material = members.stream()
                .sorted(java.util.Comparator.comparingLong(WorklogModule.MemberCredential::userId))
                .map(member -> member.userId() + ":" + (member.token() == null || member.token().isBlank()
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
}

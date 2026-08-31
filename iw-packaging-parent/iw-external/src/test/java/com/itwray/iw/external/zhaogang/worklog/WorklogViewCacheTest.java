package com.itwray.iw.external.zhaogang.worklog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Coverage;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Entries;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Scope;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorklogViewCacheTest {

    private final List<WorklogModule.MemberCredential> members = List.of(
            new WorklogModule.MemberCredential(100L, "用户", "avatar", "secret-token"));

    @Test
    void statisticsAndEntriesKeysAreSeparateAndDoNotExposeToken() {
        WorklogViewCache cache = cache(mock(StringRedisTemplate.class), new ZhaogangProperties());
        WorklogModule.Context context = context();

        String statisticsKey = cache.statisticsKey(context, Scope.SELF, 100L, YearMonth.of(2026, 8), 3, members);
        String entriesKey = cache.entriesKey(context, Scope.SELF, 100L,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), members);

        assertThat(statisticsKey).contains(":statistics:", ":SELF:100:2026-08").doesNotContain("secret-token");
        assertThat(entriesKey).contains(":entries:", ":SELF:100:2026-08-01:2026-08-07").doesNotContain("secret-token");
        assertThat(entriesKey).isNotEqualTo(statisticsKey);
    }

    @Test
    void storesStatisticsAndEntriesWithConfiguredRedisTtl() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ZhaogangProperties properties = new ZhaogangProperties();
        properties.setWorklogCacheSeconds(60);
        WorklogViewCache cache = cache(redis, properties);
        Statistics statistics = statistics();
        Entries entries = entries();

        cache.putStatistics(context(), Scope.SELF, 100L, YearMonth.of(2026, 8), 3, members, statistics);
        cache.putEntries(context(), Scope.SELF, 100L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), members, entries);

        verify(values).set(cache.statisticsKey(context(), Scope.SELF, 100L, YearMonth.of(2026, 8), 3, members),
                new ObjectMapper().findAndRegisterModules().writeValueAsString(statistics), Duration.ofSeconds(60));
        verify(values).set(cache.entriesKey(context(), Scope.SELF, 100L, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 7), members), new ObjectMapper().findAndRegisterModules().writeValueAsString(entries),
                Duration.ofSeconds(60));
    }

    @Test
    void readsStatisticsAndEntriesFromRedis() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        WorklogViewCache cache = new WorklogViewCache(redis, objectMapper, new ZhaogangProperties());
        Statistics statistics = statistics();
        Entries entries = entries();
        when(values.get(anyString())).thenReturn(objectMapper.writeValueAsString(statistics), objectMapper.writeValueAsString(entries));

        assertThat(cache.getStatistics(context(), Scope.SELF, 100L, YearMonth.of(2026, 8), 3, members)).contains(statistics);
        assertThat(cache.getEntries(context(), Scope.SELF, 100L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), members))
                .contains(entries);
    }

    private WorklogViewCache cache(StringRedisTemplate redis, ZhaogangProperties properties) {
        return new WorklogViewCache(redis, new ObjectMapper().findAndRegisterModules(), properties);
    }

    private WorklogModule.Context context() {
        return new WorklogModule.Context("secret-token", 100L, "用户", "avatar", 10L,
                "g-iijw5014", "https://g-iijw5014.coding.net");
    }

    private Statistics statistics() {
        return new Statistics(new Coverage(Scope.SELF, null, 1, 0, false, 0, ""), "2026-08",
                "2026-08-26T12:00:00+08:00", List.of(), List.of());
    }

    private Entries entries() {
        return new Entries(new Coverage(Scope.SELF, null, 1, 0, false, 0, ""), "2026-08-01", "2026-08-08",
                "2026-08-26T12:00:00+08:00", BigDecimal.ZERO, List.of());
    }
}

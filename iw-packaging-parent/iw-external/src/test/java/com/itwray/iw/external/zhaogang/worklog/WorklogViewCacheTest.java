package com.itwray.iw.external.zhaogang.worklog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Absence;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Coverage;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.MonthlySnapshot;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Entries;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Scope;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.IssueCheck;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorklogViewCacheTest {

    private final List<WorklogModule.MemberCredential> members = List.of(
            new WorklogModule.MemberCredential(100L, "用户", "avatar", "secret-token"));

    @Test
    void monthlyAndEntriesKeysAreSeparateAndDoNotExposeToken() {
        WorklogViewCache cache = cache(mock(StringRedisTemplate.class), new ZhaogangProperties());
        WorklogModule.Context context = context();

        String monthlyKey = monthlyKey(cache);
        String entriesKey = cache.entriesKey(context, Scope.SELF, 100L,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), members, 0);

        assertThat(monthlyKey).contains(":monthly:", ":SELF:100:2026-08").doesNotContain("secret-token");
        assertThat(entriesKey).contains(":entries:", ":SELF:100:2026-08-01:2026-08-07").doesNotContain("secret-token");
        assertThat(entriesKey).isNotEqualTo(monthlyKey);
    }

    @Test
    void teamReportsKeepCheckReferencesSeparateAcrossViewerCredentials() {
        WorklogViewCache cache = cache(mock(StringRedisTemplate.class), new ZhaogangProperties());
        var viewer = context();
        var otherSession = new WorklogModule.Context("other-viewer-token", viewer.userId(), viewer.userName(),
                viewer.avatar(), viewer.codingTeamId(), viewer.codingTeamKey(), viewer.codingTeamHost());
        var month = YearMonth.of(2026, 8);
        var today = month.atDay(26);
        // 团队成员凭证相同，状态引用仍须属于实际发起查询的查看者。
        assertThat(cache.monthlyKey(viewer, Scope.WORKBENCH_TEAM, 8, month, today, 3, Map.of(), members, 0))
                .isNotEqualTo(cache.monthlyKey(otherSession, Scope.WORKBENCH_TEAM, 8, month, today, 3,
                        Map.of(), members, 0));
        assertThat(cache.entriesKey(viewer, Scope.WORKBENCH_TEAM, 8, month.atDay(1), today, members, 0))
                .isNotEqualTo(cache.entriesKey(otherSession, Scope.WORKBENCH_TEAM, 8, month.atDay(1), today, members, 0));
    }

    @Test
    void storesMonthlySnapshotAndEntriesWithConfiguredRedisTtl() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ZhaogangProperties properties = new ZhaogangProperties();
        properties.setWorklogCacheSeconds(60);
        WorklogViewCache cache = cache(redis, properties);
        MonthlySnapshot snapshot = snapshot();
        Entries entries = entries();

        cache.loadMonth(monthlyKey(cache), true, () -> snapshot);
        String entriesKey = cache.entriesKey(context(), Scope.SELF, 100L, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 7), members, 0);
        cache.putEntries(entriesKey, entries);

        verify(values).set(monthlyKey(cache),
                new ObjectMapper().findAndRegisterModules().writeValueAsString(snapshot), Duration.ofSeconds(60));
        verify(values).set(cache.entriesKey(context(), Scope.SELF, 100L, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 7), members, 0), new ObjectMapper().findAndRegisterModules().writeValueAsString(entries),
                Duration.ofSeconds(60));
    }

    @Test
    void pendingEntriesAreNotCachedBeforeTitlesAreReady() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        WorklogViewCache cache = cache(redis, new ZhaogangProperties());
        Entries entries = entries();
        cache.putEntries("entries", new Entries(entries.coverage(), entries.from(), entries.toExclusive(),
                entries.syncedAt(), entries.totalHours(), entries.items(), new IssueCheck(0, "pending")));

        verify(redis, never()).opsForValue();
    }

    @Test
    void readsMonthlySnapshotAndEntriesFromRedis() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        WorklogViewCache cache = new WorklogViewCache(redis, objectMapper, new ZhaogangProperties());
        MonthlySnapshot snapshot = snapshot();
        Entries entries = entries();
        when(values.get(anyString())).thenReturn(objectMapper.writeValueAsString(snapshot), objectMapper.writeValueAsString(entries));

        assertThat(cache.loadMonth(monthlyKey(cache), false, () -> {
            throw new AssertionError("Expected cached monthly snapshot");
        })).isEqualTo(snapshot);
        assertThat(cache.getEntries(cache.entriesKey(context(), Scope.SELF, 100L, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 7), members, 0)))
                .contains(entries);
    }

    private WorklogViewCache cache(StringRedisTemplate redis, ZhaogangProperties properties) {
        return new WorklogViewCache(redis, new ObjectMapper().findAndRegisterModules(), properties);
    }

    private WorklogModule.Context context() {
        return new WorklogModule.Context("secret-token", 100L, "用户", "avatar", 10L,
                "g-iijw5014", "https://g-iijw5014.coding.net");
    }

    @Test
    void concurrentRefreshesJoinAnInFlightForcedFetch() throws Exception {
        WorklogViewCache cache = cache(mock(StringRedisTemplate.class), new ZhaogangProperties());
        String key = monthlyKey(cache);
        MonthlySnapshot snapshot = snapshot();
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch refreshing = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> cache.loadMonth(key, true, () -> {
                loading.countDown();
                await(release);
                return snapshot;
            }));
            await(loading);
            var second = executor.submit(() -> {
                refreshing.countDown();
                return cache.loadMonth(key, true, () -> {
                    throw new AssertionError("Concurrent refresh duplicated an active CODING fetch");
                });
            });
            await(refreshing);
            assertThatThrownBy(() -> second.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(snapshot);
            assertThat(second.get(5, TimeUnit.SECONDS)).isSameAs(snapshot);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void monthlyKeyChangesWithDateCalendarLeaveAndCredentials() {
        WorklogViewCache cache = cache(mock(StringRedisTemplate.class), new ZhaogangProperties());
        YearMonth month = YearMonth.of(2026, 8);
        LocalDate today = month.atDay(26);
        String key = monthlyKey(cache);

        assertThat(cache.monthlyKey(context(), Scope.SELF, 100L, month, today, 3, Map.of(), members, 1))
                .isNotEqualTo(key);
        assertThat(cache.monthlyKey(context(), Scope.SELF, 100L, month, today.plusDays(1), 3, Map.of(), members, 0))
                .isNotEqualTo(key);
        assertThat(cache.monthlyKey(context(), Scope.SELF, 100L, month, today, 4, Map.of(), members, 0))
                .isNotEqualTo(key);
        assertThat(cache.monthlyKey(context(), Scope.SELF, 100L, month, today, 3,
                Map.of(100L, Set.of(month.atDay(24))), members, 0)).isNotEqualTo(key);
        assertThat(cache.monthlyKey(context(), Scope.SELF, 100L, month, today, 3, Map.of(),
                List.of(new WorklogModule.MemberCredential(100L, "用户", "avatar", "rotated-token")), 0))
                .isNotEqualTo(key);
    }

    private String monthlyKey(WorklogViewCache cache) {
        return cache.monthlyKey(context(), Scope.SELF, 100L, YearMonth.of(2026, 8),
                LocalDate.of(2026, 8, 26), 3, Map.of(), members, 0);
    }

    private MonthlySnapshot snapshot() {
        Coverage coverage = new Coverage(Scope.SELF, null, 1, 0, false, 0, "");
        String syncedAt = "2026-08-26T12:00:00+08:00";
        return new MonthlySnapshot(new Statistics(coverage, "2026-08", syncedAt, List.of(), List.of()),
                new Absence(coverage, "2026-08", "2026-08-01", "2026-08-26", syncedAt, List.of()));
    }

    private Entries entries() {
        return new Entries(new Coverage(Scope.SELF, null, 1, 0, false, 0, ""), "2026-08-01", "2026-08-08",
                "2026-08-26T12:00:00+08:00", BigDecimal.ZERO, List.of());
    }
}

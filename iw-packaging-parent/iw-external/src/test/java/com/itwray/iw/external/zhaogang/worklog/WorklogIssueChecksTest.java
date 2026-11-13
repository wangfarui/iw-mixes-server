package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader;
import com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader.IssueKey;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Worklog;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.WorklogPage;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarDefaults;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Scope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorklogIssueChecksTest {

    @Test
    void persistsOnlyUnambiguousMissingIdsAndRetainsFailuresAndConflictingEvidence() throws Exception {
        var coding = mock(CodingOpenApiPort.class);
        var deleted = mock(WorklogDeletedIssues.class);
        var stored = new AtomicReference<Map<Long, IssueKey>>();
        var persisted = new CountDownLatch(1);
        doAnswer(call -> {
            stored.set(Map.copyOf(call.getArgument(1)));
            persisted.countDown();
            return null;
        }).when(deleted).record(eq(10L), anyMap());
        var candidates = new LinkedHashMap<IssueKey, Set<Long>>();
        for (long code = 1; code <= 8; code++) {
            candidates.put(new IssueKey("project", code), Set.of(code));
        }
        candidates.put(new IssueKey("project", 2), Set.of());
        candidates.put(new IssueKey("project", 3), Set.of(3L, 30L));
        for (long code : List.of(1L, 2L, 3L, 8L)) {
            when(coding.issue("member", "project", code)).thenThrow(
                    new CodingOpenApiException("DescribeIssue", "InvalidParameterValue", "issue_not_exist"));
        }
        when(coding.issue("member", "project", 4)).thenThrow(
                new CodingOpenApiException("DescribeIssue", "UnauthorizedOperation", "permission denied"));
        when(coding.issue("member", "project", 5)).thenThrow(new IllegalStateException("timeout"));
        when(coding.issue("member", "project", 6)).thenThrow(
                new CodingOpenApiException("DescribeIssue", "RequestLimitExceeded", "rate limited"));
        // 项目 7 的空响应和缺少内部 ID、冲突 ID 均不得永久标记。
        when(coding.issue("other-member", "project", 8)).thenReturn(new CodingOpenApiPort.Issue(
                8, "MISSION", "任务", 1, "仍存在", "项目", false, 8, "", "", BigDecimal.ZERO));
        var context = new WorklogModule.Context("viewer", 100L, "成员", "", 10L, "fixture", "fixture.invalid");
        try (var loader = new CodingIssueSnapshotLoader(coding, new ZhaogangProperties())) {
            var checks = new WorklogIssueChecks(deleted, loader);
            try {
                checks.enqueue(context, null, Map.of("member", candidates,
                        "other-member", Map.of(new IssueKey("project", 8), Set.of(8L))), false);
                assertThat(persisted.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(stored.get()).containsOnlyKeys(1L);
            } finally {
                checks.stop();
            }
        }
    }

    @Test
    void returnsMonthWhileCodingIsBlockedThenFiltersOnlyConfirmedDeletedInternalId() throws Exception {
        var coding = mock(CodingOpenApiPort.class);
        var deleted = mock(WorklogDeletedIssues.class);
        var stored = new AtomicReference<Map<Long, IssueKey>>(Map.of());
        when(deleted.find(eq(10L), anyCollection())).thenAnswer(call -> stored.get().keySet());
        var persisted = new CountDownLatch(1);
        doAnswer(call -> {
            stored.set(Map.copyOf(call.getArgument(1)));
            persisted.countDown();
            return null;
        }).when(deleted).record(eq(10L), anyMap());
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(coding.issue("member-pat", "deleted-project", 8611)).thenAnswer(call -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("查验未释放");
            throw new CodingOpenApiException("DescribeIssue", "InvalidParameterValue", "issue_not_exist");
        });
        when(coding.issue("member-pat", "other-project", 8611)).thenThrow(
                new CodingOpenApiException("DescribeIssue", "UnauthorizedOperation", "permission denied"));
        long date = Instant.parse("2026-11-05T04:00:00Z").toEpochMilli();
        when(coding.worklogPage(eq("member-pat"), anyLong(), anyLong(), eq(100L), eq(0), eq(1000)))
                .thenReturn(new WorklogPage(List.of(
                        new Worklog(1, 34996761, 8611, "deleted-project", 100, BigDecimal.valueOf(4), "", date, date, date),
                        new Worklog(2, 12345, 8611, "other-project", 100, BigDecimal.valueOf(7), "", date, date, date))));
        var context = new WorklogModule.Context("viewer-pat", 100L, "成员", "", 10L, "fixture", "fixture.invalid");
        var members = List.of(new WorklogModule.MemberCredential(100L, "成员", "", "member-pat"));
        var clock = Clock.fixed(Instant.parse("2026-11-13T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
        var month = YearMonth.of(2026, 11);
        var schedule = WorkCalendarDefaults.schedule(month);
        var caller = Executors.newSingleThreadExecutor();
        try (var loader = new CodingIssueSnapshotLoader(coding, new ZhaogangProperties())) {
            var checks = new WorklogIssueChecks(deleted, loader);
            try {
                var service = new WorklogQueryService(coding, new CodingIssueLinkBuilder(), clock, deleted, checks);
                var request = caller.submit(() -> service.queryMonth(context, Scope.WORKBENCH_TEAM, 8L,
                        members, month, schedule, Map.of(), service.today(), 0, false));
                var snapshot = request.get(2, TimeUnit.SECONDS);
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(snapshot.statistics().dailyTotals().get(4).hours()).isEqualByComparingTo("11");
                String id = snapshot.statistics().issueCheck().checkId();
                assertThat(checks.isPending(context, 8L, id)).isTrue();
                assertThat(checks.isPending(context, 9L, id)).isFalse();

                release.countDown();
                assertThat(persisted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(stored.get()).containsOnlyKeys(34996761L);
                var updated = service.queryMonth(context, Scope.WORKBENCH_TEAM, 8L,
                        members, month, schedule, Map.of(), service.today(), 1, false);
                assertThat(updated.statistics().dailyTotals().get(4).hours()).isEqualByComparingTo("7");
                assertThat(updated.absences().members().get(0).days()).anyMatch(day ->
                        day.date().equals("2026-11-05") && day.hours().compareTo(BigDecimal.valueOf(7)) == 0);
            } finally {
                release.countDown();
                checks.stop();
            }
        } finally {
            caller.shutdownNow();
        }
    }
}

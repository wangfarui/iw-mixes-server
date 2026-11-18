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
import java.time.LocalDate;
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
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorklogIssueChecksTest {

    @Test
    void personalDetailsAdvanceWhileFourteenManagedMembersShareOnePat() throws Exception {
        var coding = mock(CodingOpenApiPort.class);
        var deleted = mock(WorklogDeletedIssues.class);
        var managedStarted = new CountDownLatch(4);
        var releaseManaged = new CountDownLatch(1);
        var personalStarted = new CountDownLatch(1);
        var links = new CodingIssueLinkBuilder();
        var instant = Instant.parse("2026-11-05T04:00:00Z");
        long millis = instant.toEpochMilli();
        var clock = Clock.fixed(instant.plusSeconds(86400), ZoneId.of("Asia/Shanghai"));
        var date = LocalDate.of(2026, 11, 5);
        var month = YearMonth.from(date);
        var viewer = new WorklogModule.Context("personal", 1, "成员", "", 1, "fixture", "https://fixture.invalid");
        var members = LongStream.rangeClosed(2, 15)
                .mapToObj(id -> new WorklogModule.MemberCredential(id, "托管成员", "", "admin", true)).toList();
        when(coding.worklogPage(anyString(), anyLong(), anyLong(), anyLong(), eq(0), eq(1000)))
                .thenAnswer(call -> {
                    long user = call.getArgument(3);
                    return new WorklogPage(List.of(new Worklog(user, user, user, "project", user,
                            BigDecimal.ONE, "备注", millis, millis, millis)));
                });
        when(coding.issue(eq("admin"), eq("project"), anyLong())).thenAnswer(call -> {
            managedStarted.countDown();
            if (!releaseManaged.await(5, TimeUnit.SECONDS)) throw new AssertionError("managed titles not released");
            return new CodingOpenApiPort.Issue(call.getArgument(2), "MISSION", "任务", "团队事项", "项目", false);
        });
        when(coding.issue("personal", "project", 1)).thenAnswer(call -> {
            personalStarted.countDown();
            return new CodingOpenApiPort.Issue(1, "MISSION", "任务", "个人事项", "项目", false);
        });
        try (var loader = new CodingIssueSnapshotLoader(coding, new ZhaogangProperties())) {
            var checks = new WorklogIssueChecks(deleted, loader, links);
            try {
                var service = new WorklogQueryService(coding, links, clock, deleted, checks);
                var team = new CodingOpenApiPort.Team(18, "团队", viewer.codingTeamHost());
                var stats = service.queryMonth(viewer, Scope.WORKBENCH_TEAM, 18L, members, month,
                        WorkCalendarDefaults.schedule(month), Map.of(), service.today(), 0, false);
                assertThat(managedStarted.await(2, TimeUnit.SECONDS)).isTrue();
                var teamRows = service.queryEntries(viewer, team, Scope.WORKBENCH_TEAM, 18L, members,
                        date, date.plusDays(1), 0, false);
                assertThat(teamRows.items()).hasSize(14);
                var self = service.queryEntries(viewer, team, Scope.SELF, null,
                        List.of(new WorklogModule.MemberCredential(1, "成员", "", "personal")), date, date.plusDays(1), 0, false);
                assertThat(self.items()).hasSize(1);
                assertThat(personalStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(checks.progress(viewer, 18L, stats.statistics().issueCheck().checkId()).pending()).isTrue();
                assertThat(checks.progress(viewer, 18L, teamRows.issueCheck().checkId()).pending()).isTrue();
                releaseManaged.countDown();
                var complete = progressUntil(checks, viewer, teamRows.issueCheck().checkId(), 14, false);
                assertThat(complete.pending()).isFalse();
                assertThat(complete.updates()).hasSize(14);
            } finally {
                releaseManaged.countDown();
                checks.stop();
            }
        }
    }

    @Test
    void entriesExposeCompletedRowsToTheirOwnerWhileOtherTitlesAreStillPending() throws Exception {
        var coding = mock(CodingOpenApiPort.class);
        var deleted = mock(WorklogDeletedIssues.class);
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        when(coding.issue("admin", "project", 201L)).thenAnswer(call -> {
            if (!blocked.await(5, TimeUnit.SECONDS)) throw new AssertionError("second title did not start");
            entered.countDown();
            return new CodingOpenApiPort.Issue(201, "MISSION", "任务", "第一个标题", "真实项目", false);
        });
        when(coding.issue("admin", "project", 202L)).thenAnswer(call -> {
            blocked.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("second title not released");
            return new CodingOpenApiPort.Issue(202, "MISSION", "任务", "第二个标题", "真实项目", false);
        });
        long time = Instant.parse("2026-11-05T04:00:00Z").toEpochMilli();
        when(coding.worklogPage(eq("admin"), anyLong(), anyLong(), eq(10L), eq(0), eq(1000)))
                .thenReturn(new WorklogPage(List.of(
                        new Worklog(42, 142, 201, "project", 10, BigDecimal.ONE, "第一个备注", time, time, time),
                        new Worklog(41, 141, 202, "project", 10, BigDecimal.ONE, "第二个备注", time, time, time))));
        var viewer = new WorklogModule.Context("personal", 10, "成员", "", 1, "fixture", "https://fixture.invalid");
        var another = new WorklogModule.Context("different-pat", 10, "成员", "", 1, "fixture", "https://fixture.invalid");
        var links = new CodingIssueLinkBuilder();
        var date = LocalDate.of(2026, 11, 5);
        var clock = Clock.fixed(Instant.parse("2026-11-10T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
        try (var loader = new CodingIssueSnapshotLoader(coding, new ZhaogangProperties())) {
            var checks = new WorklogIssueChecks(deleted, loader, links);
            try {
                var service = new WorklogQueryService(coding, links, clock, deleted, checks);
                var rows = service.queryEntries(viewer, new CodingOpenApiPort.Team(18, "团队", viewer.codingTeamHost()),
                        Scope.WORKBENCH_TEAM, 18L, List.of(new WorklogModule.MemberCredential(10, "成员", "", "admin", true)),
                        date, date.plusDays(1), 0, false);
                String id = rows.issueCheck().checkId();
                assertThat(id).isNotNull();
                assertThat(rows.items()).extracting(item -> item.issue().code()).containsExactly(201L, 202L);
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                var partial = progressUntil(checks, viewer, id, 1, true);
                assertThat(partial.pending()).isTrue();
                assertThat(partial.updates()).singleElement().satisfies(update -> {
                    assertThat(update.rowIndex()).isZero();
                    assertThat(update.issue().title()).isEqualTo("第一个标题");
                    assertThat(update.issueUrl()).endsWith("/assignments/issues/201/detail");
                });
                assertThat(checks.progress(another, 18L, id).updates()).isEmpty();
                assertThat(checks.progress(viewer, 19L, id).updates()).isEmpty();
                release.countDown();
                var complete = progressUntil(checks, viewer, id, 2, false);
                assertThat(complete.pending()).isFalse();
                assertThat(complete.updates()).extracting(WorklogModels.IssueUpdate::rowIndex).containsExactly(0, 1);
            } finally {
                release.countDown();
                checks.stop();
            }
        }
    }

    private WorklogIssueChecks.Progress progressUntil(WorklogIssueChecks checks, WorklogModule.Context viewer,
                                                       String id, int rows, boolean pending) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        var progress = checks.progress(viewer, 18L, id);
        while ((progress.updates().size() < rows || progress.pending() != pending) && System.nanoTime() < until) {
            Thread.sleep(5);
            progress = checks.progress(viewer, 18L, id);
        }
        return progress;
    }

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
        var candidates = new LinkedHashMap<IssueKey, WorklogIssueChecks.Candidate>();
        for (long code = 1; code <= 8; code++) {
            candidates.put(new IssueKey("project", code), new WorklogIssueChecks.Candidate(Set.of(code), List.of()));
        }
        candidates.put(new IssueKey("project", 2), new WorklogIssueChecks.Candidate(Set.of(), List.of()));
        candidates.put(new IssueKey("project", 3), new WorklogIssueChecks.Candidate(Set.of(3L, 30L), List.of()));
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
            var checks = new WorklogIssueChecks(deleted, loader, new CodingIssueLinkBuilder());
            try {
                checks.enqueue(context, null, Map.of("member", candidates,
                        "other-member", Map.of(new IssueKey("project", 8), new WorklogIssueChecks.Candidate(Set.of(8L), List.of()))), null, false);
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
            var checks = new WorklogIssueChecks(deleted, loader, new CodingIssueLinkBuilder());
            try {
                var service = new WorklogQueryService(coding, new CodingIssueLinkBuilder(), clock, deleted, checks);
                var request = caller.submit(() -> service.queryMonth(context, Scope.WORKBENCH_TEAM, 8L,
                        members, month, schedule, Map.of(), service.today(), 0, false));
                var snapshot = request.get(2, TimeUnit.SECONDS);
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(snapshot.statistics().dailyTotals().get(4).hours()).isEqualByComparingTo("11");
                String id = snapshot.statistics().issueCheck().checkId();
                assertThat(checks.progress(context, 8L, id).pending()).isTrue();
                assertThat(checks.progress(context, 9L, id).pending()).isFalse();

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

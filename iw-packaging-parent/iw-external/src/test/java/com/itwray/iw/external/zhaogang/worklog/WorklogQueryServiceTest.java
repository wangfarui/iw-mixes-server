package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader.IssueKey;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.CodingPermissionError;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Team;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Worklog;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.WorklogPage;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarDefaults;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Day;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.DayType;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Schedule;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Absence;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Entries;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Statistics;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Scope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorklogQueryServiceTest {

    private final WorklogDeletedIssues deletedIssues = mock(WorklogDeletedIssues.class);
    private final WorklogIssueChecks issueChecks = mock(WorklogIssueChecks.class);
    private final WorklogModule.Context viewer = new WorklogModule.Context("viewer", 100L, "用户", "", 10L,
            "fixture", "https://fixture.invalid");

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void entriesPagePerMemberDeduplicatesAndKeepsPartialTeamResults() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        long startOne = Instant.parse("2026-08-24T01:00:00Z").toEpochMilli();
        long startTwo = Instant.parse("2026-08-24T02:00:00Z").toEpochMilli();
        Worklog first = new Worklog(1L, 11L, 101L, "project-a", 1L, new BigDecimal("1"),
                "第一条", startOne, startOne, startOne);
        Worklog second = new Worklog(2L, 12L, 102L, "project-a", 1L, new BigDecimal("2"),
                "第二条", startTwo, startTwo, startTwo);
        when(coding.worklogPage("token", 1787500799999L, 1788105600001L, 1L, 0, 1000))
                .thenReturn(new WorklogPage(Collections.nCopies(1000, first)));
        when(coding.worklogPage("token", 1787500799999L, 1788105600001L, 1L, 1000, 1000))
                .thenReturn(new WorklogPage(List.of(second)));
        when(coding.worklogPage("member-token", 1787500799999L, 1788105600001L, 2L, 0, 1000))
                .thenThrow(new CodingOpenApiException("DescribeAllProjectsIssueWorkLogList",
                        "UnauthorizedOperation", "permission denied"));
        when(issueChecks.cachedIssue("token", new IssueKey("project-a", 101L)))
                .thenReturn(new CodingOpenApiPort.Issue(101L, "REQUIREMENT", "需求", "第一项", "项目 A", false));
        when(issueChecks.cachedIssue("token", new IssueKey("project-a", 102L)))
                .thenReturn(new CodingOpenApiPort.Issue(102L, "MISSION", "任务", "第二项", "项目 A", false));
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T04:00:00Z"), CHINA_ZONE);
        WorklogQueryService service = service(coding, clock);
        WorklogModule.Context context = new WorklogModule.Context("token", 1L, "张三", "avatar", 10L,
                "g-iijw5014", "https://g-iijw5014.coding.net");

        Entries entries = service.queryEntries(context,
                new Team(100L, "产业数字中心", "https://g-iijw5014.coding.net"), Scope.WORKBENCH_TEAM, 300L,
                List.of(new WorklogModule.MemberCredential(1L, "张三", "avatar", "token"),
                        new WorklogModule.MemberCredential(2L, "李四", "", "member-token")),
                java.time.LocalDate.of(2026, 8, 24), java.time.LocalDate.of(2026, 8, 31), 0, false);

        assertThat(entries.coverage().partial()).isTrue();
        assertThat(entries.coverage().failedMemberCount()).isEqualTo(1);
        assertThat(entries.coverage().memberIssues()).hasSize(1);
        assertThat(entries.coverage().memberIssues().get(0).user().name()).isEqualTo("李四");
        assertThat(entries.coverage().memberIssues().get(0).reasonCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(entries.coverage().memberIssues().get(0).reason()).contains("项目协同（读写）");
        assertThat(entries.coverage().permissionError()).isEqualTo(new CodingPermissionError(
                "CODING_PERMISSION_DENIED",
                "当前 CODING 令牌缺少“项目协同（读写）”权限。请前往 CODING 令牌管理开通后重试；若已开通，请联系团队管理员检查账号权限",
                List.of("项目协同（读写）"), "DescribeAllProjectsIssueWorkLogList", "UnauthorizedOperation"));
        assertThat(entries.syncedAt()).isEqualTo("2026-08-24T12:00:00+08:00");
        assertThat(entries.items()).extracting(item -> item.issue().code()).containsExactly(102L, 101L);
        assertThat(entries.items().get(0).issueUrl()).contains("/assignments/issues/102/detail");
        verify(coding).worklogPage("token", 1787500799999L, 1788105600001L, 1L, 1000, 1000);
    }

    @Test
    void excludesMissingIssuesFromEntriesStatisticsAndAbsences() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        LocalDate date = LocalDate.of(2026, 8, 24);
        long start = date.atStartOfDay(CHINA_ZONE).toInstant().toEpochMilli();
        when(coding.worklogPage(eq("token"), anyLong(), anyLong(), eq(1L), eq(0), eq(1000)))
                .thenReturn(new WorklogPage(List.of(
                        new Worklog(1L, 11L, 101L, "project-a", 1L, new BigDecimal("2"), "", start, start, start),
                        new Worklog(2L, 12L, 102L, "project-a", 1L, new BigDecimal("7"), "", start, start, start),
                        new Worklog(3L, 13L, 103L, "project-a", 1L, new BigDecimal("3"), "", start, start, start),
                        new Worklog(4L, 14L, 104L, "project-a", 1L, BigDecimal.ONE, "", start, start, start))));
        when(deletedIssues.find(eq(10L), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(Set.of(), Set.of(12L));
        WorklogQueryService service = service(coding,
                Clock.fixed(Instant.parse("2026-08-28T05:00:00Z"), CHINA_ZONE));
        WorklogModule.Context context = new WorklogModule.Context("token", 1L, "成员", "", 10L,
                "coding-team", "https://coding-team.coding.net");
        Team team = new Team(10L, "团队", "https://coding-team.coding.net");
        List<WorklogModule.MemberCredential> members = List.of(
                new WorklogModule.MemberCredential(1L, "成员", "", "token"));
        YearMonth month = YearMonth.from(date);
        Schedule schedule = WorkCalendarDefaults.schedule(month);

        Entries beforeDeletion = service.queryEntries(context, team, Scope.SELF, null, members, date, date.plusDays(1), 0, false);
        assertThat(beforeDeletion.totalHours()).isEqualByComparingTo("13");

        Entries entries = service.queryEntries(context, team, Scope.SELF, null, members, date, date.plusDays(1), 0, false);
        Statistics stats = service.queryMonth(context, Scope.WORKBENCH_TEAM, 300L, members, month, schedule, Map.of(), service.today(), 0, false).statistics();
        Absence absence = service.queryMonth(context, Scope.SELF, null, members, month, schedule, Map.of(), service.today(), 0, false).absences();

        assertThat(entries.items()).extracting(WorklogModels.Item::workLogId).containsExactly(4L, 3L, 1L);
        assertThat(entries.totalHours()).isEqualByComparingTo("6");
        assertThat(stats.dailyTotals().get(23).hours()).isEqualByComparingTo("6");
        assertThat(stats.memberDailyTotals().get(0).dailyTotals().get(23).hours()).isEqualByComparingTo("6");
        assertThat(absence.members().get(0).days()).anySatisfy(day -> {
            assertThat(day.date()).isEqualTo(date.toString());
            assertThat(day.hours()).isEqualByComparingTo("6");
        });
    }

    @Test
    void entriesAndStatisticsIncludeLastMonthWorklogsSubmittedThisMonth() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        long friday = Instant.parse("2026-10-30T01:00:00Z").toEpochMilli();
        long monday = Instant.parse("2026-11-02T02:00:00Z").toEpochMilli();
        Worklog backdated = new Worklog(1L, 1L, 1L, "project-a", 1L, new BigDecimal("3"),
                "", friday, monday, monday);
        Worklog current = worklog(2L, new BigDecimal("5"), monday);
        Worklog undated = new Worklog(3L, 3L, 3L, "project-a", 1L, new BigDecimal("7"),
                "", null, friday, friday);
        when(coding.worklogPage(eq("token"), anyLong(), anyLong(), eq(1L), eq(0), eq(1000)))
                .thenAnswer(invocation -> {
                    long requestFrom = invocation.getArgument(1);
                    long requestTo = invocation.getArgument(2);
                    return new WorklogPage(requestFrom <= monday && monday < requestTo
                            ? List.of(backdated, current, undated) : List.of(undated));
                });
        WorklogQueryService service = service(coding,
                Clock.fixed(Instant.parse("2026-11-02T05:00:00Z"), CHINA_ZONE));
        WorklogModule.Context context = new WorklogModule.Context("token", 1L, "成员", "", 10L,
                "coding-team", "https://coding-team.coding.net");
        Team team = new Team(10L, "团队", "https://coding-team.coding.net");
        List<WorklogModule.MemberCredential> members = List.of(
                new WorklogModule.MemberCredential(1L, "成员", "", "token"));
        LocalDate weekStart = LocalDate.of(2026, 10, 26);
        YearMonth october = YearMonth.of(2026, 10);

        Entries entries = service.queryEntries(context, team, Scope.SELF, null,
                members, weekStart, weekStart.plusWeeks(1), 0, false);
        Statistics statistics = service.queryMonth(context, Scope.WORKBENCH_TEAM, 300L,
                members, october, WorkCalendarDefaults.schedule(october), Map.of(), service.today(), 0, false).statistics();

        assertThat(entries.items()).singleElement()
                .satisfies(item -> assertThat(item.startAt()).isEqualTo("2026-10-30T09:00:00+08:00"));
        assertThat(entries.totalHours()).isEqualByComparingTo("3");
        assertThat(entries.coverage().memberIssues()).extracting(WorklogModels.MemberIssue::reasonCode)
                .containsExactly("MISSING_START_AT");
        assertThat(statistics.dailyTotals().get(29).hours()).isEqualByComparingTo("3");
        assertThat(statistics.memberDailyTotals().get(0).dailyTotals().get(29).hours()).isEqualByComparingTo("3");
        assertThat(statistics.coverage().partial()).isTrue();
    }

    @Test
    void coverageIdentifiesMembersWithMissingTokenAndRequestFailure() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        when(coding.worklogPage(eq("good-token"), anyLong(), anyLong(), eq(1L), eq(0), eq(1000)))
                .thenReturn(new WorklogPage(List.of()));
        when(coding.worklogPage(eq("bad-token"), anyLong(), anyLong(), eq(3L), eq(0), eq(1000)))
                .thenThrow(new CodingOpenApiException("工时请求失败", new IllegalStateException("private detail")));
        WorklogQueryService service = service(coding,
                Clock.fixed(Instant.parse("2026-08-24T04:00:00Z"), CHINA_ZONE));

        Statistics statistics = service.queryMonth(viewer, Scope.WORKBENCH_TEAM, 300L,
                List.of(new WorklogModule.MemberCredential(1L, "成员一", "", "good-token"),
                        new WorklogModule.MemberCredential(2L, "成员二", "", ""),
                        new WorklogModule.MemberCredential(3L, "成员三", "", "bad-token")),
                YearMonth.of(2026, 8), WorkCalendarDefaults.schedule(YearMonth.of(2026, 8)), Map.of(), service.today(), 0, false).statistics();

        assertThat(statistics.coverage().failedMemberCount()).isEqualTo(2);
        assertThat(statistics.coverage().memberIssues())
                .extracting(issue -> issue.user().name() + ":" + issue.reasonCode())
                .containsExactly("成员二:TOKEN_MISSING", "成员三:REQUEST_FAILED");
        assertThat(statistics.coverage().memberIssues().get(1).reason()).doesNotContain("private detail", "bad-token");
    }

    @Test
    void coverageNamesMemberWhosePaginationReachedTheLimit() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        when(coding.worklogPage(eq("good-token"), anyLong(), anyLong(), eq(1L), eq(0), eq(1000)))
                .thenReturn(new WorklogPage(List.of()));
        when(coding.worklogPage(eq("many-token"), anyLong(), anyLong(), eq(2L), anyInt(), eq(1000)))
                .thenReturn(new WorklogPage(Collections.nCopies(1000,
                        new Worklog(1L, 1L, 1L, "project-a", 2L, BigDecimal.ONE, "工时", 0L, 0L, 0L))));
        WorklogQueryService service = service(coding,
                Clock.fixed(Instant.parse("2026-08-24T04:00:00Z"), CHINA_ZONE));

        Statistics statistics = service.queryMonth(viewer, Scope.WORKBENCH_TEAM, 300L,
                List.of(new WorklogModule.MemberCredential(1L, "成员一", "", "good-token"),
                        new WorklogModule.MemberCredential(2L, "成员二", "", "many-token")),
                YearMonth.of(2026, 8), WorkCalendarDefaults.schedule(YearMonth.of(2026, 8)), Map.of(), service.today(), 0, false).statistics();

        assertThat(statistics.coverage().partial()).isTrue();
        assertThat(statistics.coverage().failedMemberCount()).isZero();
        assertThat(statistics.coverage().memberIssues())
                .extracting(issue -> issue.user().name() + ":" + issue.reasonCode())
                .containsExactly("成员二:INCOMPLETE");
    }

    @Test
    void keepsDailyHoursSeparatedByMember() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        long memberOneStart = Instant.parse("2026-08-10T01:00:00Z").toEpochMilli();
        long memberTwoStart = Instant.parse("2026-08-10T03:00:00Z").toEpochMilli();
        when(coding.worklogPage("token", 1785513599999L, 1788192000001L, 1L, 0, 1000))
                .thenReturn(new WorklogPage(List.of(new Worklog(11L, 101L, 1L, "project-a", 1L,
                        new BigDecimal("2.5"), "成员一", memberOneStart, memberOneStart, memberOneStart))));
        when(coding.worklogPage("member-token", 1785513599999L, 1788192000001L, 2L, 0, 1000))
                .thenReturn(new WorklogPage(List.of(new Worklog(22L, 102L, 2L, "project-a", 1L,
                        new BigDecimal("4"), "成员二", memberTwoStart, memberTwoStart, memberTwoStart))));
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T04:00:00Z"), CHINA_ZONE);
        WorklogQueryService service = service(coding, clock);

        Statistics statistics = service.queryMonth(viewer, Scope.WORKBENCH_TEAM, 300L,
                List.of(new WorklogModule.MemberCredential(1L, "成员一", "avatar-a", "token"),
                        new WorklogModule.MemberCredential(2L, "成员二", "avatar-b", "member-token")), YearMonth.of(2026, 8),
                WorkCalendarDefaults.schedule(YearMonth.of(2026, 8)), Map.of(), service.today(), 0, false).statistics();

        assertThat(statistics.dailyTotals().get(9).hours()).isEqualByComparingTo("6.5");
        assertThat(statistics.memberDailyTotals()).extracting(total -> total.user().name())
                .containsExactly("成员一", "成员二");
        assertThat(statistics.memberDailyTotals().get(0).dailyTotals().get(9).hours()).isEqualByComparingTo("2.5");
        assertThat(statistics.memberDailyTotals().get(1).dailyTotals().get(9).hours()).isEqualByComparingTo("4");
        assertThat(statistics.memberDailyTotals().get(0).dailyTotals().get(10).hours()).isEqualByComparingTo("0");
    }

    @Test
    void teamSummarySumsOvertimePerMemberWhenMembersWorkOvertimeOnSameDay() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        long start = Instant.parse("2026-08-10T01:00:00Z").toEpochMilli();
        when(coding.worklogPage("token", 1785513599999L, 1788192000001L, 1L, 0, 1000))
                .thenReturn(new WorklogPage(List.of(new Worklog(11L, 1L, 1L, "project-a", 1L,
                        new BigDecimal("9"), "成员一", start, start, start))));
        when(coding.worklogPage("member-token", 1785513599999L, 1788192000001L, 2L, 0, 1000))
                .thenReturn(new WorklogPage(List.of(new Worklog(22L, 2L, 2L, "project-a", 2L,
                        new BigDecimal("9"), "成员二", start, start, start))));
        WorklogQueryService service = service(coding,
                Clock.fixed(Instant.parse("2026-08-24T04:00:00Z"), CHINA_ZONE));

        Statistics statistics = service.queryMonth(viewer, Scope.WORKBENCH_TEAM, 300L,
                List.of(new WorklogModule.MemberCredential(1L, "成员一", "avatar-a", "token"),
                        new WorklogModule.MemberCredential(2L, "成员二", "avatar-b", "member-token")),
                YearMonth.of(2026, 8), WorkCalendarDefaults.schedule(YearMonth.of(2026, 8)), Map.of(), service.today(), 0, false).statistics();

        assertThat(statistics.memberDailyTotals()).extracting(total -> total.summary().overtimeDays())
                .containsExactly(1, 1);
        assertThat(statistics.summary().overtimeDays()).isEqualTo(2);
        assertThat(statistics.summary().overtimeHours()).isEqualByComparingTo("2");
    }

    @Test
    void absenceCountsWeekdaysBelowEightHoursAndSkipsWeekends() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        long julyFirst = Instant.parse("2026-07-01T03:00:00Z").toEpochMilli();
        long julySecond = Instant.parse("2026-07-02T03:00:00Z").toEpochMilli();
        long julyFourth = Instant.parse("2026-07-04T03:00:00Z").toEpochMilli();
        when(coding.worklogPage(anyString(), anyLong(), anyLong(), eq(1L), eq(0), eq(1000)))
                .thenReturn(new WorklogPage(List.of(
                        new Worklog(1L, 1L, 1L, "project-a", 1L, new BigDecimal("7"), "不足 1 小时", julyFirst,
                                julyFirst, julyFirst),
                        new Worklog(2L, 1L, 1L, "project-a", 2L, new BigDecimal("8"), "满 8 小时", julySecond,
                                julySecond, julySecond),
                        new Worklog(3L, 1L, 1L, "project-a", 3L, new BigDecimal("0"), "周末", julyFourth,
                                julyFourth, julyFourth))));
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T04:00:00Z"), CHINA_ZONE);
        WorklogQueryService service = service(coding, clock);

        Absence absence = service.queryMonth(viewer, Scope.SELF, null,
                List.of(new WorklogModule.MemberCredential(1L, "张三", "avatar", "token")),
                YearMonth.of(2026, 7), WorkCalendarDefaults.schedule(YearMonth.of(2026, 7)), Map.of(), service.today(), 0, false).absences();

        assertThat(absence.members()).hasSize(1);
        assertThat(absence.members().get(0).days()).extracting(day -> day.date())
                .contains("2026-07-01", "2026-07-03")
                .doesNotContain("2026-07-02", "2026-07-04", "2026-07-05");
        assertThat(absence.members().get(0).days()).allSatisfy(day ->
                assertThat(LocalDate.parse(day.date()).getDayOfWeek().getValue()).isBetween(1, 5));
    }

    @Test
    void absenceIncludesBackdatedWorklogsSubmittedToday() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        long workDate = Instant.parse("2026-08-24T01:00:00Z").toEpochMilli();
        long submittedToday = Instant.parse("2026-08-28T04:00:00Z").toEpochMilli();
        Worklog backdated = new Worklog(1L, 1L, 1L, "project-a", 1L, new BigDecimal("8"),
                "今天补录此前工时", workDate, submittedToday, submittedToday);
        when(coding.worklogPage(anyString(), anyLong(), anyLong(), eq(1L), eq(0), eq(1000)))
                .thenAnswer(invocation -> {
                    long requestFrom = invocation.getArgument(1);
                    long requestTo = invocation.getArgument(2);
                    return requestFrom < submittedToday && requestTo > submittedToday
                            ? new WorklogPage(List.of(backdated))
                            : new WorklogPage(List.of());
                });
        Clock clock = Clock.fixed(Instant.parse("2026-08-28T05:55:00Z"), CHINA_ZONE);
        WorklogQueryService service = service(coding, clock);
        List<WorklogModule.MemberCredential> members = List.of(
                new WorklogModule.MemberCredential(1L, "乐以", "avatar", "token"));

        var snapshot = service.queryMonth(viewer, Scope.WORKBENCH_TEAM, 300L,
                members, YearMonth.of(2026, 8), WorkCalendarDefaults.schedule(YearMonth.of(2026, 8)), Map.of(), service.today(), 0, false);
        Statistics statistics = snapshot.statistics();
        Absence absence = snapshot.absences();

        assertThat(statistics.memberDailyTotals().get(0).dailyTotals().get(23).hours()).isEqualByComparingTo("8");
        assertThat(absence.members()).hasSize(1);
        assertThat(absence.members().get(0).days()).extracting(day -> day.date()).doesNotContain("2026-08-24");
    }

    @Test
    void calendarOverridesDriveAbsenceAndOvertimeRules() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        long julyFirst = Instant.parse("2026-07-01T03:00:00Z").toEpochMilli();
        long julySecond = Instant.parse("2026-07-02T03:00:00Z").toEpochMilli();
        long julyThird = Instant.parse("2026-07-03T03:00:00Z").toEpochMilli();
        long julyFourth = Instant.parse("2026-07-04T03:00:00Z").toEpochMilli();
        long julyFifth = Instant.parse("2026-07-05T03:00:00Z").toEpochMilli();
        when(coding.worklogPage(anyString(), anyLong(), anyLong(), eq(1L), eq(0), eq(1000)))
                .thenReturn(new WorklogPage(List.of(
                        worklog(1L, new BigDecimal("8.5"), julyFirst),
                        worklog(2L, new BigDecimal("9"), julySecond),
                        worklog(3L, new BigDecimal("1"), julyThird),
                        worklog(4L, new BigDecimal("7.5"), julyFourth),
                        worklog(5L, new BigDecimal("4"), julyFifth))));
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T04:00:00Z"), CHINA_ZONE);
        WorklogQueryService service = service(coding, clock);
        Schedule schedule = scheduleWithOverrides(YearMonth.of(2026, 7),
                new Day(LocalDate.of(2026, 7, 3), DayType.REST_DAY, true),
                new Day(LocalDate.of(2026, 7, 4), DayType.WORKDAY, true));
        List<WorklogModule.MemberCredential> members = List.of(
                new WorklogModule.MemberCredential(1L, "张三", "avatar", "token"));

        var snapshot = service.queryMonth(viewer, Scope.SELF, null,
                members, YearMonth.of(2026, 7), schedule, Map.of(), service.today(), 0, false);
        Statistics statistics = snapshot.statistics();
        Absence absence = snapshot.absences();

        assertThat(statistics.summary().overtimeHours()).isEqualByComparingTo("6.5");
        assertThat(statistics.summary().overtimeDays()).isEqualTo(2);
        assertThat(statistics.dailyTotals().get(2).dayType()).isEqualTo(DayType.REST_DAY);
        assertThat(statistics.dailyTotals().get(3).dayType()).isEqualTo(DayType.WORKDAY);
        assertThat(absence.members().get(0).days()).extracting(day -> day.date())
                .contains("2026-07-04")
                .doesNotContain("2026-07-03", "2026-07-05");
    }

    @Test
    void personalLeaveSkipsAbsenceAndAverageDenominatorButKeepsWorkdayOvertimeRules() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        long leaveWorklog = Instant.parse("2026-07-01T03:00:00Z").toEpochMilli();
        when(coding.worklogPage(anyString(), anyLong(), anyLong(), eq(1L), eq(0), eq(1000)))
                .thenReturn(new WorklogPage(List.of(worklog(1L, new BigDecimal("7"), leaveWorklog))));
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T04:00:00Z"), CHINA_ZONE);
        WorklogQueryService service = service(coding, clock);
        List<WorklogModule.MemberCredential> members = List.of(
                new WorklogModule.MemberCredential(1L, "张三", "avatar", "token"));
        Map<Long, Set<LocalDate>> leaveDates = Map.of(1L, Set.of(LocalDate.of(2026, 7, 1)));

        var snapshot = service.queryMonth(viewer, Scope.SELF, null,
                members, YearMonth.of(2026, 7), WorkCalendarDefaults.schedule(YearMonth.of(2026, 7)), leaveDates, service.today(), 0, false);
        Statistics statistics = snapshot.statistics();
        Absence absence = snapshot.absences();

        assertThat(statistics.summary().averageHours()).isEqualByComparingTo("0.32");
        assertThat(absence.members()).hasSize(1);
        assertThat(absence.members().get(0).days()).extracting(day -> day.date())
                .doesNotContain("2026-07-01");
    }

    private WorklogQueryService service(CodingOpenApiPort coding, Clock clock) {
        return new WorklogQueryService(coding, new CodingIssueLinkBuilder(), clock, deletedIssues, issueChecks);
    }

    private Worklog worklog(long id, BigDecimal hours, long startAt) {
        return new Worklog(id, 1L, 1L, "project-a", id, hours, "工时", startAt, startAt, startAt);
    }

    private Schedule scheduleWithOverrides(YearMonth month, Day... overrides) {
        var overrideMap = java.util.Arrays.stream(overrides)
                .collect(java.util.stream.Collectors.toMap(Day::date, day -> day));
        List<Day> days = WorkCalendarDefaults.schedule(month).days().stream()
                .map(day -> overrideMap.getOrDefault(day.date(), day))
                .toList();
        return new Schedule(month.toString(), 7, days);
    }
}

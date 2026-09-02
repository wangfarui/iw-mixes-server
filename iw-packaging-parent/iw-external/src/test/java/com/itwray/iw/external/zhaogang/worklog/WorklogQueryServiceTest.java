package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
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
                .thenThrow(new CodingOpenApiException("member denied"));
        when(coding.issue("token", "project-a", 101L))
                .thenReturn(new CodingOpenApiPort.Issue(101L, "REQUIREMENT", "需求", "第一项", "项目 A", false));
        when(coding.issue("token", "project-a", 102L))
                .thenReturn(new CodingOpenApiPort.Issue(102L, "MISSION", "任务", "第二项", "项目 A", false));
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T04:00:00Z"), CHINA_ZONE);
        WorklogQueryService service = new WorklogQueryService(coding, new CodingIssueLinkBuilder(), clock);
        WorklogModule.Context context = new WorklogModule.Context("token", 1L, "张三", "avatar", 10L,
                "g-iijw5014", "https://g-iijw5014.coding.net");

        Entries entries = service.queryEntries(context,
                new Team(100L, "产业数字中心", "https://g-iijw5014.coding.net"), Scope.WORKBENCH_TEAM, 300L,
                List.of(new WorklogModule.MemberCredential(1L, "张三", "avatar", "token"),
                        new WorklogModule.MemberCredential(2L, "李四", "", "member-token")),
                java.time.LocalDate.of(2026, 8, 24), java.time.LocalDate.of(2026, 8, 31));

        assertThat(entries.coverage().partial()).isTrue();
        assertThat(entries.coverage().failedMemberCount()).isEqualTo(1);
        assertThat(entries.syncedAt()).isEqualTo("2026-08-24T12:00:00+08:00");
        assertThat(entries.items()).extracting(item -> item.issue().code()).containsExactly(102L, 101L);
        assertThat(entries.items().get(0).issueUrl()).contains("/assignments/issues/102/detail");
        verify(coding).worklogPage("token", 1787500799999L, 1788105600001L, 1L, 1000, 1000);
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
        WorklogQueryService service = new WorklogQueryService(coding, new CodingIssueLinkBuilder(), clock);
        WorklogModule.Context context = new WorklogModule.Context("token", 1L, "成员一", "avatar-a", 10L,
                "g-iijw5014", "https://g-iijw5014.coding.net");

        Statistics statistics = service.queryStatistics(context,
                new Team(100L, "产业数字中心", "https://g-iijw5014.coding.net"), Scope.WORKBENCH_TEAM, 300L,
                List.of(new WorklogModule.MemberCredential(1L, "成员一", "avatar-a", "token"),
                        new WorklogModule.MemberCredential(2L, "成员二", "avatar-b", "member-token")), YearMonth.of(2026, 8),
                WorkCalendarDefaults.schedule(YearMonth.of(2026, 8)));

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
        WorklogQueryService service = new WorklogQueryService(coding, new CodingIssueLinkBuilder(),
                Clock.fixed(Instant.parse("2026-08-24T04:00:00Z"), CHINA_ZONE));

        Statistics statistics = service.queryStatistics(
                new WorklogModule.Context("token", 1L, "成员一", "avatar-a", 10L,
                        "g-iijw5014", "https://g-iijw5014.coding.net"),
                new Team(100L, "产业数字中心", "https://g-iijw5014.coding.net"), Scope.WORKBENCH_TEAM, 300L,
                List.of(new WorklogModule.MemberCredential(1L, "成员一", "avatar-a", "token"),
                        new WorklogModule.MemberCredential(2L, "成员二", "avatar-b", "member-token")),
                YearMonth.of(2026, 8), WorkCalendarDefaults.schedule(YearMonth.of(2026, 8)));

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
        WorklogQueryService service = new WorklogQueryService(coding, new CodingIssueLinkBuilder(), clock);

        Absence absence = service.queryAbsences(
                new WorklogModule.Context("token", 1L, "张三", "avatar", 10L,
                        "g-iijw5014", "https://g-iijw5014.coding.net"),
                new Team(100L, "产业数字中心", "https://g-iijw5014.coding.net"), Scope.SELF, null,
                List.of(new WorklogModule.MemberCredential(1L, "张三", "avatar", "token")),
                YearMonth.of(2026, 7), WorkCalendarDefaults.schedule(YearMonth.of(2026, 7)));

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
        WorklogQueryService service = new WorklogQueryService(coding, new CodingIssueLinkBuilder(), clock);
        WorklogModule.Context context = new WorklogModule.Context("token", 1L, "乐以", "avatar", 10L,
                "g-iijw5014", "https://g-iijw5014.coding.net");
        List<WorklogModule.MemberCredential> members = List.of(
                new WorklogModule.MemberCredential(1L, "乐以", "avatar", "token"));

        Statistics statistics = service.queryStatistics(context,
                new Team(100L, "产业数字中心", "https://g-iijw5014.coding.net"), Scope.WORKBENCH_TEAM, 300L,
                members, YearMonth.of(2026, 8), WorkCalendarDefaults.schedule(YearMonth.of(2026, 8)));
        Absence absence = service.queryAbsences(context,
                new Team(100L, "产业数字中心", "https://g-iijw5014.coding.net"), Scope.WORKBENCH_TEAM, 300L,
                members, YearMonth.of(2026, 8), WorkCalendarDefaults.schedule(YearMonth.of(2026, 8)));

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
        WorklogQueryService service = new WorklogQueryService(coding, new CodingIssueLinkBuilder(), clock);
        Schedule schedule = scheduleWithOverrides(YearMonth.of(2026, 7),
                new Day(LocalDate.of(2026, 7, 3), DayType.REST_DAY, true),
                new Day(LocalDate.of(2026, 7, 4), DayType.WORKDAY, true));
        WorklogModule.Context context = new WorklogModule.Context("token", 1L, "张三", "avatar", 10L,
                "g-iijw5014", "https://g-iijw5014.coding.net");
        List<WorklogModule.MemberCredential> members = List.of(
                new WorklogModule.MemberCredential(1L, "张三", "avatar", "token"));

        Statistics statistics = service.queryStatistics(context,
                new Team(100L, "产业数字中心", "https://g-iijw5014.coding.net"), Scope.SELF, null,
                members, YearMonth.of(2026, 7), schedule);
        Absence absence = service.queryAbsences(context,
                new Team(100L, "产业数字中心", "https://g-iijw5014.coding.net"), Scope.SELF, null,
                members, YearMonth.of(2026, 7), schedule);

        assertThat(statistics.summary().overtimeHours()).isEqualByComparingTo("6.5");
        assertThat(statistics.summary().overtimeDays()).isEqualTo(2);
        assertThat(statistics.dailyTotals().get(2).dayType()).isEqualTo(DayType.REST_DAY);
        assertThat(statistics.dailyTotals().get(3).dayType()).isEqualTo(DayType.WORKDAY);
        assertThat(absence.members().get(0).days()).extracting(day -> day.date())
                .contains("2026-07-04")
                .doesNotContain("2026-07-03", "2026-07-05");
    }

    @Test
    void futureMonthReturnsEmptyAbsenceWithoutCallingCoding() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T04:00:00Z"), CHINA_ZONE);
        WorklogQueryService service = new WorklogQueryService(coding, new CodingIssueLinkBuilder(), clock);

        Absence absence = service.queryAbsences(
                new WorklogModule.Context("token", 1L, "张三", "avatar", 10L,
                        "g-iijw5014", "https://g-iijw5014.coding.net"),
                new Team(100L, "产业数字中心", "https://g-iijw5014.coding.net"), Scope.SELF, null,
                List.of(new WorklogModule.MemberCredential(1L, "张三", "avatar", "token")),
                YearMonth.of(2026, 9), WorkCalendarDefaults.schedule(YearMonth.of(2026, 9)));

        assertThat(absence.members()).isEmpty();
        assertThat(absence.from()).isEqualTo("2026-09-01");
        assertThat(absence.toExclusive()).isEqualTo("2026-09-01");
        verify(coding, org.mockito.Mockito.never()).worklogPage(anyString(), anyLong(), anyLong(), anyLong(), anyInt(), anyInt());
    }

    @Test
    void personalLeaveSkipsAbsenceAndAverageDenominatorButKeepsWorkdayOvertimeRules() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        long leaveWorklog = Instant.parse("2026-07-01T03:00:00Z").toEpochMilli();
        when(coding.worklogPage(anyString(), anyLong(), anyLong(), eq(1L), eq(0), eq(1000)))
                .thenReturn(new WorklogPage(List.of(worklog(1L, new BigDecimal("7"), leaveWorklog))));
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T04:00:00Z"), CHINA_ZONE);
        WorklogQueryService service = new WorklogQueryService(coding, new CodingIssueLinkBuilder(), clock);
        WorklogModule.Context context = new WorklogModule.Context("token", 1L, "张三", "avatar", 10L,
                "g-iijw5014", "https://g-iijw5014.coding.net");
        List<WorklogModule.MemberCredential> members = List.of(
                new WorklogModule.MemberCredential(1L, "张三", "avatar", "token"));
        Map<Long, Set<LocalDate>> leaveDates = Map.of(1L, Set.of(LocalDate.of(2026, 7, 1)));

        Statistics statistics = service.queryStatistics(context,
                new Team(100L, "产业数字中心", "https://g-iijw5014.coding.net"), Scope.SELF, null,
                members, YearMonth.of(2026, 7), WorkCalendarDefaults.schedule(YearMonth.of(2026, 7)), leaveDates);
        Absence absence = service.queryAbsences(context,
                new Team(100L, "产业数字中心", "https://g-iijw5014.coding.net"), Scope.SELF, null,
                members, YearMonth.of(2026, 7), WorkCalendarDefaults.schedule(YearMonth.of(2026, 7)), leaveDates);

        assertThat(statistics.summary().averageHours()).isEqualByComparingTo("0.32");
        assertThat(absence.members()).hasSize(1);
        assertThat(absence.members().get(0).days()).extracting(day -> day.date())
                .doesNotContain("2026-07-01");
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

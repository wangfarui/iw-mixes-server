package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingPermissionError;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Worklog;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarDefaults;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.DayType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class WorklogModels {

    private WorklogModels() {
    }

    public enum Scope {
        SELF,
        WORKBENCH_TEAM
    }

    public record TeamOption(long id, String name, int memberCount, List<User> members) {
    }

    public record Options(List<TeamOption> teams) {
    }

    public record Coverage(Scope scope, Long workbenchTeamId, int memberCount, int visibleProjectCount,
                           boolean partial, int failedMemberCount, String warning,
                           CodingPermissionError permissionError, List<MemberIssue> memberIssues) {
        public Coverage(Scope scope, Long workbenchTeamId, int memberCount, int visibleProjectCount,
                        boolean partial, int failedMemberCount, String warning,
                        CodingPermissionError permissionError) {
            this(scope, workbenchTeamId, memberCount, visibleProjectCount, partial, failedMemberCount,
                    warning, permissionError, List.of());
        }

        public Coverage(Scope scope, Long workbenchTeamId, int memberCount, int visibleProjectCount,
                        boolean partial, int failedMemberCount, String warning) {
            this(scope, workbenchTeamId, memberCount, visibleProjectCount, partial, failedMemberCount, warning, null);
        }
    }

    public record MemberIssue(User user, String reasonCode, String reason) {
    }

    public record DailyTotal(String date, BigDecimal hours, DayType dayType) {
        public DailyTotal(String date, BigDecimal hours) {
            this(date, hours, WorkCalendarDefaults.dayType(LocalDate.parse(date)));
        }
    }

    public record User(long id, String name, String avatar) {
    }

    public record Summary(int overtimeDays, BigDecimal overtimeHours, BigDecimal averageHours,
                          int workdayCount, BigDecimal utilizationRate) {
        public Summary(int overtimeDays, BigDecimal overtimeHours, BigDecimal averageHours) {
            this(overtimeDays, overtimeHours, averageHours, 0, null);
        }

        public static Summary empty() {
            return new Summary(0, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    public record MemberDailyTotal(User user, List<DailyTotal> dailyTotals, Summary summary) {
        public MemberDailyTotal(User user, List<DailyTotal> dailyTotals) {
            this(user, dailyTotals, Summary.empty());
        }
    }

    public record Project(String name, String displayName) {
    }

    public record Issue(long code, String type, String typeName, String title) {
    }

    public record Item(long workLogId, User user, String startAt, String createdAt, String updatedAt,
                       BigDecimal hours, String workingDesc, Project project, Issue issue, String issueUrl) {
    }

    public record Statistics(Coverage coverage, String month, int calendarVersion, String syncedAt,
                             Summary summary, List<DailyTotal> dailyTotals,
                             List<MemberDailyTotal> memberDailyTotals, IssueCheck issueCheck) {
        public Statistics(Coverage coverage, String month, int calendarVersion, String syncedAt,
                          Summary summary, List<DailyTotal> dailyTotals, List<MemberDailyTotal> memberDailyTotals) {
            this(coverage, month, calendarVersion, syncedAt, summary, dailyTotals, memberDailyTotals, null);
        }

        public Statistics(Coverage coverage, String month, String syncedAt, List<DailyTotal> dailyTotals,
                          List<MemberDailyTotal> memberDailyTotals) {
            this(coverage, month, 0, syncedAt, Summary.empty(), dailyTotals, memberDailyTotals);
        }
    }

    public record IssueCheck(long deletionRevision, String checkId, boolean incomplete) {
        public IssueCheck(long deletionRevision, String checkId) {
            this(deletionRevision, checkId, false);
        }
    }

    public record IssueUpdate(int rowIndex, Issue issue, Project project, String issueUrl) {
    }

    public record IssueCheckStatus(long deletionRevision, boolean pending, List<IssueUpdate> updates, boolean incomplete) {
    }

    record MonthlySnapshot(Statistics statistics, Absence absences) {
    }

    /** Full selected range for the chart; ranking and absence stop strictly before today. */
    public record RangeReport(String from, String toExclusive, String rankingToExclusive,
                              Statistics statistics, Statistics ranking, Absence absences) {
    }

    public record Entries(Coverage coverage, String from, String toExclusive, String syncedAt,
                          BigDecimal totalHours, List<Item> items, IssueCheck issueCheck) {
        public Entries(Coverage coverage, String from, String toExclusive, String syncedAt,
                       BigDecimal totalHours, List<Item> items) {
            this(coverage, from, toExclusive, syncedAt, totalHours, items, null);
        }
    }

    public record EntriesPage(Entries entries, String nextCursor) {
    }

    record EntriesSnapshot(Coverage coverage, String syncedAt, List<EntryRow> rows) {
    }

    record EntryRow(Worklog worklog, long memberId) {
    }

    public record Absence(Coverage coverage, String month, String from, String toExclusive, String syncedAt,
                          List<MemberAbsence> members, IssueCheck issueCheck) {
        public Absence(Coverage coverage, String month, String from, String toExclusive, String syncedAt,
                       List<MemberAbsence> members) {
            this(coverage, month, from, toExclusive, syncedAt, members, null);
        }
    }

    public record MemberAbsence(User user, int absenceDays, List<AbsenceDay> days) {
    }

    public record AbsenceDay(String date, BigDecimal hours) {
    }
}

package com.itwray.iw.external.zhaogang.worklog;

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

    public record TeamOption(long id, String name, int memberCount) {
    }

    public record Options(List<TeamOption> teams) {
    }

    public record Coverage(Scope scope, Long workbenchTeamId, int memberCount, int visibleProjectCount,
                           boolean partial, int failedMemberCount, String warning) {
    }

    public record DailyTotal(String date, BigDecimal hours, DayType dayType) {
        public DailyTotal(String date, BigDecimal hours) {
            this(date, hours, WorkCalendarDefaults.dayType(LocalDate.parse(date)));
        }
    }

    public record User(long id, String name, String avatar) {
    }

    public record Summary(int overtimeDays, BigDecimal overtimeHours, BigDecimal averageHours) {
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
                             List<MemberDailyTotal> memberDailyTotals) {
        public Statistics(Coverage coverage, String month, String syncedAt, List<DailyTotal> dailyTotals,
                          List<MemberDailyTotal> memberDailyTotals) {
            this(coverage, month, 0, syncedAt, Summary.empty(), dailyTotals, memberDailyTotals);
        }
    }

    public record Entries(Coverage coverage, String from, String toExclusive, String syncedAt,
                          BigDecimal totalHours, List<Item> items) {
    }

    public record Absence(Coverage coverage, String month, String from, String toExclusive, String syncedAt,
                          List<MemberAbsence> members) {
    }

    public record MemberAbsence(User user, int absenceDays, List<AbsenceDay> days) {
    }

    public record AbsenceDay(String date, BigDecimal hours) {
    }
}

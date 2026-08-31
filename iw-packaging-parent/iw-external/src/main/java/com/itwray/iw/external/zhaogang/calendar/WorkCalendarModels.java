package com.itwray.iw.external.zhaogang.calendar;

import java.time.LocalDate;
import java.util.List;

public final class WorkCalendarModels {

    private WorkCalendarModels() {
    }

    public enum DayType {
        WORKDAY,
        REST_DAY
    }

    public record Context(long userId, String userName, long codingTeamId) {
    }

    public record Day(LocalDate date, DayType dayType, boolean overridden, boolean leave) {
        public Day(LocalDate date, DayType dayType, boolean overridden) {
            this(date, dayType, overridden, false);
        }
    }

    public record Schedule(String month, int versionNo, List<Day> days) {
        public Schedule {
            days = days == null ? List.of() : List.copyOf(days);
        }
    }

    public record Month(String month, int versionNo, boolean canManage, boolean canManageLeave, List<Day> days) {
        public Month(String month, int versionNo, boolean canManage, List<Day> days) {
            this(month, versionNo, canManage, true, days);
        }
        public Month {
            days = days == null ? List.of() : List.copyOf(days);
        }
    }

    public record UpdateDayCommand(DayType dayType) {
    }
}

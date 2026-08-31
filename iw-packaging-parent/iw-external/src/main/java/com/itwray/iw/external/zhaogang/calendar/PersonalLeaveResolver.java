package com.itwray.iw.external.zhaogang.calendar;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

@FunctionalInterface
public interface PersonalLeaveResolver {

    LeaveSchedule resolve(long codingTeamId, long codingUserId, YearMonth month);

    record LeaveSchedule(int versionNo, Set<LocalDate> dates) {
        public LeaveSchedule {
            dates = dates == null ? Set.of() : Set.copyOf(dates);
        }

        public static LeaveSchedule empty() {
            return new LeaveSchedule(0, Set.of());
        }
    }
}

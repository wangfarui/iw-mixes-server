package com.itwray.iw.external.zhaogang.calendar;

import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.DayType;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

interface WorkCalendarRepository {

    StoredSchedule find(long codingTeamId, YearMonth month);

    void saveDay(long codingTeamId, LocalDate date, DayType dayType, long updaterUserId, String updaterUserName);

    void resetDay(long codingTeamId, LocalDate date);

    Set<LocalDate> findLeaveDates(long codingTeamId, long codingUserId, YearMonth month);

    void saveLeaveDate(long codingTeamId, long codingUserId, LocalDate date);

    void resetLeaveDate(long codingTeamId, long codingUserId, LocalDate date);

    record StoredSchedule(int versionNo, List<StoredDay> days) {
    }

    record StoredDay(LocalDate date, DayType dayType) {
    }
}

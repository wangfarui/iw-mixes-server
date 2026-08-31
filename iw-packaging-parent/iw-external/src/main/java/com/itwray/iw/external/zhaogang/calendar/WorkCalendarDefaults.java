package com.itwray.iw.external.zhaogang.calendar;

import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Day;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.DayType;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Schedule;

import java.time.LocalDate;
import java.time.YearMonth;

public final class WorkCalendarDefaults {

    private WorkCalendarDefaults() {
    }

    public static Schedule schedule(YearMonth month) {
        return new Schedule(month.toString(), 0, month.atDay(1).datesUntil(month.plusMonths(1).atDay(1))
                .map(date -> new Day(date, dayType(date), false))
                .toList());
    }

    public static DayType dayType(LocalDate date) {
        return date.getDayOfWeek().getValue() <= 5 ? DayType.WORKDAY : DayType.REST_DAY;
    }
}

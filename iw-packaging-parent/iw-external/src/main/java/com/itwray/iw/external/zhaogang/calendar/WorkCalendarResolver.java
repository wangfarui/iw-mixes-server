package com.itwray.iw.external.zhaogang.calendar;

import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Schedule;

import java.time.YearMonth;

@FunctionalInterface
public interface WorkCalendarResolver {

    Schedule resolve(long codingTeamId, YearMonth month);
}

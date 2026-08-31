package com.itwray.iw.external.zhaogang.calendar;

import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Context;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Month;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Schedule;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.UpdateDayCommand;

import java.time.YearMonth;

public interface WorkCalendarModule extends WorkCalendarResolver {

    Month month(Context context, String month);

    Month updateDay(Context context, String date, UpdateDayCommand command);

    Month resetDay(Context context, String date);

    Month updateLeave(Context context, String date, boolean leave);

    @Override
    Schedule resolve(long codingTeamId, YearMonth month);
}

package com.itwray.iw.external.zhaogang.calendar;

import org.springframework.stereotype.Component;

import java.time.YearMonth;

@Component
class MybatisPersonalLeaveResolver implements PersonalLeaveResolver {

    private final WorkCalendarRepository repository;

    MybatisPersonalLeaveResolver(WorkCalendarRepository repository) {
        this.repository = repository;
    }

    @Override
    public LeaveSchedule resolve(long codingTeamId, long codingUserId, YearMonth month) {
        if (codingTeamId <= 0 || codingUserId <= 0 || month == null) return LeaveSchedule.empty();
        var dates = repository.findLeaveDates(codingTeamId, codingUserId, month);
        return new LeaveSchedule(dates.hashCode(), dates);
    }
}

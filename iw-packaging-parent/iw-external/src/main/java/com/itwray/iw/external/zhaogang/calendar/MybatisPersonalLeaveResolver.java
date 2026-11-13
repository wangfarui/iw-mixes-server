package com.itwray.iw.external.zhaogang.calendar;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
class MybatisPersonalLeaveResolver implements PersonalLeaveResolver {

    private final WorkCalendarRepository repository;

    MybatisPersonalLeaveResolver(WorkCalendarRepository repository) {
        this.repository = repository;
    }

    @Override
    public Map<Long, Set<LocalDate>> resolve(long codingTeamId, List<Long> codingUserIds, YearMonth month) {
        if (codingTeamId <= 0 || codingUserIds.isEmpty() || month == null) return Map.of();
        return repository.findLeaveDatesForMembers(codingTeamId, codingUserIds, month);
    }
}

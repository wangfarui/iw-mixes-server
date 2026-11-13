package com.itwray.iw.external.zhaogang.calendar;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

@FunctionalInterface
public interface PersonalLeaveResolver {

    Map<Long, Set<LocalDate>> resolve(long codingTeamId, List<Long> codingUserIds, YearMonth month);
}

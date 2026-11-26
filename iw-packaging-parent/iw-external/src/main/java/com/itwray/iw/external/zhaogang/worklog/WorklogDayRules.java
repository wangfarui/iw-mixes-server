package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.DayType;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class WorklogDayRules {
    private static final BigDecimal WORKDAY_HOURS = BigDecimal.valueOf(8);

    private WorklogDayRules() {}

    public record Overtime(BigDecimal hours, int days) {}

    public static Overtime overtime(BigDecimal hours, DayType type) {
        if (type == DayType.WORKDAY) {
            return new Overtime(hours.subtract(WORKDAY_HOURS).max(BigDecimal.ZERO),
                    hours.compareTo(BigDecimal.valueOf(9)) >= 0 ? 1 : 0);
        }
        return new Overtime(hours, hours.compareTo(BigDecimal.valueOf(4)) >= 0 ? 1 : 0);
    }

    public static BigDecimal utilization(BigDecimal hours, long workdays) {
        return hours.multiply(BigDecimal.valueOf(100))
                .divide(WORKDAY_HOURS.multiply(BigDecimal.valueOf(workdays)), 2, RoundingMode.HALF_UP);
    }
}

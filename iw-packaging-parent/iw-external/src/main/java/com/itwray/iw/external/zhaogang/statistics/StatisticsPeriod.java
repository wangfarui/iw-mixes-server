package com.itwray.iw.external.zhaogang.statistics;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.stream.Stream;

import static com.itwray.iw.external.zhaogang.statistics.StatisticsModels.*;

record StatisticsPeriod(Range range, Range comparison) {
    static final List<RangeKind> PRESETS = List.of(RangeKind.THIS_MONTH, RangeKind.THIS_WEEK, RangeKind.LAST_WEEK,
            RangeKind.LAST_MONTH, RangeKind.THIS_QUARTER);

    static StatisticsPeriod resolve(RangeKind kind, LocalDate from, LocalDate to, boolean compare, LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate month = today.withDayOfMonth(1);
        LocalDate quarter = month.minusMonths((month.getMonthValue() - 1) % 3);
        Range range = switch (kind) {
            case THIS_WEEK -> new Range(monday, yesterday);
            case LAST_WEEK -> new Range(monday.minusWeeks(1), monday.minusDays(1));
            case THIS_MONTH -> new Range(month, yesterday);
            case LAST_MONTH -> new Range(month.minusMonths(1), month.minusDays(1));
            case THIS_QUARTER -> new Range(quarter, yesterday);
            case CUSTOM -> {
                if (from == null || to == null || from.isAfter(to) || to.isAfter(yesterday)
                        || ChronoUnit.DAYS.between(from, to) >= 366 || from.getYear() < 1002) {
                    throw new CodingOpenApiException("请选择截至昨天、最多 366 天的统计区间");
                }
                yield new Range(from, to);
            }
        };
        if (kind != RangeKind.CUSTOM && ((from != null && !range.from().equals(from))
                || (to != null && !range.to().equals(to)))) {
            throw new CodingOpenApiException("快捷范围与统计日期不一致");
        }
        if (!compare || empty(range)) return new StatisticsPeriod(range, null);
        long days = ChronoUnit.DAYS.between(range.from(), range.to()) + 1;
        Range previous = switch (kind) {
            case LAST_MONTH -> new Range(month.minusMonths(2), month.minusMonths(1).minusDays(1));
            case THIS_MONTH -> shifted(range, 1);
            case THIS_QUARTER -> shifted(range, 3);
            case LAST_WEEK, THIS_WEEK, CUSTOM -> new Range(range.from().minusDays(days), range.from().minusDays(1));
        };
        return new StatisticsPeriod(range, previous);
    }

    private static Range shifted(Range range, int months) {
        LocalDate end = range.to().minusMonths(months);
        return end.getDayOfMonth() == range.to().getDayOfMonth()
                ? new Range(range.from().minusMonths(months), end) : null;
    }

    static StatisticsPeriod fromKey(String key) {
        String[] dates = key.split("/");
        Range range = new Range(LocalDate.parse(dates[0]), LocalDate.parse(dates[1]));
        return new StatisticsPeriod(range,
                dates.length == 4 ? new Range(LocalDate.parse(dates[2]), LocalDate.parse(dates[3])) : null);
    }

    static boolean empty(Range range) {
        return range.from().isAfter(range.to());
    }

    List<YearMonth> months() {
        return Stream.concat(months(range), comparison == null ? Stream.empty() : months(comparison))
                .distinct().sorted().toList();
    }

    private static Stream<YearMonth> months(Range range) {
        if (empty(range)) return Stream.empty();
        YearMonth from = YearMonth.from(range.from());
        return Stream.iterate(from, month -> !month.isAfter(YearMonth.from(range.to())), month -> month.plusMonths(1));
    }

    String key() {
        return range.from() + "/" + range.to() + (comparison == null ? "" : "/" + comparison.from() + "/" + comparison.to());
    }
}

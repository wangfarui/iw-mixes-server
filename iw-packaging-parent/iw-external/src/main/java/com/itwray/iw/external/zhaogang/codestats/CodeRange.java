package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;

public record CodeRange(LocalDate from, LocalDate to, List<YearMonth> months) {

    public static final int MAX_DAYS = 31;

    public CodeRange {
        months = months == null ? List.of() : List.copyOf(months);
    }

    public static CodeRange parse(String fromValue, String toValue, int retentionMonths, Clock clock) {
        LocalDate from = parseDate(fromValue, "开始日期");
        LocalDate to = parseDate(toValue, "结束日期");
        if (to.isBefore(from)) {
            throw new CodingOpenApiException("结束日期不能早于开始日期");
        }
        LocalDate today = LocalDate.now(clock);
        if (to.isAfter(today)) {
            throw new CodingOpenApiException("时间范围不能包含未来日期");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_DAYS) {
            throw new CodingOpenApiException("自定义时间范围最多选择 31 天");
        }
        int retention = Math.max(1, retentionMonths);
        LocalDate earliest = YearMonth.from(today).minusMonths(retention - 1L).atDay(1);
        if (from.isBefore(earliest)) {
            throw new CodingOpenApiException("代码统计只保留最近 " + retention + " 个自然月的数据");
        }
        List<YearMonth> months = from.getYear() == to.getYear() && from.getMonth() == to.getMonth()
                ? List.of(YearMonth.from(from))
                : YearMonth.from(from).atDay(1).datesUntil(to.plusDays(1), java.time.Period.ofMonths(1))
                .map(YearMonth::from).toList();
        return new CodeRange(from, to, months);
    }

    public CodeStatsModels.Range view() {
        return new CodeStatsModels.Range(from.toString(), to.toString(),
                (int) (ChronoUnit.DAYS.between(from, to) + 1), months.stream().map(YearMonth::toString).toList());
    }

    public LocalDate monthStart(YearMonth month) {
        return month.atDay(1);
    }

    public LocalDate monthEnd(YearMonth month, Clock clock) {
        LocalDate monthEnd = month.atEndOfMonth();
        LocalDate today = LocalDate.now(clock);
        return monthEnd.isAfter(today) ? today : monthEnd;
    }

    private static LocalDate parseDate(String value, String label) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new CodingOpenApiException(label + "格式应为 YYYY-MM-DD");
        }
    }
}

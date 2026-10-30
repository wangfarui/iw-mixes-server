package com.itwray.iw.points.service;

import cn.hutool.core.date.ChineseDate;
import cn.hutool.core.date.chinese.LunarInfo;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanScheduleDto;
import com.itwray.iw.points.model.entity.PointsTaskPlanEntity;
import com.itwray.iw.points.model.enums.TaskPlanCycleEnum;
import com.itwray.iw.web.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Immutable anchor -> occurrences. A short-month fallback never becomes the next rule. */
@Component
public class PointsTaskPlanCalendar {
    private final Clock clock;
    public PointsTaskPlanCalendar() { this(Clock.system(ZoneId.of("Asia/Shanghai"))); }
    PointsTaskPlanCalendar(Clock clock) { this.clock = clock; }
    public LocalDate today() { return LocalDate.now(clock); }
    public record Month(int month, boolean leap, String label, int days) {}
    public record DateSelection(LocalDate solarDate, int lunarYear, int lunarMonth, int lunarDay,
                                boolean leapMonth, String text) {}
    public record Occurrence(LocalDate planDate, String planDateText, LocalDate generateDate, LocalDate deadlineDate) {}
    public record Preview(String calendarType, LocalDate anchorDate, String ruleText,
                          List<Occurrence> occurrences, boolean exhausted, LocalDate serverToday) {}

    public List<Month> months(int year) {
        checkYear(year);
        List<Month> result = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            result.add(new Month(m, false, monthText(m), LunarInfo.monthDays(year, m)));
            if (LunarInfo.leapMonth(year) == m) {
                result.add(new Month(m, true, "闰" + monthText(m), LunarInfo.leapDays(year)));
            }
        }
        return result;
    }

    public DateSelection selection(LocalDate solarDate) {
        if (solarDate == null) { throw new BusinessException("请选择计划日期"); }
        ChineseDate lunar = new ChineseDate(solarDate);
        checkYear(lunar.getChineseYear());
        return new DateSelection(solarDate, lunar.getChineseYear(), lunarMonth(lunar), lunar.getDay(),
                lunar.isLeapMonth(), lunarText(lunar));
    }

    public LocalDate requestedDate(PointsTaskPlanScheduleDto dto) {
        String calendar = calendar(dto.getCalendarType());
        if ("SOLAR".equals(calendar)) {
            if (dto.getPlanDate() == null) { throw new BusinessException("请选择计划日期"); }
            checkYear(dto.getPlanDate().getYear());
            return dto.getPlanDate();
        }
        if (dto.getLunarYear() == null || dto.getLunarMonth() == null || dto.getLunarDay() == null) {
            throw new BusinessException("请选择完整的农历年月日");
        }
        int year = dto.getLunarYear(), month = dto.getLunarMonth(), day = dto.getLunarDay();
        checkYear(year);
        Month selected = months(year).stream().filter(m -> m.month() == month
                && m.leap() == Boolean.TRUE.equals(dto.getLeapMonth())).findFirst()
                .orElseThrow(() -> new BusinessException("所选年份不存在该农历月份"));
        if (day < 1 || day > selected.days()) { throw new BusinessException("所选农历日期不存在"); }
        return lunarToSolar(year, selected, day);
    }

    public PointsTaskPlanEntity prepare(PointsTaskPlanScheduleDto dto, PointsTaskPlanEntity existing) {
        if (dto.getPlanCycle() == null) { throw new BusinessException("请选择计划周期"); }
        PointsTaskPlanEntity plan = new PointsTaskPlanEntity();
        String type = dto.getCalendarType() == null && existing != null
                ? calendar(existing.getCalendarType()) : calendar(dto.getCalendarType());
        plan.setCalendarType(type);
        plan.setPlanCycle(dto.getPlanCycle());
        plan.setCycleDays(dto.getPlanCycle() == TaskPlanCycleEnum.CUSTOM ? dto.getCycleDays() : 0);
        if (dto.getPlanCycle() == TaskPlanCycleEnum.CUSTOM && (dto.getCycleDays() == null
                || dto.getCycleDays() <= 0 || dto.getCycleDays() > 65535)) {
            throw new BusinessException("计划天数需为1至65535的整数");
        }
        plan.setRemindDays(nonnegative(dto.getRemindDays()));
        plan.setDeadlineDays(nonnegative(dto.getDeadlineDays()));
        boolean legacyUnchanged = existing != null && dto.getCalendarType() == null
                && Objects.equals(dto.getPlanDate(), existing.getPlanDate());
        if (legacyUnchanged) {
            plan.setAnchorDate(anchor(existing));
        } else {
            // A legacy client cannot silently overwrite an existing lunar rule with a solar date.
            if (dto.getCalendarType() == null && "LUNAR".equals(type)) {
                throw new BusinessException("请使用支持农历的客户端修改农历计划日期");
            }
            plan.setAnchorDate(requestedDate(dto));
        }
        if (existing != null && sameRule(plan, existing)) {
            plan.setPlanDate(existing.getPlanDate());
            plan.setNextPlanDate(existing.getNextPlanDate());
        } else {
            LocalDate first = onOrAfter(plan, today());
            if (first == null) { throw new BusinessException("支持的日期范围内已无下次计划，请调整日期"); }
            plan.setPlanDate(first);
            plan.setNextPlanDate(first.minusDays(plan.getRemindDays()));
        }
        return plan;
    }

    public boolean sameRule(PointsTaskPlanEntity a, PointsTaskPlanEntity b) {
        return calendar(a.getCalendarType()).equals(calendar(b.getCalendarType()))
                && anchor(a).equals(anchor(b)) && a.getPlanCycle() == b.getPlanCycle()
                && (a.getPlanCycle() != TaskPlanCycleEnum.CUSTOM || Objects.equals(a.getCycleDays(), b.getCycleDays()))
                && nonnegative(a.getRemindDays()) == nonnegative(b.getRemindDays());
    }

    public LocalDate anchor(PointsTaskPlanEntity plan) {
        return plan.getAnchorDate() == null ? plan.getPlanDate() : plan.getAnchorDate();
    }

    public LocalDate next(PointsTaskPlanEntity plan) {
        return onOrAfter(plan, plan.getPlanDate().plusDays(1));
    }

    public LocalDate onOrAfter(PointsTaskPlanEntity plan, LocalDate lower) {
        LocalDate original = anchor(plan);
        LocalDate bound = lower.isBefore(original) ? original : lower;
        TaskPlanCycleEnum cycle = plan.getPlanCycle();
        if (cycle == TaskPlanCycleEnum.DAY || cycle == TaskPlanCycleEnum.WEEK || cycle == TaskPlanCycleEnum.CUSTOM) {
            int step = cycle == TaskPlanCycleEnum.DAY ? 1 : cycle == TaskPlanCycleEnum.WEEK ? 7 : plan.getCycleDays();
            long days = ChronoUnit.DAYS.between(original, bound);
            LocalDate result = original.plusDays(((days + step - 1) / step) * step);
            return inRange(plan, result) ? result : null;
        }
        if ("SOLAR".equals(calendar(plan.getCalendarType()))) {
            long offset = cycle == TaskPlanCycleEnum.MONTH
                    ? ChronoUnit.MONTHS.between(YearMonth.from(original), YearMonth.from(bound))
                    : bound.getYear() - original.getYear();
            LocalDate result = cycle == TaskPlanCycleEnum.MONTH ? original.plusMonths(offset) : original.plusYears(offset);
            if (result.isBefore(bound)) {
                result = cycle == TaskPlanCycleEnum.MONTH ? original.plusMonths(offset + 1) : original.plusYears(offset + 1);
            }
            return inRange(plan, result) ? result : null;
        }
        if (bound.isAfter(LocalDate.of(2100, 12, 31))) { return null; }
        ChineseDate lunarAnchor = new ChineseDate(original);
        int startYear = Math.max(lunarAnchor.getChineseYear(), new ChineseDate(bound).getChineseYear());
        for (int year = startYear; year <= 2099; year++) {
            for (Month m : months(year)) {
                if (cycle == TaskPlanCycleEnum.YEAR && (m.month() != lunarMonth(lunarAnchor)
                        || m.leap() != (lunarAnchor.isLeapMonth() && LunarInfo.leapMonth(year) == m.month()))) {
                    continue;
                }
                LocalDate result = lunarToSolar(year, m, Math.min(lunarAnchor.getDay(), m.days()));
                if (!result.isBefore(bound)) { return result; }
            }
        }
        return null;
    }

    public Preview preview(PointsTaskPlanEntity plan) {
        List<Occurrence> occurrences = new ArrayList<>();
        LocalDate date = plan.getPlanDate();
        for (int i = 0; i < 3 && date != null; i++) {
            occurrences.add(new Occurrence(date, dateText(plan.getCalendarType(), date),
                    date.minusDays(nonnegative(plan.getRemindDays())), date.plusDays(nonnegative(plan.getDeadlineDays()))));
            date = onOrAfter(plan, date.plusDays(1));
        }
        return new Preview(calendar(plan.getCalendarType()), anchor(plan), ruleText(plan), occurrences, date == null, today());
    }

    public String dateText(String type, LocalDate date) {
        return "LUNAR".equals(calendar(type)) ? selection(date).text() + "（公历 " + date + "）" : "公历 " + date;
    }

    public String ruleText(PointsTaskPlanEntity plan) {
        String type = calendar(plan.getCalendarType());
        if (plan.getPlanCycle() == TaskPlanCycleEnum.MONTH) {
            return "LUNAR".equals(type) ? "每个农历月" + dayText(new ChineseDate(anchor(plan)).getDay()) + "（含闰月）"
                    : "每月" + anchor(plan).getDayOfMonth() + "日";
        }
        if (plan.getPlanCycle() == TaskPlanCycleEnum.YEAR) {
            return "LUNAR".equals(type) ? "每年农历" + lunarMonthDay(new ChineseDate(anchor(plan)))
                    : "每年公历" + anchor(plan).getMonthValue() + "月" + anchor(plan).getDayOfMonth() + "日";
        }
        return plan.getPlanCycle() == TaskPlanCycleEnum.CUSTOM ? "每隔" + plan.getCycleDays() + "天" : plan.getPlanCycle().getName();
    }

    private boolean inRange(PointsTaskPlanEntity plan, LocalDate date) {
        if (date.getYear() > 2100) { return false; }
        int year = "LUNAR".equals(calendar(plan.getCalendarType())) ? new ChineseDate(date).getChineseYear() : date.getYear();
        return year >= 1901 && year <= 2099;
    }
    private String calendar(String value) {
        if (value == null) { return "SOLAR"; }
        if (!Set.of("SOLAR", "LUNAR").contains(value)) { throw new BusinessException("请选择公历或农历"); }
        return value;
    }
    private int nonnegative(Integer value) {
        if (value != null && (value < 0 || value > 65535)) { throw new BusinessException("天数需为0至65535的整数"); }
        return value == null ? 0 : value;
    }
    private void checkYear(int year) {
        if (year < 1901 || year > 2099) { throw new BusinessException("日期年份支持1901至2099年"); }
    }
    private LocalDate lunarToSolar(int year, Month month, int day) {
        ChineseDate result = new ChineseDate(year, month.month(), day, month.leap());
        return LocalDate.of(result.getGregorianYear(), result.getGregorianMonthBase1(), result.getGregorianDay());
    }
    // Hutool 5.8.x getMonth() uses month + 1 for the leap month itself.
    private int lunarMonth(ChineseDate date) { return date.getMonth() - (date.isLeapMonth() ? 1 : 0); }
    private String lunarText(ChineseDate date) { return "农历" + date.getChineseYear() + "年" + lunarMonthDay(date); }
    private String lunarMonthDay(ChineseDate date) {
        return (date.isLeapMonth() ? "闰" : "") + monthText(lunarMonth(date)) + dayText(date.getDay());
    }
    private String monthText(int month) {
        return new String[]{"正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月"}[month - 1];
    }
    private String dayText(int day) {
        if (day == 10) { return "初十"; }
        if (day == 20) { return "二十"; }
        if (day == 30) { return "三十"; }
        return new String[]{"初", "十", "廿"}[(day - 1) / 10] + "一二三四五六七八九".charAt((day - 1) % 10);
    }
}

package com.itwray.iw.points.service;

import com.itwray.iw.points.model.dto.plan.PointsTaskPlanScheduleDto;
import com.itwray.iw.points.model.entity.PointsTaskPlanEntity;
import com.itwray.iw.points.model.enums.TaskPlanCycleEnum;
import com.itwray.iw.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class PointsTaskPlanCalendarTest {
    private PointsTaskPlanCalendar at(String day) {
        return new PointsTaskPlanCalendar(Clock.fixed(LocalDate.parse(day).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(), ZoneId.of("Asia/Shanghai")));
    }
    private PointsTaskPlanScheduleDto solar(String day, TaskPlanCycleEnum cycle) {
        var dto = new PointsTaskPlanScheduleDto();
        dto.setCalendarType("SOLAR"); dto.setPlanDate(LocalDate.parse(day)); dto.setPlanCycle(cycle);
        return dto;
    }
    private PointsTaskPlanScheduleDto lunar(int year, int month, int day, boolean leap, TaskPlanCycleEnum cycle) {
        var dto = new PointsTaskPlanScheduleDto();
        dto.setCalendarType("LUNAR"); dto.setLunarYear(year); dto.setLunarMonth(month);
        dto.setLunarDay(day); dto.setLeapMonth(leap); dto.setPlanCycle(cycle);
        return dto;
    }

    @Test void independentHkoReferenceDatesAndLeapMonthRoundTrip() {
        var calendar = at("2023-01-01");
        assertEquals(LocalDate.of(2026, 2, 17), calendar.requestedDate(lunar(2026, 1, 1, false, TaskPlanCycleEnum.YEAR)));
        assertEquals(LocalDate.of(2026, 11, 13), calendar.requestedDate(lunar(2026, 10, 5, false, TaskPlanCycleEnum.YEAR)));
        var dto = lunar(2023, 2, 1, true, TaskPlanCycleEnum.YEAR);
        assertEquals(LocalDate.of(2023, 3, 22), calendar.requestedDate(dto));
        var converted = calendar.selection(calendar.requestedDate(dto));
        assertEquals(2, converted.lunarMonth()); assertTrue(converted.leapMonth());
        assertEquals("农历2023年闰二月初一", converted.text());
    }

    @Test void monthlyIncludesNormalAndLeapMonthInChronologicalOrder() {
        var c = at("2023-02-20");
        var plan = c.prepare(lunar(2023, 2, 1, false, TaskPlanCycleEnum.MONTH), null);
        var preview = c.preview(plan);
        assertEquals(LocalDate.of(2023, 2, 20), preview.occurrences().get(0).planDate());
        assertEquals(LocalDate.of(2023, 3, 22), preview.occurrences().get(1).planDate());
        assertEquals(LocalDate.of(2023, 4, 20), preview.occurrences().get(2).planDate());
        assertTrue(preview.occurrences().get(1).planDateText().contains("闰二月"));
    }

    @Test void annualOrdinaryMonthDoesNotExecuteTwiceAndLeapRuleFallsBack() {
        var c = at("2023-01-01");
        var ordinary = c.prepare(lunar(2023, 2, 1, false, TaskPlanCycleEnum.YEAR), null);
        assertEquals(LocalDate.of(2024, 3, 10), c.next(ordinary));
        var leap = c.prepare(lunar(2023, 2, 1, true, TaskPlanCycleEnum.YEAR), null);
        assertEquals(LocalDate.of(2024, 3, 10), c.next(leap));
        assertEquals(LocalDate.of(2023, 3, 22), leap.getAnchorDate());
        // The original leap flag is retained even after several ordinary-year fallbacks.
        LocalDate later = c.onOrAfter(leap, LocalDate.of(2042, 1, 1));
        assertTrue(c.selection(later).leapMonth());
    }

    @Test void lunarThirtyClampsOnlyOccurrenceAndRestoresAfterShortMonth() {
        var c = at("2023-01-01");
        var plan = c.prepare(lunar(2023, 2, 30, false, TaskPlanCycleEnum.MONTH), null);
        LocalDate original = plan.getAnchorDate();
        LocalDate shortMonth = c.next(plan);
        assertEquals(29, c.selection(shortMonth).lunarDay());
        assertTrue(c.selection(shortMonth).leapMonth());
        plan.setPlanDate(shortMonth);
        LocalDate next = c.next(plan);
        while (c.selection(next).lunarDay() != 30) {
            plan.setPlanDate(next); next = c.next(plan);
        }
        assertEquals(original, plan.getAnchorDate());
        assertEquals(30, c.selection(next).lunarDay());
    }

    @Test void solarAnchorRestoresMonthEndAndLeapDay() {
        var c = at("2024-01-01");
        var month = c.prepare(solar("2024-01-31", TaskPlanCycleEnum.MONTH), null);
        month.setPlanDate(c.next(month));
        assertEquals(LocalDate.of(2024, 2, 29), month.getPlanDate());
        assertEquals(LocalDate.of(2024, 3, 31), c.next(month));
        var year = c.prepare(solar("2024-02-29", TaskPlanCycleEnum.YEAR), null);
        assertEquals(LocalDate.of(2025, 2, 28), c.next(year));
        assertEquals(LocalDate.of(2028, 2, 29), c.onOrAfter(year, LocalDate.of(2028, 1, 1)));
    }

    @Test void remindersUseRealDaysAcrossYearAndDoNotSkipCurrentPeriod() {
        var c = at("2026-02-16");
        var dto = lunar(2026, 1, 1, false, TaskPlanCycleEnum.YEAR);
        dto.setRemindDays(3); dto.setDeadlineDays(2);
        var plan = c.prepare(dto, null);
        assertEquals(LocalDate.of(2026, 2, 17), plan.getPlanDate());
        assertEquals(LocalDate.of(2026, 2, 14), plan.getNextPlanDate());
        assertEquals(LocalDate.of(2026, 2, 19), c.preview(plan).occurrences().get(0).deadlineDate());
        assertEquals(LocalDate.of(2026, 2, 17), at("2026-02-17").prepare(dto, null).getPlanDate());
        assertTrue(at("2026-02-18").prepare(dto, null).getPlanDate().isAfter(LocalDate.of(2027, 1, 1)));
    }

    @Test void dailyWeeklyAndCustomAreElapsedDaysEvenInLunarCalendar() {
        var c = at("2023-01-01");
        for (var cycle : new TaskPlanCycleEnum[]{TaskPlanCycleEnum.DAY, TaskPlanCycleEnum.WEEK, TaskPlanCycleEnum.CUSTOM}) {
            var dto = lunar(2023, 2, 1, true, cycle); dto.setCycleDays(30);
            var plan = c.prepare(dto, null);
            int days = cycle == TaskPlanCycleEnum.DAY ? 1 : cycle == TaskPlanCycleEnum.WEEK ? 7 : 30;
            assertEquals(plan.getPlanDate().plusDays(days), c.next(plan));
        }
    }

    @Test void legacyCreateDefaultsSolarAndOldUpdatePreservesLunarCursor() {
        var c = at("2026-01-01");
        var old = solar("2026-02-17", TaskPlanCycleEnum.YEAR); old.setCalendarType(null);
        assertEquals("SOLAR", c.prepare(old, null).getCalendarType());
        var plan = c.prepare(lunar(2026, 1, 1, false, TaskPlanCycleEnum.YEAR), null);
        plan.setPlanDate(c.next(plan)); plan.setNextPlanDate(plan.getPlanDate());
        old.setPlanDate(plan.getPlanDate());
        var edited = c.prepare(old, plan);
        assertEquals("LUNAR", edited.getCalendarType());
        assertEquals(plan.getAnchorDate(), edited.getAnchorDate());
        assertEquals(plan.getPlanDate(), edited.getPlanDate());
        old.setPlanDate(old.getPlanDate().plusDays(1));
        assertThrows(BusinessException.class, () -> c.prepare(old, plan));
    }

    @Test void legacyNullAnchorKeepsScheduleAndExplicitCalendarCanBeChanged() {
        var c = at("2026-01-01");
        var plan = c.prepare(solar("2026-02-17", TaskPlanCycleEnum.YEAR), null);
        plan.setCalendarType(null); plan.setAnchorDate(null);
        var dto = solar("2026-02-17", TaskPlanCycleEnum.YEAR); dto.setCalendarType(null);
        assertEquals(plan.getNextPlanDate(), c.prepare(dto, plan).getNextPlanDate());
        assertEquals("LUNAR", c.prepare(lunar(2026, 1, 1, false, TaskPlanCycleEnum.YEAR), plan).getCalendarType());
    }

    @Test void invalidDatesAndDayCountsAreRejectedAndEndOfRangeIsFinite() {
        var c = at("2026-01-01");
        assertThrows(BusinessException.class, () -> c.prepare(lunar(2026, 2, 1, true, TaskPlanCycleEnum.YEAR), null));
        assertThrows(BusinessException.class, () -> c.prepare(lunar(2026, 12, 30, false, TaskPlanCycleEnum.YEAR), null));
        var bad = solar("2026-02-17", TaskPlanCycleEnum.CUSTOM); bad.setCycleDays(0);
        assertThrows(BusinessException.class, () -> c.prepare(bad, null));
        bad.setPlanCycle(TaskPlanCycleEnum.DAY); bad.setRemindDays(-1);
        assertThrows(BusinessException.class, () -> c.prepare(bad, null));
        bad.setRemindDays(0); bad.setCalendarType("OTHER");
        assertThrows(BusinessException.class, () -> c.prepare(bad, null));
        var last = c.prepare(lunar(2099, 12, 1, false, TaskPlanCycleEnum.YEAR), null);
        assertNull(c.next(last)); assertTrue(c.preview(last).exhausted());
        assertEquals(1, c.preview(last).occurrences().size());
        assertThrows(BusinessException.class, () -> c.months(2100));
    }
}

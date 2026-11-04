package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodeRangeTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void splitsCrossMonthRangeIntoNaturalMonths() {
        CodeRange range = CodeRange.parse("2026-09-25", "2026-10-10", 24, clock);

        assertEquals(List.of(YearMonth.of(2026, 9), YearMonth.of(2026, 10)), range.months());
        assertEquals(16, range.view().days());
    }

    @Test
    void acceptsExactlyThirtyOneDays() {
        CodeRange range = CodeRange.parse("2026-09-30", "2026-10-30", 24, clock);

        assertEquals(31, range.view().days());
        assertEquals(List.of(YearMonth.of(2026, 9), YearMonth.of(2026, 10)), range.months());
    }

    @Test
    void rejectsMoreThanThirtyOneDays() {
        assertThrows(CodingOpenApiException.class,
                () -> CodeRange.parse("2026-09-30", "2026-10-31", 24, clock));
    }

    @Test
    void rejectsFutureAndReversedRange() {
        assertThrows(CodingOpenApiException.class,
                () -> CodeRange.parse("2026-10-01", "2026-11-01", 24, clock));
        assertThrows(CodingOpenApiException.class,
                () -> CodeRange.parse("2026-10-20", "2026-10-10", 24, clock));
    }

    @Test
    void rejectsRangeOlderThanRetention() {
        assertThrows(CodingOpenApiException.class,
                () -> CodeRange.parse("2024-10-31", "2024-11-10", 24, clock));
        assertEquals(1, CodeRange.parse("2024-11-01", "2024-11-10", 24, clock).months().size());
    }

    @Test
    void clampsCurrentMonthScanEndToToday() {
        CodeRange range = CodeRange.parse("2026-10-01", "2026-10-30", 24, clock);

        assertEquals(LocalDate.of(2026, 10, 30), range.monthEnd(YearMonth.of(2026, 10), clock));
        assertEquals(LocalDate.of(2026, 9, 30), range.monthEnd(YearMonth.of(2026, 9), clock));
    }
}

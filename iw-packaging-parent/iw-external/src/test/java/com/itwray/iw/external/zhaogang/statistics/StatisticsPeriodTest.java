package com.itwray.iw.external.zhaogang.statistics;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.itwray.iw.external.zhaogang.statistics.StatisticsModels.*;
import static org.assertj.core.api.Assertions.*;

class StatisticsPeriodTest {
    @Test
    void shortcutAndCustomComparisonsUseCalendarSemantics() {
        LocalDate april = LocalDate.of(2026, 4, 24);
        var current = StatisticsPeriod.resolve(RangeKind.THIS_MONTH, null, null, true, april);
        assertThat(current.comparison()).isEqualTo(new Range(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 23)));
        var completed = StatisticsPeriod.resolve(RangeKind.LAST_MONTH, null, null, true, april);
        assertThat(completed.comparison()).isEqualTo(new Range(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)));
        var custom = StatisticsPeriod.resolve(RangeKind.CUSTOM, april.minusDays(366), april.minusDays(1), true, april);
        assertThat(custom.comparison()).isEqualTo(new Range(april.minusDays(732), april.minusDays(367)));
        assertThat(StatisticsPeriod.fromKey(custom.key())).isEqualTo(custom);
        assertThatThrownBy(() -> StatisticsPeriod.resolve(RangeKind.CUSTOM, april.minusDays(367), april.minusDays(1), true, april))
                .isInstanceOf(CodingOpenApiException.class);
    }

    @Test
    void nonexistentComparisonDatesAndMonthStartHaveNoInventedDays() {
        var end = StatisticsPeriod.resolve(RangeKind.THIS_MONTH, null, null, true, LocalDate.of(2026, 3, 31));
        assertThat(end.comparison()).isNull();
        var start = StatisticsPeriod.resolve(RangeKind.THIS_MONTH, null, null, true, LocalDate.of(2026, 4, 1));
        assertThat(StatisticsPeriod.empty(start.range())).isTrue();
        assertThat(start.months()).isEmpty();
    }
}

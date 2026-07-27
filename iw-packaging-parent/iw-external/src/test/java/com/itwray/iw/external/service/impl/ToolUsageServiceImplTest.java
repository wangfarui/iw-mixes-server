package com.itwray.iw.external.service.impl;

import com.itwray.iw.external.mapper.ExternalToolUsageDailyMapper;
import com.itwray.iw.external.model.vo.ToolUsageSummaryVo;
import com.itwray.iw.web.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolUsageServiceImplTest {

    @Test
    void summaryUsesThirtyShanghaiNaturalDaysAndReturnsTopTenInStableOrder() {
        ExternalToolUsageDailyMapper mapper = mock(ExternalToolUsageDailyMapper.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-27T01:00:00Z"), ZoneId.of("Asia/Shanghai"));
        when(mapper.selectStatistics(java.time.LocalDate.of(2026, 6, 28), java.time.LocalDate.of(2026, 7, 27)))
                .thenReturn(List.of(
                        row("calculator", 80L, 8L, 2L),
                        row("formatter", 50L, 8L, 1L),
                        row("text-diff", 120L, 8L, 3L),
                        row("bmi-calculator", 12L, 2L, 0L)
                ));

        ToolUsageServiceImpl service = new ToolUsageServiceImpl(mapper, clock);

        ToolUsageSummaryVo summary = service.summary();

        assertEquals(262L, summary.getTotalUsageCount());
        assertEquals(6L, summary.getTodayUsageCount());
        assertEquals(30, summary.getRankingPeriodDays());
        assertEquals(List.of("text-diff", "calculator", "formatter", "bmi-calculator"),
                summary.getPopularTools().stream().map(ToolUsageSummaryVo.ToolStat::getToolKey).toList());
    }

    @Test
    void recordRejectsUnknownToolKeyBeforePersisting() {
        ToolUsageServiceImpl service = new ToolUsageServiceImpl(mock(ExternalToolUsageDailyMapper.class), Clock.systemUTC());

        assertThrows(BusinessException.class, () -> service.record("not-a-tool"));
    }

    private ExternalToolUsageDailyMapper.ToolUsageStatisticsRow row(String toolKey,
                                                                     Long totalUsageCount,
                                                                     Long periodUsageCount,
                                                                     Long todayUsageCount) {
        return new ExternalToolUsageDailyMapper.ToolUsageStatisticsRow(toolKey, totalUsageCount, periodUsageCount, todayUsageCount);
    }
}

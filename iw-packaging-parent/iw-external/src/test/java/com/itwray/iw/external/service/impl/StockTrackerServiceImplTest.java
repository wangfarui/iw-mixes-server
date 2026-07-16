package com.itwray.iw.external.service.impl;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.model.enums.StockTrackerApiCodeEnum;
import com.itwray.iw.external.model.vo.StockTrackerCandleSeriesVo;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockTrackerServiceImplTest {

    private final StockTrackerServiceImpl service = new StockTrackerServiceImpl();

    @Test
    void queryCandlesRejectsInvalidHistoricalEndTimeBeforeRequestingSource() {
        GeneralResponse<StockTrackerCandleSeriesVo> response = service.queryCandles(
                "600519", "daily", 66, "2026/07/16");

        assertEquals(StockTrackerApiCodeEnum.INVALID_END_TIME.getCode(), response.getCode());
    }

    @Test
    void applyCandlePageRemovesSentinelAndBuildsNextEndTime() {
        StockTrackerCandleSeriesVo series = new StockTrackerCandleSeriesVo();
        series.getCandles().add(candle("2026-07-10"));
        series.getCandles().add(candle("2026-07-11"));
        series.getCandles().add(candle("2026-07-14"));
        series.getCandles().add(candle("2026-07-15"));

        service.applyCandlePage(series, 3, "daily");

        assertTrue(series.getHasMoreBefore());
        assertEquals(3, series.getCandles().size());
        assertEquals("2026-07-11", series.getOldestTime());
        assertEquals("2026-07-15", series.getNewestTime());
        assertEquals("2026-07-10", series.getNextEndTime());
    }

    @Test
    void applyCandlePageMarksLastPageWithoutCursor() {
        StockTrackerCandleSeriesVo series = new StockTrackerCandleSeriesVo();
        series.getCandles().add(candle("2026-07-14"));
        series.getCandles().add(candle("2026-07-15"));

        service.applyCandlePage(series, 3, "weekly");

        assertFalse(series.getHasMoreBefore());
        assertNull(series.getNextEndTime());
    }

    @Test
    void yahooWindowScalesWithRequestedInterval() {
        LocalDate end = LocalDate.of(2026, 7, 16);

        assertEquals(LocalDate.of(2025, 7, 17), service.calculateYahooStartDate(end, "weekly", 48));
        assertEquals(LocalDate.of(2023, 5, 16), service.calculateYahooStartDate(end, "monthly", 36));
    }

    private StockTrackerCandleSeriesVo.Candle candle(String tradeTime) {
        StockTrackerCandleSeriesVo.Candle candle = new StockTrackerCandleSeriesVo.Candle();
        candle.setTradeTime(tradeTime);
        return candle;
    }
}

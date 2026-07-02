package com.itwray.iw.external.service;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.model.vo.StockTrackerCandleSeriesVo;
import com.itwray.iw.external.model.vo.StockTrackerQuoteVo;

import java.util.List;

/**
 * 股票跟踪服务。
 *
 * @author wray
 * @since 2026/7/1
 */
public interface StockTrackerService {

    GeneralResponse<StockTrackerQuoteVo> queryQuote(String symbol);

    GeneralResponse<List<StockTrackerQuoteVo>> queryBatchQuotes(String symbols);

    GeneralResponse<StockTrackerCandleSeriesVo> queryCandles(String symbol, String interval, Integer limit);
}

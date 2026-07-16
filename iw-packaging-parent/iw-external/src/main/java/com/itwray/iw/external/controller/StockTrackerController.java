package com.itwray.iw.external.controller;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.model.vo.StockTrackerCandleSeriesVo;
import com.itwray.iw.external.model.vo.StockTrackerQuoteVo;
import com.itwray.iw.external.service.StockTrackerService;
import com.itwray.iw.web.annotation.SkipWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 股票跟踪公开接口。
 *
 * @author wray
 * @since 2026/7/1
 */
@RestController
@RequestMapping("/external-service/api/stock-tracker")
@Validated
@Tag(name = "股票跟踪公开接口")
@SkipWrapper
public class StockTrackerController {

    private final StockTrackerService stockTrackerService;

    public StockTrackerController(StockTrackerService stockTrackerService) {
        this.stockTrackerService = stockTrackerService;
    }

    @GetMapping("/quote")
    @Operation(summary = "查询股票实时行情快照")
    public GeneralResponse<StockTrackerQuoteVo> quote(@RequestParam @NotBlank @Size(max = 32) String symbol) {
        return stockTrackerService.queryQuote(symbol);
    }

    @GetMapping("/batch-quotes")
    @Operation(summary = "批量查询股票实时行情快照")
    public GeneralResponse<List<StockTrackerQuoteVo>> batchQuotes(@RequestParam @NotBlank @Size(max = 512) String symbols) {
        return stockTrackerService.queryBatchQuotes(symbols);
    }

    @GetMapping("/candles")
    @Operation(summary = "查询股票K线")
    public GeneralResponse<StockTrackerCandleSeriesVo> candles(@RequestParam @NotBlank @Size(max = 32) String symbol,
                                                               @RequestParam(defaultValue = "intraday") String interval,
                                                               @RequestParam(required = false) @Min(1) @Max(800) Integer limit,
                                                               @RequestParam(required = false) @Size(max = 10) String endTime) {
        return stockTrackerService.queryCandles(symbol, interval, limit, endTime);
    }
}

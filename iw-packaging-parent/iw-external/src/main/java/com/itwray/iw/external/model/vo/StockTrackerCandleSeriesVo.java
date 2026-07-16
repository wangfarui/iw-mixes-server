package com.itwray.iw.external.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 股票K线序列 VO。
 *
 * @author wray
 * @since 2026/7/1
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "股票K线序列VO")
public class StockTrackerCandleSeriesVo {

    @Schema(title = "市场")
    private String market;

    @Schema(title = "交易所")
    private String exchange;

    @Schema(title = "东方财富secid")
    private String secid;

    @Schema(title = "股票代码")
    private String symbol;

    @Schema(title = "股票名称")
    private String name;

    @Schema(title = "K线周期")
    private String interval;

    @Schema(title = "K线周期名称")
    private String intervalLabel;

    @Schema(title = "复权模式")
    private String adjust;

    @Schema(title = "数据源")
    private String source;

    @Schema(title = "生成时间")
    private OffsetDateTime generatedAt;

    @Schema(title = "K线列表")
    private List<Candle> candles = new ArrayList<>();

    @Schema(title = "是否还有更早的K线")
    private Boolean hasMoreBefore;

    @Schema(title = "查询更早K线时使用的结束时间")
    private String nextEndTime;

    @Schema(title = "当前结果最早时间")
    private String oldestTime;

    @Schema(title = "当前结果最新时间")
    private String newestTime;

    @Schema(title = "提示信息")
    private List<String> warnings = new ArrayList<>();

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Candle {

        @Schema(title = "时间戳，秒")
        private Long time;

        @Schema(title = "交易时间文本")
        private String tradeTime;

        @Schema(title = "开盘价")
        private BigDecimal open;

        @Schema(title = "收盘价")
        private BigDecimal close;

        @Schema(title = "最高价")
        private BigDecimal high;

        @Schema(title = "最低价")
        private BigDecimal low;

        @Schema(title = "成交量")
        private BigDecimal volume;

        @Schema(title = "成交额")
        private BigDecimal amount;

        @Schema(title = "振幅")
        private BigDecimal amplitude;

        @Schema(title = "涨跌幅")
        private BigDecimal changePercent;

        @Schema(title = "涨跌额")
        private BigDecimal change;

        @Schema(title = "换手率")
        private BigDecimal turnoverRate;
    }
}

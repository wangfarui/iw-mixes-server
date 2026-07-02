package com.itwray.iw.external.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 股票行情快照 VO。
 *
 * @author wray
 * @since 2026/7/1
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "股票行情快照VO")
public class StockTrackerQuoteVo {

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

    @Schema(title = "币种")
    private String currency;

    @Schema(title = "最新价")
    private BigDecimal price;

    @Schema(title = "涨跌额")
    private BigDecimal change;

    @Schema(title = "涨跌幅")
    private BigDecimal changePercent;

    @Schema(title = "今开")
    private BigDecimal open;

    @Schema(title = "最高")
    private BigDecimal high;

    @Schema(title = "最低")
    private BigDecimal low;

    @Schema(title = "昨收")
    private BigDecimal previousClose;

    @Schema(title = "成交量")
    private BigDecimal volume;

    @Schema(title = "成交额")
    private BigDecimal amount;

    @Schema(title = "行情时间")
    private OffsetDateTime asOf;

    @Schema(title = "数据源")
    private String source;

    @Schema(title = "提示信息")
    private List<String> warnings = new ArrayList<>();
}

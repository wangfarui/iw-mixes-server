package com.itwray.iw.external.model.enums;

import com.itwray.iw.common.ApiCode;

/**
 * 股票跟踪接口编码。
 *
 * @author wray
 * @since 2026/7/1
 */
public enum StockTrackerApiCodeEnum implements ApiCode {

    INVALID_SYMBOL(40031, "仅支持沪深A股/ETF、港股和美股代码"),

    UNSUPPORTED_INTERVAL(40032, "不支持的K线周期"),

    TOO_MANY_SYMBOLS(40033, "单次最多查询20只股票"),

    NO_DATA(40431, "未查询到股票行情"),

    SOURCE_FAILED(50331, "行情数据源暂不可用");

    private final int code;

    private final String message;

    StockTrackerApiCodeEnum(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}

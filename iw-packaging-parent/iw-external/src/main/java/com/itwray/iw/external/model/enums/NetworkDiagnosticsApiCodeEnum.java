package com.itwray.iw.external.model.enums;

import com.itwray.iw.common.ApiCode;

/**
 * 网络诊断接口响应码。
 *
 * @author wray
 * @since 2026/7/1
 */
public enum NetworkDiagnosticsApiCodeEnum implements ApiCode {

    ANONYMOUS_DAILY_QUOTA_EXCEEDED(42911, "匿名诊断额度已用完，请登录后继续使用"),
    ANONYMOUS_MINUTE_QUOTA_EXCEEDED(42912, "匿名请求过于频繁，请稍后再试或登录后继续使用"),
    USER_DAILY_QUOTA_EXCEEDED(42913, "今日诊断次数较多，请明天再试"),
    USER_MINUTE_QUOTA_EXCEEDED(42914, "请求过于频繁，请稍后再试"),
    TOTAL_DAILY_QUOTA_EXCEEDED(42915, "今日网络诊断总额度已用完"),
    DIAGNOSTIC_FAILED(50311, "网络诊断暂时不可用"),
    ;

    private final int code;

    private final String message;

    NetworkDiagnosticsApiCodeEnum(int code, String message) {
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

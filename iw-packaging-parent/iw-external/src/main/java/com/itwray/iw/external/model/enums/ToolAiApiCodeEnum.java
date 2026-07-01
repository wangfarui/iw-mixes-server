package com.itwray.iw.external.model.enums;

import com.itwray.iw.common.ApiCode;

/**
 * 公开工具AI接口响应码
 *
 * @author wray
 * @since 2026/7/1
 */
public enum ToolAiApiCodeEnum implements ApiCode {

    UNSUPPORTED_BUSINESS_TYPE(40022, "不支持的AI工具类型"),
    CONTENT_BLOCKED(40021, "内容不适合AI生成"),
    TOTAL_QUOTA_EXCEEDED(42901, "今日AI生成总额度已用完"),
    TYPE_QUOTA_EXCEEDED(42902, "当前AI工具类型今日额度已用完"),
    IP_QUOTA_EXCEEDED(42903, "当前访问来源今日额度已用完"),
    AI_SERVICE_FAILED(50301, "AI服务暂时不可用"),
    ;

    private final int code;

    private final String message;

    ToolAiApiCodeEnum(int code, String message) {
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

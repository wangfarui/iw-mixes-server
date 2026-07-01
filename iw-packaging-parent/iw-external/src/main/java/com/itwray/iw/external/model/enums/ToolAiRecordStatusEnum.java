package com.itwray.iw.external.model.enums;

import lombok.Getter;

/**
 * 公开工具AI调用记录状态
 *
 * @author wray
 * @since 2026/7/1
 */
@Getter
public enum ToolAiRecordStatusEnum {

    SUCCESS("SUCCESS", "成功"),
    BLOCKED("BLOCKED", "内容拦截"),
    QUOTA_EXCEEDED("QUOTA_EXCEEDED", "额度超限"),
    FAILED("FAILED", "调用失败"),
    ;

    private final String code;

    private final String name;

    ToolAiRecordStatusEnum(String code, String name) {
        this.code = code;
        this.name = name;
    }
}

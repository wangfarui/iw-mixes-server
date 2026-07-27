package com.itwray.iw.external.model.enums;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;

/**
 * 工具箱统计支持的稳定工具标识。
 *
 * @author wray
 * @since 2026/7/27
 */
@Getter
public enum ToolUsageToolKeyEnum {

    BMI_CALCULATOR("bmi-calculator"),
    TEXT_DIFF("text-diff"),
    NUMBER_GENERATOR("number-generator"),
    ADDRESS_GENERATOR("address-generator"),
    CALCULATOR("calculator"),
    STOCK_TRACKER("stock-tracker"),
    FORMATTER("formatter"),
    ENCODING_CONVERTER("encoding-converter"),
    IMAGE_PROCESSOR("image-processor"),
    DOCUMENT_CONVERTER("document-converter"),
    COLOR_PICKER("color-picker"),
    TEXT_PLAYGROUND("text-playground"),
    IP_LOOKUP("ip-lookup"),
    NETWORK_DIAGNOSTICS("network-diagnostics");

    private final String toolKey;

    ToolUsageToolKeyEnum(String toolKey) {
        this.toolKey = toolKey;
    }

    public static ToolUsageToolKeyEnum findByToolKey(String toolKey) {
        return Arrays.stream(values())
                .filter(item -> StringUtils.equals(item.getToolKey(), StringUtils.trimToEmpty(toolKey)))
                .findFirst()
                .orElse(null);
    }
}

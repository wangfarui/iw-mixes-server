package com.itwray.iw.external.model.enums;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * 公开工具AI业务类型
 *
 * @author wray
 * @since 2026/7/1
 */
@Getter
public enum ToolAiBusinessTypeEnum {

    TEXT_GAME_ACROSTIC("TEXT_GAME_ACROSTIC", "藏头诗", 120, 512, new BigDecimal("1.1")),
    TEXT_GAME_QUOTE("TEXT_GAME_QUOTE", "随机语录", 160, 768, new BigDecimal("1.2")),
    TEXT_GAME_DANMAKU("TEXT_GAME_DANMAKU", "弹幕文案", 80, 512, new BigDecimal("1.1")),
    ;

    private final String type;

    private final String name;

    private final int dailyLimit;

    private final int maxTokens;

    private final BigDecimal temperature;

    ToolAiBusinessTypeEnum(String type, String name, int dailyLimit, int maxTokens, BigDecimal temperature) {
        this.type = type;
        this.name = name;
        this.dailyLimit = dailyLimit;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
    }

    public static ToolAiBusinessTypeEnum findByType(String type) {
        return Arrays.stream(values())
                .filter(item -> StringUtils.equalsIgnoreCase(item.getType(), type))
                .findFirst()
                .orElse(null);
    }
}

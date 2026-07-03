package com.itwray.iw.wardrobe.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 衣柜 AI 搭配建议 DTO
 *
 * @author codex
 * @since 2026-07-03
 */
@Data
@Schema(name = "衣柜AI搭配建议DTO")
public class WardrobeAiSuggestDto {

    @Schema(title = "自然语言需求")
    private String prompt;

    @Schema(title = "锁定衣物id")
    private Integer lockedItemId;

    @Schema(title = "需要替换的衣物id")
    private Integer replaceItemId;

    @Schema(title = "排除衣物id列表")
    private List<Integer> excludeItemIds;

    @Schema(title = "季节")
    private String season;

    @Schema(title = "场景")
    private String scene;

    @Schema(title = "风格")
    private String style;

    @Schema(title = "天气")
    private String weatherText;

    @Schema(title = "推荐数量")
    private Integer limit;

    @Schema(title = "是否调用外部AI，false时只使用本地规则")
    private Boolean useAi;
}

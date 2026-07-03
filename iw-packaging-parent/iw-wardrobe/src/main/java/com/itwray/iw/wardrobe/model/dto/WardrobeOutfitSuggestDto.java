package com.itwray.iw.wardrobe.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 搭配推荐 DTO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@Schema(name = "搭配推荐DTO")
public class WardrobeOutfitSuggestDto {

    @Schema(title = "锁定衣物id")
    private Integer lockedItemId;

    @Schema(title = "季节标签")
    private String season;

    @Schema(title = "场景标签")
    private String scene;

    @Schema(title = "风格标签")
    private String style;

    @Schema(title = "天气")
    private String weatherText;

    @Schema(title = "优先使用闲置衣物")
    private Boolean preferIdle;

    @Schema(title = "避开最近穿过天数")
    private Integer avoidRecentDays;

    @Schema(title = "排除衣物id列表")
    private java.util.List<Integer> excludeItemIds;

    @Schema(title = "推荐数量")
    private Integer limit;
}

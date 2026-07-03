package com.itwray.iw.wardrobe.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 衣柜 AI 搭配建议 VO
 *
 * @author codex
 * @since 2026-07-03
 */
@Data
@Schema(name = "衣柜AI搭配建议VO")
public class WardrobeAiSuggestVo {

    @Schema(title = "来源(ai/rule)")
    private String source;

    @Schema(title = "AI或规则摘要")
    private String summary;

    @Schema(title = "AI原始内容")
    private String rawAiContent;

    @Schema(title = "推荐搭配")
    private List<WardrobeOutfitSuggestionVo> suggestions;
}

package com.itwray.iw.wardrobe.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 搭配推荐 VO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@Schema(name = "搭配推荐VO")
public class WardrobeOutfitSuggestionVo {

    private String suggestionName;

    private String reason;

    private List<WardrobeOutfitItemVo> itemList;
}

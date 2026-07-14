package com.itwray.iw.wardrobe.model.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.itwray.iw.web.json.serialize.FullImageSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 搭配衣物 VO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@Schema(name = "搭配衣物VO")
public class WardrobeOutfitItemVo {

    private Integer itemId;

    private String itemName;

    @JsonSerialize(using = FullImageSerializer.class)
    private String itemImage;

    private Integer category;

    private Integer itemStyle;

    private Integer sort;
}

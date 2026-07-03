package com.itwray.iw.wardrobe.model.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.itwray.iw.web.json.serialize.FullImageSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 搭配分页 VO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@Schema(name = "搭配分页VO")
public class WardrobeOutfitPageVo {

    private Integer id;

    private String outfitName;

    @JsonSerialize(using = FullImageSerializer.class)
    private String coverImage;

    private String seasonTags;

    private String sceneTags;

    private String styleTags;

    private String customTags;

    private String colorSummary;

    private Integer itemCount;

    private Integer wearCount;

    private LocalDate lastWearDate;

    private Integer status;

    private List<WardrobeOutfitItemVo> itemList;
}

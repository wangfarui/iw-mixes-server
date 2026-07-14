package com.itwray.iw.wardrobe.model.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.itwray.iw.web.json.serialize.FullImageSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 衣物分页 VO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@Schema(name = "衣物分页VO")
public class WardrobeItemPageVo {

    private Integer id;

    private String itemName;

    @JsonSerialize(using = FullImageSerializer.class)
    @Schema(title = "封面图片，优先优化图")
    private String itemImage;

    @JsonSerialize(using = FullImageSerializer.class)
    @Schema(title = "原图")
    private String originalImage;

    @JsonSerialize(using = FullImageSerializer.class)
    @Schema(title = "优化后的图片")
    private String optimizedImage;

    private Integer category;

    private Integer itemStyle;

    private String colorName;

    private String colorHex;

    private String seasonTags;

    private String sceneTags;

    private String styleTags;

    private String brand;

    private String size;

    private String material;

    private String purchaseChannel;

    private String storageLocation;

    private LocalDate purchaseDate;

    private BigDecimal price;

    private String customTags;

    private Integer status;

    private Integer wearCount;

    private LocalDate lastWearDate;
}

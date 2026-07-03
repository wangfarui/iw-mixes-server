package com.itwray.iw.wardrobe.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 衣物识别草稿 VO
 *
 * @author codex
 * @since 2026-07-03
 */
@Data
@Schema(name = "衣物识别草稿VO")
public class WardrobeItemDraftVo {

    @Schema(title = "衣物名称")
    private String itemName;

    @Schema(title = "图片地址")
    private String itemImage;

    @Schema(title = "分类")
    private Integer category;

    @Schema(title = "分类名称")
    private String categoryName;

    @Schema(title = "颜色名称")
    private String colorName;

    @Schema(title = "颜色值")
    private String colorHex;

    @Schema(title = "季节标签")
    private String seasonTags;

    @Schema(title = "场景标签")
    private String sceneTags;

    @Schema(title = "风格标签")
    private String styleTags;

    @Schema(title = "品牌")
    private String brand;

    @Schema(title = "尺码")
    private String size;

    @Schema(title = "材质")
    private String material;

    @Schema(title = "购买渠道")
    private String purchaseChannel;

    @Schema(title = "收纳位置")
    private String storageLocation;

    @Schema(title = "购买日期")
    private String purchaseDate;

    @Schema(title = "价格")
    private BigDecimal price;

    @Schema(title = "自定义标签")
    private String customTags;

    @Schema(title = "状态")
    private Integer status;

    @Schema(title = "备注")
    private String remark;

    @Schema(title = "识别提示词")
    private String prompt;

    @Schema(title = "原始AI响应")
    private String rawAiContent;

    @Schema(title = "来源，ai/rule")
    private String source;
}

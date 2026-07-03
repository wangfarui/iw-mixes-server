package com.itwray.iw.wardrobe.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 衣物图片AI优化 DTO
 *
 * @author codex
 * @since 2026-07-03
 */
@Data
@Schema(name = "衣物图片AI优化DTO")
public class WardrobeItemImageOptimizeDto {

    @NotNull(message = "衣物ID不能为空")
    @Schema(title = "衣物ID")
    private Integer itemId;

    @NotBlank(message = "图片地址不能为空")
    @Schema(title = "当前衣物图片")
    private String imageUrl;

    @Schema(title = "衣物名称")
    private String itemName;

    @Schema(title = "衣物分类")
    private Integer category;

    @Schema(title = "分类名称")
    private String categoryName;

    @Schema(title = "颜色名称")
    private String colorName;

    @Schema(title = "季节标签")
    private String seasonTags;

    @Schema(title = "场景标签")
    private String sceneTags;

    @Schema(title = "风格标签")
    private String styleTags;

    @Schema(title = "品牌")
    private String brand;

    @Schema(title = "材质")
    private String material;

    @Schema(title = "自定义标签")
    private String customTags;

    @Schema(title = "备注")
    private String remark;

    @Schema(title = "用户补充优化要求")
    private String prompt;
}

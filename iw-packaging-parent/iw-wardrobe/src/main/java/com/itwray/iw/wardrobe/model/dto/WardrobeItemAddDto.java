package com.itwray.iw.wardrobe.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.itwray.iw.common.utils.DateUtils;
import com.itwray.iw.web.model.dto.AddDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 衣物新增 DTO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@Schema(name = "衣物新增DTO")
public class WardrobeItemAddDto implements AddDto {

    @NotBlank(message = "衣物名称不能为空")
    @Size(max = 64, message = "衣物名称不能超过64个字符")
    @Schema(title = "衣物名称")
    private String itemName;

    @Schema(title = "衣物图片")
    private String itemImage;

    @Schema(title = "分类")
    private Integer category;

    @Schema(title = "颜色名称")
    private String colorName;

    @Schema(title = "颜色hex")
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

    @Schema(title = "存放位置")
    private String storageLocation;

    @JsonFormat(pattern = DateUtils.DATE_FORMAT)
    @Schema(title = "购买日期")
    private LocalDate purchaseDate;

    @Schema(title = "价格")
    private BigDecimal price;

    @Schema(title = "自定义标签，逗号分隔")
    private String customTags;

    @Schema(title = "状态")
    private Integer status;

    @Size(max = 255, message = "备注不能超过255个字符")
    @Schema(title = "备注")
    private String remark;
}

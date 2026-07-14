package com.itwray.iw.wardrobe.model.dto;

import com.itwray.iw.web.model.dto.PageDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 衣物分页 DTO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "衣物分页DTO")
public class WardrobeItemPageDto extends PageDto {

    @Schema(title = "衣物名称")
    private String itemName;

    @Schema(title = "关键词，匹配名称或自定义标签")
    private String keyword;

    @Schema(title = "衣物品类")
    private Integer category;

    @Schema(title = "衣物款式")
    private Integer itemStyle;

    @Schema(title = "颜色名称")
    private String colorName;

    @Schema(title = "季节标签")
    private String season;

    @Schema(title = "场景标签")
    private String scene;

    @Schema(title = "风格标签")
    private String style;

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

    @Schema(title = "自定义标签")
    private String customTag;

    @Schema(title = "状态")
    private Integer status;

    @Schema(title = "穿着状态(1未穿 2最近穿过 3最少穿)")
    private Integer wearState;

    @Schema(title = "最低价格")
    private java.math.BigDecimal minPrice;

    @Schema(title = "最高价格")
    private java.math.BigDecimal maxPrice;

    @Schema(title = "购买开始日期")
    private java.time.LocalDate purchaseStartDate;

    @Schema(title = "购买结束日期")
    private java.time.LocalDate purchaseEndDate;

    @Schema(title = "闲置天数")
    private Integer idleDays;

    @Schema(title = "排序类型(recentWear,leastWear,mostWear,priceDesc,priceAsc,purchaseDate,idleDays,createTime)")
    private String sortType;
}

package com.itwray.iw.wardrobe.model.dto;

import com.itwray.iw.web.model.dto.PageDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 搭配分页 DTO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "搭配分页DTO")
public class WardrobeOutfitPageDto extends PageDto {

    @Schema(title = "搭配名称")
    private String outfitName;

    @Schema(title = "季节标签")
    private String season;

    @Schema(title = "场景标签")
    private String scene;

    @Schema(title = "风格标签")
    private String style;

    @Schema(title = "自定义标签")
    private String customTag;

    @Schema(title = "状态")
    private Integer status;

    @Schema(title = "排序类型(recentWear,leastWear,mostWear,createTime)")
    private String sortType;
}

package com.itwray.iw.wardrobe.model.dto;

import com.itwray.iw.web.model.dto.AddDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 搭配新增 DTO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@Schema(name = "搭配新增DTO")
public class WardrobeOutfitAddDto implements AddDto {

    @NotBlank(message = "搭配名称不能为空")
    @Size(max = 64, message = "搭配名称不能超过64个字符")
    @Schema(title = "搭配名称")
    private String outfitName;

    @Schema(title = "搭配封面")
    private String coverImage;

    @Schema(title = "季节标签")
    private String seasonTags;

    @Schema(title = "场景标签")
    private String sceneTags;

    @Schema(title = "风格标签")
    private String styleTags;

    @Schema(title = "自定义标签，逗号分隔")
    private String customTags;

    @Size(min = 1, message = "请至少选择一件衣物")
    @Schema(title = "衣物id列表")
    private List<Integer> itemIds;

    @Size(max = 255, message = "备注不能超过255个字符")
    @Schema(title = "备注")
    private String remark;
}

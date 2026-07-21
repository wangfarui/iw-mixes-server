package com.itwray.iw.wardrobe.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(title = "用户补充优化要求")
    private String prompt;
}

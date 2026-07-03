package com.itwray.iw.wardrobe.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 衣物批量新增 DTO
 *
 * @author codex
 * @since 2026-07-03
 */
@Data
@Schema(name = "衣物批量新增DTO")
public class WardrobeItemBatchAddDto {

    @Valid
    @NotEmpty(message = "衣物列表不能为空")
    @Schema(title = "衣物列表")
    private List<WardrobeItemAddDto> itemList;
}

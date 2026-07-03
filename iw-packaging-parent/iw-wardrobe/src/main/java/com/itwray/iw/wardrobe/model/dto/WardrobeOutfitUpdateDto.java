package com.itwray.iw.wardrobe.model.dto;

import com.itwray.iw.web.model.dto.UpdateDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 搭配更新 DTO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "搭配更新DTO")
public class WardrobeOutfitUpdateDto extends WardrobeOutfitAddDto implements UpdateDto {

    @NotNull(message = "id不能为空")
    @Schema(title = "id")
    private Integer id;
}

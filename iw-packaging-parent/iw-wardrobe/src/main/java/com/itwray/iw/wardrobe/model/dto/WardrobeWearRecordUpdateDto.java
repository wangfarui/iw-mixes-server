package com.itwray.iw.wardrobe.model.dto;

import com.itwray.iw.web.model.dto.UpdateDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 穿着记录更新 DTO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "穿着记录更新DTO")
public class WardrobeWearRecordUpdateDto extends WardrobeWearRecordAddDto implements UpdateDto {

    @NotNull(message = "id不能为空")
    @Schema(title = "id")
    private Integer id;
}

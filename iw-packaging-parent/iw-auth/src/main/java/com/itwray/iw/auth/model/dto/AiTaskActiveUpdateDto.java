package com.itwray.iw.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * AI任务活跃时间更新DTO
 *
 * @author wray
 * @since 2026-07-28
 */
@Data
@Schema(name = "AI任务活跃时间更新DTO")
public class AiTaskActiveUpdateDto {

    @Schema(title = "任务id")
    @NotNull(message = "任务id不能为空")
    private Integer id;
}

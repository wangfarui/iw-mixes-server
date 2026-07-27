package com.itwray.iw.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * AI任务置顶更新DTO
 *
 * @author wray
 * @since 2026-07-24
 */
@Data
@Schema(name = "AI任务置顶更新DTO")
public class AiTaskTopUpdateDto {

    @Schema(title = "任务id")
    @NotNull(message = "任务id不能为空")
    private Integer id;

    @Schema(title = "是否置顶(0否 1是)")
    @NotNull(message = "置顶状态不能为空")
    @Min(value = 0, message = "置顶状态只能是0或1")
    @Max(value = 1, message = "置顶状态只能是0或1")
    private Integer isTop;
}

package com.itwray.iw.external.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工具使用记录请求。
 *
 * @author wray
 * @since 2026/7/27
 */
@Data
@Schema(name = "工具使用记录请求")
public class ToolUsageRecordDto {

    @NotBlank(message = "工具标识不能为空")
    @Size(max = 64, message = "工具标识长度不能超过64")
    @Schema(description = "稳定工具标识")
    private String toolKey;
}

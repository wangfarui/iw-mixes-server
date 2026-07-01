package com.itwray.iw.external.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 公开工具AI生成请求
 *
 * @author wray
 * @since 2026/7/1
 */
@Data
@Schema(name = "公开工具AI生成请求")
public class ToolAiGenerateDto {

    @NotBlank(message = "业务类型不能为空")
    @Size(max = 64, message = "业务类型长度不能超过64")
    @Schema(description = "业务类型")
    private String businessType;

    @NotNull(message = "消息体不能为空")
    @Schema(description = "消息体")
    private Map<String, Object> message;
}

package com.itwray.iw.external.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(name = "参考图生成请求DTO")
public class ReferenceImageGenerateDto {

    @NotBlank(message = "参考图片不能为空")
    @Schema(title = "可信参考图片URL")
    private String sourceImageUrl;

    @NotBlank(message = "提示词不能为空")
    @Schema(title = "最终生成提示词")
    private String prompt;
}

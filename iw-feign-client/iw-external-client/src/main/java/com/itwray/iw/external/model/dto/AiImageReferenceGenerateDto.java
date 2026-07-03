package com.itwray.iw.external.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AI参考图生成请求 DTO
 *
 * @author codex
 * @since 2026-07-03
 */
@Data
@Schema(name = "AI参考图生成请求DTO")
public class AiImageReferenceGenerateDto {

    @NotBlank(message = "提示词不能为空")
    @Schema(title = "生成提示词")
    private String prompt;

    @NotBlank(message = "参考图片不能为空")
    @Schema(title = "参考图片URL")
    private String imageUrl;

    @NotBlank(message = "业务类型不能为空")
    @Schema(title = "业务类型")
    private String businessType;

    @Schema(title = "业务自定义分类")
    private String businessCustomCategory;

    @NotBlank(message = "业务ID不能为空")
    @Schema(title = "业务ID")
    private String businessId;
}

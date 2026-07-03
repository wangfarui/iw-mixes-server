package com.itwray.iw.external.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * AI参考图生成响应 VO
 *
 * @author codex
 * @since 2026-07-03
 */
@Data
@Schema(name = "AI参考图生成响应VO")
public class AiImageReferenceGenerateVo {

    @Schema(title = "AI供应商任务ID")
    private String taskId;

    @Schema(title = "任务状态")
    private String status;

    @Schema(title = "生成图片Base64")
    private String imageBase64;

    @Schema(title = "图片MIME类型")
    private String mimeType;

    @Schema(title = "模型优化后的提示词")
    private String revisedPrompt;

    @Schema(title = "错误信息")
    private String errorMessage;
}

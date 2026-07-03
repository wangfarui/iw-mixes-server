package com.itwray.iw.wardrobe.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 衣物图片识别草稿 DTO
 *
 * @author codex
 * @since 2026-07-03
 */
@Data
@Schema(name = "衣物图片识别草稿DTO")
public class WardrobeItemRecognizeDto {

    @NotBlank(message = "图片地址不能为空")
    @Schema(title = "已上传图片地址")
    private String imageUrl;

    @Schema(title = "用户补充提示")
    private String prompt;

    @Schema(title = "是否调用外部AI，false时只生成本地草稿")
    private Boolean useAi;
}

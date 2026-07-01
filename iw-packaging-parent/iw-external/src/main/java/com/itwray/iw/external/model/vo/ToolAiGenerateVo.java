package com.itwray.iw.external.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 公开工具AI生成响应
 *
 * @author wray
 * @since 2026/7/1
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "公开工具AI生成响应")
public class ToolAiGenerateVo {

    @Schema(description = "请求ID")
    private String requestId;

    @Schema(description = "业务类型")
    private String businessType;

    @Schema(description = "AI原始内容")
    private String content;

    @Schema(description = "结构化结果")
    private List<String> items;

    @Schema(description = "模型")
    private String model;

    @Schema(description = "使用Token数量")
    private Integer totalTokens;
}

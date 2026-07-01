package com.itwray.iw.external.model.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 同步对话结果
 *
 * @author wray
 * @since 2026/7/1
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiCompletionResult {

    private String content;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private String finishReason;

    private boolean success;

    private String failReason;
}

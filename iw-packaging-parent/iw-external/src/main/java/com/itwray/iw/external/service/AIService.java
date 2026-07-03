package com.itwray.iw.external.service;

import cn.hutool.http.HttpResponse;
import com.itwray.iw.external.model.bo.AIMessage;
import com.itwray.iw.external.model.bo.AiCompletionResult;
import com.itwray.iw.external.model.dto.AiImageReferenceGenerateDto;
import com.itwray.iw.external.model.dto.AiStructuredChatDto;
import com.itwray.iw.external.model.vo.AiImageReferenceGenerateVo;
import com.itwray.iw.external.model.vo.AiStructuredChatVo;
import org.springframework.lang.NonNull;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

/**
 * AI 接口服务
 *
 * @author farui.wang
 * @since 2025/6/19
 */
public interface AIService {

    String answer(String content);

    String chat(String content);

    AiStructuredChatVo structuredChat(AiStructuredChatDto dto);

    AiImageReferenceGenerateVo startReferenceGenerateImage(AiImageReferenceGenerateDto dto);

    AiImageReferenceGenerateVo getReferenceGenerateImageStatus(String taskId);

    /**
     * 同步非流式AI对话
     *
     * @param messages    消息列表
     * @param model       模型
     * @param maxTokens   最大输出token
     * @param temperature 温度
     * @return AI对话结果
     */
    AiCompletionResult complete(List<AIMessage> messages, String model, Integer maxTokens, BigDecimal temperature);

    /**
     * 流式对话
     * @param chatId 对话id
     * @param prompt 当前对话内容
     * @return AI回答内容
     */
    HttpResponse streamChat(@NonNull String chatId, @NonNull String prompt) throws IOException;
}

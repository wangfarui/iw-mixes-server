package com.itwray.iw.external.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.itwray.iw.external.model.bo.AIMessage;
import com.itwray.iw.external.model.bo.AIRequestBody;
import com.itwray.iw.external.model.bo.AIResponseBody;
import com.itwray.iw.external.model.bo.AIResponseFormat;
import com.itwray.iw.external.model.bo.AiCompletionResult;
import com.itwray.iw.external.model.dto.AiImageReferenceGenerateDto;
import com.itwray.iw.external.model.dto.AiChatMessageDto;
import com.itwray.iw.external.model.dto.AiStructuredChatDto;
import com.itwray.iw.external.model.vo.AiImageReferenceGenerateVo;
import com.itwray.iw.external.model.enums.ExternalRedisKeyEnum;
import com.itwray.iw.external.model.vo.AiStructuredChatVo;
import com.itwray.iw.external.service.AIService;
import com.itwray.iw.starter.redis.RedisUtil;
import com.itwray.iw.web.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * AI 服务实现层
 *
 * @author farui.wang
 * @since 2025/6/19
 */
@Service
@Slf4j
public class AIServiceImpl implements AIService {

    private static final String IMAGE_PROVIDER_OPENAI = "openai";
    private static final String IMAGE_PROVIDER_OPENAI_RESPONSES = "openai-responses";
    private static final String IMAGE_PROVIDER_GEMINI = "gemini";
    private static final String OPENAI_DEFAULT_IMAGE_MODEL = "gpt-image-2";
    private static final String OPENAI_DEFAULT_IMAGES_EDITS_URL = "https://api.openai.com/v1/images/edits";
    private static final String GEMINI_DEFAULT_IMAGE_MODEL = "gemini-3.1-flash-image";
    private static final String GEMINI_DEFAULT_API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final int IMAGE_REFERENCE_DOWNLOAD_TIMEOUT_MS = 30 * 1000;
    private static final int IMAGE_GENERATE_TIMEOUT_MS = 60 * 1000;
    private static final int OPENAI_IMAGE_GENERATE_TIMEOUT_MS = 8 * 60 * 1000;
    private static final int GEMINI_INLINE_IMAGE_MAX_BYTES = 20 * 1024 * 1024;

    @Value("${iw.ai.default.api-url:https://api.deepseek.com/chat/completions}")
    private String defaultApiUrl;

    @Value("${iw.ai.default.api-key:}")
    private String defaultApiKey;

    @Value("${iw.ai.default.model:gpt-5.5}")
    private String defaultModel;

    @Value("${iw.ai.image.provider:gemini}")
    private String imageProvider;

    @Value("${iw.ai.image.model:}")
    private String imageModel;

    @Value("${iw.ai.image.api-url:${iw.ai.default.api-url:https://api.deepseek.com/chat/completions}}")
    private String imageApiUrl;

    @Value("${iw.ai.image.api-key:${iw.ai.default.api-key:}}")
    private String imageApiKey;

    @Override
    public String answer(String content) {
        if (StringUtils.isBlank(content)) {
            return "请输入您的对话内容";
        }
        List<AIMessage> messages = new ArrayList<>();
        messages.add(new AIMessage("你是一个百科助手,针对用户提问,可以精准且简要的回复问题.", "system"));
        messages.add(new AIMessage(content, "user"));
        return this.requestChatCompletion(messages, null, 1024, new BigDecimal("1.3"));
    }

    @Override
    public String chat(String content) {
        List<AIMessage> messages = new ArrayList<>();
        messages.add(new AIMessage(content, "user"));

        AIRequestBody body = new AIRequestBody();
        body.setMessages(messages);
        body.setModel(this.resolveModel(null));
        body.setFrequency_penalty(0);
        body.setMax_tokens(1024);
        body.setPresence_penalty(0);
        body.setResponse_format(new AIResponseFormat("text"));
        body.setStream(true);
        body.setTemperature(new BigDecimal("1.0"));
        body.setTop_p(new BigDecimal("1"));
        body.setLogprobs(false);

        HttpRequest httpRequest = HttpUtil.createPost(this.defaultApiUrl)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream") // 声明接收流式数据
                .header("Authorization", this.defaultApiKey)
                .body(JSONUtil.toJsonStr(body))
                // 设置长超时
                .timeout(60 * 1000);

        // 使用 StringBuilder 拼接完整内容
        StringBuilder fullContent = new StringBuilder();
        try (HttpResponse response = httpRequest.executeAsync()) {
            Scanner scanner = new Scanner(response.bodyStream(), StandardCharsets.UTF_8);

            // 逐行读取流式数据
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.startsWith("data:") && !line.equals("data: [DONE]")) {
                    String jsonStr = line.substring(5).trim();
                    if (JSONUtil.isTypeJSON(jsonStr)) {
                        JSONObject json = JSONUtil.parseObj(jsonStr);
                        if (json.containsKey("choices")) {
                            String chunkContent = json.getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("delta")
                                    .getStr("content", "");
                            fullContent.append(chunkContent);
                            System.out.print(chunkContent); // 实时打印到控制台
                        }

                        // 检查是否因长度截断
                        String finishReason = json.getJSONArray("choices")
                                .getJSONObject(0)
                                .getStr("finish_reason");
                        if ("length".equals(finishReason)) {
                            System.out.println("\n警告：回答因长度限制可能不完整！");
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("请求失败: " + e.getMessage());
            e.printStackTrace();
        }

        return fullContent.toString();
    }

    @Override
    public AiStructuredChatVo structuredChat(AiStructuredChatDto dto) {
        List<AIMessage> messages = dto.getMessages()
                .stream()
                .map(this::convertMessage)
                .toList();

        String content = this.requestChatCompletion(
                messages,
                null,
                dto.getMaxTokens() == null ? 1024 : dto.getMaxTokens(),
                BigDecimal.valueOf(dto.getTemperature() == null ? 0.3D : dto.getTemperature())
        );

        AiStructuredChatVo vo = new AiStructuredChatVo();
        vo.setContent(content);
        return vo;
    }

    @Override
    public AiImageReferenceGenerateVo startReferenceGenerateImage(AiImageReferenceGenerateDto dto) {
        if (this.isOpenAiResponsesImageProvider()) {
            return this.startOpenAiResponsesReferenceGenerateImage(dto);
        }
        if (this.isOpenAiImageProvider()) {
            return this.generateOpenAiReferenceImage(dto);
        }
        if (this.isGeminiImageProvider()) {
            return this.generateGeminiReferenceImage(dto);
        }
        return this.failedReferenceImageResponse("不支持的AI图片生成供应商：" + StringUtils.defaultString(this.imageProvider));
    }

    private AiImageReferenceGenerateVo generateOpenAiReferenceImage(AiImageReferenceGenerateDto dto) {
        JSONObject requestBody = new JSONObject();
        requestBody.set("model", this.resolveOpenAiImageModel());
        requestBody.set("prompt", dto.getPrompt());
        requestBody.set("images", List.of(Map.of("image_url", dto.getImageUrl())));
        requestBody.set("n", 1);
        requestBody.set("output_format", "png");

        try (HttpResponse response = HttpUtil.createPost(this.resolveOpenAiImagesEditsUrl())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", this.imageApiKey)
                .body(JSONUtil.toJsonStr(requestBody))
                .timeout(OPENAI_IMAGE_GENERATE_TIMEOUT_MS)
                .execute()) {
            if (!response.isOk()) {
                log.error("AIService#generateOpenAiReferenceImage 请求失败, status: {}, body: {}", response.getStatus(), response.body());
                if (response.getStatus() == 429) {
                    return this.tooManyRequestsReferenceImageResponse();
                }
                return this.failedReferenceImageResponse("OpenAI图片生成失败，供应商HTTP "
                        + response.getStatus() + this.formatProviderError(response.body()));
            }
            return this.parseOpenAiImageResponse(response.body());
        } catch (Exception e) {
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            log.error("AIService#generateOpenAiReferenceImage 请求异常", e);
            return this.failedReferenceImageResponse("OpenAI图片生成异常：" + StringUtils.defaultString(e.getMessage()));
        }
    }

    private AiImageReferenceGenerateVo startOpenAiResponsesReferenceGenerateImage(AiImageReferenceGenerateDto dto) {
        JSONObject requestBody = new JSONObject();
        requestBody.set("model", this.resolveOpenAiResponsesImageModel());
        requestBody.set("background", true);
        requestBody.set("input", List.of(Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("type", "input_text", "text", dto.getPrompt()),
                        Map.of("type", "input_image", "image_url", dto.getImageUrl())
                )
        )));
        requestBody.set("tools", List.of(Map.of("type", "image_generation")));

        try (HttpResponse response = HttpUtil.createPost(this.resolveResponsesUrl())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", this.imageApiKey)
                .body(JSONUtil.toJsonStr(requestBody))
                .timeout(30 * 1000)
                .execute()) {
            if (!response.isOk()) {
                log.error("AIService#startReferenceGenerateImage 请求失败, status: {}, body: {}", response.getStatus(), response.body());
                if (response.getStatus() == 429) {
                    return this.tooManyRequestsReferenceImageResponse();
                }
                return this.failedReferenceImageResponse("OpenAI图片生成任务启动失败，供应商HTTP "
                        + response.getStatus() + this.formatProviderError(response.body()));
            }
            return this.parseReferenceImageResponse(response.body());
        } catch (Exception e) {
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            log.error("AIService#startReferenceGenerateImage 请求异常", e);
            return this.failedReferenceImageResponse("OpenAI图片生成任务启动异常：" + StringUtils.defaultString(e.getMessage()));
        }
    }

    @Override
    public AiImageReferenceGenerateVo getReferenceGenerateImageStatus(String taskId) {
        if (!this.isOpenAiResponsesImageProvider()) {
            return this.failedReferenceImageResponse("当前AI图片生成供应商不使用后台任务状态查询");
        }
        if (StringUtils.isBlank(taskId)) {
            throw new BusinessException("AI图片生成任务ID不能为空");
        }
        try (HttpResponse response = HttpUtil.createGet(this.resolveResponsesUrl() + "/" + taskId)
                .header("Accept", "application/json")
                .header("Authorization", this.imageApiKey)
                .timeout(30 * 1000)
                .execute()) {
            if (!response.isOk()) {
                log.error("AIService#getReferenceGenerateImageStatus 请求失败, taskId: {}, status: {}, body: {}", taskId, response.getStatus(), response.body());
                if (response.getStatus() == 429) {
                    return this.tooManyRequestsReferenceImageResponse();
                }
                return this.failedReferenceImageResponse("OpenAI图片生成任务查询失败，供应商HTTP "
                        + response.getStatus() + this.formatProviderError(response.body()));
            }
            return this.parseReferenceImageResponse(response.body());
        } catch (Exception e) {
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            log.error("AIService#getReferenceGenerateImageStatus 请求异常, taskId: {}", taskId, e);
            return this.failedReferenceImageResponse("OpenAI图片生成任务查询异常：" + StringUtils.defaultString(e.getMessage()));
        }
    }

    private AiImageReferenceGenerateVo generateGeminiReferenceImage(AiImageReferenceGenerateDto dto) {
        ImagePayload referenceImage = this.downloadImagePayload(dto.getImageUrl());

        JSONObject requestBody = new JSONObject();
        requestBody.set("contents", List.of(Map.of(
                "parts", List.of(
                        Map.of("text", dto.getPrompt()),
                        Map.of("inline_data", Map.of(
                                "mime_type", referenceImage.mimeType(),
                                "data", referenceImage.base64()
                        ))
                )
        )));
        requestBody.set("generationConfig", Map.of("responseModalities", List.of("TEXT", "IMAGE")));

        try (HttpResponse response = HttpUtil.createPost(this.resolveGeminiGenerateContentUrl())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("x-goog-api-key", this.resolveGeminiApiKey())
                .body(JSONUtil.toJsonStr(requestBody))
                .timeout(IMAGE_GENERATE_TIMEOUT_MS)
                .execute()) {
            if (!response.isOk()) {
                log.error("AIService#generateGeminiReferenceImage 请求失败, status: {}, body: {}", response.getStatus(), response.body());
                if (response.getStatus() == 429) {
                    return this.tooManyRequestsReferenceImageResponse();
                }
                return this.failedReferenceImageResponse("Gemini图片生成失败，供应商HTTP "
                        + response.getStatus() + this.formatProviderError(response.body()));
            }
            return this.parseGeminiImageResponse(response.body());
        } catch (Exception e) {
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            log.error("AIService#generateGeminiReferenceImage 请求异常", e);
            return this.failedReferenceImageResponse("Gemini图片生成异常：" + StringUtils.defaultString(e.getMessage()));
        }
    }

    @Override
    public AiCompletionResult complete(List<AIMessage> messages, String model, Integer maxTokens, BigDecimal temperature) {
        return this.requestChatCompletionResult(messages, model, maxTokens, temperature);
    }

    @Override
    public HttpResponse streamChat(@NonNull String chatId, @NonNull String prompt) {
        log.info("收到对话: chatId: {}, prompt: {}", chatId, prompt);
        List<AIMessage> messageList = new ArrayList<>();
        // 获取之前的对话内容
        Set<AIMessage> messageSet = RedisUtil.members(ExternalRedisKeyEnum.AI_CHAT_CONTENT.getKey(chatId), AIMessage.class);
        if (CollUtil.isEmpty(messageSet)) {
            AIMessage firstMessage = new AIMessage("你是一个百科助手,针对用户提问,可以精准且简要的回复问题.", "system");
            Long sortValue = RedisUtil.incrementOne(ExternalRedisKeyEnum.AI_CHAT_SORT.getKey(chatId));
            firstMessage.setInnerSort(sortValue);
            // 添加到AI消息体和缓存中
            messageList.add(firstMessage);
            RedisUtil.sSetJson(ExternalRedisKeyEnum.AI_CHAT_CONTENT.getKey(chatId), firstMessage);
        } else {
            messageList.addAll(messageSet);
        }
        // 构建当前对话内容
        AIMessage userMessage = new AIMessage(prompt, "user");
        Long sortValue = RedisUtil.incrementOne(ExternalRedisKeyEnum.AI_CHAT_SORT.getKey(chatId));
        userMessage.setInnerSort(sortValue);
        // 添加到AI消息体和缓存中
        messageList.add(userMessage);
        RedisUtil.sSetJson(ExternalRedisKeyEnum.AI_CHAT_CONTENT.getKey(chatId), userMessage);
        // 更新对话内容的缓存时间
        ExternalRedisKeyEnum.AI_CHAT_CONTENT.setExpire(chatId);
        ExternalRedisKeyEnum.AI_CHAT_SORT.setExpire(chatId);

        // 构建AI请求体
        AIRequestBody requestBody = new AIRequestBody();
        requestBody.setMessages(messageList.stream().sorted(Comparator.comparing(AIMessage::getInnerSort)).toList());
        requestBody.setModel(this.resolveModel(null));
        requestBody.setFrequency_penalty(0);
        requestBody.setMax_tokens(1024);
        requestBody.setPresence_penalty(0);
        requestBody.setResponse_format(new AIResponseFormat("text"));
        requestBody.setStream(true);
        requestBody.setTemperature(new BigDecimal("1.3"));
        requestBody.setTop_p(new BigDecimal("1"));
        requestBody.setLogprobs(false);

        return HttpUtil.createPost(this.defaultApiUrl)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", this.defaultApiKey)
                .body(JSONUtil.toJsonStr(requestBody))
                .timeout(60 * 1000)
                .executeAsync();
    }

    private AIMessage convertMessage(AiChatMessageDto dto) {
        AIMessage message = new AIMessage();
        message.setRole(dto.getRole());
        message.setContent(dto.getContent());
        message.setName(dto.getName());
        return message;
    }

    private String requestChatCompletion(List<AIMessage> messages, String model, Integer maxTokens, BigDecimal temperature) {
        return this.requestChatCompletionResult(messages, model, maxTokens, temperature).getContent();
    }

    private AiCompletionResult requestChatCompletionResult(List<AIMessage> messages, String model, Integer maxTokens, BigDecimal temperature) {
        String resolvedModel = this.resolveModel(model);
        AIRequestBody requestBody = new AIRequestBody();
        requestBody.setMessages(messages);
        requestBody.setModel(resolvedModel);
        requestBody.setFrequency_penalty(0);
        requestBody.setMax_tokens(maxTokens);
        requestBody.setPresence_penalty(0);
        requestBody.setResponse_format(new AIResponseFormat("text"));
        requestBody.setStream(false);
        requestBody.setTemperature(temperature);
        requestBody.setTop_p(new BigDecimal("1"));
        requestBody.setLogprobs(false);

        try (HttpResponse response = HttpUtil.createPost(this.defaultApiUrl)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", this.defaultApiKey)
                .body(JSONUtil.toJsonStr(requestBody))
                .execute()) {
            if (!response.isOk()) {
                log.error("AIService#requestChatCompletion 请求失败, status: {}, body: {}", response.getStatus(), response.body());
                return AiCompletionResult.builder()
                        .content("服务请求失败, 请重试")
                        .model(resolvedModel)
                        .success(false)
                        .failReason("AI HTTP status: " + response.getStatus())
                        .build();
            }
            AIResponseBody responseBody = JSONUtil.toBean(response.body(), AIResponseBody.class);
            if (responseBody != null && CollUtil.isNotEmpty(responseBody.getChoices())) {
                AIResponseBody.Choice choice = responseBody.getChoices().get(0);
                if (choice != null && choice.getMessage() != null) {
                    AIResponseBody.Usage usage = responseBody.getUsage();
                    return AiCompletionResult.builder()
                            .content(Objects.toString(choice.getMessage().getContent(), ""))
                            .model(StringUtils.defaultIfBlank(responseBody.getModel(), resolvedModel))
                            .promptTokens(usage == null ? null : usage.getPrompt_tokens())
                            .completionTokens(usage == null ? null : usage.getCompletion_tokens())
                            .totalTokens(usage == null ? null : usage.getTotal_tokens())
                            .finishReason(choice.getFinish_reason())
                            .success(true)
                            .build();
                }
            }
        } catch (Exception e) {
            log.error("AIService#requestChatCompletion 请求异常", e);
            return AiCompletionResult.builder()
                    .content("服务请求超时, 请重试")
                    .model(resolvedModel)
                    .success(false)
                    .failReason(e.getMessage())
                    .build();
        }

        return AiCompletionResult.builder()
                .content("服务请求超时, 请重试")
                .model(resolvedModel)
                .success(false)
                .failReason("AI response has no choices")
                .build();
    }

    private String resolveModel(String model) {
        return StringUtils.defaultIfBlank(model, StringUtils.defaultIfBlank(this.defaultModel, "gpt-5.5"));
    }

    private String resolveOpenAiImageModel() {
        return StringUtils.defaultIfBlank(this.imageModel, OPENAI_DEFAULT_IMAGE_MODEL);
    }

    private String resolveOpenAiResponsesImageModel() {
        return StringUtils.defaultIfBlank(this.imageModel, this.resolveModel(null));
    }

    private String resolveGeminiImageModel() {
        return StringUtils.defaultIfBlank(this.imageModel, GEMINI_DEFAULT_IMAGE_MODEL);
    }

    private boolean isOpenAiImageProvider() {
        return StringUtils.equalsAnyIgnoreCase(StringUtils.trimToEmpty(this.imageProvider), IMAGE_PROVIDER_OPENAI);
    }

    private boolean isOpenAiResponsesImageProvider() {
        return StringUtils.equalsAnyIgnoreCase(StringUtils.trimToEmpty(this.imageProvider), IMAGE_PROVIDER_OPENAI_RESPONSES);
    }

    private boolean isGeminiImageProvider() {
        String provider = StringUtils.trimToEmpty(this.imageProvider);
        return StringUtils.isBlank(provider) || StringUtils.equalsAnyIgnoreCase(provider, IMAGE_PROVIDER_GEMINI, "google");
    }

    private String resolveResponsesUrl() {
        String url = StringUtils.trimToEmpty(this.imageApiUrl);
        if (StringUtils.endsWith(url, "/chat/completions")) {
            return StringUtils.removeEnd(url, "/chat/completions") + "/responses";
        }
        return "https://api.openai.com/v1/responses";
    }

    private String resolveOpenAiImagesEditsUrl() {
        String url = StringUtils.trimToEmpty(this.imageApiUrl);
        if (StringUtils.isBlank(url) || this.isKnownNonOpenAiImageApiUrl(url)) {
            return OPENAI_DEFAULT_IMAGES_EDITS_URL;
        }

        String cleanUrl = StringUtils.removeEnd(StringUtils.substringBefore(url, "?"), "/");
        if (StringUtils.endsWith(cleanUrl, "/images/edits")) {
            return cleanUrl;
        }
        if (StringUtils.endsWith(cleanUrl, "/images/generations")) {
            return this.appendOpenAiImagesEditsPath(StringUtils.substringBefore(cleanUrl, "/images/generations"));
        }
        if (StringUtils.endsWith(cleanUrl, "/responses")) {
            return this.appendOpenAiImagesEditsPath(StringUtils.removeEnd(cleanUrl, "/responses"));
        }
        if (StringUtils.endsWith(cleanUrl, "/chat/completions")) {
            return this.appendOpenAiImagesEditsPath(StringUtils.removeEnd(cleanUrl, "/chat/completions"));
        }
        return this.appendOpenAiImagesEditsPath(cleanUrl);
    }

    private boolean isKnownNonOpenAiImageApiUrl(String url) {
        return StringUtils.contains(StringUtils.lowerCase(StringUtils.trimToEmpty(url)), "api.deepseek.com");
    }

    private String appendOpenAiImagesEditsPath(String baseUrl) {
        String normalized = StringUtils.removeEnd(StringUtils.trimToEmpty(baseUrl), "/");
        if (StringUtils.endsWith(normalized, "/v1")) {
            return normalized + "/images/edits";
        }
        return normalized + "/v1/images/edits";
    }

    private String resolveGeminiGenerateContentUrl() {
        String model = this.normalizeGeminiModelName(this.resolveGeminiImageModel());
        String url = StringUtils.trimToEmpty(this.imageApiUrl);
        if (StringUtils.isBlank(url) || this.isKnownNonGeminiChatCompletionUrl(url)) {
            return this.buildGeminiGenerateContentUrl(GEMINI_DEFAULT_API_BASE_URL, model);
        }

        String cleanUrl = StringUtils.removeEnd(StringUtils.substringBefore(url, "?"), "/");
        if (StringUtils.endsWith(cleanUrl, ":generateContent")) {
            return cleanUrl;
        }
        if (StringUtils.contains(cleanUrl, "/models/")) {
            return this.buildGeminiGenerateContentUrl(StringUtils.substringBefore(cleanUrl, "/models/"), model);
        }
        if (StringUtils.contains(url, "/openai/chat/completions")) {
            return this.buildGeminiGenerateContentUrl(StringUtils.substringBefore(cleanUrl, "/openai/chat/completions"), model);
        }
        if (StringUtils.endsWith(cleanUrl, "/chat/completions")) {
            String baseUrl = StringUtils.removeEnd(cleanUrl, "/chat/completions");
            if (StringUtils.endsWith(baseUrl, "/openai")) {
                baseUrl = StringUtils.removeEnd(baseUrl, "/openai");
            }
            return this.buildGeminiGenerateContentUrl(this.toGeminiNativeBaseUrl(baseUrl), model);
        }
        if (StringUtils.endsWith(cleanUrl, "/interactions")) {
            return this.buildGeminiGenerateContentUrl(this.toGeminiNativeBaseUrl(StringUtils.removeEnd(cleanUrl, "/interactions")), model);
        }
        if (StringUtils.endsWith(cleanUrl, "/models")) {
            return cleanUrl + "/" + model + ":generateContent";
        }
        return this.buildGeminiGenerateContentUrl(this.toGeminiNativeBaseUrl(cleanUrl), model);
    }

    private boolean isKnownNonGeminiChatCompletionUrl(String url) {
        String normalized = StringUtils.lowerCase(StringUtils.trimToEmpty(url));
        return StringUtils.contains(normalized, "api.deepseek.com")
                || StringUtils.contains(normalized, "api.openai.com");
    }

    private String normalizeGeminiModelName(String model) {
        String normalized = StringUtils.removeEnd(StringUtils.trimToEmpty(model), ":generateContent");
        if (StringUtils.contains(normalized, "/models/")) {
            normalized = StringUtils.substringAfter(normalized, "/models/");
        }
        if (StringUtils.startsWith(normalized, "models/")) {
            normalized = StringUtils.substringAfter(normalized, "models/");
        }
        return StringUtils.defaultIfBlank(normalized, GEMINI_DEFAULT_IMAGE_MODEL);
    }

    private String toGeminiNativeBaseUrl(String baseUrl) {
        String normalized = StringUtils.removeEnd(StringUtils.trimToEmpty(baseUrl), "/");
        if (StringUtils.endsWith(normalized, "/v1beta")) {
            return normalized;
        }
        if (StringUtils.endsWith(normalized, "/v1")) {
            return StringUtils.removeEnd(normalized, "/v1") + "/v1beta";
        }
        return normalized + "/v1beta";
    }

    private String buildGeminiGenerateContentUrl(String baseUrl, String model) {
        return StringUtils.removeEnd(baseUrl, "/") + "/models/" + model + ":generateContent";
    }

    private String resolveGeminiApiKey() {
        String key = StringUtils.trimToEmpty(this.imageApiKey);
        if (StringUtils.startsWithIgnoreCase(key, "Bearer ")) {
            return StringUtils.trimToEmpty(StringUtils.substringAfter(key, " "));
        }
        return key;
    }

    private AiImageReferenceGenerateVo parseReferenceImageResponse(String body) {
        AiImageReferenceGenerateVo vo = new AiImageReferenceGenerateVo();
        vo.setMimeType("image/png");
        JSONObject responseBody = JSONUtil.parseObj(body);
        vo.setTaskId(StringUtils.trimToEmpty(responseBody.getStr("id")));
        vo.setStatus(StringUtils.defaultIfBlank(responseBody.getStr("status"), "unknown"));
        Object error = responseBody.get("error");
        if (error != null) {
            vo.setErrorMessage(StringUtils.left(String.valueOf(error), 500));
        }
        if (!responseBody.containsKey("output")) {
            return vo;
        }
        for (Object item : responseBody.getJSONArray("output")) {
            JSONObject output = JSONUtil.parseObj(item);
            if (!StringUtils.equals(output.getStr("type"), "image_generation_call")) {
                continue;
            }
            vo.setImageBase64(StringUtils.trimToEmpty(output.getStr("result")));
            vo.setRevisedPrompt(StringUtils.trimToEmpty(output.getStr("revised_prompt")));
            return vo;
        }
        return vo;
    }

    private AiImageReferenceGenerateVo parseOpenAiImageResponse(String body) {
        AiImageReferenceGenerateVo vo = new AiImageReferenceGenerateVo();
        vo.setMimeType("image/png");
        vo.setStatus("completed");
        JSONObject responseBody = JSONUtil.parseObj(body);
        vo.setTaskId(StringUtils.trimToEmpty(responseBody.getStr("id")));

        Object error = responseBody.get("error");
        if (error != null) {
            vo.setStatus("failed");
            vo.setErrorMessage(StringUtils.left(String.valueOf(error), 500));
            return vo;
        }

        String outputFormat = StringUtils.trimToEmpty(responseBody.getStr("output_format"));
        JSONArray data = responseBody.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            vo.setStatus("failed");
            vo.setErrorMessage("OpenAI图片生成响应未包含图片数据");
            return vo;
        }
        for (Object item : data) {
            JSONObject imageObject = JSONUtil.parseObj(item);
            if (this.fillOpenAiImageFromDataItem(vo, imageObject, outputFormat)) {
                return vo;
            }
        }

        vo.setStatus("failed");
        vo.setErrorMessage("OpenAI图片生成响应未包含Base64图片数据");
        return vo;
    }

    private boolean fillOpenAiImageFromDataItem(AiImageReferenceGenerateVo vo, JSONObject imageObject, String outputFormat) {
        String imageBase64 = StringUtils.trimToEmpty(imageObject.getStr("b64_json"));
        if (StringUtils.isNotBlank(imageBase64)) {
            vo.setImageBase64(imageBase64);
            vo.setMimeType(this.openAiOutputMimeType(outputFormat));
            vo.setRevisedPrompt(StringUtils.trimToEmpty(imageObject.getStr("revised_prompt")));
            return true;
        }

        String imageUrl = StringUtils.trimToEmpty(imageObject.getStr("url"));
        if (!StringUtils.startsWithIgnoreCase(imageUrl, "data:")) {
            return false;
        }
        ImagePayload imagePayload = this.parseDataUrlImagePayload(imageUrl);
        vo.setImageBase64(imagePayload.base64());
        vo.setMimeType(imagePayload.mimeType());
        vo.setRevisedPrompt(StringUtils.trimToEmpty(imageObject.getStr("revised_prompt")));
        return true;
    }

    private String openAiOutputMimeType(String outputFormat) {
        return switch (StringUtils.lowerCase(StringUtils.trimToEmpty(outputFormat))) {
            case "jpeg", "jpg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> "image/png";
        };
    }

    private AiImageReferenceGenerateVo parseGeminiImageResponse(String body) {
        AiImageReferenceGenerateVo vo = new AiImageReferenceGenerateVo();
        vo.setMimeType("image/jpeg");
        vo.setStatus("completed");
        JSONObject responseBody = JSONUtil.parseObj(body);
        vo.setTaskId(this.firstNonBlank(responseBody.getStr("id"), responseBody.getStr("responseId")));

        Object error = responseBody.get("error");
        if (error != null) {
            vo.setStatus("failed");
            vo.setErrorMessage(StringUtils.left(String.valueOf(error), 500));
            return vo;
        }

        if (this.fillGeminiImageFromCandidates(vo, responseBody.getJSONArray("candidates"))) {
            return vo;
        }
        if (this.fillGeminiImageFromOutputImage(vo, responseBody.getJSONObject("output_image"))) {
            return vo;
        }
        if (this.fillGeminiImageFromSteps(vo, responseBody.getJSONArray("steps"))) {
            return vo;
        }

        vo.setStatus("failed");
        vo.setErrorMessage("Gemini图片生成响应未包含图片数据");
        return vo;
    }

    private boolean fillGeminiImageFromCandidates(AiImageReferenceGenerateVo vo, JSONArray candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (Object candidate : candidates) {
            JSONObject candidateObject = JSONUtil.parseObj(candidate);
            JSONObject content = candidateObject.getJSONObject("content");
            JSONArray parts = content == null ? candidateObject.getJSONArray("parts") : content.getJSONArray("parts");
            if (this.fillGeminiImageFromParts(vo, parts)) {
                return true;
            }
        }
        return false;
    }

    private boolean fillGeminiImageFromParts(AiImageReferenceGenerateVo vo, JSONArray parts) {
        if (parts == null || parts.isEmpty()) {
            return false;
        }
        StringBuilder textContent = new StringBuilder();
        for (Object part : parts) {
            JSONObject partObject = JSONUtil.parseObj(part);
            String text = StringUtils.trimToEmpty(partObject.getStr("text"));
            if (StringUtils.isNotBlank(text)) {
                textContent.append(text);
            }
            if (this.fillGeminiImageFromInlineData(vo, this.firstNonNullObject(
                    partObject.getJSONObject("inlineData"),
                    partObject.getJSONObject("inline_data")
            ))) {
                vo.setRevisedPrompt(StringUtils.left(textContent.toString(), 500));
                return true;
            }
        }
        return false;
    }

    private boolean fillGeminiImageFromInlineData(AiImageReferenceGenerateVo vo, JSONObject inlineData) {
        if (inlineData == null) {
            return false;
        }
        String imageBase64 = StringUtils.trimToEmpty(inlineData.getStr("data"));
        if (StringUtils.isBlank(imageBase64)) {
            return false;
        }
        vo.setImageBase64(imageBase64);
        vo.setMimeType(this.firstNonBlank(inlineData.getStr("mimeType"), inlineData.getStr("mime_type"), "image/jpeg"));
        return true;
    }

    private boolean fillGeminiImageFromOutputImage(AiImageReferenceGenerateVo vo, JSONObject outputImage) {
        if (outputImage == null) {
            return false;
        }
        String imageBase64 = StringUtils.trimToEmpty(outputImage.getStr("data"));
        if (StringUtils.isBlank(imageBase64)) {
            return false;
        }
        vo.setImageBase64(imageBase64);
        vo.setMimeType(this.firstNonBlank(outputImage.getStr("mime_type"), outputImage.getStr("mimeType"), "image/jpeg"));
        return true;
    }

    private boolean fillGeminiImageFromSteps(AiImageReferenceGenerateVo vo, JSONArray steps) {
        if (steps == null || steps.isEmpty()) {
            return false;
        }
        for (Object step : steps) {
            JSONObject stepObject = JSONUtil.parseObj(step);
            if (this.fillGeminiImageFromContentBlocks(vo, stepObject.getJSONArray("content"))) {
                return true;
            }
            if (this.fillGeminiImageFromContentBlocks(vo, stepObject.getJSONArray("summary"))) {
                return true;
            }
        }
        return false;
    }

    private boolean fillGeminiImageFromContentBlocks(AiImageReferenceGenerateVo vo, JSONArray contentBlocks) {
        if (contentBlocks == null || contentBlocks.isEmpty()) {
            return false;
        }
        for (Object contentBlock : contentBlocks) {
            JSONObject block = JSONUtil.parseObj(contentBlock);
            if (!StringUtils.equalsAnyIgnoreCase(block.getStr("type"), "image")) {
                continue;
            }
            String imageBase64 = StringUtils.trimToEmpty(block.getStr("data"));
            if (StringUtils.isBlank(imageBase64)) {
                continue;
            }
            vo.setImageBase64(imageBase64);
            vo.setMimeType(this.firstNonBlank(block.getStr("mime_type"), block.getStr("mimeType"), "image/jpeg"));
            return true;
        }
        return false;
    }

    private AiImageReferenceGenerateVo failedReferenceImageResponse(String errorMessage) {
        AiImageReferenceGenerateVo vo = new AiImageReferenceGenerateVo();
        vo.setStatus("failed");
        vo.setMimeType("image/png");
        vo.setErrorMessage(StringUtils.left(StringUtils.defaultIfBlank(errorMessage, "AI图片生成失败"), 500));
        return vo;
    }

    private AiImageReferenceGenerateVo tooManyRequestsReferenceImageResponse() {
        return this.failedReferenceImageResponse("图片过大，优化失败");
    }

    private ImagePayload downloadImagePayload(String imageUrl) {
        String trimmedUrl = StringUtils.trimToEmpty(imageUrl);
        if (StringUtils.startsWithIgnoreCase(trimmedUrl, "data:")) {
            return this.parseDataUrlImagePayload(trimmedUrl);
        }

        try (HttpResponse response = HttpUtil.createGet(trimmedUrl)
                .header("Accept", "image/*,*/*")
                .timeout(IMAGE_REFERENCE_DOWNLOAD_TIMEOUT_MS)
                .execute()) {
            if (!response.isOk()) {
                throw new BusinessException("参考图片下载失败，HTTP " + response.getStatus());
            }
            byte[] bytes = response.bodyBytes();
            if (bytes == null || bytes.length == 0) {
                throw new BusinessException("参考图片内容为空");
            }
            if (bytes.length > GEMINI_INLINE_IMAGE_MAX_BYTES) {
                throw new BusinessException("参考图片过大，请使用小于20MB的图片");
            }
            return new ImagePayload(
                    this.normalizeImageMimeType(response.header("Content-Type")),
                    Base64.getEncoder().encodeToString(bytes)
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("AIService#downloadImagePayload 请求异常, imageUrl: {}", imageUrl, e);
            throw new BusinessException("参考图片下载异常：" + StringUtils.defaultString(e.getMessage()));
        }
    }

    private ImagePayload parseDataUrlImagePayload(String dataUrl) {
        String metadata = StringUtils.substringBetween(dataUrl, "data:", ",");
        String base64Data = StringUtils.substringAfter(dataUrl, ",");
        if (StringUtils.isBlank(base64Data)) {
            throw new BusinessException("参考图片DataURL无效");
        }
        String mimeType = StringUtils.substringBefore(StringUtils.defaultString(metadata), ";");
        return new ImagePayload(this.normalizeImageMimeType(mimeType), base64Data);
    }

    private String normalizeImageMimeType(String mimeType) {
        String normalized = StringUtils.substringBefore(StringUtils.trimToEmpty(mimeType), ";");
        if (!StringUtils.startsWithIgnoreCase(normalized, "image/")) {
            return "image/jpeg";
        }
        return normalized;
    }

    private String formatProviderError(String body) {
        String message = this.extractProviderError(body);
        return StringUtils.isBlank(message) ? "" : "：" + message;
    }

    private String extractProviderError(String body) {
        if (!JSONUtil.isTypeJSON(body)) {
            return StringUtils.left(StringUtils.trimToEmpty(body), 200);
        }
        try {
            JSONObject responseBody = JSONUtil.parseObj(body);
            JSONObject error = responseBody.getJSONObject("error");
            if (error != null) {
                return StringUtils.left(StringUtils.defaultString(error.getStr("message"), error.toString()), 200);
            }
            return StringUtils.left(StringUtils.trimToEmpty(body), 200);
        } catch (Exception e) {
            return StringUtils.left(StringUtils.trimToEmpty(body), 200);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    @SafeVarargs
    private final <T> T firstNonNullObject(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private record ImagePayload(String mimeType, String base64) {
    }
}

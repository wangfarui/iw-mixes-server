package com.itwray.iw.external.referenceimage.provider.gemini;

import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.itwray.iw.external.model.enums.ReferenceImageErrorCode;
import com.itwray.iw.external.referenceimage.GeneratedImage;
import com.itwray.iw.external.referenceimage.GenerationOutcome;
import com.itwray.iw.external.referenceimage.ReferenceImageCommand;
import com.itwray.iw.external.referenceimage.provider.ProviderContext;
import com.itwray.iw.external.referenceimage.provider.ReferenceImageProvider;
import com.itwray.iw.external.referenceimage.support.DownloadedImage;
import com.itwray.iw.external.referenceimage.support.ImagePayloads;
import com.itwray.iw.external.referenceimage.support.SafeImageDownloader;
import com.itwray.iw.external.referenceimage.support.SourceImagePolicy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GeminiReferenceImageProvider implements ReferenceImageProvider {

    private static final String PROVIDER_ID = "gemini";

    private final SourceImagePolicy sourceImagePolicy;
    private final SafeImageDownloader imageDownloader;

    public GeminiReferenceImageProvider(SourceImagePolicy sourceImagePolicy,
                                        SafeImageDownloader imageDownloader) {
        this.sourceImagePolicy = sourceImagePolicy;
        this.imageDownloader = imageDownloader;
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String defaultApiBaseUrl() {
        return "https://generativelanguage.googleapis.com/v1beta";
    }

    @Override
    public String defaultModel() {
        return "gemini-3.1-flash-image";
    }

    @Override
    public GenerationOutcome generate(ReferenceImageCommand command, ProviderContext context) {
        DownloadedImage reference;
        try {
            URI source = sourceImagePolicy.requireTrusted(command.sourceImageUrl());
            reference = imageDownloader.downloadTrustedSource(source);
        } catch (IllegalArgumentException e) {
            return new GenerationOutcome.Failure(ReferenceImageErrorCode.INVALID_INPUT,
                    e.getMessage(), this.metadata(context));
        } catch (Exception e) {
            log.error("Gemini参考图片下载异常", e);
            return new GenerationOutcome.Failure(ReferenceImageErrorCode.PROVIDER_UNAVAILABLE,
                    "参考图片暂时无法读取", this.metadata(context));
        }

        JSONObject body = new JSONObject();
        body.set("contents", List.of(Map.of(
                "parts", List.of(
                        Map.of("text", command.prompt()),
                        Map.of("inline_data", Map.of(
                                "mime_type", reference.mimeType(),
                                "data", java.util.Base64.getEncoder().encodeToString(reference.content())
                        ))
                )
        )));
        body.set("generationConfig", Map.of("responseModalities", List.of("TEXT", "IMAGE")));

        try (HttpResponse response = HttpUtil.createPost(
                        context.apiBaseUrl() + "/models/" + context.model() + ":generateContent")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("x-goog-api-key", this.apiKey(context.apiKey()))
                .body(JSONUtil.toJsonStr(body))
                .timeout(context.requestTimeoutMs())
                .execute()) {
            if (!response.isOk()) {
                log.error("Gemini参考图生成失败, status: {}, body: {}",
                        response.getStatus(), this.diagnostic(response.body()));
                return this.httpFailure(response.getStatus(), context);
            }
            return this.parse(response.body(), context);
        } catch (Exception e) {
            log.error("Gemini参考图生成异常", e);
            return new GenerationOutcome.Failure(ReferenceImageErrorCode.PROVIDER_UNAVAILABLE,
                    "Gemini图片生成暂时不可用", this.metadata(context));
        }
    }

    private GenerationOutcome parse(String body, ProviderContext context) {
        try {
            JSONObject response = JSONUtil.parseObj(body);
            JSONArray candidates = response.getJSONArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                return this.integrationFailure(context, "Gemini响应未包含图片");
            }
            for (Object candidateValue : candidates) {
                JSONObject candidate = JSONUtil.parseObj(candidateValue);
                JSONObject content = candidate.getJSONObject("content");
                JSONArray parts = content == null ? candidate.getJSONArray("parts") : content.getJSONArray("parts");
                GenerationOutcome outcome = this.readParts(parts, context);
                if (outcome != null) {
                    return outcome;
                }
            }
            return this.integrationFailure(context, "Gemini响应未包含有效图片");
        } catch (Exception e) {
            log.error("Gemini参考图响应解析异常", e);
            return this.integrationFailure(context, "Gemini图片响应无效");
        }
    }

    private GenerationOutcome readParts(JSONArray parts, ProviderContext context) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        StringBuilder revisedPrompt = new StringBuilder();
        for (Object partValue : parts) {
            JSONObject part = JSONUtil.parseObj(partValue);
            String text = StringUtils.trimToEmpty(part.getStr("text"));
            if (StringUtils.isNotBlank(text)) {
                revisedPrompt.append(text);
            }
            JSONObject inlineData = this.firstNonNull(part.getJSONObject("inlineData"),
                    part.getJSONObject("inline_data"));
            if (inlineData == null) {
                continue;
            }
            String data = StringUtils.trimToEmpty(inlineData.getStr("data"));
            String mimeType = StringUtils.defaultIfBlank(
                    inlineData.getStr("mimeType"), inlineData.getStr("mime_type"));
            DownloadedImage image = ImagePayloads.decodeBase64(data, mimeType, context.maxImageBytes());
            return new GenerationOutcome.Success(
                    new GeneratedImage(image.content(), image.mimeType(),
                            StringUtils.left(revisedPrompt.toString(), 500)),
                    this.metadata(context));
        }
        return null;
    }

    private JSONObject firstNonNull(JSONObject first, JSONObject second) {
        return first == null ? second : first;
    }

    private GenerationOutcome httpFailure(int status, ProviderContext context) {
        ReferenceImageErrorCode code;
        String message;
        if (status == 429) {
            code = ReferenceImageErrorCode.RATE_LIMITED;
            message = "Gemini请求过于频繁，请稍后重试";
        } else if (status == 401 || status == 403) {
            code = ReferenceImageErrorCode.CONFIGURATION_ERROR;
            message = "Gemini图片生成配置无效";
        } else if (status >= 500) {
            code = ReferenceImageErrorCode.PROVIDER_UNAVAILABLE;
            message = "Gemini图片生成暂时不可用";
        } else {
            code = ReferenceImageErrorCode.PROVIDER_REJECTED;
            message = "Gemini拒绝了本次图片生成";
        }
        return new GenerationOutcome.Failure(code, message, this.metadata(context));
    }

    private GenerationOutcome integrationFailure(ProviderContext context, String message) {
        return new GenerationOutcome.Failure(ReferenceImageErrorCode.INTEGRATION_ERROR,
                message, this.metadata(context));
    }

    private String apiKey(String key) {
        String value = StringUtils.trimToEmpty(key);
        return StringUtils.startsWithIgnoreCase(value, "Bearer ")
                ? StringUtils.trimToEmpty(StringUtils.substringAfter(value, " "))
                : value;
    }

    private String diagnostic(String body) {
        return StringUtils.left(StringUtils.defaultString(body), 500);
    }
}

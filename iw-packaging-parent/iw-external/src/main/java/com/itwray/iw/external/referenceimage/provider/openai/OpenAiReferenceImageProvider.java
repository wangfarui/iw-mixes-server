package com.itwray.iw.external.referenceimage.provider.openai;

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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OpenAiReferenceImageProvider implements ReferenceImageProvider {

    private static final String PROVIDER_ID = "openai";

    private final SafeImageDownloader imageDownloader;

    public OpenAiReferenceImageProvider(SafeImageDownloader imageDownloader) {
        this.imageDownloader = imageDownloader;
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String defaultApiBaseUrl() {
        return "https://api.openai.com/v1";
    }

    @Override
    public String defaultModel() {
        return "gpt-image-2";
    }

    @Override
    public GenerationOutcome generate(ReferenceImageCommand command, ProviderContext context) {
        JSONObject body = new JSONObject();
        body.set("model", context.model());
        body.set("prompt", command.prompt());
        body.set("images", List.of(Map.of("image_url", command.sourceImageUrl())));
        body.set("n", 1);
        body.set("output_format", "png");

        try (HttpResponse response = HttpUtil.createPost(context.apiBaseUrl() + "/images/edits")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", context.apiKey())
                .body(JSONUtil.toJsonStr(body))
                .timeout(context.requestTimeoutMs())
                .execute()) {
            if (!response.isOk()) {
                log.error("OpenAI参考图生成失败, status: {}, body: {}",
                        response.getStatus(), this.diagnostic(response.body()));
                return this.httpFailure(response.getStatus(), context);
            }
            return this.parse(response.body(), context);
        } catch (Exception e) {
            log.error("OpenAI参考图生成异常", e);
            return new GenerationOutcome.Failure(ReferenceImageErrorCode.PROVIDER_UNAVAILABLE,
                    "OpenAI图片生成暂时不可用", this.metadata(context));
        }
    }

    private GenerationOutcome parse(String body, ProviderContext context) {
        try {
            JSONObject response = JSONUtil.parseObj(body);
            JSONArray data = response.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                return this.integrationFailure(context, "OpenAI响应未包含图片");
            }
            for (Object value : data) {
                JSONObject image = JSONUtil.parseObj(value);
                DownloadedImage payload = this.readImage(image, response.getStr("output_format"),
                        context.maxImageBytes());
                if (payload == null) {
                    continue;
                }
                String revisedPrompt = StringUtils.trimToEmpty(image.getStr("revised_prompt"));
                return new GenerationOutcome.Success(
                        new GeneratedImage(payload.content(), payload.mimeType(), revisedPrompt),
                        this.metadata(context));
            }
            return this.integrationFailure(context, "OpenAI响应未包含有效图片");
        } catch (Exception e) {
            log.error("OpenAI参考图响应解析异常", e);
            return this.integrationFailure(context, "OpenAI图片响应无效");
        }
    }

    private DownloadedImage readImage(JSONObject image, String outputFormat, int maxBytes) {
        String base64 = StringUtils.trimToEmpty(image.getStr("b64_json"));
        if (StringUtils.isNotBlank(base64)) {
            return ImagePayloads.decodeBase64(base64, this.outputMimeType(outputFormat), maxBytes);
        }
        String url = StringUtils.trimToEmpty(image.getStr("url"));
        if (StringUtils.startsWithIgnoreCase(url, "data:")) {
            return ImagePayloads.decodeBase64(url, "image/png", maxBytes);
        }
        if (StringUtils.startsWithIgnoreCase(url, "https://")) {
            return imageDownloader.downloadProviderOutput(URI.create(url));
        }
        return null;
    }

    private GenerationOutcome httpFailure(int status, ProviderContext context) {
        ReferenceImageErrorCode code;
        String message;
        if (status == 429) {
            code = ReferenceImageErrorCode.RATE_LIMITED;
            message = "OpenAI请求过于频繁，请稍后重试";
        } else if (status == 401 || status == 403) {
            code = ReferenceImageErrorCode.CONFIGURATION_ERROR;
            message = "OpenAI图片生成配置无效";
        } else if (status >= 500) {
            code = ReferenceImageErrorCode.PROVIDER_UNAVAILABLE;
            message = "OpenAI图片生成暂时不可用";
        } else {
            code = ReferenceImageErrorCode.PROVIDER_REJECTED;
            message = "OpenAI拒绝了本次图片生成";
        }
        return new GenerationOutcome.Failure(code, message, this.metadata(context));
    }

    private GenerationOutcome integrationFailure(ProviderContext context, String message) {
        return new GenerationOutcome.Failure(ReferenceImageErrorCode.INTEGRATION_ERROR,
                message, this.metadata(context));
    }

    private String outputMimeType(String outputFormat) {
        return switch (StringUtils.lowerCase(StringUtils.trimToEmpty(outputFormat))) {
            case "jpeg", "jpg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> "image/png";
        };
    }

    private String diagnostic(String body) {
        return StringUtils.left(StringUtils.defaultString(body), 500);
    }
}

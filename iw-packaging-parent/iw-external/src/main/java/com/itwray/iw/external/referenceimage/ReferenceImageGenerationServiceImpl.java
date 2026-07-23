package com.itwray.iw.external.referenceimage;

import com.itwray.iw.external.model.enums.ReferenceImageErrorCode;
import com.itwray.iw.external.referenceimage.config.ReferenceImageProperties;
import com.itwray.iw.external.referenceimage.provider.ProviderContext;
import com.itwray.iw.external.referenceimage.provider.ReferenceImageProvider;
import com.itwray.iw.external.referenceimage.support.SourceImagePolicy;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class ReferenceImageGenerationServiceImpl implements ReferenceImageGenerationService {

    private final ReferenceImageProperties properties;
    private final SourceImagePolicy sourceImagePolicy;
    private final Map<String, ReferenceImageProvider> providers = new LinkedHashMap<>();

    public ReferenceImageGenerationServiceImpl(ReferenceImageProperties properties,
                                               SourceImagePolicy sourceImagePolicy,
                                               List<ReferenceImageProvider> providers) {
        this.properties = properties;
        this.sourceImagePolicy = sourceImagePolicy;
        providers.forEach(provider -> this.providers.put(provider.id(), provider));
    }

    @PostConstruct
    void logConfiguration() {
        String providerId = this.providerId();
        ReferenceImageProvider provider = providers.get(providerId);
        String model = provider == null ? StringUtils.trimToEmpty(properties.getModel()) : this.model(provider);
        String endpoint = provider == null ? StringUtils.trimToEmpty(properties.getApiBaseUrl()) : this.apiBaseUrl(provider);
        log.info("参考图生成配置已解析, provider: {}, apiBaseUrl: {}, model: {}, apiKeyConfigured: {}, sourceBaseUrlConfigured: {}",
                providerId, endpoint, model, StringUtils.isNotBlank(properties.getApiKey()),
                StringUtils.isNotBlank(properties.getSourceBaseUrl()));
        if (provider == null || StringUtils.isBlank(properties.getApiKey())
                || StringUtils.isBlank(properties.getSourceBaseUrl())) {
            log.warn("参考图生成配置不完整；调用时将返回CONFIGURATION_ERROR");
        }
    }

    @Override
    public GenerationOutcome generate(ReferenceImageCommand command) {
        String providerId = this.providerId();
        ReferenceImageProvider provider = providers.get(providerId);
        if (command == null || StringUtils.isBlank(command.sourceImageUrl())
                || StringUtils.isBlank(command.prompt())) {
            ExecutionMetadata metadata = provider == null
                    ? new ExecutionMetadata(providerId, StringUtils.trimToEmpty(properties.getModel()))
                    : provider.metadata(this.context(provider));
            return new GenerationOutcome.Failure(ReferenceImageErrorCode.INVALID_INPUT,
                    "参考图片和提示词不能为空", metadata);
        }
        if (provider == null) {
            return this.configurationFailure(providerId, StringUtils.trimToEmpty(properties.getModel()),
                    "不支持的图片供应商");
        }
        ProviderContext context = this.context(provider);
        ExecutionMetadata metadata = provider.metadata(context);
        if (StringUtils.isBlank(context.apiKey()) || StringUtils.isBlank(properties.getSourceBaseUrl())) {
            return new GenerationOutcome.Failure(ReferenceImageErrorCode.CONFIGURATION_ERROR,
                    "图片生成配置不完整", metadata);
        }
        try {
            sourceImagePolicy.requireTrusted(command.sourceImageUrl());
        } catch (IllegalArgumentException e) {
            return new GenerationOutcome.Failure(ReferenceImageErrorCode.INVALID_INPUT,
                    e.getMessage(), metadata);
        }
        try {
            return provider.generate(command, context);
        } catch (Exception e) {
            log.error("参考图生成adapter异常, provider: {}, model: {}", providerId, context.model(), e);
            return new GenerationOutcome.Failure(ReferenceImageErrorCode.INTEGRATION_ERROR,
                    "图片生成服务异常", metadata);
        }
    }

    private GenerationOutcome configurationFailure(String provider, String model, String message) {
        return new GenerationOutcome.Failure(ReferenceImageErrorCode.CONFIGURATION_ERROR, message,
                new ExecutionMetadata(provider, model));
    }

    private ProviderContext context(ReferenceImageProvider provider) {
        return new ProviderContext(this.apiBaseUrl(provider), StringUtils.trimToEmpty(properties.getApiKey()),
                this.model(provider), Math.max(1, properties.getRequestTimeoutMs()),
                Math.max(1, properties.getMaxImageBytes()));
    }

    private String providerId() {
        return StringUtils.lowerCase(StringUtils.trimToEmpty(properties.getProvider()), Locale.ROOT);
    }

    private String apiBaseUrl(ReferenceImageProvider provider) {
        return StringUtils.removeEnd(
                StringUtils.defaultIfBlank(StringUtils.trimToEmpty(properties.getApiBaseUrl()),
                        provider.defaultApiBaseUrl()), "/");
    }

    private String model(ReferenceImageProvider provider) {
        return StringUtils.defaultIfBlank(StringUtils.trimToEmpty(properties.getModel()), provider.defaultModel());
    }
}

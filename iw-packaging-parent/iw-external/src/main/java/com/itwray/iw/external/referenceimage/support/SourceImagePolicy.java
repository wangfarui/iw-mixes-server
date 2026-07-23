package com.itwray.iw.external.referenceimage.support;

import com.itwray.iw.external.referenceimage.config.ReferenceImageProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class SourceImagePolicy {

    private final ReferenceImageProperties properties;

    public SourceImagePolicy(ReferenceImageProperties properties) {
        this.properties = properties;
    }

    public URI requireTrusted(String sourceImageUrl) {
        URI source = this.parse(sourceImageUrl, "参考图片URL无效");
        URI base = this.parse(properties.getSourceBaseUrl(), "可信图片基础URL未配置");
        if (!StringUtils.equalsIgnoreCase("https", source.getScheme())
                || !StringUtils.equalsIgnoreCase("https", base.getScheme())
                || source.getUserInfo() != null
                || !StringUtils.equalsIgnoreCase(source.getHost(), base.getHost())
                || this.effectivePort(source) != this.effectivePort(base)) {
            throw new IllegalArgumentException("参考图片不属于可信来源");
        }
        String basePath = this.normalizedBasePath(base);
        String sourcePath = StringUtils.defaultIfBlank(source.normalize().getPath(), "/");
        if (!sourcePath.startsWith(basePath)) {
            throw new IllegalArgumentException("参考图片不属于可信路径");
        }
        return source;
    }

    private URI parse(String value, String message) {
        try {
            if (StringUtils.isBlank(value)) {
                throw new IllegalArgumentException(message);
            }
            return URI.create(value.trim()).normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(message, e);
        }
    }

    private int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 443 : uri.getPort();
    }

    private String normalizedBasePath(URI base) {
        String path = StringUtils.defaultIfBlank(base.normalize().getPath(), "/");
        return StringUtils.appendIfMissing(path, "/");
    }
}

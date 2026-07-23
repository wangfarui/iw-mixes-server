package com.itwray.iw.external.referenceimage.support;

import com.itwray.iw.external.referenceimage.config.ReferenceImageProperties;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class SafeImageDownloader {

    private final ReferenceImageProperties properties;
    private final HttpClient httpClient;

    public SafeImageDownloader(ReferenceImageProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public DownloadedImage downloadTrustedSource(URI uri) {
        return this.download(uri, properties.getSourceDownloadTimeoutMs(), properties.getMaxImageBytes());
    }

    public DownloadedImage downloadProviderOutput(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("供应商图片地址必须使用HTTPS");
        }
        return this.download(uri, properties.getSourceDownloadTimeoutMs(), properties.getMaxImageBytes());
    }

    private DownloadedImage download(URI uri, int timeoutMs, int maxBytes) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Accept", "image/jpeg,image/png,image/webp")
                    .timeout(Duration.ofMillis(Math.max(1, timeoutMs)))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("图片下载失败，HTTP " + response.statusCode());
            }
            String mimeType = ImagePayloads.normalizeMimeType(
                    response.headers().firstValue("Content-Type").orElse(""));
            if (!ImagePayloads.isAllowedMimeType(mimeType)) {
                throw new IllegalArgumentException("不支持的图片格式");
            }
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > maxBytes) {
                throw new IllegalArgumentException("图片内容超过大小限制");
            }
            try (InputStream inputStream = response.body()) {
                byte[] content = inputStream.readNBytes(maxBytes + 1);
                if (content.length == 0 || content.length > maxBytes) {
                    throw new IllegalArgumentException("图片内容为空或超过大小限制");
                }
                return new DownloadedImage(content, mimeType);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("图片下载异常", e);
        }
    }
}

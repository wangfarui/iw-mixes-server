package com.itwray.iw.external.referenceimage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "iw.ai.image")
public class ReferenceImageProperties {

    private String provider = "gemini";

    private String apiBaseUrl;

    private String apiKey;

    private String model;

    private String sourceBaseUrl;

    private int requestTimeoutMs = 720_000;

    private int sourceDownloadTimeoutMs = 30_000;

    private int maxImageBytes = 10 * 1024 * 1024;
}

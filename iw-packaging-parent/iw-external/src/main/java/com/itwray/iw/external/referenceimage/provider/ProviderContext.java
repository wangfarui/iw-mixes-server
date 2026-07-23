package com.itwray.iw.external.referenceimage.provider;

public record ProviderContext(String apiBaseUrl, String apiKey, String model,
                              int requestTimeoutMs, int maxImageBytes) {
}

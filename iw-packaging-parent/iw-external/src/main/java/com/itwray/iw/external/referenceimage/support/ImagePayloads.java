package com.itwray.iw.external.referenceimage.support;

import org.apache.commons.lang3.StringUtils;

import java.util.Base64;
import java.util.Set;

public final class ImagePayloads {

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private ImagePayloads() {
    }

    public static DownloadedImage decodeBase64(String encoded, String mimeType, int maxBytes) {
        String value = StringUtils.trimToEmpty(encoded);
        String resolvedMimeType = normalizeMimeType(mimeType);
        int marker = value.indexOf("base64,");
        if (marker >= 0) {
            String metadata = StringUtils.substringBefore(value, ",");
            resolvedMimeType = normalizeMimeType(StringUtils.substringBetween(metadata, "data:", ";"));
            value = value.substring(marker + "base64,".length());
        }
        if (!isAllowedMimeType(resolvedMimeType)) {
            throw new IllegalArgumentException("不支持的图片格式");
        }
        long maximumEncodedLength = ((long) maxBytes + 2L) / 3L * 4L + 16L;
        if (value.length() > maximumEncodedLength) {
            throw new IllegalArgumentException("图片内容超过大小限制");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("图片Base64无效", e);
        }
        if (content.length == 0 || content.length > maxBytes) {
            throw new IllegalArgumentException("图片内容为空或超过大小限制");
        }
        return new DownloadedImage(content, resolvedMimeType);
    }

    public static boolean isAllowedMimeType(String mimeType) {
        return ALLOWED_MIME_TYPES.contains(normalizeMimeType(mimeType));
    }

    public static String normalizeMimeType(String mimeType) {
        String value = StringUtils.lowerCase(StringUtils.trimToEmpty(mimeType));
        if (StringUtils.equals(value, "image/jpg")) {
            return "image/jpeg";
        }
        return StringUtils.substringBefore(value, ";");
    }
}

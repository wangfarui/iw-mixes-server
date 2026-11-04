package com.itwray.iw.external.zhaogang.codestats;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarDefaults;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class CodeStatsSupport {

    public static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    public static final int MAX_BRANCH_NAMES = 50;
    private static final ObjectMapper JSON = new ObjectMapper();

    private CodeStatsSupport() {
    }

    public static String format(LocalDateTime time) {
        return time == null ? null : time.atZone(CHINA_ZONE).toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public static String format(LocalDate date) {
        return date == null ? null : date.toString();
    }

    public static LocalDateTime fromMillis(long millis) {
        return millis <= 0 ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), CHINA_ZONE);
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(StringUtils.defaultString(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static String normalizeEmail(String email) {
        return StringUtils.trimToEmpty(email).toLowerCase(Locale.ROOT);
    }

    public static String normalizeName(String name) {
        return StringUtils.normalizeSpace(StringUtils.defaultString(name)).toLowerCase(Locale.ROOT);
    }

    public static String emailHash(String email) {
        String normalized = normalizeEmail(email);
        return StringUtils.isBlank(normalized) ? null : sha256(normalized);
    }

    public static String nameKey(String name) {
        String normalized = normalizeName(name);
        return StringUtils.isBlank(normalized) ? "" : sha256(normalized);
    }

    public static String maskEmail(String email) {
        String normalized = normalizeEmail(email);
        int at = normalized.indexOf('@');
        if (at <= 0) return "***";
        return normalized.charAt(0) + "***" + normalized.substring(at);
    }

    public static String maskName(String name) {
        String value = StringUtils.trimToEmpty(name);
        if (value.isEmpty()) return "";
        int[] points = value.codePoints().toArray();
        if (points.length == 1) return value + "*";
        if (points.length == 2) return new String(points, 0, 1) + "*";
        return new String(points, 0, 1) + "*" + new String(points, points.length - 1, 1);
    }

    public static String toBranchJson(List<String> branches) {
        try {
            return JSON.writeValueAsString(branches == null ? List.of() : branches);
        } catch (Exception e) {
            return "[]";
        }
    }

    public static List<String> fromBranchJson(String value) {
        if (StringUtils.isBlank(value)) return List.of();
        try {
            return JSON.readValue(value, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    public static String dayType(LocalDate date) {
        return WorkCalendarDefaults.dayType(date).name();
    }

    public static String truncate(String value, int max) {
        String text = StringUtils.defaultString(value);
        return text.length() <= max ? text : text.substring(0, max);
    }

    public static String commitUrl(String depotWebUrl, String sha) {
        String base = StringUtils.removeEnd(StringUtils.trimToEmpty(depotWebUrl), "/");
        if (!base.startsWith("http://") && !base.startsWith("https://")) return "";
        return base + "/git/commit/" + sha;
    }

    public static double ratio(long numerator, long denominator) {
        if (denominator <= 0) return 1D;
        return Math.round((double) numerator / denominator * 1000D) / 1000D;
    }
}

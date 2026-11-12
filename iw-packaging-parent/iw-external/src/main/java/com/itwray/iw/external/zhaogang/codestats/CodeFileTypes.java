package com.itwray.iw.external.zhaogang.codestats;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;

/** 按文件扩展名聚合变更行，缓存中不保留文件路径。 */
public final class CodeFileTypes {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Lines>> LINES = new TypeReference<>() {};

    private CodeFileTypes() {}

    public record Lines(long additions, long deletions) {
        Lines plus(Lines other) {
            return new Lines(additions + other.additions, deletions + other.deletions);
        }
    }

    public static void add(Map<String, Lines> totals, String path, int additions, int deletions) {
        if (path == null || path.isBlank() || "/dev/null".equals(path) || (additions == 0 && deletions == 0)) return;
        String filename = path.substring(path.lastIndexOf('/') + 1);
        int dot = filename.lastIndexOf('.');
        String type = dot > 0 && dot < filename.length() - 1
                ? filename.substring(dot).toLowerCase(Locale.ROOT) : "无扩展名";
        totals.merge(type, new Lines(additions, deletions), Lines::plus);
    }

    static String encode(Map<String, Lines> types) {
        try {
            return JSON.writeValueAsString(types);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法编码文件类型统计", e);
        }
    }

    static Map<String, Lines> decode(String value) {
        if (value == null) return Map.of();
        try {
            Map<String, Lines> types = JSON.readValue(value, LINES);
            return types == null ? Map.of() : types;
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    /** Returns the unclassified lines, or null if the breakdown cannot fit the stored totals. */
    static Lines remainder(Map<String, Lines> types, long additions, long deletions) {
        for (Lines lines : types.values()) {
            if (lines == null || lines.additions() < 0 || lines.deletions() < 0
                    || lines.additions() > additions || lines.deletions() > deletions) {
                return null;
            }
            additions -= lines.additions();
            deletions -= lines.deletions();
        }
        return new Lines(additions, deletions);
    }
}

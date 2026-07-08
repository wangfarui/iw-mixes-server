package com.itwray.iw.wardrobe.model.enums;

import com.itwray.iw.web.model.enums.BusinessConstantEnum;
import lombok.Getter;

import java.util.List;
import java.util.Objects;

/**
 * 衣物状态枚举
 *
 * @author codex
 * @since 2026-07-07
 */
@Getter
public enum WardrobeItemStatusEnum implements BusinessConstantEnum {

    WEARING(1, "在穿"),
    IDLE(2, "闲置"),
    ELIMINATED(5, "已淘汰"),
    ;

    private static final List<Integer> AVAILABLE_CODES = List.of(WEARING.code, IDLE.code);

    private final Integer code;

    private final String name;

    WardrobeItemStatusEnum(Integer code, String name) {
        this.code = code;
        this.name = name;
    }

    public static Integer defaultCode() {
        return WEARING.code;
    }

    public static List<Integer> availableCodes() {
        return AVAILABLE_CODES;
    }

    public static boolean isAvailable(Integer code) {
        return AVAILABLE_CODES.contains(normalizeCode(code));
    }

    public static boolean isIdle(Integer code) {
        return Objects.equals(normalizeCode(code), IDLE.code);
    }

    public static Integer normalizeCode(Integer code) {
        if (Objects.equals(code, WEARING.code)
                || Objects.equals(code, IDLE.code)
                || Objects.equals(code, ELIMINATED.code)) {
            return code;
        }
        if (Objects.equals(code, 3) || Objects.equals(code, 4)) {
            return IDLE.code;
        }
        return defaultCode();
    }
}

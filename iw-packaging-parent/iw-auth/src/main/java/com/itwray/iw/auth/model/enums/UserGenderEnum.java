package com.itwray.iw.auth.model.enums;

import com.itwray.iw.web.model.enums.BusinessConstantEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户性别枚举
 *
 * @author wray
 * @since 2026/7/15
 */
@Getter
@AllArgsConstructor
public enum UserGenderEnum implements BusinessConstantEnum {

    /**
     * 保密
     */
    UNDISCLOSED(0, "保密"),

    /**
     * 男
     */
    MALE(1, "男"),

    /**
     * 女
     */
    FEMALE(2, "女");

    private final Integer code;
    private final String name;
}

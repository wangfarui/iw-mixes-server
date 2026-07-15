package com.itwray.iw.auth.model.enums;

import com.itwray.iw.common.ConstantEnum;
import lombok.Getter;

/**
 * 用户联系方式类型。
 */
@Getter
public enum UserContactTypeEnum implements ConstantEnum {

    PHONE(1, "手机号"),
    EMAIL(2, "邮箱"),
    ;

    private final Integer code;

    private final String name;

    UserContactTypeEnum(Integer code, String name) {
        this.code = code;
        this.name = name;
    }
}

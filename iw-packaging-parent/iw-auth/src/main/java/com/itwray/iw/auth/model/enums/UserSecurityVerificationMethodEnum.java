package com.itwray.iw.auth.model.enums;

import com.itwray.iw.common.ConstantEnum;
import lombok.Getter;

/**
 * 账号敏感操作的身份验证方式。
 */
@Getter
public enum UserSecurityVerificationMethodEnum implements ConstantEnum {

    PASSWORD(1, "登录密码"),
    PHONE(2, "手机验证码"),
    EMAIL(3, "邮箱验证码"),
    ;

    private final Integer code;

    private final String name;

    UserSecurityVerificationMethodEnum(Integer code, String name) {
        this.code = code;
        this.name = name;
    }
}

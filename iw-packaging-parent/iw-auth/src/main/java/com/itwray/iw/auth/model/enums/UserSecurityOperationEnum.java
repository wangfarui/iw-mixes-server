package com.itwray.iw.auth.model.enums;

import com.itwray.iw.common.ConstantEnum;
import lombok.Getter;

/**
 * 需要二次身份验证的用户操作。
 */
@Getter
public enum UserSecurityOperationEnum implements ConstantEnum {

    BIND_PHONE(1, "绑定手机号", UserContactTypeEnum.PHONE, false),
    CHANGE_PHONE(2, "更换手机号", UserContactTypeEnum.PHONE, false),
    UNBIND_PHONE(3, "解绑手机号", UserContactTypeEnum.PHONE, true),
    BIND_EMAIL(4, "绑定邮箱", UserContactTypeEnum.EMAIL, false),
    CHANGE_EMAIL(5, "更换邮箱", UserContactTypeEnum.EMAIL, false),
    UNBIND_EMAIL(6, "解绑邮箱", UserContactTypeEnum.EMAIL, true),
    EDIT_USERNAME(7, "修改用户名", null, false),
    SET_PASSWORD(8, "设置密码", null, false),
    CHANGE_PASSWORD(9, "修改密码", null, false),
    ;

    private final Integer code;

    private final String name;

    private final UserContactTypeEnum contactType;

    private final boolean unbind;

    UserSecurityOperationEnum(Integer code, String name, UserContactTypeEnum contactType, boolean unbind) {
        this.code = code;
        this.name = name;
        this.contactType = contactType;
        this.unbind = unbind;
    }

    public boolean isContactOperation() {
        return contactType != null;
    }
}

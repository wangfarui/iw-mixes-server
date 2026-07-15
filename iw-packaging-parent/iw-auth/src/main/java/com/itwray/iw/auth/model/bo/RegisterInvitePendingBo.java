package com.itwray.iw.auth.model.bo;

import lombok.Data;

/**
 * 新用户验证码注册临时票据缓存对象
 *
 * @author wray
 * @since 2026/7/2
 */
@Data
public class RegisterInvitePendingBo {

    /**
     * 登录方式
     *
     * @see com.itwray.iw.auth.model.enums.UserLoginWayEnum
     */
    private Integer loginWay;

    /**
     * 电话号码
     */
    private String phoneNumber;

    /**
     * 邮箱地址
     */
    private String emailAddress;

}

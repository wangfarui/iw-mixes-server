package com.itwray.iw.auth.service;

import com.itwray.iw.auth.model.dto.LoginInviteRegisterDto;
import com.itwray.iw.auth.model.dto.LoginVerificationCodeDto;
import com.itwray.iw.auth.model.dto.RegisterInviteConfigUpdateDto;
import com.itwray.iw.auth.model.vo.RegisterInviteStatusVo;
import com.itwray.iw.auth.model.vo.UserInfoVo;

/**
 * 新用户注册邀请码服务
 *
 * @author wray
 * @since 2026/7/2
 */
public interface AuthRegisterInviteService {

    /**
     * 是否开启邀请码注册
     */
    boolean isInviteRegisterEnabled();

    /**
     * 查询邀请码状态
     */
    RegisterInviteStatusVo getStatus();

    /**
     * 更新邀请码注册开关
     */
    void updateConfig(RegisterInviteConfigUpdateDto dto);

    /**
     * 生成邀请码
     */
    RegisterInviteStatusVo generateInvite();

    /**
     * 删除当前邀请码
     */
    void deleteInvite();

    /**
     * 构建需要邀请码的登录响应，并创建临时注册票据
     */
    UserInfoVo createInviteRequiredResponse(LoginVerificationCodeDto dto);

    /**
     * 根据邀请码完成验证码登录注册
     */
    UserInfoVo registerByInvite(LoginInviteRegisterDto dto);

    /**
     * 当邀请码注册开启时，校验邀请码
     */
    void verifyInviteIfEnabled(String inviteCode);
}

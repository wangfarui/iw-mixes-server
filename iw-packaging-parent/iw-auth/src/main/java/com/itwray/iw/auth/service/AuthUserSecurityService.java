package com.itwray.iw.auth.service;

import com.itwray.iw.auth.model.dto.*;
import com.itwray.iw.auth.model.vo.UserInfoVo;
import com.itwray.iw.auth.model.vo.UserSecurityTicketVo;

/**
 * 用户账号敏感操作服务。
 */
public interface AuthUserSecurityService {

    void sendSecurityVerificationCode(UserSecurityCodeSendDto dto);

    UserSecurityTicketVo verifySecurityIdentity(UserSecurityVerifyDto dto);

    void sendContactVerificationCode(UserContactCodeSendDto dto);

    UserInfoVo updateContact(UserContactUpdateDto dto);

    UserInfoVo unbindContact(UserContactUnbindDto dto);

    UserInfoVo editUsername(UserUsernameEditDto dto);

    UserInfoVo editPassword(UserPasswordSecurityEditDto dto);
}

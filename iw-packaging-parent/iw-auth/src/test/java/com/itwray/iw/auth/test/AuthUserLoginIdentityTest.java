package com.itwray.iw.auth.test;

import cn.hutool.crypto.digest.BCrypt;
import com.itwray.iw.auth.dao.AuthUserDao;
import com.itwray.iw.auth.model.dto.LoginPasswordDto;
import com.itwray.iw.auth.model.entity.AuthUserEntity;
import com.itwray.iw.auth.model.vo.UserInfoVo;
import com.itwray.iw.auth.service.impl.AuthUserServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthUserLoginIdentityTest {

    @Test
    void passwordLoginContinuesAfterEarlierIdentityPasswordMismatch() {
        AuthUserDao authUserDao = mock(AuthUserDao.class);
        AuthUserServiceImpl authUserService = new AuthUserServiceImpl(authUserDao);

        AuthUserEntity usernameCandidate = new AuthUserEntity();
        usernameCandidate.setId(1);
        usernameCandidate.setPassword(BCrypt.hashpw("wrong-password"));
        AuthUserEntity emailCandidate = new AuthUserEntity();
        emailCandidate.setId(2);
        emailCandidate.setPassword(BCrypt.hashpw("correct-password"));

        UserInfoVo expected = new UserInfoVo();
        expected.setId(2);
        when(authUserDao.queryPasswordLoginCandidates("shared-account"))
                .thenReturn(List.of(usernameCandidate, emailCandidate));
        when(authUserDao.loginSuccessAfter(2)).thenReturn(expected);

        LoginPasswordDto dto = new LoginPasswordDto();
        dto.setAccount(" shared-account ");
        dto.setPassword("correct-password");

        UserInfoVo actual = authUserService.loginByPassword(dto);

        assertSame(expected, actual);
        verify(authUserDao).loginBefore("shared-account");
        verify(authUserDao).loginSuccessAfter(2);
        verify(authUserDao, never()).loginSuccessAfter(1);
    }

    @Test
    void userInfoExposesSecurityStateFromStoredCredentials() {
        AuthUserDao authUserDao = new AuthUserDao();
        AuthUserEntity user = new AuthUserEntity();
        user.setId(8);
        user.setUsername("u_8");
        user.setPhoneNumber("13800138000");
        user.setEmailAddress(null);
        user.setPassword(null);

        UserInfoVo userInfo = authUserDao.buildUserInfoVo(user);

        assertEquals("u_8", userInfo.getUsername());
        assertTrue(userInfo.getCanEditUsername());
        assertTrue(userInfo.getPhoneBound());
        assertFalse(userInfo.getEmailBound());
        assertFalse(userInfo.getHasPassword());
    }
}

package com.itwray.iw.auth.service.impl;

import cn.hutool.json.JSONUtil;
import com.itwray.iw.auth.dao.AuthUserDao;
import com.itwray.iw.auth.model.AuthRedisKeyEnum;
import com.itwray.iw.auth.model.bo.RegisterInvitePendingBo;
import com.itwray.iw.auth.model.bo.UserAddBo;
import com.itwray.iw.auth.model.dto.LoginInviteRegisterDto;
import com.itwray.iw.auth.model.dto.LoginVerificationCodeDto;
import com.itwray.iw.auth.model.dto.RegisterInviteConfigUpdateDto;
import com.itwray.iw.auth.model.entity.AuthUserEntity;
import com.itwray.iw.auth.model.vo.RegisterInviteStatusVo;
import com.itwray.iw.auth.model.vo.UserInfoVo;
import com.itwray.iw.auth.service.AuthRegisterInviteService;
import com.itwray.iw.starter.redis.RedisUtil;
import com.itwray.iw.starter.redis.lock.DistributedLock;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.enums.RoleTypeEnum;
import com.itwray.iw.web.utils.UserUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

/**
 * 新用户注册邀请码服务实现层
 *
 * @author wray
 * @since 2026/7/2
 */
@Service
public class AuthRegisterInviteServiceImpl implements AuthRegisterInviteService {

    private static final String CODE_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final int CODE_LENGTH = 6;

    private static final String DISABLED_VALUE = "false";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthUserDao authUserDao;

    public AuthRegisterInviteServiceImpl(AuthUserDao authUserDao) {
        this.authUserDao = authUserDao;
    }

    @Override
    public boolean isInviteRegisterEnabled() {
        String enabled = RedisUtil.get(AuthRedisKeyEnum.USER_REGISTER_INVITE_ENABLED_KEY.getKey(), String.class);
        return !DISABLED_VALUE.equalsIgnoreCase(enabled);
    }

    @Override
    public RegisterInviteStatusVo getStatus() {
        checkSuperAdminPermission();
        return buildStatus();
    }

    @Override
    public void updateConfig(RegisterInviteConfigUpdateDto dto) {
        checkSuperAdminPermission();
        RedisUtil.set(AuthRedisKeyEnum.USER_REGISTER_INVITE_ENABLED_KEY.getKey(), String.valueOf(Boolean.TRUE.equals(dto.getEnabled())));
    }

    @Override
    @DistributedLock(lockName = "'register:invite:generate'")
    public RegisterInviteStatusVo generateInvite() {
        checkSuperAdminPermission();
        String oldInviteCode = getCurrentInviteCode();
        if (StringUtils.isNotBlank(oldInviteCode)) {
            throw new BusinessException("当前已存在邀请码");
        }

        String inviteCode = generateCode();
        String nowEpochSeconds = String.valueOf(Instant.now().getEpochSecond());
        AuthRedisKeyEnum.USER_REGISTER_INVITE_CODE_KEY.setStringValue(inviteCode);
        AuthRedisKeyEnum.USER_REGISTER_INVITE_CREATE_TIME_KEY.setStringValue(nowEpochSeconds);
        return buildStatus();
    }

    @Override
    public void deleteInvite() {
        checkSuperAdminPermission();
        AuthRedisKeyEnum.USER_REGISTER_INVITE_CODE_KEY.delete();
        AuthRedisKeyEnum.USER_REGISTER_INVITE_CREATE_TIME_KEY.delete();
    }

    @Override
    public UserInfoVo createInviteRequiredResponse(LoginVerificationCodeDto dto) {
        RegisterInvitePendingBo pendingBo = new RegisterInvitePendingBo();
        pendingBo.setLoginWay(dto.getLoginWay().getCode());
        pendingBo.setPhoneNumber(dto.getPhoneNumber());
        pendingBo.setEmailAddress(dto.getEmailAddress());
        pendingBo.setPassword(dto.getPassword());

        String ticket = UUID.randomUUID().toString().replace("-", "");
        AuthRedisKeyEnum.USER_REGISTER_INVITE_PENDING_TICKET_KEY.setStringValue(JSONUtil.toJsonStr(pendingBo), ticket);

        UserInfoVo userInfoVo = new UserInfoVo();
        userInfoVo.setInviteRequired(true);
        userInfoVo.setRegisterTicket(ticket);
        return userInfoVo;
    }

    @Override
    @Transactional
    public UserInfoVo registerByInvite(LoginInviteRegisterDto dto) {
        String ticketKey = AuthRedisKeyEnum.USER_REGISTER_INVITE_PENDING_TICKET_KEY.getKey(dto.getRegisterTicket());
        String pendingText = RedisUtil.get(ticketKey, String.class);
        if (StringUtils.isBlank(pendingText)) {
            throw new BusinessException("注册状态已失效，请重新获取验证码");
        }

        verifyInviteCode(dto.getInviteCode());

        RegisterInvitePendingBo pendingBo = JSONUtil.toBean(pendingText, RegisterInvitePendingBo.class);
        UserAddBo userAddBo = new UserAddBo();
        userAddBo.setPhoneNumber(pendingBo.getPhoneNumber());
        userAddBo.setEmailAddress(pendingBo.getEmailAddress());
        userAddBo.setPassword(pendingBo.getPassword());

        AuthUserEntity authUserEntity = authUserDao.addNewUser(userAddBo);
        RedisUtil.delete(ticketKey);
        return authUserDao.loginSuccessAfter(authUserEntity.getId());
    }

    @Override
    public void verifyInviteIfEnabled(String inviteCode) {
        if (isInviteRegisterEnabled()) {
            verifyInviteCode(inviteCode);
        }
    }

    private RegisterInviteStatusVo buildStatus() {
        String inviteCode = getCurrentInviteCode();
        RegisterInviteStatusVo statusVo = new RegisterInviteStatusVo();
        statusVo.setEnabled(isInviteRegisterEnabled());
        statusVo.setHasInvite(StringUtils.isNotBlank(inviteCode));
        statusVo.setInviteCode(inviteCode);

        if (StringUtils.isNotBlank(inviteCode)) {
            LocalDateTime createTime = getInviteCreateTime();
            long ttl = RedisUtil.getTime(AuthRedisKeyEnum.USER_REGISTER_INVITE_CODE_KEY.getKey());
            statusVo.setCreateTime(createTime);
            if (createTime != null) {
                statusVo.setExpireTime(createTime.plusSeconds(AuthRedisKeyEnum.USER_REGISTER_INVITE_CODE_KEY.getExpireTime()));
            } else if (ttl > 0) {
                statusVo.setExpireTime(LocalDateTime.now().plusSeconds(ttl));
            }
        }

        return statusVo;
    }

    private String getCurrentInviteCode() {
        return RedisUtil.get(AuthRedisKeyEnum.USER_REGISTER_INVITE_CODE_KEY.getKey(), String.class);
    }

    private LocalDateTime getInviteCreateTime() {
        String createEpochSecondsText = RedisUtil.get(AuthRedisKeyEnum.USER_REGISTER_INVITE_CREATE_TIME_KEY.getKey(), String.class);
        if (StringUtils.isBlank(createEpochSecondsText)) {
            return null;
        }
        long createEpochSeconds = Long.parseLong(createEpochSecondsText);
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(createEpochSeconds), ZoneId.systemDefault());
    }

    private void verifyInviteCode(String inviteCode) {
        String normalizedInviteCode = normalizeInviteCode(inviteCode);
        if (!isValidInviteCode(normalizedInviteCode)) {
            throw new BusinessException("邀请码格式错误");
        }

        String currentInviteCode = getCurrentInviteCode();
        if (StringUtils.isBlank(currentInviteCode)) {
            throw new BusinessException("邀请码已失效");
        }
        if (!currentInviteCode.equals(normalizedInviteCode)) {
            throw new BusinessException("邀请码错误");
        }
    }

    private String normalizeInviteCode(String inviteCode) {
        return StringUtils.trimToEmpty(inviteCode).toUpperCase(Locale.ROOT);
    }

    private boolean isValidInviteCode(String inviteCode) {
        if (StringUtils.length(inviteCode) != CODE_LENGTH) {
            return false;
        }
        for (char c : inviteCode.toCharArray()) {
            if (CODE_CHARS.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    private String generateCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return code.toString();
    }

    private void checkSuperAdminPermission() {
        Integer userId = UserUtils.getUserId();
        AuthUserEntity authUserEntity = authUserDao.queryById(userId);
        if (!RoleTypeEnum.SUPER_ADMIN.getCode().equals(authUserEntity.getRoleType())) {
            throw new BusinessException("权限不足");
        }
    }
}

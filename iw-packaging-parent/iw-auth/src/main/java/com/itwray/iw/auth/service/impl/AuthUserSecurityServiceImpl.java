package com.itwray.iw.auth.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.itwray.iw.auth.dao.AuthUserDao;
import com.itwray.iw.auth.model.AuthRedisKeyEnum;
import com.itwray.iw.auth.model.bo.UserSecurityTicketBo;
import com.itwray.iw.auth.model.dto.*;
import com.itwray.iw.auth.model.entity.AuthUserEntity;
import com.itwray.iw.auth.model.enums.UserContactTypeEnum;
import com.itwray.iw.auth.model.enums.UserSecurityOperationEnum;
import com.itwray.iw.auth.model.enums.UserSecurityVerificationMethodEnum;
import com.itwray.iw.auth.model.vo.UserInfoVo;
import com.itwray.iw.auth.model.vo.UserSecurityTicketVo;
import com.itwray.iw.auth.service.AuthFamilyGroupService;
import com.itwray.iw.auth.service.AuthUserSecurityService;
import com.itwray.iw.auth.service.AuthVerificationService;
import com.itwray.iw.common.utils.ConstantEnumUtil;
import com.itwray.iw.common.utils.NumberUtils;
import com.itwray.iw.starter.redis.RedisUtil;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.UserUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthUserSecurityServiceImpl implements AuthUserSecurityService {

    private final AuthUserDao authUserDao;

    private final AuthVerificationService authVerificationService;

    private final AuthFamilyGroupService authFamilyGroupService;

    public AuthUserSecurityServiceImpl(AuthUserDao authUserDao, AuthVerificationService authVerificationService,
                                       AuthFamilyGroupService authFamilyGroupService) {
        this.authUserDao = authUserDao;
        this.authVerificationService = authVerificationService;
        this.authFamilyGroupService = authFamilyGroupService;
    }

    @Override
    public void sendSecurityVerificationCode(UserSecurityCodeSendDto dto) {
        UserSecurityOperationEnum operation = getOperation(dto.getOperation());
        UserSecurityVerificationMethodEnum method = getVerificationMethod(dto.getMethod());
        AuthUserEntity user = getCurrentUser();
        validateOperationState(user, operation);
        String destination = getVerificationDestination(user, method);
        Object[] keyArgs = securityCodeKeyArgs(user.getId(), operation, method);
        switch (method) {
            case PHONE -> authVerificationService.getPhoneVerificationCode(
                    destination, AuthRedisKeyEnum.USER_SECURITY_VERIFY_KEY, keyArgs);
            case EMAIL -> authVerificationService.getEmailVerificationCode(
                    destination, AuthRedisKeyEnum.USER_SECURITY_VERIFY_KEY, keyArgs);
            case PASSWORD -> throw new BusinessException("密码验证不需要发送验证码");
        }
    }

    @Override
    public UserSecurityTicketVo verifySecurityIdentity(UserSecurityVerifyDto dto) {
        UserSecurityOperationEnum operation = getOperation(dto.getOperation());
        UserSecurityVerificationMethodEnum method = getVerificationMethod(dto.getMethod());
        AuthUserEntity user = getCurrentUser();
        validateOperationState(user, operation);

        if (method == UserSecurityVerificationMethodEnum.PASSWORD) {
            verifyPassword(user, dto.getPassword());
        } else {
            getVerificationDestination(user, method);
            boolean matched = authVerificationService.compareVerificationCode(
                    dto.getVerificationCode(), AuthRedisKeyEnum.USER_SECURITY_VERIFY_KEY,
                    securityCodeKeyArgs(user.getId(), operation, method));
            if (!matched) {
                throw new BusinessException("验证码错误");
            }
        }

        String ticket = UUID.randomUUID().toString().replace("-", "");
        UserSecurityTicketBo ticketBo = new UserSecurityTicketBo();
        ticketBo.setUserId(user.getId());
        ticketBo.setToken(UserUtils.getToken());
        ticketBo.setOperation(operation.getCode());
        AuthRedisKeyEnum.USER_SECURITY_TICKET_KEY.setStringValue(JSONUtil.toJsonStr(ticketBo), ticket);
        return new UserSecurityTicketVo(ticket, AuthRedisKeyEnum.USER_SECURITY_TICKET_KEY.getExpireTime());
    }

    @Override
    public void sendContactVerificationCode(UserContactCodeSendDto dto) {
        UserContactTypeEnum contactType = getContactType(dto.getContactType());
        UserSecurityTicketBo ticket = getTicket(dto.getSecurityTicket());
        UserSecurityOperationEnum operation = getOperation(ticket.getOperation());
        AuthUserEntity user = getCurrentUser();
        validateContactUpdateOperation(user, operation, contactType);
        String contact = normalizeContact(contactType, dto.getContact());
        validateContactChanged(user, contactType, contact);
        checkContactUnique(user.getId(), contactType, contact);

        Object[] keyArgs = contactCodeKeyArgs(user.getId(), contactType, contact);
        if (contactType == UserContactTypeEnum.PHONE) {
            authVerificationService.getPhoneVerificationCode(
                    contact, AuthRedisKeyEnum.USER_CONTACT_VERIFY_KEY, keyArgs);
        } else {
            authVerificationService.getEmailVerificationCode(
                    contact, AuthRedisKeyEnum.USER_CONTACT_VERIFY_KEY, keyArgs);
        }
    }

    @Override
    @Transactional
    public UserInfoVo updateContact(UserContactUpdateDto dto) {
        UserContactTypeEnum contactType = getContactType(dto.getContactType());
        UserSecurityTicketBo ticket = getTicket(dto.getSecurityTicket());
        UserSecurityOperationEnum operation = getOperation(ticket.getOperation());
        AuthUserEntity user = getCurrentUser();
        validateContactUpdateOperation(user, operation, contactType);
        String contact = normalizeContact(contactType, dto.getContact());
        validateContactChanged(user, contactType, contact);
        checkContactUnique(user.getId(), contactType, contact);

        boolean matched = authVerificationService.compareVerificationCode(
                dto.getVerificationCode(), AuthRedisKeyEnum.USER_CONTACT_VERIFY_KEY,
                contactCodeKeyArgs(user.getId(), contactType, contact));
        if (!matched) {
            throw new BusinessException("验证码错误");
        }
        consumeTicket(dto.getSecurityTicket());

        try {
            var update = authUserDao.lambdaUpdate()
                    .eq(AuthUserEntity::getId, user.getId())
                    .set(AuthUserEntity::getUpdateTime, LocalDateTime.now());
            if (contactType == UserContactTypeEnum.PHONE) {
                update.set(AuthUserEntity::getPhoneNumber, contact);
                user.setPhoneNumber(contact);
            } else {
                update.set(AuthUserEntity::getEmailAddress, contact);
                user.setEmailAddress(contact);
            }
            if (!update.update()) {
                throw new BusinessException("联系方式状态已变化，请刷新重试");
            }
        } catch (DuplicateKeyException e) {
            throw contactDuplicateException(contactType);
        }

        revokeOtherTokens(user.getId());
        return authUserDao.buildUserInfoVo(user);
    }

    @Override
    @Transactional
    public UserInfoVo unbindContact(UserContactUnbindDto dto) {
        UserContactTypeEnum contactType = getContactType(dto.getContactType());
        UserSecurityTicketBo ticket = getTicket(dto.getSecurityTicket());
        UserSecurityOperationEnum operation = getOperation(ticket.getOperation());
        AuthUserEntity user = getCurrentUser();
        validateContactUnbindOperation(user, operation, contactType);
        consumeTicket(dto.getSecurityTicket());

        var update = authUserDao.lambdaUpdate()
                .eq(AuthUserEntity::getId, user.getId())
                .set(AuthUserEntity::getUpdateTime, LocalDateTime.now());
        if (contactType == UserContactTypeEnum.PHONE) {
            update.set(AuthUserEntity::getPhoneNumber, null);
            user.setPhoneNumber(null);
        } else {
            update.set(AuthUserEntity::getEmailAddress, null);
            user.setEmailAddress(null);
        }
        if (!update.update()) {
            throw new BusinessException("联系方式状态已变化，请刷新重试");
        }

        revokeOtherTokens(user.getId());
        return authUserDao.buildUserInfoVo(user);
    }

    @Override
    @Transactional
    public UserInfoVo editUsername(UserUsernameEditDto dto) {
        UserSecurityTicketBo ticket = getTicket(dto.getSecurityTicket());
        UserSecurityOperationEnum operation = getOperation(ticket.getOperation());
        if (operation != UserSecurityOperationEnum.EDIT_USERNAME) {
            throw new BusinessException("安全票据与当前操作不匹配");
        }
        AuthUserEntity user = getCurrentUser();
        validateOperationState(user, operation);

        String username = StringUtils.trim(dto.getUsername());
        if (StringUtils.isBlank(username)) {
            throw new BusinessException("新用户名不能为空");
        }
        if (username.length() > 64) {
            throw new BusinessException("用户名不能超过64位");
        }
        if (StringUtils.startsWithIgnoreCase(username, AuthUserDao.SYSTEM_USERNAME_PREFIX)) {
            throw new BusinessException("用户名不能以u_开头");
        }
        if (NumberUtils.isValidPhoneNumber(username) || NumberUtils.isValidEmailAddress(username)) {
            throw new BusinessException("用户名不能使用完整手机号或邮箱");
        }
        AuthUserEntity existing = authUserDao.queryOneByUsername(username);
        if (existing != null && !Objects.equals(existing.getId(), user.getId())) {
            throw new BusinessException("用户名已存在");
        }
        consumeTicket(dto.getSecurityTicket());

        try {
            boolean updated = authUserDao.lambdaUpdate()
                    .eq(AuthUserEntity::getId, user.getId())
                    .eq(AuthUserEntity::getUsername, user.getUsername())
                    .set(AuthUserEntity::getUsername, username)
                    .set(AuthUserEntity::getUpdateTime, LocalDateTime.now())
                    .update();
            if (!updated) {
                throw new BusinessException("用户名状态已变化，请刷新重试");
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException("用户名已存在");
        }

        user.setUsername(username);
        revokeOtherTokens(user.getId());
        return authUserDao.buildUserInfoVo(user);
    }

    @Override
    @Transactional
    public UserInfoVo editPassword(UserPasswordSecurityEditDto dto) {
        UserSecurityTicketBo ticket = getTicket(dto.getSecurityTicket());
        UserSecurityOperationEnum operation = getOperation(ticket.getOperation());
        AuthUserEntity user = getCurrentUser();
        validateOperationState(user, operation);
        if (operation != UserSecurityOperationEnum.SET_PASSWORD
                && operation != UserSecurityOperationEnum.CHANGE_PASSWORD) {
            throw new BusinessException("安全票据与当前操作不匹配");
        }
        consumeTicket(dto.getSecurityTicket());

        String encodedPassword = BCrypt.hashpw(dto.getNewPassword());
        boolean updated = authUserDao.lambdaUpdate()
                .eq(AuthUserEntity::getId, user.getId())
                .set(AuthUserEntity::getPassword, encodedPassword)
                .set(AuthUserEntity::getUpdateTime, LocalDateTime.now())
                .update();
        if (!updated) {
            throw new BusinessException("密码状态已变化，请刷新重试");
        }
        user.setPassword(encodedPassword);

        revokeOtherTokens(user.getId());
        return authUserDao.buildUserInfoVo(user);
    }

    @Override
    @Transactional
    public void deleteAccount(UserAccountDeletionDto dto) {
        UserSecurityTicketBo ticket = getTicket(dto.getSecurityTicket());
        UserSecurityOperationEnum operation = getOperation(ticket.getOperation());
        if (operation != UserSecurityOperationEnum.DELETE_ACCOUNT) {
            throw new BusinessException("安全票据与当前操作不匹配");
        }

        AuthUserEntity user = getCurrentUser();
        authFamilyGroupService.prepareAccountDeletion(user.getId());
        consumeTicket(dto.getSecurityTicket());

        boolean updated = authUserDao.lambdaUpdate()
                .eq(AuthUserEntity::getId, user.getId())
                .eq(AuthUserEntity::getDeleted, Boolean.FALSE)
                .set(AuthUserEntity::getCancelledTime, LocalDateTime.now())
                .set(AuthUserEntity::getDeleted, Boolean.TRUE)
                .update();
        if (!updated) {
            throw new BusinessException("用户不存在，请刷新重试");
        }

        revokeAllTokens(user.getId());
        RedisUtil.delete(AuthRedisKeyEnum.DICT_KEY.getKey(user.getId()));
        RedisUtil.delete(AuthRedisKeyEnum.USER_DICT_VERSION.getKey(user.getId()));
    }

    private void verifyPassword(AuthUserEntity user, String password) {
        Integer failCount = AuthRedisKeyEnum.USER_SECURITY_PASSWORD_FAIL_KEY.getStringValue(Integer.class, user.getId());
        if (failCount != null && failCount >= 5) {
            throw new BusinessException("密码错误次数过多，请稍后再试");
        }
        if (StringUtils.isBlank(user.getPassword()) || StringUtils.isBlank(password)
                || !BCrypt.checkpw(password, user.getPassword())) {
            RedisUtil.incrementOne(AuthRedisKeyEnum.USER_SECURITY_PASSWORD_FAIL_KEY.getKey(user.getId()));
            AuthRedisKeyEnum.USER_SECURITY_PASSWORD_FAIL_KEY.setExpire(user.getId());
            throw new BusinessException("身份验证失败");
        }
        AuthRedisKeyEnum.USER_SECURITY_PASSWORD_FAIL_KEY.delete(user.getId());
    }

    private String getVerificationDestination(AuthUserEntity user, UserSecurityVerificationMethodEnum method) {
        return switch (method) {
            case PHONE -> {
                if (StringUtils.isBlank(user.getPhoneNumber())) {
                    throw new BusinessException("未绑定手机号");
                }
                yield user.getPhoneNumber();
            }
            case EMAIL -> {
                if (StringUtils.isBlank(user.getEmailAddress())) {
                    throw new BusinessException("未绑定邮箱");
                }
                yield user.getEmailAddress();
            }
            case PASSWORD -> {
                if (StringUtils.isBlank(user.getPassword())) {
                    throw new BusinessException("尚未设置密码");
                }
                yield null;
            }
        };
    }

    private void validateOperationState(AuthUserEntity user, UserSecurityOperationEnum operation) {
        switch (operation) {
            case BIND_PHONE -> requireState(StringUtils.isBlank(user.getPhoneNumber()), "手机号已绑定");
            case CHANGE_PHONE -> requireState(StringUtils.isNotBlank(user.getPhoneNumber()), "未绑定手机号");
            case UNBIND_PHONE -> {
                requireState(StringUtils.isNotBlank(user.getPhoneNumber()), "未绑定手机号");
                requireState(StringUtils.isNotBlank(user.getEmailAddress()), "请先绑定邮箱再解绑手机号");
            }
            case BIND_EMAIL -> requireState(StringUtils.isBlank(user.getEmailAddress()), "邮箱已绑定");
            case CHANGE_EMAIL -> requireState(StringUtils.isNotBlank(user.getEmailAddress()), "未绑定邮箱");
            case UNBIND_EMAIL -> {
                requireState(StringUtils.isNotBlank(user.getEmailAddress()), "未绑定邮箱");
                requireState(StringUtils.isNotBlank(user.getPhoneNumber()), "请先绑定手机号再解绑邮箱");
            }
            case EDIT_USERNAME -> requireState(
                    StringUtils.startsWithIgnoreCase(user.getUsername(), AuthUserDao.SYSTEM_USERNAME_PREFIX),
                    "用户名仅允许修改一次");
            case SET_PASSWORD -> requireState(StringUtils.isBlank(user.getPassword()), "密码已设置");
            case CHANGE_PASSWORD -> requireState(StringUtils.isNotBlank(user.getPassword()), "尚未设置密码");
            case DELETE_ACCOUNT -> {
                // 账号注销支持当前账号已有的任一身份验证方式。
            }
        }
    }

    private void validateContactUpdateOperation(AuthUserEntity user, UserSecurityOperationEnum operation,
                                                UserContactTypeEnum contactType) {
        validateOperationState(user, operation);
        if (!operation.isContactOperation() || operation.isUnbind() || operation.getContactType() != contactType) {
            throw new BusinessException("安全票据与当前操作不匹配");
        }
    }

    private void validateContactUnbindOperation(AuthUserEntity user, UserSecurityOperationEnum operation,
                                                UserContactTypeEnum contactType) {
        validateOperationState(user, operation);
        if (!operation.isContactOperation() || !operation.isUnbind() || operation.getContactType() != contactType) {
            throw new BusinessException("安全票据与当前操作不匹配");
        }
    }

    private void requireState(boolean condition, String message) {
        if (!condition) {
            throw new BusinessException(message);
        }
    }

    private String normalizeContact(UserContactTypeEnum contactType, String value) {
        String contact = StringUtils.trim(value);
        if (contactType == UserContactTypeEnum.PHONE) {
            if (!NumberUtils.isValidPhoneNumber(contact)) {
                throw new BusinessException("电话号码格式错误");
            }
            return contact;
        }
        contact = contact.toLowerCase(Locale.ROOT);
        if (!NumberUtils.isValidEmailAddress(contact)) {
            throw new BusinessException("邮箱格式错误");
        }
        return contact;
    }

    private void checkContactUnique(Integer userId, UserContactTypeEnum contactType, String contact) {
        AuthUserEntity existing = contactType == UserContactTypeEnum.PHONE
                ? authUserDao.queryOneByPhoneNumber(contact)
                : authUserDao.queryOneByEmailAddress(contact);
        if (existing != null && !Objects.equals(existing.getId(), userId)) {
            throw contactDuplicateException(contactType);
        }
    }

    private void validateContactChanged(AuthUserEntity user, UserContactTypeEnum contactType, String contact) {
        String currentContact = contactType == UserContactTypeEnum.PHONE
                ? user.getPhoneNumber()
                : user.getEmailAddress();
        if (Objects.equals(currentContact, contact)) {
            throw new BusinessException(contactType == UserContactTypeEnum.PHONE
                    ? "新手机号不能与当前手机号相同"
                    : "新邮箱不能与当前邮箱相同");
        }
    }

    private BusinessException contactDuplicateException(UserContactTypeEnum contactType) {
        return new BusinessException(contactType == UserContactTypeEnum.PHONE ? "手机号已被其他账号绑定" : "邮箱已被其他账号绑定");
    }

    private UserSecurityTicketBo getTicket(String ticket) {
        String ticketText = AuthRedisKeyEnum.USER_SECURITY_TICKET_KEY.getStringValue(String.class, ticket);
        if (StringUtils.isBlank(ticketText)) {
            throw new BusinessException("安全验证已失效，请重新验证");
        }
        UserSecurityTicketBo ticketBo = JSONUtil.toBean(ticketText, UserSecurityTicketBo.class);
        if (!Objects.equals(ticketBo.getUserId(), UserUtils.getUserId())
                || !Objects.equals(ticketBo.getToken(), UserUtils.getToken())) {
            throw new BusinessException("安全票据无效，请重新验证");
        }
        return ticketBo;
    }

    private void consumeTicket(String ticket) {
        String ticketKey = AuthRedisKeyEnum.USER_SECURITY_TICKET_KEY.getKey(ticket);
        String ticketText = RedisUtil.getAndDelete(ticketKey, String.class);
        if (StringUtils.isBlank(ticketText)) {
            throw new BusinessException("安全验证已失效，请重新验证");
        }
        UserSecurityTicketBo ticketBo = JSONUtil.toBean(ticketText, UserSecurityTicketBo.class);
        if (!Objects.equals(ticketBo.getUserId(), UserUtils.getUserId())
                || !Objects.equals(ticketBo.getToken(), UserUtils.getToken())) {
            throw new BusinessException("安全票据无效，请重新验证");
        }
    }

    private void revokeOtherTokens(Integer userId) {
        String currentToken = UserUtils.getToken();
        String tokenSetKey = AuthRedisKeyEnum.USER_TOKEN_SET_KEY.getKey(userId);
        Set<String> userTokens = RedisUtil.members(tokenSetKey, String.class);
        if (userTokens == null) {
            return;
        }
        for (String token : userTokens) {
            if (!Objects.equals(token, currentToken)) {
                RedisUtil.delete(AuthRedisKeyEnum.USER_TOKEN_KEY.getKey(token));
                RedisUtil.remove(tokenSetKey, token);
            }
        }
        AuthRedisKeyEnum.USER_TOKEN_SET_KEY.setExpire(userId);
    }

    private void revokeAllTokens(Integer userId) {
        String tokenSetKey = AuthRedisKeyEnum.USER_TOKEN_SET_KEY.getKey(userId);
        Set<String> userTokens = RedisUtil.members(tokenSetKey, String.class);
        if (userTokens != null) {
            for (String token : userTokens) {
                RedisUtil.delete(AuthRedisKeyEnum.USER_TOKEN_KEY.getKey(token));
            }
        }
        RedisUtil.delete(tokenSetKey);
    }

    private AuthUserEntity getCurrentUser() {
        AuthUserEntity user = authUserDao.queryById(UserUtils.getUserId());
        if (user == null) {
            throw new BusinessException("用户不存在，请刷新重试");
        }
        return user;
    }

    private UserSecurityOperationEnum getOperation(Integer code) {
        UserSecurityOperationEnum operation = ConstantEnumUtil.findByType(UserSecurityOperationEnum.class, code);
        if (operation == null) {
            throw new BusinessException("不支持的安全操作");
        }
        return operation;
    }

    private UserSecurityVerificationMethodEnum getVerificationMethod(Integer code) {
        UserSecurityVerificationMethodEnum method = ConstantEnumUtil.findByType(UserSecurityVerificationMethodEnum.class, code);
        if (method == null) {
            throw new BusinessException("不支持的验证方式");
        }
        return method;
    }

    private UserContactTypeEnum getContactType(Integer code) {
        UserContactTypeEnum contactType = ConstantEnumUtil.findByType(UserContactTypeEnum.class, code);
        if (contactType == null) {
            throw new BusinessException("不支持的联系方式类型");
        }
        return contactType;
    }

    private Object[] securityCodeKeyArgs(Integer userId, UserSecurityOperationEnum operation,
                                         UserSecurityVerificationMethodEnum method) {
        return new Object[]{userId, operation.getCode(), method.getCode()};
    }

    private Object[] contactCodeKeyArgs(Integer userId, UserContactTypeEnum contactType, String contact) {
        return new Object[]{userId, contactType.getCode(), DigestUtil.sha256Hex(contact)};
    }
}

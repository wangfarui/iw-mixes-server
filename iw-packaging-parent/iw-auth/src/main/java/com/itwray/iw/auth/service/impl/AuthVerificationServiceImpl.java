package com.itwray.iw.auth.service.impl;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.itwray.iw.auth.model.AuthRedisKeyEnum;
import com.itwray.iw.auth.service.AuthVerificationService;
import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.common.utils.NumberUtils;
import com.itwray.iw.external.client.InternalApiClient;
import com.itwray.iw.external.model.dto.SendEmailDto;
import com.itwray.iw.external.model.dto.SmsSendVerificationCodeDto;
import com.itwray.iw.starter.redis.RedisKeyManager;
import com.itwray.iw.starter.redis.RedisUtil;
import com.itwray.iw.starter.redis.lock.DistributedLock;
import com.itwray.iw.web.config.IwAliyunProperties;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.IpUtils;
import com.itwray.iw.web.utils.SpringWebHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 授权验证服务实现层
 *
 * @author wray
 * @since 2025/3/11
 */
@Service
@Slf4j
public class AuthVerificationServiceImpl implements AuthVerificationService {

    private InternalApiClient internalApiClient;

    private IwAliyunProperties iwAliyunProperties;

    // SmsClient 统一由 web 模块扫描注册为Bean对象
    @Autowired
    public void setInternalApiClient(InternalApiClient internalApiClient) {
        this.internalApiClient = internalApiClient;
    }

    @Autowired
    public void setIwAliyunProperties(IwAliyunProperties iwAliyunProperties) {
        this.iwAliyunProperties = iwAliyunProperties;
    }

    @Override
    @DistributedLock(lockName = "'getPhoneVerificationCode:' + #phoneNumber")
    public String getPhoneVerificationCode(String phoneNumber, RedisKeyManager keyManager) {
        return this.sendPhoneVerificationCode(phoneNumber, keyManager, phoneNumber);
    }

    @Override
    @DistributedLock(lockName = "'getPhoneVerificationCode:' + #phoneNumber")
    public String getPhoneVerificationCode(String phoneNumber, RedisKeyManager keyManager, Object... keyArgs) {
        return this.sendPhoneVerificationCode(phoneNumber, keyManager, keyArgs);
    }

    private String sendPhoneVerificationCode(String phoneNumber, RedisKeyManager keyManager, Object... keyArgs) {
        if (!NumberUtils.isValidPhoneNumber(phoneNumber)) {
            throw new BusinessException("电话号码格式错误");
        }

        return this.genericVerificationCode(keyManager, keyArgs, verificationCode -> {
            // 构建发送短信验证码对象
            SmsSendVerificationCodeDto dto = new SmsSendVerificationCodeDto();
            dto.setPhoneNumber(phoneNumber);
            dto.setSignName(this.iwAliyunProperties.getSms().getSignName());
            dto.setTemplateCode(this.iwAliyunProperties.getSms().getTemplateCode());
            dto.setTemplateParam("{\"code\":\"" + verificationCode + "\"}");
            // 同步调用发送验证码
            GeneralResponse<Void> response = internalApiClient.sendVerificationCode(dto);
            if (!response.isSuccess()) {
                throw new BusinessException("短信发送失败");
            }
        });
    }

    @Override
    public boolean compareVerificationCode(String verificationCode, String account, RedisKeyManager keyManager) {
        return this.compareVerificationCode(verificationCode, keyManager, account);
    }

    @Override
    public boolean compareVerificationCode(String verificationCode, RedisKeyManager keyManager, Object... keyArgs) {
        if (StringUtils.isBlank(verificationCode)) {
            return false;
        }
        String verificationKey = keyManager.getKey(keyArgs);
        String keyHash = DigestUtil.sha256Hex(verificationKey);
        Integer failCount = RedisUtil.get(AuthRedisKeyEnum.VERIFICATION_FAIL_COUNT_KEY.getKey(keyHash), Integer.class);
        if (failCount != null && failCount >= 5) {
            RedisUtil.delete(verificationKey);
            throw new BusinessException("验证码错误次数过多，请重新获取");
        }

        String cachedCode = RedisUtil.get(verificationKey, String.class);
        if (StringUtils.isBlank(cachedCode)) {
            throw new BusinessException("验证码失效，请重新获取");
        }

        boolean matched = cachedCode.equals(verificationCode);
        if (matched) {
            RedisUtil.delete(verificationKey);
            AuthRedisKeyEnum.VERIFICATION_FAIL_COUNT_KEY.delete(keyHash);
        } else {
            RedisUtil.incrementOne(AuthRedisKeyEnum.VERIFICATION_FAIL_COUNT_KEY.getKey(keyHash));
            AuthRedisKeyEnum.VERIFICATION_FAIL_COUNT_KEY.setExpire(keyHash);
        }
        return matched;
    }

    @Override
    @DistributedLock(lockName = "'getEmailVerificationCode:' + #emailAddress")
    public String getEmailVerificationCode(String emailAddress, RedisKeyManager keyManager) {
        return this.sendEmailVerificationCode(emailAddress, keyManager, emailAddress);
    }

    @Override
    @DistributedLock(lockName = "'getEmailVerificationCode:' + #emailAddress")
    public String getEmailVerificationCode(String emailAddress, RedisKeyManager keyManager, Object... keyArgs) {
        return this.sendEmailVerificationCode(emailAddress, keyManager, keyArgs);
    }

    private String sendEmailVerificationCode(String emailAddress, RedisKeyManager keyManager, Object... keyArgs) {
        if (!NumberUtils.isValidEmailAddress(emailAddress)) {
            throw new BusinessException("邮箱格式错误");
        }

        return this.genericVerificationCode(keyManager, keyArgs, verificationCode -> {
            IwAliyunProperties.Email email = this.iwAliyunProperties.getEmail();
            // 构建发送邮箱验证码对象
            SendEmailDto dto = new SendEmailDto();
            dto.setAccountName(email.getAccountName());
            dto.setFromAlias(email.getFromAlias());
            dto.setToAddress(emailAddress);
            dto.setSubject("IW 验证码");
            dto.setTextBody("您的验证码是: " + verificationCode + ", 有效期5分钟. 请勿泄露.");
            // 同步调用发送验证码
            GeneralResponse<Void> response = internalApiClient.sendSingleEmail(dto);
            if (!response.isSuccess()) {
                throw new BusinessException("邮件发送失败");
            }
        });
    }

    private String genericVerificationCode(RedisKeyManager keyManager, Object[] keyArgs, Consumer<String> consumer) {
        String clientIp = IpUtils.getClientIp(SpringWebHolder.getRequest());
        String verificationKey = keyManager.getKey(keyArgs);
        String keyHash = DigestUtil.sha256Hex(verificationKey);

        if (AuthRedisKeyEnum.VERIFICATION_SEND_COOLDOWN_KEY.getStringValue(String.class, keyHash) != null) {
            throw new BusinessException("验证码发送过于频繁，请稍后再试");
        }

        // 校验当前ip获取验证码的次数
        Integer verifyCount = RedisUtil.get(AuthRedisKeyEnum.PHONE_VERIFY_IP_KEY.getKey(clientIp), Integer.class);
        if (verifyCount != null && verifyCount >= 5) {
            throw new BusinessException("操作频繁，请稍后再试");
        }

        // 生成6位验证码
        Integer[] codes = NumberUtil.generateBySet(100000, 999999, 1);
        String verificationCode = codes[0].toString();

        // 调用验证码发送服务
        consumer.accept(verificationCode);

        RedisUtil.set(verificationKey, verificationCode, keyManager.getExpireTime());
        AuthRedisKeyEnum.VERIFICATION_SEND_COOLDOWN_KEY.setStringValue("1", keyHash);
        AuthRedisKeyEnum.VERIFICATION_FAIL_COUNT_KEY.delete(keyHash);
        // 同一ip, 1小时内只发5次
        RedisUtil.incrementOne(AuthRedisKeyEnum.PHONE_VERIFY_IP_KEY.getKey(clientIp));
        AuthRedisKeyEnum.PHONE_VERIFY_IP_KEY.setExpire(clientIp);

        return verificationCode;
    }
}

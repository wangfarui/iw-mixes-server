package com.itwray.iw.auth.dao;

import cn.hutool.crypto.digest.BCrypt;
import com.itwray.iw.auth.mapper.AuthUserMapper;
import com.itwray.iw.auth.model.AuthRedisKeyEnum;
import com.itwray.iw.auth.model.bo.UserAddBo;
import com.itwray.iw.auth.model.entity.AuthUserEntity;
import com.itwray.iw.auth.model.enums.UserGenderEnum;
import com.itwray.iw.auth.model.enums.UserLoginWayEnum;
import com.itwray.iw.auth.model.vo.UserInfoVo;
import com.itwray.iw.common.utils.NumberUtils;
import com.itwray.iw.starter.redis.RedisUtil;
import com.itwray.iw.starter.rocketmq.MQProducerHelper;
import com.itwray.iw.web.constants.WebCommonConstants;
import com.itwray.iw.web.dao.BaseDao;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.exception.IwWebException;
import com.itwray.iw.web.model.enums.mq.RegisterNewUserTopicEnum;
import com.itwray.iw.web.utils.IpUtils;
import com.itwray.iw.web.utils.SpringWebHolder;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.itwray.iw.common.constants.RequestHeaderConstants.TOKEN_HEADER;

/**
 * 用户 Dao
 *
 * @author wray
 * @since 2024/3/2
 */
@Component
public class AuthUserDao extends BaseDao<AuthUserMapper, AuthUserEntity> {

    public static final String SYSTEM_USERNAME_PREFIX = "u_";

    /**
     * 新增用户
     *
     * @param bo 用户新增对象
     * @return 用户实体
     */
    @Transactional
    public AuthUserEntity addNewUser(UserAddBo bo) {
        String phoneNumber = StringUtils.trimToNull(bo.getPhoneNumber());
        String emailAddress = StringUtils.trimToNull(bo.getEmailAddress());
        if (emailAddress != null) {
            emailAddress = emailAddress.toLowerCase(Locale.ROOT);
        }
        if (phoneNumber == null && emailAddress == null) {
            throw new IwWebException("手机号和邮箱不能同时为空");
        }
        if (phoneNumber != null && !NumberUtils.isValidPhoneNumber(phoneNumber)) {
            throw new BusinessException("电话号码格式错误");
        }
        if (emailAddress != null && !NumberUtils.isValidEmailAddress(emailAddress)) {
            throw new BusinessException("邮箱格式错误");
        }

        // 校验用户唯一性
        this.checkUserUnique(phoneNumber, null, emailAddress);

        // 自增ID生成前使用系统保留的临时用户名，事务提交前会更新为 u_<id>。
        String temporaryUsername = SYSTEM_USERNAME_PREFIX + "tmp_" + UUID.randomUUID().toString().replace("-", "");

        // 保存用户
        AuthUserEntity addUser = new AuthUserEntity();
        BeanUtils.copyProperties(bo, addUser);
        addUser.setPhoneNumber(phoneNumber);
        addUser.setEmailAddress(emailAddress);
        addUser.setUsername(temporaryUsername);
        addUser.setName("IW用户");
        addUser.setGender(UserGenderEnum.UNDISCLOSED);
        addUser.setPassword(StringUtils.isBlank(bo.getPassword()) ? null : BCrypt.hashpw(bo.getPassword()));
        try {
            this.save(addUser);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(phoneNumber != null ? "手机号已被其他账号绑定" : "邮箱已被其他账号绑定");
        }

        String systemUsername = SYSTEM_USERNAME_PREFIX + addUser.getId();
        String systemName = "用户" + addUser.getId();
        boolean usernameUpdated;
        try {
            usernameUpdated = this.lambdaUpdate()
                    .eq(AuthUserEntity::getId, addUser.getId())
                    .eq(AuthUserEntity::getUsername, temporaryUsername)
                    .set(AuthUserEntity::getUsername, systemUsername)
                    .set(AuthUserEntity::getName, systemName)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new BusinessException("系统用户名冲突，请联系管理员");
        }
        if (!usernameUpdated) {
            throw new IwWebException("系统用户名生成失败");
        }
        addUser.setUsername(systemUsername);
        addUser.setName(systemName);

        // 发送注册新用户成功的MQ消息
        bo.setUserId(addUser.getId());
        bo.setUsername(systemUsername);
        bo.setName(systemName);
        bo.setPhoneNumber(phoneNumber);
        bo.setEmailAddress(emailAddress);
        MQProducerHelper.send(RegisterNewUserTopicEnum.INIT, bo);

        // 标记当前用户为新注册的用户
        addUser.setNewUser(true);

        return addUser;
    }

    /**
     * 登录操作前的动作
     *
     * @param account 登录账号
     */
    public void loginBefore(String account) {
        String clientIp = IpUtils.getCurrentClientIp();

        // 判断当前客户端ip短时间内的登录失败次数是否超过上限
        Integer ipFailCount = RedisUtil.get(AuthRedisKeyEnum.LOGIN_FAIL_IP_KEY.getKey(clientIp), Integer.class);
        if (ipFailCount != null && ipFailCount >= 10) {
            throw new BusinessException("操作频繁，请稍后再试");
        }

        // 判断当前用户和客户端ip短时间内的登录失败次数是否超过上限
        Integer userIpFailCount = RedisUtil.get(AuthRedisKeyEnum.LOGIN_ACTION_USER_IP_KEY.getKey(account, clientIp), Integer.class);
        if (userIpFailCount != null && userIpFailCount >= 5) {
            throw new BusinessException("操作频繁，请稍后再试");
        }
    }

    /**
     * 登录成功之后的操作
     *
     * @param userId 用户id
     * @return 用户登录信息
     */
    public UserInfoVo loginSuccessAfter(Integer userId) {
        AuthUserEntity authUserEntity = this.queryById(userId);
        // 更新用户最后登录时间
        this.lambdaUpdate()
                .eq(AuthUserEntity::getId, authUserEntity.getId())
                .set(AuthUserEntity::getLastLoginTime, LocalDateTime.now())
                .update();

        // 生成Token并缓存
        String token = this.genericUserToken(authUserEntity.getId());

        // 将token写入到请求头中
        this.setTokenValue(token);

        // 构建响应对象
        UserInfoVo userInfoVo = this.buildUserInfoVo(authUserEntity);
        userInfoVo.setTokenName(TOKEN_HEADER);
        userInfoVo.setTokenValue(token);
        userInfoVo.setNewUser(authUserEntity.isNewUser());

        return userInfoVo;
    }

    /**
     * 构建包含账号安全状态的用户信息。
     */
    public UserInfoVo buildUserInfoVo(AuthUserEntity authUserEntity) {
        UserInfoVo userInfoVo = new UserInfoVo();
        BeanUtils.copyProperties(authUserEntity, userInfoVo);
        userInfoVo.setCanEditUsername(StringUtils.startsWithIgnoreCase(authUserEntity.getUsername(), SYSTEM_USERNAME_PREFIX));
        userInfoVo.setPhoneBound(StringUtils.isNotBlank(authUserEntity.getPhoneNumber()));
        userInfoVo.setEmailBound(StringUtils.isNotBlank(authUserEntity.getEmailAddress()));
        userInfoVo.setHasPassword(StringUtils.isNotBlank(authUserEntity.getPassword()));
        return userInfoVo;
    }

    /**
     * 生成用户token 并缓存到Redis
     */
    public String genericUserToken(Integer userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        AuthRedisKeyEnum.USER_TOKEN_KEY.setStringValue(userId, token);
        RedisUtil.sSet(AuthRedisKeyEnum.USER_TOKEN_SET_KEY.getKey(userId), token);
        AuthRedisKeyEnum.USER_TOKEN_SET_KEY.setExpire(userId);
        return token;
    }

    /**
     * 根据用户名查询唯一的用户
     *
     * @param username 用户名
     * @return 用户实体
     */
    public @Nullable AuthUserEntity queryOneByUsername(String username) {
        if (StringUtils.isBlank(username)) {
            throw new BusinessException("用户名不能为空");
        }
        return this.lambdaQuery()
                .eq(AuthUserEntity::getUsername, username)
                .last(WebCommonConstants.LIMIT_ONE)
                .one();
    }

    /**
     * 根据电话号码查询唯一的用户
     *
     * @param phoneNumber 电话号码
     * @return 用户实体
     */
    public @Nullable AuthUserEntity queryOneByPhoneNumber(String phoneNumber) {
        if (StringUtils.isBlank(phoneNumber)) {
            throw new BusinessException("电话号码不能为空");
        }
        return this.lambdaQuery()
                .eq(AuthUserEntity::getPhoneNumber, phoneNumber)
                .last(WebCommonConstants.LIMIT_ONE)
                .one();
    }

    /**
     * 根据邮箱地址查询唯一的用户
     *
     * @param emailAddress 邮箱地址
     * @return 用户实体
     */
    public @Nullable AuthUserEntity queryOneByEmailAddress(String emailAddress) {
        if (StringUtils.isBlank(emailAddress)) {
            throw new BusinessException("邮箱地址不能为空");
        }
        return this.lambdaQuery()
                .eq(AuthUserEntity::getEmailAddress, emailAddress)
                .last(WebCommonConstants.LIMIT_ONE)
                .one();
    }

    /**
     * 按用户名、邮箱、手机号顺序查询密码登录候选用户。
     */
    public List<AuthUserEntity> queryPasswordLoginCandidates(String account) {
        LinkedHashMap<Integer, AuthUserEntity> candidates = new LinkedHashMap<>();
        AuthUserEntity usernameUser = this.queryOneByUsername(account);
        if (usernameUser != null) {
            candidates.put(usernameUser.getId(), usernameUser);
        }

        AuthUserEntity emailUser = this.queryOneByEmailAddress(account.toLowerCase(Locale.ROOT));
        if (emailUser != null) {
            candidates.putIfAbsent(emailUser.getId(), emailUser);
        }

        AuthUserEntity phoneUser = this.queryOneByPhoneNumber(account);
        if (phoneUser != null) {
            candidates.putIfAbsent(phoneUser.getId(), phoneUser);
        }
        return List.copyOf(candidates.values());
    }

    /**
     * 根据登录方式查询唯一用户
     *
     * @param account  用户登录账号
     * @param loginWay 登录方式
     * @return 用户实体
     */
    public @Nullable AuthUserEntity queryOneByLoginWay(String account, UserLoginWayEnum loginWay) {
        AuthUserEntity authUserEntity;
        switch (loginWay) {
            case PHONE -> authUserEntity = this.queryOneByPhoneNumber(account);
            case EMAIL -> authUserEntity = this.queryOneByEmailAddress(account);
            default -> authUserEntity = null;
        }
        return authUserEntity;
    }

    /**
     * 根据用户名修改用户密码
     *
     * @param username        用户名
     * @param encodedPassword 加密过后的密码
     * @return 是否修改成功
     */
    public boolean updatePasswordByUsername(String username, String encodedPassword) {
        return this.lambdaUpdate()
                .eq(AuthUserEntity::getUsername, username)
                .set(AuthUserEntity::getPassword, encodedPassword)
                .update();
    }

    /**
     * 自增客户端锁次数
     *
     * @param clientIp 客户端ip
     */
    public void incrementClientIpLockCount(String clientIp) {
        // 不限制内部客户端ip
        if (WebCommonConstants.INNER_CLIENT_IP.equals(clientIp)) {
            return;
        }
        RedisUtil.incrementOne(AuthRedisKeyEnum.REGISTER_IP_KEY.getKey(clientIp));
        AuthRedisKeyEnum.REGISTER_IP_KEY.setExpire(clientIp);
    }

    /**
     * 校验用户是否唯一
     *
     * @param phoneNumber  唯一电话号码
     * @param username     唯一用户名
     * @param emailAddress 唯一邮箱地址
     */
    public void checkUserUnique(@Nullable String phoneNumber, @Nullable String username, @Nullable String emailAddress) {
        // 获取客户端ip
        String clientIp;
        if (SpringWebHolder.isWeb()) {
            clientIp = IpUtils.getClientIp(SpringWebHolder.getRequest());
        } else {
            // 非web请求，则为内部客户端ip
            clientIp = WebCommonConstants.INNER_CLIENT_IP;
        }

        // 校验电话号码是否已注册
        if (StringUtils.isNotBlank(phoneNumber)) {
            AuthUserEntity authUserEntity = this.queryOneByPhoneNumber(phoneNumber);
            if (authUserEntity != null) {
                // 为防止用户恶意猜测电话号码，增加注册次数限制
                this.incrementClientIpLockCount(clientIp);
                throw new BusinessException("用户已存在");
            }
        }

        // 校验用户名是否已注册
        if (StringUtils.isNotBlank(username)) {
            AuthUserEntity authUserEntity = this.queryOneByUsername(username);
            if (authUserEntity != null) {
                // 为防止用户恶意猜测用户名，增加注册次数限制
                this.incrementClientIpLockCount(clientIp);
                throw new BusinessException("用户已存在");
            }
        }

        // 校验邮箱地址是否已注册
        if (StringUtils.isNotBlank(emailAddress)) {
            AuthUserEntity authUserEntity = this.queryOneByEmailAddress(emailAddress);
            if (authUserEntity != null) {
                // 为防止用户恶意猜测用户名，增加注册次数限制
                this.incrementClientIpLockCount(clientIp);
                throw new BusinessException("用户已存在");
            }
        }
    }

    /**
     * 存放token至请求头中
     *
     * @param tokenValue token值
     */
    private void setTokenValue(String tokenValue) {
        HttpServletResponse response = SpringWebHolder.getResponse();
        response.setHeader(TOKEN_HEADER, tokenValue);
        // 此处必须在响应头里指定 Access-Control-Expose-Headers: token-name，否则前端无法读取到这个响应头
        response.addHeader("Access-Control-Expose-Headers", TOKEN_HEADER);
    }
}

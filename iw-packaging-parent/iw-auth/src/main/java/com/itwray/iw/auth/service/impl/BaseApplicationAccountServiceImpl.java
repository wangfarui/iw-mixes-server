package com.itwray.iw.auth.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.itwray.iw.auth.dao.AuthUserDao;
import com.itwray.iw.auth.dao.BaseApplicationAccountDao;
import com.itwray.iw.auth.mapper.BaseApplicationAccountMapper;
import com.itwray.iw.auth.model.AuthRedisKeyEnum;
import com.itwray.iw.auth.model.dto.ApplicationAccountAddDto;
import com.itwray.iw.auth.model.dto.ApplicationAccountPageDto;
import com.itwray.iw.auth.model.dto.ApplicationAccountRefreshPasswordDto;
import com.itwray.iw.auth.model.dto.ApplicationAccountUpdateDto;
import com.itwray.iw.auth.model.entity.AuthUserEntity;
import com.itwray.iw.auth.model.entity.BaseApplicationAccountEntity;
import com.itwray.iw.auth.model.vo.ApplicationAccountDetailVo;
import com.itwray.iw.auth.model.vo.ApplicationAccountPageVo;
import com.itwray.iw.auth.service.AuthVerificationService;
import com.itwray.iw.auth.service.BaseApplicationAccountService;
import com.itwray.iw.common.constants.CommonConstants;
import com.itwray.iw.common.utils.AESUtils;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.vo.PageVo;
import com.itwray.iw.web.service.impl.WebServiceImpl;
import com.itwray.iw.web.utils.EnvironmentHolder;
import com.itwray.iw.web.utils.UserUtils;
import jakarta.annotation.Nonnull;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.Set;

/**
 * 应用账号信息表 服务实现类
 *
 * @author wray
 * @since 2025-03-06
 */
@Service
@Slf4j
public class BaseApplicationAccountServiceImpl extends WebServiceImpl<BaseApplicationAccountDao, BaseApplicationAccountMapper, BaseApplicationAccountEntity,
        ApplicationAccountAddDto, ApplicationAccountUpdateDto, ApplicationAccountDetailVo, Integer> implements BaseApplicationAccountService, ApplicationListener<EnvironmentChangeEvent> {

    @Value("${iw.auth.application-account.aes-key}")
    private String aesKey;

    @Nonnull
    private SecretKey secretKey;

    private final AuthUserDao authUserDao;

    @Autowired
    private AccountCategoryService categories;

    private AuthVerificationService authVerificationService;

    @Autowired
    public BaseApplicationAccountServiceImpl(BaseApplicationAccountDao baseDao, AuthUserDao authUserDao) {
        super(baseDao);
        this.authUserDao = authUserDao;
    }

    @Autowired
    public void setAuthVerificationService(AuthVerificationService authVerificationService) {
        this.authVerificationService = authVerificationService;
    }

    @PostConstruct
    public void init() {
        secretKey = AESUtils.generateSecretKey(this.aesKey);
    }

    @Override
    @Transactional
    public Integer add(ApplicationAccountAddDto dto) {
        int user=UserUtils.getUserId();
        categories.lockOwner(user);
        validateFields(dto);
        if(dto.getType()==null) dto.setType(0);
        categories.validateSelection(user,dto.getType(),null);
        String encryptHex = this.encrypt(dto.getPassword());
        dto.setPassword(encryptHex);
        return super.add(dto);
    }

    @Override
    @Transactional
    public void update(ApplicationAccountUpdateDto dto) {
        int user=UserUtils.getUserId();
        categories.lockOwner(user);
        BaseApplicationAccountEntity old=owned(dto.getId());
        validateFields(dto);
        if(dto.getType()==null) dto.setType(0);
        categories.validateSelection(user,dto.getType(),old.getType());
        if (Boolean.TRUE.equals(dto.getUpdatePassword())) {
            String encryptHex = this.encrypt(dto.getPassword());
            dto.setPassword(encryptHex);
        } else {
            dto.setPassword(null);
        }
        super.update(dto);

    }

    @Override
    public PageVo<ApplicationAccountPageVo> page(ApplicationAccountPageDto dto) {
        LambdaQueryWrapper<BaseApplicationAccountEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(BaseApplicationAccountEntity::getUserId,UserUtils.getUserId())
                .eq(dto.getType()!=null,BaseApplicationAccountEntity::getType,dto.getType())
                .like(StringUtils.isNotBlank(dto.getName()),BaseApplicationAccountEntity::getName,StringUtils.trim(dto.getName()))
                .like(StringUtils.isNotBlank(dto.getAddress()),BaseApplicationAccountEntity::getAddress,StringUtils.trim(dto.getAddress()));
        if(Boolean.TRUE.equals(dto.getUnclassified())) queryWrapper.and(q -> q.isNull(BaseApplicationAccountEntity::getType).or().eq(BaseApplicationAccountEntity::getType,0));
        if(StringUtils.isNotBlank(dto.getKeyword())) {
            String keyword=dto.getKeyword().trim();
            queryWrapper.and(q -> q.like(BaseApplicationAccountEntity::getName,keyword).or().like(BaseApplicationAccountEntity::getAddress,keyword));
        }
        queryWrapper.orderByDesc(BaseApplicationAccountEntity::getId);
        PageVo<ApplicationAccountPageVo> page=getBaseDao().page(dto,queryWrapper,ApplicationAccountPageVo.class);
        java.util.Map<Integer,String> names=categoryNames();
        page.getRecords().forEach(row -> row.setTypeName(typeName(names,row.getType())));
        return page;
    }

    @Override
    public ApplicationAccountDetailVo detail(Integer id) {
        ApplicationAccountDetailVo vo=cn.hutool.core.bean.BeanUtil.copyProperties(owned(id),ApplicationAccountDetailVo.class);
        vo.setTypeName(typeName(categoryNames(),vo.getType()));
        return vo;
    }

    @Override
    @Transactional
    public void delete(Integer id) {
        categories.lockOwner(UserUtils.getUserId()); owned(id); super.delete(id);
    }

    private BaseApplicationAccountEntity owned(Integer id) {
        BaseApplicationAccountEntity row=getBaseDao().lambdaQuery().eq(BaseApplicationAccountEntity::getId,id)
            .eq(BaseApplicationAccountEntity::getUserId,UserUtils.getUserId()).one();
        if(row==null) throw new BusinessException("账号不存在或无权访问");
        return row;
    }

    private java.util.Map<Integer,String> categoryNames() {
        java.util.Map<Integer,String> names=new java.util.HashMap<>();
        categories.items(UserUtils.getUserId()).items().forEach(c -> names.put(c.code(),c.name()));
        return names;
    }
    private String typeName(java.util.Map<Integer,String> names,Integer type) {
        return type==null||type==0?"未分类":names.getOrDefault(type,"分类已失效");
    }
    private void validateFields(ApplicationAccountAddDto dto) {
        dto.setName(StringUtils.trimToEmpty(dto.getName()));
        dto.setAddress(StringUtils.trimToEmpty(dto.getAddress()));
        dto.setAccount(StringUtils.trimToEmpty(dto.getAccount()));
        if(StringUtils.isAllBlank(dto.getName(),dto.getAddress(),dto.getAccount()))
            throw new BusinessException("应用名称、应用地址、账号至少填写一项");
    }

    @Override
    public String viewPassword(Integer id) {
        BaseApplicationAccountEntity accountEntity = owned(id);
        if (StringUtils.isBlank(accountEntity.getPassword())) {
            return CommonConstants.EMPTY;
        }
        try {
            return this.decrypt(accountEntity.getPassword());
        } catch (Exception e) {
            log.error("viewPassword解密异常, accountId: {}", id);
            throw new BusinessException("密码读取失败，请稍后重试");
        }
    }

    @Override
    @Transactional
    public void refreshPassword(ApplicationAccountRefreshPasswordDto dto) {
        // 校验验证码的正确性
        Integer userId = UserUtils.getUserId();
        AuthUserEntity authUserEntity = authUserDao.getById(userId);
        if (authUserEntity == null) {
            throw new BusinessException("用户不存在，请刷新重试");
        }
        if (!authVerificationService.compareVerificationCode(
                dto.getVerificationCode(),
                authUserEntity.getPhoneNumber(),
                AuthRedisKeyEnum.APPLICATION_ACCOUNT_REFRESH_KEY)) {
            throw new BusinessException("验证码错误");
        }

        List<BaseApplicationAccountEntity> updateEntityList;
        if (dto.isRefreshAll()) {
            updateEntityList = getBaseDao().lambdaQuery()
                    .eq(BaseApplicationAccountEntity::getUserId, UserUtils.getUserId())
                    .select(BaseApplicationAccountEntity::getId, BaseApplicationAccountEntity::getPassword)
                    .list();
        } else if (CollUtil.isNotEmpty(dto.getIdList())) {
            updateEntityList = getBaseDao().lambdaQuery()
                    .in(BaseApplicationAccountEntity::getId, dto.getIdList())
                    .eq(BaseApplicationAccountEntity::getUserId, UserUtils.getUserId())
                    .select(BaseApplicationAccountEntity::getId, BaseApplicationAccountEntity::getPassword)
                    .list();
        } else {
            log.warn("refreshPassword入参异常, dto: {}", dto);
            return;
        }

        if (CollUtil.isEmpty(updateEntityList)) {
            return;
        }

        // 如果存在历史AES,则先解密
        if (StringUtils.isNotBlank(dto.getOldAes())) {
            SecretKey key = AESUtils.generateSecretKey(dto.getOldAes());
            for (BaseApplicationAccountEntity entity : updateEntityList) {
                entity.setPassword(AESUtils.decryptAESGCM(key, entity.getPassword()));
            }
        }

        // 使用动态配置的aes 再进行加密
        for (BaseApplicationAccountEntity entity : updateEntityList) {
            if (StringUtils.isBlank(entity.getPassword())) {
                log.info("refreshPassword 密码为空, 无需加密, id: {}", entity.getId());
                continue;
            }
            if (this.isEncryptPassword(entity.getPassword())) {
                log.warn("refreshPassword 不建议二次加密密码, id: {}", entity.getId());
                continue;
            }
            entity.setPassword(this.encrypt(entity.getPassword()));
        }

        // 更新所有实体对象
        getBaseDao().updateBatchById(updateEntityList);
    }

    private String encrypt(String originalPassword) {
        if (StringUtils.isBlank(originalPassword)) {
            return CommonConstants.EMPTY;
        }
        return AESUtils.encryptAESGCM(this.secretKey, originalPassword);
    }

    private String decrypt(String encryptPassword) {
        return AESUtils.decryptAESGCM(this.secretKey, encryptPassword);
    }

    private boolean isEncryptPassword(String encryptPassword) {
        return AESUtils.isAesGcmEncrypted(this.secretKey, encryptPassword);
    }

    @Override
    public void onApplicationEvent(EnvironmentChangeEvent event) {
        Set<String> keys = event.getKeys();
        if (keys.contains("iw.auth.application-account.aes-key")) {
            log.info("配置已变更: iw.auth.application-account.aes-key");
            this.aesKey = EnvironmentHolder.getProperty("iw.auth.application-account.aes-key");
            this.init();
        }
    }
}

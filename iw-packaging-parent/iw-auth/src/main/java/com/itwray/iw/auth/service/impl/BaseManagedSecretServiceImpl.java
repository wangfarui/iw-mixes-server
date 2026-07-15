package com.itwray.iw.auth.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.itwray.iw.auth.dao.BaseManagedSecretDao;
import com.itwray.iw.auth.mapper.BaseManagedSecretMapper;
import com.itwray.iw.auth.model.dto.ManagedSecretAddDto;
import com.itwray.iw.auth.model.dto.ManagedSecretFieldDto;
import com.itwray.iw.auth.model.dto.ManagedSecretPageDto;
import com.itwray.iw.auth.model.dto.ManagedSecretRevealDto;
import com.itwray.iw.auth.model.dto.ManagedSecretUpdateDto;
import com.itwray.iw.auth.model.entity.BaseManagedSecretEntity;
import com.itwray.iw.auth.model.vo.ManagedSecretDetailVo;
import com.itwray.iw.auth.model.vo.ManagedSecretFieldVo;
import com.itwray.iw.auth.model.vo.ManagedSecretPageVo;
import com.itwray.iw.auth.model.vo.ManagedSecretRevealVo;
import com.itwray.iw.auth.service.BaseManagedSecretService;
import com.itwray.iw.common.constants.CommonConstants;
import com.itwray.iw.common.utils.AESUtils;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.vo.PageVo;
import com.itwray.iw.web.service.impl.WebServiceImpl;
import jakarta.annotation.Nonnull;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BaseManagedSecretServiceImpl extends WebServiceImpl<BaseManagedSecretDao, BaseManagedSecretMapper, BaseManagedSecretEntity,
        ManagedSecretAddDto, ManagedSecretUpdateDto, ManagedSecretDetailVo, Integer> implements BaseManagedSecretService {

    private static final int ENCRYPTION_VERSION = 1;
    private static final int EXPIRING_DAYS = 30;

    @Value("${iw.auth.managed-secret.aes-key}")
    private String aesKey;

    @Nonnull
    private SecretKey secretKey;

    public BaseManagedSecretServiceImpl(BaseManagedSecretDao baseDao) {
        super(baseDao);
    }

    @PostConstruct
    public void init() {
        secretKey = AESUtils.generateSecretKey(aesKey);
    }

    @Override
    @Transactional
    public Integer add(ManagedSecretAddDto dto) {
        Map<String, String> values = validateAndCollectValues(dto.getFields(), true, Map.of());
        BaseManagedSecretEntity entity = BeanUtil.copyProperties(dto, BaseManagedSecretEntity.class);
        entity.setFieldSchema(JSONUtil.toJsonStr(toFieldVoList(dto.getFields(), values)));
        entity.setSecretCiphertext(encryptValues(values));
        entity.setEncryptionVersion(ENCRYPTION_VERSION);
        getBaseDao().save(entity);
        return entity.getId();
    }

    @Override
    @Transactional
    public void update(ManagedSecretUpdateDto dto) {
        BaseManagedSecretEntity entity = getBaseDao().queryById(dto.getId());
        Map<String, String> existingValues = decryptValues(entity.getSecretCiphertext());
        Map<String, String> values = validateAndCollectValues(dto.getFields(), false, existingValues);

        BaseManagedSecretEntity updateEntity = BeanUtil.copyProperties(dto, BaseManagedSecretEntity.class);
        updateEntity.setFieldSchema(JSONUtil.toJsonStr(toFieldVoList(dto.getFields(), values)));
        updateEntity.setSecretCiphertext(encryptValues(values));
        updateEntity.setEncryptionVersion(ENCRYPTION_VERSION);
        getBaseDao().updateById(updateEntity);
    }

    @Override
    public ManagedSecretDetailVo detail(Integer id) {
        BaseManagedSecretEntity entity = getBaseDao().queryById(id);
        ManagedSecretDetailVo detail = BeanUtil.copyProperties(entity, ManagedSecretDetailVo.class);
        detail.setFields(parseFieldSchema(entity.getFieldSchema()));
        return detail;
    }

    @Override
    public PageVo<ManagedSecretPageVo> page(ManagedSecretPageDto dto) {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<BaseManagedSecretEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(StringUtils.isNotBlank(dto.getKeyword()), query -> query
                        .like(BaseManagedSecretEntity::getName, dto.getKeyword())
                        .or()
                        .like(BaseManagedSecretEntity::getServiceName, dto.getKeyword())
                        .or()
                        .like(BaseManagedSecretEntity::getTags, dto.getKeyword()))
                .eq(StringUtils.isNotBlank(dto.getSecretType()), BaseManagedSecretEntity::getSecretType, dto.getSecretType())
                .eq(StringUtils.isNotBlank(dto.getEnvironment()), BaseManagedSecretEntity::getEnvironment, dto.getEnvironment());
        applyExpiryStatus(wrapper, dto.getExpiryStatus(), now);
        wrapper.orderByDesc(BaseManagedSecretEntity::getUpdateTime);
        return getBaseDao().page(dto, wrapper, entity -> {
            ManagedSecretPageVo result = BeanUtil.copyProperties(entity, ManagedSecretPageVo.class);
            result.setFieldSummary(parseFieldSchema(entity.getFieldSchema()).stream()
                    .map(ManagedSecretFieldVo::getLabel)
                    .collect(Collectors.joining(" + ")));
            return result;
        });
    }

    @Override
    @Transactional
    public ManagedSecretRevealVo reveal(ManagedSecretRevealDto dto) {
        BaseManagedSecretEntity entity = getBaseDao().queryById(dto.getId());
        if (parseFieldSchema(entity.getFieldSchema()).stream().noneMatch(field -> dto.getFieldCode().equals(field.getCode()))) {
            throw new BusinessException("密钥字段不存在，请刷新重试");
        }
        String value = decryptValues(entity.getSecretCiphertext()).get(dto.getFieldCode());
        if (value == null) {
            throw new BusinessException("密钥字段未保存值");
        }
        getBaseDao().lambdaUpdate()
                .eq(BaseManagedSecretEntity::getId, entity.getId())
                .set(BaseManagedSecretEntity::getLastAccessTime, LocalDateTime.now())
                .update();
        return new ManagedSecretRevealVo(value);
    }

    private void applyExpiryStatus(LambdaQueryWrapper<BaseManagedSecretEntity> wrapper, Integer expiryStatus, LocalDateTime now) {
        if (expiryStatus == null) {
            return;
        }
        if (expiryStatus == 1) {
            wrapper.and(query -> query.isNull(BaseManagedSecretEntity::getExpireTime)
                    .or()
                    .gt(BaseManagedSecretEntity::getExpireTime, now.plusDays(EXPIRING_DAYS)));
        } else if (expiryStatus == 2) {
            wrapper.gt(BaseManagedSecretEntity::getExpireTime, now)
                    .le(BaseManagedSecretEntity::getExpireTime, now.plusDays(EXPIRING_DAYS));
        } else if (expiryStatus == 3) {
            wrapper.le(BaseManagedSecretEntity::getExpireTime, now);
        } else {
            throw new BusinessException("有效状态参数不正确");
        }
    }

    private Map<String, String> validateAndCollectValues(List<ManagedSecretFieldDto> fields, boolean isAdd,
                                                          Map<String, String> existingValues) {
        Set<String> codes = new HashSet<>();
        Map<String, String> values = new LinkedHashMap<>();
        for (ManagedSecretFieldDto field : fields) {
            if (!codes.add(field.getCode())) {
                throw new BusinessException("密钥字段标识不能重复");
            }
            String value = field.getValue();
            if (value != null) {
                if (StringUtils.isBlank(value)) {
                    throw new BusinessException("密钥字段值不能为空");
                }
                values.put(field.getCode(), value);
            } else if (!isAdd && existingValues.containsKey(field.getCode())) {
                values.put(field.getCode(), existingValues.get(field.getCode()));
            } else {
                throw new BusinessException("请填写新增密钥字段的值");
            }
        }
        return values;
    }

    private List<ManagedSecretFieldVo> toFieldVoList(List<ManagedSecretFieldDto> fields, Map<String, String> values) {
        List<ManagedSecretFieldVo> result = new ArrayList<>();
        for (ManagedSecretFieldDto field : fields) {
            ManagedSecretFieldVo fieldVo = new ManagedSecretFieldVo();
            fieldVo.setCode(field.getCode());
            fieldVo.setLabel(field.getLabel());
            fieldVo.setInputType(StringUtils.defaultIfBlank(field.getInputType(), "TEXT"));
            fieldVo.setHasValue(values.containsKey(field.getCode()));
            result.add(fieldVo);
        }
        return result;
    }

    private List<ManagedSecretFieldVo> parseFieldSchema(String fieldSchema) {
        if (StringUtils.isBlank(fieldSchema)) {
            return List.of();
        }
        return JSONUtil.parseArray(fieldSchema).toList(ManagedSecretFieldVo.class);
    }

    private String encryptValues(Map<String, String> values) {
        return AESUtils.encryptAESGCM(secretKey, JSONUtil.toJsonStr(values));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> decryptValues(String ciphertext) {
        if (StringUtils.isBlank(ciphertext)) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> rawValues = JSONUtil.parseObj(AESUtils.decryptAESGCM(secretKey, ciphertext)).toBean(Map.class);
            Map<String, String> values = new HashMap<>();
            rawValues.forEach((key, value) -> values.put(key, value == null ? CommonConstants.EMPTY : String.valueOf(value)));
            return values;
        } catch (Exception exception) {
            throw new BusinessException("密钥数据无法解密，请联系管理员处理");
        }
    }
}

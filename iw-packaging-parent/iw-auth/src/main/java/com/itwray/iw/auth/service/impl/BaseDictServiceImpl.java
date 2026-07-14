package com.itwray.iw.auth.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.itwray.iw.auth.dao.AuthUserDao;
import com.itwray.iw.auth.model.AuthRedisKeyEnum;
import com.itwray.iw.auth.model.bo.UserAddBo;
import com.itwray.iw.auth.model.dto.DictAddDto;
import com.itwray.iw.auth.model.dto.DictPageDto;
import com.itwray.iw.auth.model.dto.DictUpdateDto;
import com.itwray.iw.auth.model.entity.AuthUserEntity;
import com.itwray.iw.auth.model.vo.*;
import com.itwray.iw.auth.service.BaseDictService;
import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.common.constants.BoolEnum;
import com.itwray.iw.common.constants.EnableEnum;
import com.itwray.iw.common.utils.ConstantEnumUtil;
import com.itwray.iw.common.utils.NumberUtils;
import com.itwray.iw.starter.redis.RedisUtil;
import com.itwray.iw.starter.redis.lock.RedisLockUtil;
import com.itwray.iw.starter.rocketmq.MQProducerHelper;
import com.itwray.iw.starter.rocketmq.config.RocketMQClientListener;
import com.itwray.iw.web.constants.WebCommonConstants;
import com.itwray.iw.web.dao.BaseDictDao;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.mapper.BaseDictMapper;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import com.itwray.iw.web.model.enums.DictTypeEnum;
import com.itwray.iw.web.model.enums.RoleTypeEnum;
import com.itwray.iw.web.model.enums.mq.RegisterNewUserTopicEnum;
import com.itwray.iw.web.model.vo.PageVo;
import com.itwray.iw.web.service.impl.WebServiceImpl;
import com.itwray.iw.web.utils.UserUtils;
import lombok.extern.slf4j.Slf4j;
import com.itwray.iw.starter.rocketmq.config.LocalMessageListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 字典服务实现层
 *
 * @author wray
 * @since 2024/5/26
 */
@Service
@Slf4j
@LocalMessageListener(consumerGroup = "auth-dict-service", topic = RegisterNewUserTopicEnum.TOPIC, tag = "init")
public class BaseDictServiceImpl extends WebServiceImpl<BaseDictDao, BaseDictMapper, BaseDictEntity,
        DictAddDto, DictUpdateDto, DictDetailVo, Integer> implements BaseDictService, RocketMQClientListener<UserAddBo> {

    private AuthUserDao authUserDao;

    /**
     * 操作管理员字典 分布式锁Key
     * <p>全局锁</p>
     */
    private static final String OPERATE_ADMIN_DICT_LOCK_KEY = "OperateAdminDict";

    @Autowired
    public BaseDictServiceImpl(BaseDictDao baseDao) {
        super(baseDao);
    }

    @Autowired
    public void setAuthUserDao(AuthUserDao authUserDao) {
        this.authUserDao = authUserDao;
    }

    @Override
    public List<DictTypeVo> getDictTypeList() {
        boolean adminUser = this.isAdminUser(UserUtils.getUserId());
        return Arrays.stream(DictTypeEnum.values())
                // 如果是管理员用户, 则默认所有字典为true. 如果不是管理员用户, 则只返回非管理员字典
                .filter(t -> !t.isAdminDict() || adminUser)
                .map(t -> new DictTypeVo(t.getCode(), t.getName()))
                .collect(Collectors.toList());
    }

    @Override
    public List<DictListVo> getDictList(Integer dictType) {
        return getBaseDao().lambdaQuery()
                .eq(BaseDictEntity::getDictType, dictType)
                .eq(BaseDictEntity::getDictStatus, EnableEnum.ENABLE.getCode())
                .orderByAsc(BaseDictEntity::getSort)
                .list()
                .stream().map(t -> BeanUtil.copyProperties(t, DictListVo.class))
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, List<DictAllListVo>> getAllDictList(Boolean latest) {
        // 默认不查询最新实时字典数据
        if (latest == null || !latest) {
            Map<Object, Object> dictMapCache = RedisUtil.getHashEntries(this.obtainDictRedisKeyByUser());
            if (!dictMapCache.isEmpty()) {
                return (Map) dictMapCache;
            }
        }

        Map<String, List<DictAllListVo>> dictMap = getBaseDao().lambdaQuery()
                .eq(BaseDictEntity::getDictStatus, EnableEnum.ENABLE.getCode())
                .list()
                .stream()
                .collect(Collectors.groupingBy(t -> t.getDictType().toString(),
                        // 对每个分组先排序，再进行转换
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                (List<BaseDictEntity> list) -> {
                                    // 对每个分组的 List<BaseDictEntity> 按 sort 字段排序
                                    list.sort(Comparator.comparing(BaseDictEntity::getSort));

                                            // 将排序后的 BaseDictEntity 转换为 DictAllListVo
                                            return list.stream()
                                                    .map(baseDictEntity -> new DictAllListVo(
                                                            baseDictEntity.getId(),
                                                            baseDictEntity.getParentId(),
                                                            baseDictEntity.getDictCode(),
                                                            baseDictEntity.getDictName())
                                                    )
                                                    .collect(Collectors.toList());
                                }
                        )));

        RedisUtil.add(this.obtainDictRedisKeyByUser(), dictMap);
        RedisUtil.expire(this.obtainDictRedisKeyByUser(), AuthRedisKeyEnum.DICT_KEY.getExpireTime());

        return dictMap;
    }

    @Override
    @Transactional
    public Integer add(DictAddDto dto) {
        boolean isAdminDict = this.verifyUserPermission(dto.getDictType(), dto.getIsSyncAll());
        this.checkAndFillSaveParam(dto);

        // 如果新增时没有指定sort值
        if (NumberUtils.isNullOrZero(dto.getSort())) {
            // 根据字典类型查询当前最大sort值
            dto.setSort(getBaseDao().queryNextSortValue(dto.getDictType()));
        }

        Integer userId = UserUtils.getUserId();
        Integer id = super.add(dto);
        if (isAdminDict) {
            this.saveAdminDictTemplateIfAbsent(dto);
        }
        // 更新Redis缓存
        List<DictAllListVo> dictAllListVos = queryAllDictByType(dto.getDictType());
        RedisUtil.putHashKey(this.obtainDictRedisKeyByUser(), dto.getDictType(), dictAllListVos);
        RedisUtil.expire(this.obtainDictRedisKeyByUser(), AuthRedisKeyEnum.DICT_KEY.getExpireTime());
        AuthRedisKeyEnum.USER_DICT_VERSION.setStringValue(System.currentTimeMillis(), userId);

        // 如果是管理员字典项, 则需要同步给所有用户
        if (isAdminDict) {
            RedisLockUtil.lock(OPERATE_ADMIN_DICT_LOCK_KEY);
            try {
                List<AuthUserEntity> userEntityList = authUserDao.getBaseMapper().queryAllUser();
                BaseDictEntity dictEntity = BeanUtil.copyProperties(dto, BaseDictEntity.class);
                for (AuthUserEntity userEntity : userEntityList) {
                    // 跳过当前用户
                    if (Objects.equals(userId, userEntity.getId())) {
                        continue;
                    }
                    dictEntity.setId(null);
                    dictEntity.setParentId(id);
                    dictEntity.setUserId(userEntity.getId());
                    getBaseDao().save(dictEntity);
                    // 删除其Redis缓存
                    RedisUtil.delete(this.obtainDictRedisKeyByUser(userEntity.getId()));
                    AuthRedisKeyEnum.USER_DICT_VERSION.setStringValue(System.currentTimeMillis(), userEntity.getId());
                }
                // 同步所有用户字典后, 返回的字典id默认为0
                return 0;
            } finally {
                RedisLockUtil.unlock(OPERATE_ADMIN_DICT_LOCK_KEY);
            }
        }

        return id;
    }

    @Override
    @Transactional
    public void update(DictUpdateDto dto) {
        boolean isAdminDict = this.verifyUserPermission(dto.getDictType(), dto.getIsSyncAll());
        this.checkAndFillSaveParam(dto);

        // 根据id查询字典类型
        BaseDictEntity baseDictEntity = this.checkDataSecurity(dto.getId(), dto.getDictStatus());

        super.update(dto);
        // 更新Redis缓存
        List<DictAllListVo> dictAllListVos = queryAllDictByType(baseDictEntity.getDictType());
        RedisUtil.putHashKey(this.obtainDictRedisKeyByUser(), baseDictEntity.getDictType(), dictAllListVos);
        AuthRedisKeyEnum.USER_DICT_VERSION.setStringValue(System.currentTimeMillis(), UserUtils.getUserId());

        // 如果是管理员字典项, 则需要同步给所有用户
        if (isAdminDict) {
            RedisLockUtil.lock(OPERATE_ADMIN_DICT_LOCK_KEY);
            try {
                BaseDictEntity updateEntity = new BaseDictEntity();
                updateEntity.setDictCode(dto.getDictCode());
                updateEntity.setDictName(dto.getDictName());
                updateEntity.setDictStatus(dto.getDictStatus());
                updateEntity.setSort(dto.getSort());
                DictTypeEnum dictTypeEnum = DictTypeEnum.getDictByCode(dto.getDictType());
                // 管理员字典类型通过code+名称更新
                if (dictTypeEnum != null && dictTypeEnum.isAdminDict()) {
                    getBaseDao().getBaseMapper().updateAllDictByDictName(baseDictEntity.getDictType(), baseDictEntity.getDictName(), updateEntity);
                } else {
                    // 用户字典类型通过parentId更新
                    getBaseDao().getBaseMapper().updateAllDictByParentId(baseDictEntity.getDictType(), baseDictEntity.getId(), updateEntity);
                }

                // 查询所有用户
                List<AuthUserEntity> userEntityList = authUserDao.getBaseMapper().queryAllUser();
                for (AuthUserEntity userEntity : userEntityList) {
                    // 删除其Redis缓存
                    RedisUtil.delete(this.obtainDictRedisKeyByUser(userEntity.getId()));
                    AuthRedisKeyEnum.USER_DICT_VERSION.setStringValue(System.currentTimeMillis(), userEntity.getId());
                }
            } finally {
                RedisLockUtil.unlock(OPERATE_ADMIN_DICT_LOCK_KEY);
            }
        }
    }

    @Override
    @Transactional
    public void delete(Integer id) {
        BaseDictEntity dictEntity = getBaseDao().queryById(id);
        boolean isAdminDict = this.verifyUserPermission(dictEntity.getDictType(), null);

        // 根据id查询字典类型
        this.checkDataSecurity(id, null);

        // 如果是管理员字典项, 则需要同步给所有用户
        if (isAdminDict) {
            BaseDictEntity updateEntity = new BaseDictEntity();
            updateEntity.setDeleted(Boolean.TRUE);
            RedisLockUtil.lock(OPERATE_ADMIN_DICT_LOCK_KEY);
            try {
                getBaseDao().getBaseMapper().updateAllDictByDictName(dictEntity.getDictType(), dictEntity.getDictName(), updateEntity);
                // 查询所有用户
                List<AuthUserEntity> userEntityList = authUserDao.getBaseMapper().queryAllUser();
                for (AuthUserEntity userEntity : userEntityList) {
                    // 删除其Redis缓存
                    RedisUtil.delete(this.obtainDictRedisKeyByUser(userEntity.getId()));
                    AuthRedisKeyEnum.USER_DICT_VERSION.setStringValue(System.currentTimeMillis(), userEntity.getId());
                }
            } finally {
                RedisLockUtil.unlock(OPERATE_ADMIN_DICT_LOCK_KEY);
            }
        } else {
            super.delete(id);
            // 更新Redis缓存
            List<DictAllListVo> dictAllListVos = queryAllDictByType(dictEntity.getDictType());
            RedisUtil.putHashKey(this.obtainDictRedisKeyByUser(), dictEntity.getDictType(), dictAllListVos);
            AuthRedisKeyEnum.USER_DICT_VERSION.setStringValue(System.currentTimeMillis(), UserUtils.getUserId());
        }
    }

    @Override
    public PageVo<DictPageVo> page(DictPageDto dto) {
        LambdaQueryWrapper<BaseDictEntity> queryWrapper = new LambdaQueryWrapper<>();
        boolean adminUser = this.isAdminUser(UserUtils.getUserId());
        queryWrapper.eq(dto.getDictType() != null, BaseDictEntity::getDictType, dto.getDictType())
                .eq(dto.getDictCode() != null, BaseDictEntity::getDictCode, dto.getDictCode())
                .eq(dto.getDictStatus() != null, BaseDictEntity::getDictStatus, dto.getDictStatus())
                .in(!adminUser, BaseDictEntity::getDictType, DictTypeEnum.getUserDict().stream().map(DictTypeEnum::getCode).toList())
                .like(dto.getDictName() != null, BaseDictEntity::getDictName, dto.getDictName());
        if (dto.getDictType() != null) {
            queryWrapper.orderByAsc(BaseDictEntity::getSort);
        } else {
            queryWrapper.orderByDesc(BaseDictEntity::getId);
        }
        return getBaseDao().page(dto, queryWrapper, DictPageVo.class);
    }

    @Override
    public GeneralResponse<Long> getDictVersion() {
        Long version = AuthRedisKeyEnum.USER_DICT_VERSION.getStringValue(Long.class, UserUtils.getUserId());
        if (version == null) {
            long currentVersion = System.currentTimeMillis();
            AuthRedisKeyEnum.USER_DICT_VERSION.setStringValue(currentVersion, UserUtils.getUserId());
            return GeneralResponse.success(currentVersion);
        }
        return GeneralResponse.success(version);
    }

    @Override
    @Transactional
    public Integer repairUserVisibleDictData() {
        if (!this.isSuperAdminUser(UserUtils.getUserId())) {
            throw new BusinessException("权限不足");
        }

        RedisLockUtil.lock(OPERATE_ADMIN_DICT_LOCK_KEY);
        try {
            int insertCount = this.repairAdminDictTemplateData();
            List<AuthUserEntity> userEntityList = authUserDao.getBaseMapper().queryAllUser();
            for (AuthUserEntity userEntity : userEntityList) {
                int userInsertCount = this.copyMissingVisibleDictData(userEntity.getId());
                insertCount += userInsertCount;
                if (userInsertCount > 0) {
                    this.refreshUserDictCacheVersion(userEntity.getId());
                }
            }
            return insertCount;
        } finally {
            RedisLockUtil.unlock(OPERATE_ADMIN_DICT_LOCK_KEY);
        }
    }

    private List<DictAllListVo> queryAllDictByType(Integer dictType) {
        return getBaseDao().lambdaQuery()
                .eq(BaseDictEntity::getDictType, dictType)
                .eq(BaseDictEntity::getDictStatus, EnableEnum.ENABLE.getCode())
                .orderByAsc(BaseDictEntity::getSort)
                .list()
                .stream()
                .map(t -> new DictAllListVo(t.getId(), t.getParentId(), t.getDictCode(), t.getDictName()))
                .collect(Collectors.toList());
    }

    private void saveAdminDictTemplateIfAbsent(DictAddDto dto) {
        DictUniqueKey dictUniqueKey = this.buildDictUniqueKey(dto.getDictType(), dto.getDictCode(), dto.getDictName());
        if (dictUniqueKey == null) {
            return;
        }

        boolean exists = this.queryTemplateDictList(DictTypeEnum.getAdminManagedDict()).stream()
                .map(this::buildDictUniqueKey)
                .anyMatch(dictUniqueKey::equals);
        if (exists) {
            return;
        }

        BaseDictEntity templateEntity = new BaseDictEntity();
        templateEntity.setParentId(WebCommonConstants.DATABASE_DEFAULT_INT_VALUE);
        templateEntity.setDictType(dto.getDictType());
        templateEntity.setDictCode(dto.getDictCode());
        templateEntity.setDictName(dto.getDictName());
        templateEntity.setDictStatus(dto.getDictStatus());
        templateEntity.setSort(dto.getSort());
        templateEntity.setUserId(WebCommonConstants.DATABASE_DEFAULT_INT_VALUE);
        getBaseDao().save(templateEntity);
    }

    private int repairAdminDictTemplateData() {
        Map<DictUniqueKey, BaseDictEntity> templateDictMap = this.toDictUniqueMap(this.queryTemplateDictList(DictTypeEnum.getAdminManagedDict()));
        Map<DictUniqueKey, BaseDictEntity> adminDictMap = this.toDictUniqueMap(this.queryAdminManagedDictList());
        List<BaseDictEntity> insertList = adminDictMap.entrySet().stream()
                .filter(entry -> !templateDictMap.containsKey(entry.getKey()))
                .map(entry -> this.copyDictEntity(entry.getValue(), WebCommonConstants.DATABASE_DEFAULT_INT_VALUE))
                .collect(Collectors.toList());
        if (CollectionUtil.isEmpty(insertList)) {
            return 0;
        }
        getBaseDao().saveBatch(insertList);
        return insertList.size();
    }

    private int copyMissingVisibleDictData(Integer userId) {
        List<BaseDictEntity> templateDictList = this.queryTemplateDictList(DictTypeEnum.getUserVisibleDict());
        Map<DictUniqueKey, BaseDictEntity> templateDictMap = this.toDictUniqueMap(templateDictList);
        Map<DictUniqueKey, BaseDictEntity> userDictMap = this.toDictUniqueMap(this.queryDictListByUser(userId, DictTypeEnum.getUserVisibleDict()));
        List<BaseDictEntity> insertList = templateDictMap.entrySet().stream()
                .filter(entry -> !userDictMap.containsKey(entry.getKey()))
                .map(entry -> this.copyDictEntity(entry.getValue(), userId))
                .collect(Collectors.toList());
        int updateCount = 0;
        if (CollectionUtil.isEmpty(insertList)) {
            updateCount = this.remapUserDictParentIds(userId, templateDictList);
            if (updateCount > 0) {
                RedisUtil.delete(this.obtainDictRedisKeyByUser(userId));
            }
            return updateCount;
        }
        getBaseDao().saveBatch(insertList);
        updateCount = this.remapUserDictParentIds(userId, templateDictList);
        RedisUtil.delete(this.obtainDictRedisKeyByUser(userId));
        return insertList.size() + updateCount;
    }

    private List<BaseDictEntity> queryTemplateDictList(List<DictTypeEnum> dictTypeList) {
        return getBaseDao().lambdaQuery()
                .eq(BaseDictEntity::getUserId, WebCommonConstants.DATABASE_DEFAULT_INT_VALUE)
                .in(BaseDictEntity::getDictType, this.toDictTypeCodeList(dictTypeList))
                .eq(BaseDictEntity::getDictStatus, EnableEnum.ENABLE.getCode())
                .orderByAsc(BaseDictEntity::getDictType)
                .orderByAsc(BaseDictEntity::getSort)
                .orderByAsc(BaseDictEntity::getId)
                .list();
    }

    private List<BaseDictEntity> queryDictListByUser(Integer userId, List<DictTypeEnum> dictTypeList) {
        return getBaseDao().lambdaQuery()
                .eq(BaseDictEntity::getUserId, userId)
                .in(BaseDictEntity::getDictType, this.toDictTypeCodeList(dictTypeList))
                .list();
    }

    private List<BaseDictEntity> queryAdminManagedDictList() {
        try {
            UserUtils.setUserDataPermission(false);
            return getBaseDao().lambdaQuery()
                    .in(BaseDictEntity::getDictType, this.toDictTypeCodeList(DictTypeEnum.getAdminManagedDict()))
                    .eq(BaseDictEntity::getDictStatus, EnableEnum.ENABLE.getCode())
                    .orderByAsc(BaseDictEntity::getDictType)
                    .orderByAsc(BaseDictEntity::getSort)
                    .orderByAsc(BaseDictEntity::getUserId)
                    .orderByAsc(BaseDictEntity::getId)
                    .list();
        } finally {
            UserUtils.removeUserDataPermission();
        }
    }

    private Map<DictUniqueKey, BaseDictEntity> toDictUniqueMap(List<BaseDictEntity> dictEntityList) {
        Map<DictUniqueKey, BaseDictEntity> dictMap = new LinkedHashMap<>();
        for (BaseDictEntity dictEntity : dictEntityList) {
            DictUniqueKey dictUniqueKey = this.buildDictUniqueKey(dictEntity);
            if (dictUniqueKey != null) {
                dictMap.putIfAbsent(dictUniqueKey, dictEntity);
            }
        }
        return dictMap;
    }

    private DictUniqueKey buildDictUniqueKey(BaseDictEntity dictEntity) {
        return this.buildDictUniqueKey(dictEntity.getDictType(), dictEntity.getDictCode(), dictEntity.getDictName());
    }

    private DictUniqueKey buildDictUniqueKey(Integer dictType, Integer dictCode, String dictName) {
        DictTypeEnum dictTypeEnum = DictTypeEnum.getDictByCode(dictType);
        if (dictTypeEnum == null) {
            return null;
        }
        if (DictTypeEnum.DataType.CODE.equals(dictTypeEnum.getDataType())) {
            return new DictUniqueKey(dictType, dictCode, null);
        }
        return new DictUniqueKey(dictType, null, dictName);
    }

    private BaseDictEntity copyDictEntity(BaseDictEntity source, Integer userId) {
        BaseDictEntity dictEntity = BeanUtil.copyProperties(source, BaseDictEntity.class);
        dictEntity.setId(null);
        dictEntity.setParentId(WebCommonConstants.DATABASE_DEFAULT_INT_VALUE);
        dictEntity.setUserId(userId);
        dictEntity.setDeleted(Boolean.FALSE);
        dictEntity.setCreateTime(null);
        dictEntity.setUpdateTime(null);
        return dictEntity;
    }

    private int remapUserDictParentIds(Integer userId, List<BaseDictEntity> templateDictList) {
        Map<Integer, BaseDictEntity> templateIdMap = templateDictList.stream()
                .collect(Collectors.toMap(BaseDictEntity::getId, t -> t, (a, b) -> a));
        Map<DictUniqueKey, BaseDictEntity> templateDictMap = this.toDictUniqueMap(templateDictList);
        List<BaseDictEntity> userDictList = this.queryDictListByUser(userId, DictTypeEnum.getUserVisibleDict());
        Map<DictUniqueKey, BaseDictEntity> userDictMap = this.toDictUniqueMap(userDictList);
        int updateCount = 0;
        for (BaseDictEntity userDict : userDictList) {
            BaseDictEntity templateDict = templateDictMap.get(this.buildDictUniqueKey(userDict));
            if (templateDict == null || NumberUtils.isNullOrZero(templateDict.getParentId())) {
                continue;
            }
            BaseDictEntity templateParent = templateIdMap.get(templateDict.getParentId());
            if (templateParent == null) {
                continue;
            }
            BaseDictEntity userParent = userDictMap.get(this.buildDictUniqueKey(templateParent));
            if (userParent == null || Objects.equals(userDict.getParentId(), userParent.getId())) {
                continue;
            }
            boolean updated = getBaseDao().lambdaUpdate()
                    .eq(BaseDictEntity::getId, userDict.getId())
                    .eq(BaseDictEntity::getUserId, userId)
                    .set(BaseDictEntity::getParentId, userParent.getId())
                    .update();
            if (updated) {
                updateCount++;
            }
        }
        return updateCount;
    }

    private List<Integer> toDictTypeCodeList(List<DictTypeEnum> dictTypeList) {
        return dictTypeList.stream().map(DictTypeEnum::getCode).collect(Collectors.toList());
    }

    private void refreshUserDictCacheVersion(Integer userId) {
        RedisUtil.delete(this.obtainDictRedisKeyByUser(userId));
        AuthRedisKeyEnum.USER_DICT_VERSION.setStringValue(System.currentTimeMillis(), userId);
    }

    private record DictUniqueKey(Integer dictType, Integer dictCode, String dictName) {
    }

    /**
     * 校验数据安全
     *
     * @param id 字典id
     * @return BaseDictEntity
     */
    private BaseDictEntity checkDataSecurity(Serializable id, Integer dictStatus) {
        BaseDictEntity dictEntity = getBaseDao().queryById(id);

        // 判断当前字典类型是否少于1个值
        Long dictEntityCounts = getBaseDao().lambdaQuery()
                .eq(BaseDictEntity::getDictType, dictEntity.getDictType())
                .eq(BaseDictEntity::getDictStatus, EnableEnum.ENABLE.getCode())
                .ne(dictStatus == null || BoolEnum.FALSE.getCode().equals(dictStatus), BaseDictEntity::getId, id)
                .count();
        if (dictEntityCounts < 1) {
            throw new BusinessException("当前字典类型至少需要包含一个启用的字典值！");
        }

        return dictEntity;
    }

    /**
     * 检测并填充保存时的参数合法性
     */
    private void checkAndFillSaveParam(DictAddDto dto) {
        DictTypeEnum dictTypeEnum = ConstantEnumUtil.findByType(DictTypeEnum.class, dto.getDictType());
        if (dictTypeEnum == null) {
            throw new BusinessException("字典类型错误");
        }
        this.checkWardrobeItemSubcategoryParent(dto, dictTypeEnum);

        Integer oldDictId = null;
        // 如果是更新操作
        if (dto instanceof DictUpdateDto updateDto) {
            oldDictId = updateDto.getId();
            // 查询历史字典
            BaseDictEntity oldDictEntity = getBaseDao().queryById(oldDictId);
            DictTypeEnum oldDictTypeEnum = ConstantEnumUtil.findByType(DictTypeEnum.class, oldDictEntity.getDictType());
            // 如果修改的是管理员字典, 则不能修改其 dictType 值
            if (oldDictTypeEnum.isAdminDict() && !dictTypeEnum.equals(oldDictTypeEnum)) {
                throw new BusinessException("管理员字典不能修改其字典类型");
            }
            // 如果修改的不是管理员字典, 而不能将其修改为管理员字典
            if (!oldDictTypeEnum.isAdminDict() && dictTypeEnum.isAdminDict()) {
                throw new BusinessException("普通字典不能修改为管理员字典");
            }
        }
        if (dictTypeEnum.getDataType().equals(DictTypeEnum.DataType.CODE)) {
            // 字典类型为CODE时, dictCode如果为空，则code自动累加1
            if (dto.getDictCode() == null) {
                Integer maxDictCode = getBaseDao().lambdaQuery()
                        .eq(BaseDictEntity::getDictType, dto.getDictType())
                        .select(BaseDictEntity::getDictCode)
                        .orderByDesc(BaseDictEntity::getDictCode)
                        .last(WebCommonConstants.LIMIT_ONE)
                        .oneOpt()
                        .map(BaseDictEntity::getDictCode)
                        .orElse(1);
                dto.setDictCode(maxDictCode + 1);
            } else {
                // 检测字典code是否重复
                Long count = getBaseDao().lambdaQuery()
                        .eq(BaseDictEntity::getDictType, dto.getDictType())
                        .eq(BaseDictEntity::getDictCode, dto.getDictCode())
                        .ne(oldDictId != null, BaseDictEntity::getId, oldDictId)
                        .count();
                if (count > 0) {
                    throw new BusinessException("CODE类型的字典项, 其字典code不能重复");
                }
            }
        }
        // 检测字典name是否重复
        Long count = getBaseDao().lambdaQuery()
                .eq(BaseDictEntity::getDictType, dto.getDictType())
                .eq(BaseDictEntity::getDictName, dto.getDictName())
                .ne(oldDictId != null, BaseDictEntity::getId, oldDictId)
                .count();
        if (count > 0) {
            throw new BusinessException("字典名称不能重复");
        }
    }

    private void checkWardrobeItemSubcategoryParent(DictAddDto dto, DictTypeEnum dictTypeEnum) {
        if (!DictTypeEnum.WARDROBE_ITEM_SUBCATEGORY.equals(dictTypeEnum)) {
            return;
        }
        if (Objects.equals(BoolEnum.TRUE.getCode(), dto.getIsSyncAll())) {
            throw new BusinessException("衣物款式不支持同步所有用户，请通过模板字典和修复接口初始化");
        }
        if (NumberUtils.isNullOrZero(dto.getParentId())) {
            throw new BusinessException("衣物款式必须选择所属品类");
        }
        BaseDictEntity parentDict = getBaseDao().queryById(dto.getParentId(), "请选择有效的衣物品类");
        if (!Objects.equals(parentDict.getDictType(), DictTypeEnum.WARDROBE_ITEM_CATEGORY.getCode())) {
            throw new BusinessException("衣物款式只能归属衣物品类");
        }
        if (!Objects.equals(parentDict.getDictStatus(), EnableEnum.ENABLE.getCode())) {
            throw new BusinessException("衣物款式不能归属已禁用的品类");
        }
    }

    /**
     * 校验用户操作字典的角色权限
     *
     * @param dictType 字典类型code
     * @return 是否为管理员字典项 true->是
     */
    private boolean verifyUserPermission(Integer dictType, Integer isSyncAll) {
        DictTypeEnum dictTypeEnum = ConstantEnumUtil.findByType(DictTypeEnum.class, dictType);
        if (dictTypeEnum == null) {
            throw new BusinessException("无权操作");
        }
        boolean isAdminUser = this.isAdminUser(UserUtils.getUserId());
        if (dictTypeEnum.isAdminDict()) {
            if (!isAdminUser) {
                throw new BusinessException("无权操作");
            }
        }
        boolean isAdminDict = dictTypeEnum.isAdminDict();
        // 非管理员字典，但是请求需要同步所有用户的字典项
        if (!isAdminDict && Objects.equals(BoolEnum.TRUE.getCode(), isSyncAll)) {
            if (!isAdminUser) {
                throw new BusinessException("权限不足");
            }
            isAdminDict = true;
        }
        return isAdminDict;
    }

    /**
     * 是否为管理员用户
     *
     * @param userId 用户id
     * @return true -> 是
     */
    private boolean isAdminUser(Integer userId) {
        AuthUserEntity authUserEntity = authUserDao.queryById(userId);
        return RoleTypeEnum.isAdminRole(authUserEntity.getRoleType());
    }

    private boolean isSuperAdminUser(Integer userId) {
        AuthUserEntity authUserEntity = authUserDao.queryById(userId);
        return RoleTypeEnum.SUPER_ADMIN.getCode().equals(authUserEntity.getRoleType());
    }

    /**
     * 通过用户获取字典redisKey
     *
     * @return dict:[userId]
     */
    private String obtainDictRedisKeyByUser() {
        return obtainDictRedisKeyByUser(UserUtils.getUserId());
    }

    /**
     * 通过用户获取字典redisKey
     *
     * @param userId 指定用户id
     * @return dict:[userId]
     */
    private String obtainDictRedisKeyByUser(Integer userId) {
        return AuthRedisKeyEnum.DICT_KEY.getKey(userId);
    }

    @Override
    public Class<UserAddBo> getGenericClass() {
        return UserAddBo.class;
    }

    /**
     * 新用户注册初始化基础字典数据
     *
     * @param bo 新用户对象
     */
    @Override
    @Transactional
    public void doConsume(UserAddBo bo) {
        // 初始化字典数据
        this.initDictData(bo);

        // 字典数据初始化完成之后, 发送 依赖字典数据 的消息
        MQProducerHelper.send(RegisterNewUserTopicEnum.DEPEND_DICT, bo);
    }

    /**
     * 新用户注册初始化基础字典数据
     */
    private void initDictData(UserAddBo bo) {
        RedisLockUtil.lock(OPERATE_ADMIN_DICT_LOCK_KEY);
        try {
            int insertCount = this.copyMissingVisibleDictData(bo.getUserId());
            if (insertCount == 0) {
                log.info("用户[{}]无需补齐基础字典数据", bo.getUserId());
            } else {
                this.refreshUserDictCacheVersion(bo.getUserId());
            }
        } finally {
            RedisLockUtil.unlock(OPERATE_ADMIN_DICT_LOCK_KEY);
        }

        log.info("用户[{}]基础字典数据初始化完成", bo.getUserId());
    }
}

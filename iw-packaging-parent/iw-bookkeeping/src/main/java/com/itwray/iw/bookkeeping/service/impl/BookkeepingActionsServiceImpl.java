package com.itwray.iw.bookkeeping.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.itwray.iw.auth.model.bo.UserAddBo;
import com.itwray.iw.bookkeeping.dao.BookkeepingActionsDao;
import com.itwray.iw.bookkeeping.mapper.BookkeepingActionsMapper;
import com.itwray.iw.bookkeeping.model.BookkeepingRedisKeyEnum;
import com.itwray.iw.bookkeeping.model.dto.BookkeepingActionsAddDto;
import com.itwray.iw.bookkeeping.model.dto.BookkeepingActionsUpdateDto;
import com.itwray.iw.bookkeeping.model.entity.BookkeepingActionsEntity;
import com.itwray.iw.bookkeeping.model.vo.BookkeepingActionsDetailVo;
import com.itwray.iw.bookkeeping.service.BookkeepingActionsService;
import com.itwray.iw.starter.rocketmq.config.RocketMQClientListener;
import com.itwray.iw.web.constants.WebCommonConstants;
import com.itwray.iw.web.model.enums.mq.RegisterNewUserTopicEnum;
import com.itwray.iw.web.service.impl.WebServiceImpl;
import com.itwray.iw.web.utils.UserUtils;
import com.itwray.iw.starter.rocketmq.config.LocalMessageListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import com.itwray.iw.web.exception.BusinessException;

/**
 * 记账行为表 服务实现类
 *
 * @author wray
 * @since 2025-04-08
 */
@Slf4j
@Service
@LocalMessageListener(consumerGroup = "bookkeeping-service", topic = RegisterNewUserTopicEnum.TOPIC, tag = "dependDict")
public class BookkeepingActionsServiceImpl extends WebServiceImpl<BookkeepingActionsDao, BookkeepingActionsMapper, BookkeepingActionsEntity,
        BookkeepingActionsAddDto, BookkeepingActionsUpdateDto, BookkeepingActionsDetailVo, Integer> implements BookkeepingActionsService, RocketMQClientListener<UserAddBo> {

    @Autowired private com.itwray.iw.web.bookkeeping.BookkeepingDictionaryAccess dictionaries;
    @Autowired private com.itwray.iw.bookkeeping.support.BookkeepingDictionarySnapshots snapshots;
    @Autowired private com.itwray.iw.web.dao.BaseDictDao dictDao;

    @Autowired
    public BookkeepingActionsServiceImpl(BookkeepingActionsDao baseDao) {
        super(baseDao);
    }

    @Override
    @Transactional
    public Integer add(BookkeepingActionsAddDto dto) {
        validate(dto);
        // 查询当前用户记账行为中排序最大值
        BookkeepingActionsEntity entity = getBaseDao().lambdaQuery()
                .eq(BookkeepingActionsEntity::getUserId, UserUtils.getUserId())
                .orderByDesc(BookkeepingActionsEntity::getSort)
                .last(WebCommonConstants.LIMIT_ONE)
                .one();
        if (entity != null) {
            dto.setSort(entity.getSort().add(BigDecimal.TEN));
        } else {
            dto.setSort(BigDecimal.TEN);
        }

        Integer id = super.add(dto);
        BookkeepingRedisKeyEnum.ACTION_LIST.delete(UserUtils.getUserId(), dto.getRecordCategory());
        return id;
    }

    @Override
    @Transactional
    public void update(BookkeepingActionsUpdateDto dto) {
        validate(dto);
        BookkeepingRedisKeyEnum.ACTION_LIST.delete(UserUtils.getUserId(), dto.getRecordCategory());
        super.update(dto);
    }

    @Override
    @Transactional
    public void delete(Integer id) {
        BookkeepingActionsEntity entity = getBaseDao().queryById(id);
        BookkeepingRedisKeyEnum.ACTION_LIST.delete(UserUtils.getUserId(), entity.getRecordCategory());
        super.delete(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookkeepingActionsDetailVo> list(Integer recordCategory) {
        Integer userId = UserUtils.getUserId();

        List<BookkeepingActionsDetailVo> voList = getBaseDao().lambdaQuery()
                .eq(BookkeepingActionsEntity::getUserId, userId)
                .eq(BookkeepingActionsEntity::getRecordCategory, recordCategory)
                .orderByAsc(BookkeepingActionsEntity::getSort)
                .orderByAsc(BookkeepingActionsEntity::getId)
                .list()
                .stream()
                .map(t -> BeanUtil.copyProperties(t, BookkeepingActionsDetailVo.class))
                .toList();

        resolve(voList);
        // Future configurations refresh source validity on every read; cached IDs are never facts.

        return voList;
    }

    private List<Integer> tags(String value){
        if(value==null||value.isBlank())return List.of();
        try{return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(v->!v.isEmpty()).map(Integer::valueOf).distinct().toList();}
        catch(NumberFormatException e){throw new com.itwray.iw.web.exception.BusinessException("标签参数不正确");}
    }
    private void validate(BookkeepingActionsAddDto dto){
        if(dto.getRecordType()!=null)throw new com.itwray.iw.web.exception.BusinessException("请更新客户端后重试：快捷分类须使用来源ID");
        if(!java.util.Objects.equals(dto.getRecordCategory(),1)&&!java.util.Objects.equals(dto.getRecordCategory(),2))throw new com.itwray.iw.web.exception.BusinessException("收支类型不正确");
        snapshots.lock(dto.getRecordTypeId(),tags(dto.getRecordTags()),dto.getRecordCategory()==1?4001:4011);dto.setRecordType(0);
    }
    private void resolve(List<BookkeepingActionsDetailVo> actions) {
        Set<Integer> ids=new TreeSet<>();
        for(var action:actions) {
            if(action.getRecordTypeId()!=null && action.getRecordTypeId()>0) ids.add(action.getRecordTypeId());
            try { tags(action.getRecordTags()).stream().filter(id->id>0).forEach(ids::add); }
            catch(BusinessException ignored) { /* Mark only this malformed action unavailable below. */ }
        }
        Map<Integer,BaseDictEntity> resolved=ids.isEmpty()?Map.of():dictionaries.resolveSuccessors(UserUtils.getUserId(),ids);
        actions.forEach(vo->resolve(vo,resolved));
    }
    private BaseDictEntity current(Map<Integer,BaseDictEntity> resolved,Integer id,int type) {
        BaseDictEntity row=resolved.get(id);
        if(row==null || !java.util.Objects.equals(row.getDictType(),type))
            throw new BusinessException("记账配置选项已失效，请重新保存配置");
        return row;
    }
    private void resolve(BookkeepingActionsDetailVo vo,Map<Integer,BaseDictEntity> resolved){
        try{
            if(vo.getRecordTypeId()==null&&vo.getRecordType()!=null&&vo.getRecordType()!=0)throw new com.itwray.iw.web.exception.BusinessException("请重新保存快捷配置");
            if(vo.getRecordTypeId()!=null){var current=current(resolved,vo.getRecordTypeId(),4002);vo.setRecordTypeId(current.getId());vo.setRecordTypeName(current.getDictName());}
            var tagSnapshots=new java.util.ArrayList<com.itwray.iw.web.bookkeeping.DictionaryTagSnapshot>();
            int type=vo.getRecordCategory()==1?4001:4011;
            for(Integer id:tags(vo.getRecordTags())){var current=current(resolved,id,type);tagSnapshots.add(new com.itwray.iw.web.bookkeeping.DictionaryTagSnapshot(current.getId(),type,current.getDictName()));}
            vo.setRecordTags(tagSnapshots.stream().map(t->t.getId().toString()).collect(java.util.stream.Collectors.joining(",")));vo.setRecordTagSnapshots(tagSnapshots);vo.setAvailable(true);
        }catch(com.itwray.iw.web.exception.BusinessException ex){vo.setAvailable(false);vo.setUnavailableReason("请重新配置快捷记账："+ex.getMessage());}
    }
    @Override
    @Transactional(readOnly = true)
    public BookkeepingActionsDetailVo detail(Integer id){var vo=super.detail(id);resolve(List.of(vo));return vo;}

    @Override
    public Class<UserAddBo> getGenericClass() {
        return UserAddBo.class;
    }

    @Override
    @Transactional
    public void doConsume(UserAddBo bo) {
        // 判断该用户是否已生成过字典数据
        BookkeepingActionsEntity actionsEntity = getBaseDao().lambdaQuery()
                .eq(BookkeepingActionsEntity::getUserId, bo.getUserId())
                .select(BookkeepingActionsEntity::getId)
                .last(WebCommonConstants.LIMIT_ONE)
                .one();
        if (actionsEntity != null) {
            log.info("用户[{}]已存在记账行为数据, 默认跳过初始化默认记账行为数据操作", bo.getUserId());
            return;
        }

        // 查询默认记账行为数据
        List<BookkeepingActionsEntity> defaultActionList = getBaseDao().lambdaQuery()
                .eq(BookkeepingActionsEntity::getUserId, WebCommonConstants.DATABASE_DEFAULT_INT_VALUE)
                .list();
        if (CollectionUtil.isEmpty(defaultActionList)) {
            log.info("数据库内无默认记账行为数据, 用户[{}]默认跳过初始化默认记账行为数据操作", bo.getUserId());
            return;
        }

        // 保存默认记账行为数据
        for (BookkeepingActionsEntity action : defaultActionList) {
            BookkeepingActionsEntity newAction = BeanUtil.copyProperties(action, BookkeepingActionsEntity.class);
            newAction.setId(null);  // id重置为空，采用数据库自增id
            newAction.setUserId(bo.getUserId()); // 使用新用户id
            var category=dictDao.lambdaQuery().eq(com.itwray.iw.web.model.entity.BaseDictEntity::getUserId,bo.getUserId())
                .eq(com.itwray.iw.web.model.entity.BaseDictEntity::getScopeType,0)
                .eq(com.itwray.iw.web.model.entity.BaseDictEntity::getFamilyGroupId,0)
                .eq(com.itwray.iw.web.model.entity.BaseDictEntity::getDictType,4002)
                .eq(com.itwray.iw.web.model.entity.BaseDictEntity::getDictCode,action.getRecordType()).list();
            if(action.getRecordTypeId()!=null){
                var templates=dictDao.lambdaQuery().eq(com.itwray.iw.web.model.entity.BaseDictEntity::getId,action.getRecordTypeId()).eq(com.itwray.iw.web.model.entity.BaseDictEntity::getScopeType,0).eq(com.itwray.iw.web.model.entity.BaseDictEntity::getUserId,0).list();
                if(!templates.isEmpty())category=dictDao.lambdaQuery().eq(com.itwray.iw.web.model.entity.BaseDictEntity::getUserId,bo.getUserId())
                    .eq(com.itwray.iw.web.model.entity.BaseDictEntity::getScopeType,0).eq(com.itwray.iw.web.model.entity.BaseDictEntity::getDictType,4002)
                    .apply("CAST(dict_name AS BINARY) = CAST({0} AS BINARY)",templates.get(0).getDictName()).list();
            }
            if(category.size()!=1 && !(category.isEmpty() && (action.getRecordType()==null || action.getRecordType()==0) && action.getRecordTypeId()==null)){
                log.warn("用户默认快捷配置分类无法唯一映射，已跳过；用户ID {}",bo.getUserId());continue;
            }
            newAction.setRecordTypeId(category.isEmpty()?null:category.get(0).getId());newAction.setRecordType(0);
            List<Integer> mappedTags=new java.util.ArrayList<>();boolean invalidTags=false;
            int type=action.getRecordCategory()==1?4001:4011;
            for(Integer templateId:tags(action.getRecordTags())){
                var templates=dictDao.lambdaQuery().eq(com.itwray.iw.web.model.entity.BaseDictEntity::getId,templateId)
                    .eq(com.itwray.iw.web.model.entity.BaseDictEntity::getUserId,0).eq(com.itwray.iw.web.model.entity.BaseDictEntity::getScopeType,0)
                    .eq(com.itwray.iw.web.model.entity.BaseDictEntity::getFamilyGroupId,0).eq(com.itwray.iw.web.model.entity.BaseDictEntity::getDictType,type).list();
                if(templates.size()!=1){invalidTags=true;break;}
                var personal=dictDao.lambdaQuery().eq(com.itwray.iw.web.model.entity.BaseDictEntity::getUserId,bo.getUserId())
                    .eq(com.itwray.iw.web.model.entity.BaseDictEntity::getScopeType,0).eq(com.itwray.iw.web.model.entity.BaseDictEntity::getFamilyGroupId,0)
                    .eq(com.itwray.iw.web.model.entity.BaseDictEntity::getDictType,type).apply("CAST(dict_name AS BINARY) = CAST({0} AS BINARY)",templates.get(0).getDictName()).list();
                if(personal.size()!=1){invalidTags=true;break;}mappedTags.add(personal.get(0).getId());
            }
            if(invalidTags){log.warn("用户默认快捷配置标签无法唯一映射，已跳过；用户ID {}",bo.getUserId());continue;}
            newAction.setRecordTags(mappedTags.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
            getBaseDao().save(newAction);
        }

        log.info("用户[{}]默认记账行为数据初始化完成", bo.getUserId());
    }
}

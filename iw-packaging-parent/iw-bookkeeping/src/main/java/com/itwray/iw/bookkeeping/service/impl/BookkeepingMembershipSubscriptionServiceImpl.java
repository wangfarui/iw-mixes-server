package com.itwray.iw.bookkeeping.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.itwray.iw.bookkeeping.dao.BookkeepingMembershipSubscriptionDao;
import com.itwray.iw.bookkeeping.mapper.BookkeepingMembershipSubscriptionMapper;
import com.itwray.iw.bookkeeping.model.dto.BookkeepingMembershipSubscriptionAddDto;
import com.itwray.iw.bookkeeping.model.dto.BookkeepingMembershipSubscriptionListDto;
import com.itwray.iw.bookkeeping.model.dto.BookkeepingMembershipSubscriptionUpdateDto;
import com.itwray.iw.bookkeeping.model.entity.BookkeepingMembershipSubscriptionEntity;
import com.itwray.iw.bookkeeping.model.enums.MembershipSubscriptionExpiryTypeEnum;
import com.itwray.iw.bookkeeping.model.vo.BookkeepingMembershipSubscriptionDetailVo;
import com.itwray.iw.bookkeeping.model.vo.BookkeepingMembershipSubscriptionListVo;
import com.itwray.iw.bookkeeping.service.BookkeepingMembershipSubscriptionService;
import com.itwray.iw.bookkeeping.utils.MembershipBillingCycleUtils;
import com.itwray.iw.web.model.enums.SortTypeEnum;
import com.itwray.iw.web.bookkeeping.BookkeepingDictionaryAccess;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.bookkeeping.model.dto.BookkeepingMembershipSubscriptionFields;
import com.itwray.iw.bookkeeping.model.vo.BookkeepingMembershipFilterOption;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;
import java.util.ArrayList;
import com.itwray.iw.web.model.enums.SortWayEnum;
import com.itwray.iw.web.service.impl.WebServiceImpl;
import com.itwray.iw.web.utils.UserUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * 会员订阅记录表 服务实现类
 *
 * @author wray
 * @since 2025/11/5
 */
@Service
public class BookkeepingMembershipSubscriptionServiceImpl extends WebServiceImpl<BookkeepingMembershipSubscriptionDao, BookkeepingMembershipSubscriptionMapper, BookkeepingMembershipSubscriptionEntity,
        BookkeepingMembershipSubscriptionAddDto, BookkeepingMembershipSubscriptionUpdateDto, BookkeepingMembershipSubscriptionDetailVo, Integer> implements BookkeepingMembershipSubscriptionService {

    @Autowired
    private BookkeepingDictionaryAccess dictionaries;

    @Autowired
    public BookkeepingMembershipSubscriptionServiceImpl(BookkeepingMembershipSubscriptionDao baseDao) {
        super(baseDao);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer add(BookkeepingMembershipSubscriptionAddDto dto) {
        if(dto.getMembershipTypeId()==null) throw new BusinessException("请更新客户端，从记账设置选择会员类型");
        BaseDictEntity source=select(dto.getMembershipTypeId());
        fillEndDateIfAbsent(dto);
        BookkeepingMembershipSubscriptionEntity row=BeanUtil.copyProperties(dto,BookkeepingMembershipSubscriptionEntity.class);
        row.setUserId(UserUtils.getUserId());
        snapshot(row,source);
        getBaseDao().save(row);
        return row.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(BookkeepingMembershipSubscriptionUpdateDto dto) {
        if(dto.isMembershipTypeChangePresent() && dto.getMembershipTypeChange()==null)
            throw new BusinessException("类型变更不能为空，请重新选择会员类型");
        // Consistent lock order: personal owner/source first, subscription second.
        BaseDictEntity source=dto.getMembershipTypeChange()==null?null:select(dto.getMembershipTypeChange().getSourceId());
        BookkeepingMembershipSubscriptionEntity old=getBaseDao().getBaseMapper().lockOwned(dto.getId(),UserUtils.getUserId());
        if(old==null) throw new BusinessException("订阅不存在或无权修改");
        if(source==null && dto.getMembershipType()!=null && !Objects.equals(dto.getMembershipType(),old.getMembershipType()))
            throw new BusinessException("请更新客户端，主动重新选择会员类型");
        fillEndDateIfAbsent(dto);
        BookkeepingMembershipSubscriptionEntity row=BeanUtil.copyProperties(dto,BookkeepingMembershipSubscriptionEntity.class);
        row.setUserId(old.getUserId());
        // Omission preserves the saved snapshot, even when its source is retired/disabled/deleted.
        row.setMembershipType(old.getMembershipType());
        row.setMembershipTypeId(old.getMembershipTypeId());
        row.setMembershipTypeName(old.getMembershipTypeName());
        row.setMembershipTypeSnapshotStatus(old.getMembershipTypeSnapshotStatus());
        if(source!=null) snapshot(row,source);
        getBaseDao().updateById(row);
    }

    @Override
    public BookkeepingMembershipSubscriptionDetailVo detail(Integer id) {
        BookkeepingMembershipSubscriptionEntity row=getBaseDao().lambdaQuery()
                .eq(BookkeepingMembershipSubscriptionEntity::getId,id)
                .eq(BookkeepingMembershipSubscriptionEntity::getUserId,UserUtils.getUserId()).one();
        if(row==null) throw new BusinessException("订阅不存在或无权查看");
        return BeanUtil.copyProperties(row,BookkeepingMembershipSubscriptionDetailVo.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Integer id) {
        if(getBaseDao().getBaseMapper().lockOwned(id,UserUtils.getUserId())==null) throw new BusinessException("订阅不存在或无权删除");
        getBaseDao().removeById(id);
    }

    private BaseDictEntity select(Integer id) {
        if(id==null || id<=0) throw new BusinessException("请选择有效会员类型");
        return dictionaries.lockSelectable(UserUtils.getUserId(),4004,List.of(id)).get(0);
    }
    private void snapshot(BookkeepingMembershipSubscriptionEntity row,BaseDictEntity source) {
        // Legacy code is retained for compatibility; it is never a source ID.
        row.setMembershipType(source.getDictCode());
        row.setMembershipTypeId(source.getId());
        row.setMembershipTypeName(source.getDictName());
        row.setMembershipTypeSnapshotStatus(1);
    }

    @Override
    public List<BookkeepingMembershipFilterOption> filterOptions() {
        List<BookkeepingMembershipSubscriptionEntity> rows=getBaseDao().lambdaQuery()
                .eq(BookkeepingMembershipSubscriptionEntity::getUserId,UserUtils.getUserId())
                .select(BookkeepingMembershipSubscriptionEntity::getMembershipTypeName,BookkeepingMembershipSubscriptionEntity::getMembershipTypeSnapshotStatus).list();
        List<BookkeepingMembershipFilterOption> result=new ArrayList<>();
        rows.stream().filter(r->Objects.equals(r.getMembershipTypeSnapshotStatus(),1) && r.getMembershipTypeName()!=null)
                .map(BookkeepingMembershipSubscriptionEntity::getMembershipTypeName).distinct().sorted()
                .forEach(name->result.add(new BookkeepingMembershipFilterOption(name,null)));
        if(rows.stream().anyMatch(r->!Objects.equals(r.getMembershipTypeSnapshotStatus(),1))) result.add(new BookkeepingMembershipFilterOption(null,"PENDING"));
        return result;
    }

    @Override
    public List<BookkeepingMembershipSubscriptionListVo> list(BookkeepingMembershipSubscriptionListDto dto) {
        if(dto.getMembershipTypeState()!=null && (!"PENDING".equals(dto.getMembershipTypeState()) || dto.getMembershipTypeName()!=null))
            throw new BusinessException("类型名称与待核对状态不可同时筛选");
        List<BookkeepingMembershipSubscriptionEntity> list = getBaseDao().lambdaQuery()
                .eq(BookkeepingMembershipSubscriptionEntity::getUserId, UserUtils.getUserId())
                .eq(dto.getMembershipTypeName()!=null,BookkeepingMembershipSubscriptionEntity::getMembershipTypeName,dto.getMembershipTypeName())
                .eq(dto.getMembershipTypeName()!=null,BookkeepingMembershipSubscriptionEntity::getMembershipTypeSnapshotStatus,1)
                .eq("PENDING".equals(dto.getMembershipTypeState()),BookkeepingMembershipSubscriptionEntity::getMembershipTypeSnapshotStatus,0)
                .eq(dto.getMembershipType() != null, BookkeepingMembershipSubscriptionEntity::getMembershipType, dto.getMembershipType())
                .and(MembershipSubscriptionExpiryTypeEnum.VALID.equals(dto.getExpiryType()), wrapper -> wrapper
                        .isNull(BookkeepingMembershipSubscriptionEntity::getEndDate)
                        .or()
                        .ge(BookkeepingMembershipSubscriptionEntity::getEndDate, LocalDate.now())
                )
                .and(MembershipSubscriptionExpiryTypeEnum.ABOUT_EXPIRE.equals(dto.getExpiryType()), wrapper -> wrapper
                        .isNotNull(BookkeepingMembershipSubscriptionEntity::getEndDate)
                        .le(BookkeepingMembershipSubscriptionEntity::getEndDate, LocalDate.now().plusWeeks(1L))
                        .ge(BookkeepingMembershipSubscriptionEntity::getEndDate, LocalDate.now())
                )
                .lt(MembershipSubscriptionExpiryTypeEnum.EXPIRED.equals(dto.getExpiryType()), BookkeepingMembershipSubscriptionEntity::getEndDate, LocalDate.now())
                .orderByAsc(SortWayEnum.isAsc(dto.getSortWay()), SortTypeEnum.getDefaultSortField(dto.getSortType()))
                .orderByDesc(!SortWayEnum.isAsc(dto.getSortWay()), SortTypeEnum.getDefaultSortField(dto.getSortType()))
                .list();
        if (list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream()
                .map(t -> BeanUtil.copyProperties(t, BookkeepingMembershipSubscriptionListVo.class))
                .toList();
    }

    @Override
    public int autoRenew() {
        int count = 0;
        // 查询所有开通了自动续费的会员服务
        List<BookkeepingMembershipSubscriptionEntity> subscriptionEntityList = getBaseDao().lambdaQuery()
                .eq(BookkeepingMembershipSubscriptionEntity::getAutoRenew, Boolean.TRUE)
                .eq(BookkeepingMembershipSubscriptionEntity::getEndDate, LocalDate.now())
                .list();
        if (CollectionUtils.isEmpty(subscriptionEntityList)) {
            return count;
        }
        for (BookkeepingMembershipSubscriptionEntity subscriptionEntity : subscriptionEntityList) {
            // 计算计费周期下的下一个续费时长
            subscriptionEntity.setStartDate(subscriptionEntity.getEndDate());
            LocalDate endDate = MembershipBillingCycleUtils.computeBillingCycleDate(subscriptionEntity);
            if (endDate == null) {
                continue;
            }

            BookkeepingMembershipSubscriptionEntity updateEntity = new BookkeepingMembershipSubscriptionEntity();
            updateEntity.setId(subscriptionEntity.getId());
            updateEntity.setEndDate(endDate);
            boolean updateCount = getBaseDao().updateById(updateEntity);
            count += updateCount ? 1 : 0;
        }
        return count;
    }

    /**
     * 如果结束日期为空，则根据开始日期和计费周期计算结束日期
     */
    private void fillEndDateIfAbsent(BookkeepingMembershipSubscriptionFields dto) {
        if (dto.getEndDate() == null) {
            dto.setEndDate(MembershipBillingCycleUtils.computeBillingCycleDate(
                    dto.getStartDate(), dto.getBillingCycle(), dto.getCycleNum(), dto.getCycleUnit()
            ));
        }
    }
}

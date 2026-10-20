package com.itwray.iw.bookkeeping.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.itwray.iw.bookkeeping.dao.BookkeepingBudgetDao;
import com.itwray.iw.bookkeeping.dao.BookkeepingRecordsDao;
import com.itwray.iw.bookkeeping.mapper.BookkeepingBudgetMapper;
import com.itwray.iw.bookkeeping.model.dto.*;
import com.itwray.iw.bookkeeping.model.entity.BookkeepingBudgetEntity;
import com.itwray.iw.bookkeeping.model.enums.BudgetTypeEnum;
import com.itwray.iw.bookkeeping.model.enums.RecordCategoryEnum;
import com.itwray.iw.bookkeeping.model.vo.BookkeepingBudgetDetailVo;
import com.itwray.iw.bookkeeping.model.vo.BookkeepingBudgetStatisticsVo;
import com.itwray.iw.bookkeeping.model.vo.BookkeepingConsumeStatisticsCategoryVo;
import com.itwray.iw.bookkeeping.model.vo.BookkeepingStatisticsTotalVo;
import com.itwray.iw.bookkeeping.service.BookkeepingBudgetService;
import com.itwray.iw.common.utils.DateUtils;
import com.itwray.iw.web.constants.WebCommonConstants;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.service.impl.WebServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 记账预算表 服务实现类
 *
 * @author wray
 * @since 2025-04-24
 */
@Service
public class BookkeepingBudgetServiceImpl extends WebServiceImpl<BookkeepingBudgetDao, BookkeepingBudgetMapper, BookkeepingBudgetEntity,
        BookkeepingBudgetAddDto, BookkeepingBudgetUpdateDto, BookkeepingBudgetDetailVo, Integer> implements BookkeepingBudgetService {

    private final BookkeepingRecordsDao bookkeepingRecordsDao;
    @Autowired private com.itwray.iw.bookkeeping.support.BookkeepingDictionarySnapshots snapshots;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    public BookkeepingBudgetServiceImpl(BookkeepingBudgetDao baseDao, BookkeepingRecordsDao bookkeepingRecordsDao) {
        super(baseDao);
        this.bookkeepingRecordsDao = bookkeepingRecordsDao;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Integer add(BookkeepingBudgetAddDto dto) {
        if(dto.getRecordType()!=null)throw new BusinessException("请更新客户端后重试：预算分类须使用来源ID");
        boolean total=dto.getBudgetType().isTotalBudgetType();
        if(!total&&dto.getRecordTypeId()==null)throw new BusinessException("分类预算的分类不能为空");
        dto.setRecordTypeName(total?null:snapshots.lock(dto.getRecordTypeId(),List.of(),4001).get(dto.getRecordTypeId()).getDictName());
        if(total)dto.setRecordTypeId(null);
        dto.setBudgetYear(dto.getBudgetType().isMonthBudgetType()?null:LocalDate.now().getYear());
        dto.setBudgetMonth(dto.getBudgetType().isMonthBudgetType()?DateUtils.startDateOfNowMonth():null);
        lockOwner();
        checkDuplicate(dto,null);
        if(dto.getPunishPoints()!=null)dto.setPunishPoints(-Math.abs(dto.getPunishPoints()));
        dto.setRecordType(0);
        return super.add(dto);
    }
    private void lockOwner(){jdbc.queryForList("select id from auth_user where id=? for update",com.itwray.iw.web.utils.UserUtils.getUserId());}
    private void checkDuplicate(BookkeepingBudgetAddDto dto,Integer excluded){
        var query=getBaseDao().lambdaQuery().eq(BookkeepingBudgetEntity::getBudgetType,dto.getBudgetType())
            .eq(dto.getBudgetMonth()!=null,BookkeepingBudgetEntity::getBudgetMonth,dto.getBudgetMonth())
            .eq(dto.getBudgetYear()!=null,BookkeepingBudgetEntity::getBudgetYear,dto.getBudgetYear())
            .ne(excluded!=null,BookkeepingBudgetEntity::getId,excluded);
        if(!dto.getBudgetType().isTotalBudgetType())query.apply("CAST(record_type_name AS BINARY) = CAST({0} AS BINARY)",dto.getRecordTypeName());
        if(query.count()>0)throw new BusinessException("已经存在同期同名预算，请勿重复添加");
    }
    @Override
    @org.springframework.transaction.annotation.Transactional
    public void update(BookkeepingBudgetUpdateDto dto) {
        if(dto.isRecordTypeIdPresent() && dto.getRecordTypeId()!=null)snapshots.lock(dto.getRecordTypeId(),List.of(),4001);
        lockOwner();
        var original=getBaseDao().lambdaQuery().eq(BookkeepingBudgetEntity::getId,dto.getId()).last("FOR UPDATE").one();
        if(original==null)throw new BusinessException("预算不存在或无权修改");
        if(dto.getRecordType()!=null)throw new BusinessException("请更新客户端后重试：预算分类须使用来源ID");
        dto.setBudgetType(original.getBudgetType());dto.setBudgetYear(original.getBudgetYear());dto.setBudgetMonth(original.getBudgetMonth());
        dto.setRecordTypeName(original.getRecordTypeName());
        if(dto.isRecordTypeIdPresent()&&!original.getBudgetType().isTotalBudgetType()){
            if(dto.getRecordTypeId()==null)throw new BusinessException("分类预算的分类不能为空");
            dto.setRecordTypeName(snapshots.lock(dto.getRecordTypeId(),List.of(),4001).get(dto.getRecordTypeId()).getDictName());
        }else dto.setRecordTypeId(original.getRecordTypeId());
        checkDuplicate(dto,dto.getId());
        if(dto.getPunishPoints()!=null)dto.setPunishPoints(-Math.abs(dto.getPunishPoints()));
        super.update(dto);
    }

    @Override
    public BookkeepingBudgetDetailVo detail(Integer integer) {
        BookkeepingBudgetDetailVo vo = super.detail(integer);
        if (vo.getPunishPoints() != null) {
            vo.setPunishPoints(-vo.getPunishPoints());
        }
        return vo;
    }

    @Override
    public BookkeepingBudgetStatisticsVo getTotalBudget(BudgetTypeEnum budgetType) {
        if (!budgetType.isTotalBudgetType()) {
            throw new BusinessException("查询类型不是总预算类型");
        }
        boolean isMonthBudgetType = budgetType.isMonthBudgetType();

        BookkeepingBudgetStatisticsVo vo = new BookkeepingBudgetStatisticsVo();

        BookkeepingBudgetEntity budgetEntity = getBaseDao().lambdaQuery()
                .eq(BookkeepingBudgetEntity::getBudgetType, budgetType)
                .eq(!isMonthBudgetType, BookkeepingBudgetEntity::getBudgetYear, LocalDate.now().getYear())
                .eq(isMonthBudgetType, BookkeepingBudgetEntity::getBudgetMonth, DateUtils.startDateOfNowMonth())
                .last(WebCommonConstants.LIMIT_ONE)
                .one();
        if (budgetEntity == null) {
            // 预算金额返回0,表示未设置预算
            vo.setBudgetAmount(BigDecimal.ZERO);
            return vo;
        }

        vo.setBudgetType(budgetType);
        vo.setId(budgetEntity.getId());
        vo.setBudgetAmount(budgetEntity.getBudgetAmount());

        // 查询总支出金额
        BookkeepingStatisticsDto dto = new BookkeepingStatisticsDto();
        dto.setRecordCategory(RecordCategoryEnum.CONSUME);
        dto.setQueryOnlyMyself(1);
        dto.setCurrentStartMonth(budgetType.equals(BudgetTypeEnum.MONTH) ? DateUtils.startDateOfNowMonth() : DateUtils.startDateOfNowYear());
        dto.setCurrentEndMonth(budgetType.equals(BudgetTypeEnum.MONTH) ? DateUtils.endDateOfNowMonth() : DateUtils.endDateOfNowYear());
        BookkeepingStatisticsTotalVo statisticsTotalVo = bookkeepingRecordsDao.getBaseMapper().totalStatistics(dto);
        if (statisticsTotalVo == null) {
            statisticsTotalVo = new BookkeepingStatisticsTotalVo();
        }
        if (statisticsTotalVo.getTotalAmount() == null) {
            statisticsTotalVo.setTotalAmount(BigDecimal.ZERO);
        }

        vo.setUsedAmount(statisticsTotalVo.getTotalAmount());
        vo.setRemainingAmount(budgetEntity.getBudgetAmount().subtract(statisticsTotalVo.getTotalAmount()));
        vo.setUsedRatio(vo.getRemainingAmount().compareTo(BigDecimal.ZERO) <= 0
                ? new BigDecimal("100")
                : statisticsTotalVo.getTotalAmount().compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : statisticsTotalVo.getTotalAmount().multiply(new BigDecimal("100")).divide(budgetEntity.getBudgetAmount(), 2, RoundingMode.HALF_UP));

        return vo;
    }

    @Override
    public List<BookkeepingBudgetStatisticsVo> getCategoryBudget(BudgetTypeEnum budgetType) {
        if (budgetType.isTotalBudgetType()) {
            throw new BusinessException("查询类型不是分类预算类型");
        }
        boolean isMonthBudgetType = budgetType.isMonthBudgetType();

        List<BookkeepingBudgetEntity> budgetEntityList = getBaseDao().lambdaQuery()
                .eq(BookkeepingBudgetEntity::getBudgetType, budgetType)
                .eq(isMonthBudgetType, BookkeepingBudgetEntity::getBudgetMonth, DateUtils.startDateOfNowMonth())
                .eq(!isMonthBudgetType, BookkeepingBudgetEntity::getBudgetYear, LocalDate.now().getYear())
                .list();

        if (CollUtil.isEmpty(budgetEntityList)) {
            return Collections.emptyList();
        }

        BookkeepingConsumeCategoryStatisticsDto dto = new BookkeepingConsumeCategoryStatisticsDto();
        dto.setRecordCategory(RecordCategoryEnum.CONSUME);
        dto.setQueryOnlyMyself(1);
        dto.setCurrentStartMonth(budgetType.equals(BudgetTypeEnum.MONTH_CATEGORY) ? DateUtils.startDateOfNowMonth() : DateUtils.startDateOfNowYear());
        dto.setCurrentEndMonth(budgetType.equals(BudgetTypeEnum.MONTH_CATEGORY) ? DateUtils.endDateOfNowMonth() : DateUtils.endDateOfNowYear());
        dto.setCategoryNames(budgetEntityList.stream().map(BookkeepingBudgetEntity::getRecordTypeName).filter(java.util.Objects::nonNull).toList());
        dto.setQueryOnlyMyself(1);
        Map<String, BigDecimal> recordTypeMap = bookkeepingRecordsDao.getBaseMapper().categoryStatistics(dto)
                .stream()
                .collect(Collectors.toMap(v -> java.util.Objects.toString(v.getRecordTypeName(), ""), BookkeepingConsumeStatisticsCategoryVo::getAmount));

        List<BookkeepingBudgetStatisticsVo> voList = new ArrayList<>();
        for (BookkeepingBudgetEntity budgetEntity : budgetEntityList) {
            BookkeepingBudgetStatisticsVo vo = new BookkeepingBudgetStatisticsVo();
            vo.setBudgetType(budgetType);
            vo.setId(budgetEntity.getId());
            vo.setRecordTypeId(budgetEntity.getRecordTypeId());
            vo.setRecordTypeName(budgetEntity.getRecordTypeName());
            vo.setBudgetAmount(budgetEntity.getBudgetAmount());
            vo.setUsedAmount(recordTypeMap.getOrDefault(budgetEntity.getRecordTypeName(), BigDecimal.ZERO));
            vo.setRemainingAmount(budgetEntity.getBudgetAmount().subtract(vo.getUsedAmount()));
            vo.setUsedRatio(vo.getRemainingAmount().compareTo(BigDecimal.ZERO) <= 0
                    ? new BigDecimal("100")
                    : vo.getUsedAmount().compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : vo.getUsedAmount().multiply(new BigDecimal("100")).divide(budgetEntity.getBudgetAmount(), 2, RoundingMode.HALF_UP));
            voList.add(vo);
        }

        return voList;
    }
}

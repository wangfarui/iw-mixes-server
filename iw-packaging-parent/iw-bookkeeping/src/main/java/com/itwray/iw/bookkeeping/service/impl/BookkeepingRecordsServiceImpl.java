package com.itwray.iw.bookkeeping.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.idev.excel.FastExcel;
import com.itwray.iw.auth.client.AuthUserClient;
import com.itwray.iw.auth.client.BaseDictClient;
import com.itwray.iw.auth.service.AuthFamilyGroupService;
import com.itwray.iw.auth.model.enums.ShareStateEnum;
import com.itwray.iw.auth.model.vo.FamilySharedSavePolicyVo;
import com.itwray.iw.auth.model.vo.DictListVo;
import com.itwray.iw.bookkeeping.dao.BookkeepingRecordsDao;
import com.itwray.iw.bookkeeping.support.BookkeepingExpenseFillSupport;
import com.itwray.iw.bookkeeping.support.BookkeepingPayerPolicy;
import com.itwray.iw.bookkeeping.support.BookkeepingWalletLedger;
import com.itwray.iw.auth.model.vo.FamilySharedQueryPolicyVo;
import com.itwray.iw.points.service.PointsRecordsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import com.itwray.iw.bookkeeping.excel.listener.BookkeepingRecordsImportDataListener;
import com.itwray.iw.bookkeeping.mapper.BookkeepingRecordsMapper;
import com.itwray.iw.bookkeeping.model.bo.BookkeepingRecordsImportBo;
import com.itwray.iw.bookkeeping.model.bo.RecordsStatisticsBo;
import com.itwray.iw.bookkeeping.model.dto.*;
import com.itwray.iw.bookkeeping.model.entity.BookkeepingBudgetEntity;
import com.itwray.iw.bookkeeping.model.entity.BookkeepingRecordsEntity;
import com.itwray.iw.bookkeeping.model.enums.RecordCategoryEnum;
import com.itwray.iw.bookkeeping.model.vo.*;
import com.itwray.iw.bookkeeping.model.vo.yearly.consume.*;
import com.itwray.iw.bookkeeping.model.vo.yearly.income.*;
import com.itwray.iw.bookkeeping.model.vo.yearly.overview.BookkeepingRecordsOverviewHabitsVo;
import com.itwray.iw.bookkeeping.model.vo.yearly.overview.BookkeepingRecordsOverviewMonthlyVo;
import com.itwray.iw.bookkeeping.model.vo.yearly.overview.BookkeepingRecordsOverviewSummaryVo;
import com.itwray.iw.bookkeeping.model.vo.yearly.overview.BookkeepingRecordsYearStatisticsOverviewVo;
import com.itwray.iw.bookkeeping.service.BookkeepingRecordsService;
import com.itwray.iw.common.constants.BoolEnum;
import com.itwray.iw.common.utils.DateUtils;
import com.itwray.iw.external.client.InternalApiClient;
import com.itwray.iw.external.model.dto.GetExchangeRateDto;
import com.itwray.iw.points.model.dto.PointsRecordsAddDto;
import com.itwray.iw.points.model.enums.PointsSourceTypeEnum;
import com.itwray.iw.points.model.enums.PointsTransactionTypeEnum;
import com.itwray.iw.starter.rocketmq.MQProducerHelper;
import com.itwray.iw.web.dao.BaseBusinessFileDao;
import com.itwray.iw.web.dao.BaseDictBusinessRelationDao;
import com.itwray.iw.web.dao.BaseDictDao;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.exception.IwWebException;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import com.itwray.iw.web.model.enums.BusinessFileTypeEnum;
import com.itwray.iw.web.model.enums.DictBusinessTypeEnum;
import com.itwray.iw.web.model.enums.DictTypeEnum;
import com.itwray.iw.web.model.enums.OrderNoEnum;
import com.itwray.iw.web.model.enums.mq.PointsRecordsTopicEnum;
import com.itwray.iw.web.model.vo.FileVo;
import com.itwray.iw.web.model.vo.PageVo;
import com.itwray.iw.web.service.impl.WebServiceImpl;
import com.itwray.iw.web.utils.OrderNoUtils;
import com.itwray.iw.web.utils.UserUtils;
import lombok.extern.slf4j.Slf4j;
import cn.hutool.core.collection.CollectionUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记录表 服务实现层
 *
 * @author wray
 * @since 2024/8/28
 */
@Service
@Slf4j
public class BookkeepingRecordsServiceImpl extends WebServiceImpl<BookkeepingRecordsDao, BookkeepingRecordsMapper, BookkeepingRecordsEntity,
        BookkeepingRecordAddDto, BookkeepingRecordUpdateDto, BookkeepingRecordDetailVo, Integer> implements BookkeepingRecordsService {

    private final BaseDictBusinessRelationDao baseDictBusinessRelationDao;
    @Autowired private com.itwray.iw.bookkeeping.support.BookkeepingDictionarySnapshots snapshots;
    @Autowired private com.itwray.iw.web.bookkeeping.BookkeepingDictionaryAccess dictionaries;

    private final BaseBusinessFileDao baseBusinessFileDao;

    private BaseDictDao baseDictDao;

    private InternalApiClient internalApiClient;

    private BaseDictClient baseDictClient;

    private AuthUserClient authUserClient;

    @Autowired
    private BookkeepingWalletLedger walletLedger;
    @Autowired
    private BookkeepingPayerPolicy payerPolicy;
    @Autowired
    private PointsRecordsService pointsRecordsService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthFamilyGroupService familyService;
    @Autowired
    private com.itwray.iw.points.bookkeeping.TaskBookkeepingSourceQuery taskSourceQuery;

    /** All balance writes use wallet then points, also on later edit/delete. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Integer addFromTask(BookkeepingRecordAddDto dto, LocalDateTime completedAt, Integer groupId) {
        BookkeepingRecordsEntity entity = this.buildBookkeepingRecordAddDto(dto);
        var tagSnapshots = snapshots.prepareNew(entity, dto);
        entity.setRecordDate(completedAt.toLocalDate());
        entity.setRecordTime(completedAt);
        entity.setGroupId(groupId);
        entity.setShareState(Objects.equals(dto.getShared(), 1) ? ShareStateEnum.SHARED : ShareStateEnum.NOT_SHARED);
        entity.setOrderNo(OrderNoUtils.getAndIncrement(OrderNoEnum.BOOKKEEPING_RECORDS));
        entity.setUserId(UserUtils.getUserId());
        entity.setExpenseUserId(RecordCategoryEnum.CONSUME.equals(dto.getRecordCategory()) ? UserUtils.getUserId() : null);
        if (!getBaseDao().save(entity)) throw new BusinessException("记账保存失败，请重试");
        snapshots.replace(entity.getId(), tagSnapshots);
        walletLedger.apply(null, entity);
        if (RecordCategoryEnum.INCOME.equals(dto.getRecordCategory()) && Objects.equals(dto.getIsExcitationRecord(), 1)) {
            syncExcitationInTransaction(entity.getOrderNo(), 1);
        }
        return entity.getId();
    }

    private boolean isTaskRecord(Integer id) {
        Integer count = jdbc.queryForObject("select count(*) from points_task_bookkeeping where bookkeeping_record_id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    private void syncExcitationInTransaction(String orderNo, int points) {
        PointsRecordsAddDto dto = new PointsRecordsAddDto();
        dto.setPoints(points);
        dto.setSource((points > 0 ? "记账收入: " : "记账收入被撤回: ") + orderNo);
        dto.setSourceType(PointsSourceTypeEnum.BOOKKEEPING.getCode());
        dto.setUserId(UserUtils.getUserId());
        pointsRecordsService.add(dto);
    }

    @Autowired
    public BookkeepingRecordsServiceImpl(BookkeepingRecordsDao baseDao,
                                         BaseDictBusinessRelationDao baseDictBusinessRelationDao,
                                         BaseBusinessFileDao baseBusinessFileDao) {
        super(baseDao);
        this.baseDictBusinessRelationDao = baseDictBusinessRelationDao;
        this.baseBusinessFileDao = baseBusinessFileDao;
    }

    @Autowired
    public void setBaseDictDao(BaseDictDao baseDictDao) {
        this.baseDictDao = baseDictDao;
    }

    @Autowired
    public void setInternalApiClient(InternalApiClient internalApiClient) {
        this.internalApiClient = internalApiClient;
    }

    @Autowired
    public void setBaseDictClient(BaseDictClient baseDictClient) {
        this.baseDictClient = baseDictClient;
    }

    @Autowired
    public void setAuthUserClient(AuthUserClient authUserClient) {
        this.authUserClient = authUserClient;
    }

    @Override
    @Transactional
    public Integer add(BookkeepingRecordAddDto dto) {
        BookkeepingRecordsEntity bookkeepingRecords = this.buildBookkeepingRecordAddDto(dto);
        var tagSnapshots = snapshots.prepareNew(bookkeepingRecords, dto);
        Integer userId = UserUtils.getUserId();
        FamilySharedSavePolicyVo sharedSavePolicy = familyService.querySharedSavePolicy(userId);
        bookkeepingRecords.setGroupId(sharedSavePolicy.getCurrentGroupId());
        bookkeepingRecords.setShareState(this.resolveRecordShareState(sharedSavePolicy.getCurrentGroupId(), sharedSavePolicy, dto.getShared()));

        payerPolicy.apply(bookkeepingRecords, dto.getExpenseUserId(), null, sharedSavePolicy, false);

        // 生成订单号
        bookkeepingRecords.setOrderNo(OrderNoUtils.getAndIncrement(OrderNoEnum.BOOKKEEPING_RECORDS));

        // 保存记账记录
        if (!getBaseDao().save(bookkeepingRecords)) throw new BusinessException("记账保存失败，请重试");

        // 保存标签
        snapshots.replace(bookkeepingRecords.getId(), tagSnapshots);

        // 保存记账附件
        baseBusinessFileDao.saveBusinessFile(bookkeepingRecords.getId(), BusinessFileTypeEnum.BOOKKEEPING_RECORDS, dto.getFileList());

        // 记录为激励收入时，积分+1
        if (RecordCategoryEnum.INCOME.equals(dto.getRecordCategory())
                && BoolEnum.TRUE.getCode().equals(dto.getIsExcitationRecord())) {
            this.addPointsRecordsByExcitation(bookkeepingRecords.getOrderNo());
        }

        // 同步用户钱包余额
        walletLedger.apply(null, bookkeepingRecords);

        return bookkeepingRecords.getId();
    }

    @Override
    @Transactional
    public void update(BookkeepingRecordUpdateDto dto) {
        taskSourceQuery.lockTaskForRecord(dto.getId());
        BookkeepingRecordsEntity bookkeepingRecordsEntity = this.queryEditableRecord(dto.getId(), "修改");
        boolean taskRecord = isTaskRecord(dto.getId());
        if (!bookkeepingRecordsEntity.getRecordCategory().equals(dto.getRecordCategory())) {
            throw new BusinessException("不支持修改记账记录类型操作");
        }

        // 修改标签


        // 保存记账附件
        baseBusinessFileDao.saveBusinessFile(dto.getId(), BusinessFileTypeEnum.BOOKKEEPING_RECORDS, dto.getFileList());

        // 记账记录类型为收入类型时
        if (RecordCategoryEnum.INCOME.equals(dto.getRecordCategory())) {
            // 如果修改了激励记录状态, 则同步积分数据
            if (!taskRecord && !bookkeepingRecordsEntity.getIsExcitationRecord().equals(dto.getIsExcitationRecord())) {
                if (BoolEnum.TRUE.getCode().equals(dto.getIsExcitationRecord())) {
                    this.addPointsRecordsByExcitation(bookkeepingRecordsEntity.getOrderNo());
                } else {
                    this.deductPointsRecordsByExcitation(bookkeepingRecordsEntity.getOrderNo());
                }
            }
        }

        BookkeepingRecordsEntity recordsEntity = this.buildBookkeepingRecordAddDto(dto);
        var tagSnapshots = snapshots.prepareUpdate(recordsEntity, bookkeepingRecordsEntity, dto.getDictionaryChanges(), dto);
        FamilySharedSavePolicyVo sharedSavePolicy = familyService.querySharedSavePolicy(bookkeepingRecordsEntity.getUserId());
        recordsEntity.setGroupId(bookkeepingRecordsEntity.getGroupId());
        // Missing update fields preserve the saved choice; defaults apply only to newly created records.
        Integer requestedShared = dto.getShared() != null ? dto.getShared() : resolveSharedCode(bookkeepingRecordsEntity.getShareState());
        recordsEntity.setShareState(this.resolveRecordShareState(bookkeepingRecordsEntity.getGroupId(), sharedSavePolicy, requestedShared));
        payerPolicy.apply(recordsEntity, dto.getExpenseUserId(), bookkeepingRecordsEntity, sharedSavePolicy, taskRecord);
        if (!getBaseDao().updateById(recordsEntity)) throw new BusinessException("记账更新失败，请重试");

        if (dto.getDictionaryChanges() != null && dto.getDictionaryChanges().isTagsPresent()) snapshots.replace(dto.getId(), tagSnapshots);
        walletLedger.apply(bookkeepingRecordsEntity, recordsEntity);
        if (taskRecord && RecordCategoryEnum.INCOME.equals(dto.getRecordCategory())
                && !Objects.equals(bookkeepingRecordsEntity.getIsExcitationRecord(), dto.getIsExcitationRecord())) {
            syncExcitationInTransaction(bookkeepingRecordsEntity.getOrderNo(), Objects.equals(dto.getIsExcitationRecord(), 1) ? 1 : -1);
        }
    }

    private BookkeepingRecordsEntity buildBookkeepingRecordAddDto(BookkeepingRecordAddDto dto) {
        BookkeepingRecordsEntity bookkeepingRecords = BeanUtil.copyProperties(dto, BookkeepingRecordsEntity.class);

        // 记录日期为空是默认取当前时间
        if (bookkeepingRecords.getRecordDate() == null) {
            bookkeepingRecords.setRecordDate(LocalDate.now());
            bookkeepingRecords.setRecordTime(LocalDateTime.now());
        } else {
            // 日期取指定日期，时间取当前时间
            bookkeepingRecords.setRecordTime(bookkeepingRecords.getRecordDate().atTime(LocalTime.now()));
        }

        // 如果货币类型不为空，则转换货币
        if (StringUtils.isNotBlank(dto.getFromCurrency())) {
            GetExchangeRateDto exchangeRateDto = new GetExchangeRateDto();
            exchangeRateDto.setFromCurrency(dto.getFromCurrency());
            exchangeRateDto.setToCurrency("CNY");
            exchangeRateDto.setQueryDate(dto.getRecordDate());
            exchangeRateDto.setFromAmount(dto.getAmount());
            Object exchangeRateVo = internalApiClient.getExchangeRate(exchangeRateDto);
            if (exchangeRateVo != null) {
                if (exchangeRateVo instanceof Map map) {
                    bookkeepingRecords.setAmount(new BigDecimal(map.get("toAmount").toString()).setScale(2, RoundingMode.HALF_UP));
                }
            }
        }

        if (bookkeepingRecords.getAmount() == null || bookkeepingRecords.getAmount().signum() <= 0
                || bookkeepingRecords.getAmount().compareTo(new BigDecimal("999999.99")) > 0
                || bookkeepingRecords.getAmount().stripTrailingZeros().scale() > 2) {
            throw new BusinessException("请输入大于0且最多两位小数的有效金额");
        }
        return bookkeepingRecords;
    }

    @Override
    @Transactional
    public void delete(Integer id) {
        taskSourceQuery.lockTaskForRecord(id);
        BookkeepingRecordsEntity bookkeepingRecordsEntity = this.queryEditableRecord(id, "删除");
        boolean taskRecord = isTaskRecord(id);
        if (!getBaseDao().removeById(id)) throw new BusinessException("记账删除失败，请重试");

        // 删除标签
        // Snapshot rows remain available for provenance after logical bill deletion.

        // 删除记账附件
        baseBusinessFileDao.removeBusinessFile(id, BusinessFileTypeEnum.BOOKKEEPING_RECORDS);

        // 同步积分数据
        walletLedger.apply(bookkeepingRecordsEntity, null);
        if (RecordCategoryEnum.INCOME.equals(bookkeepingRecordsEntity.getRecordCategory())
                && BoolEnum.TRUE.getCode().equals(bookkeepingRecordsEntity.getIsExcitationRecord())) {
            if (taskRecord) syncExcitationInTransaction(bookkeepingRecordsEntity.getOrderNo(), -1);
            else this.deductPointsRecordsByExcitation(bookkeepingRecordsEntity.getOrderNo());
        }

        // 同步用户钱包余额

    }

    @Override
    public BookkeepingRecordDetailVo detail(Integer id) {
        BookkeepingRecordsEntity bookkeepingRecordsEntity = this.queryRecordByIdIgnorePermission(id);
        Integer userId = UserUtils.getUserId();
        if (bookkeepingRecordsEntity == null) throw new BusinessException("记账记录不存在或无权查看");
        boolean participant = Objects.equals(userId, bookkeepingRecordsEntity.getUserId())
                || (RecordCategoryEnum.CONSUME.equals(bookkeepingRecordsEntity.getRecordCategory())
                    && Objects.equals(userId, bookkeepingRecordsEntity.getExpenseUserId()));
        if (!participant) {
            FamilySharedQueryPolicyVo policy = familyService.querySharedQueryPolicy(userId);
            if (Objects.equals(policy.getForceQueryOnlyMyself(), 1)
                    || !ShareStateEnum.SHARED.equals(bookkeepingRecordsEntity.getShareState())
                    || policy.getCurrentGroupId() == null || policy.getCurrentGroupId() <= 0
                    || !Objects.equals(policy.getCurrentGroupId(), bookkeepingRecordsEntity.getGroupId())) {
                throw new BusinessException("记账记录不存在或无权查看");
            }
        }
        BookkeepingRecordDetailVo vo = BeanUtil.copyProperties(bookkeepingRecordsEntity, BookkeepingRecordDetailVo.class);
        vo.setTaskSource(taskSourceQuery.query(id));
        vo.setCanChangeExpenseUser(vo.getTaskSource() == null
                && !ShareStateEnum.LEFT_GROUP.equals(bookkeepingRecordsEntity.getShareState()));

        // 查询标签
        var savedTags = snapshots.tags(id);
        vo.setRecordTagSnapshots(savedTags);
        List<Integer> tagIdList = savedTags.stream().map(com.itwray.iw.web.bookkeeping.DictionaryTagSnapshot::getId).toList();
        vo.setRecordTags(tagIdList);

        // 查询记账附件
        List<FileVo> fileVoList = baseBusinessFileDao.getBusinessFile(id, BusinessFileTypeEnum.BOOKKEEPING_RECORDS);
        vo.setFileList(fileVoList);
        if (bookkeepingRecordsEntity != null) {
            vo.setShared(this.resolveSharedCode(bookkeepingRecordsEntity.getShareState()));
        }
        BookkeepingExpenseFillSupport.fill(Collections.singletonList(vo));

        return vo;
    }

    @Override
    public PageVo<BookkeepingRecordPageVo> page(BookkeepingRecordPageDto dto) {
        this.processBookkeepingRecordPageDto(dto);

        PageVo<BookkeepingRecordPageVo> pageVo = new PageVo<>(dto);
        getBaseDao().getBaseMapper().page(pageVo, dto);

        LocalDate now = LocalDate.now();
        int nowYear = now.getYear();
        DateTimeFormatter oldYearFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        DateTimeFormatter nowYearFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");
        pageVo.getRecords().forEach(t -> {
            // 格式化记账日期
            LocalDate localDate = t.getRecordTime().toLocalDate();
            if (now.equals(localDate)) {
                t.setRecordTimeStr("今天 " + t.getRecordTime().toLocalTime().format(timeFormatter));
            } else if (now.equals(localDate.plusDays(1))) {
                t.setRecordTimeStr("昨天 " + t.getRecordTime().toLocalTime().format(timeFormatter));
            } else if (nowYear == localDate.getYear()) {
                t.setRecordTimeStr(t.getRecordTime().format(nowYearFormatter));
            } else {
                t.setRecordTimeStr(t.getRecordTime().format(oldYearFormatter));
            }
        });
        pageVo.getRecords().forEach(vo -> vo.setRecordTagSnapshots(snapshots.tags(vo.getId())));
        BookkeepingExpenseFillSupport.fill(pageVo.getRecords());

        return pageVo;
    }

    @Override
    public List<BookkeepingRecordPageVo> list(BookkeepingRecordListDto dto) {
        // 记账日期为空时，默认查当天
        if (dto.getRecordDate() == null) {
            dto.setRecordDate(LocalDate.now());
        }
        List<BookkeepingRecordPageVo> recordList = getBaseDao().lambdaQuery()
                .eq(BookkeepingRecordsEntity::getRecordDate, dto.getRecordDate())
                .orderByDesc(BookkeepingRecordsEntity::getId)
                .list()
                .stream()
                .map(t -> BeanUtil.copyProperties(t, BookkeepingRecordPageVo.class))
                .collect(Collectors.toList());
        recordList.forEach(vo -> vo.setRecordTagSnapshots(snapshots.tags(vo.getId())));
        BookkeepingExpenseFillSupport.fill(recordList);
        return recordList;
    }

    @Override
    public Map<String,List<Map<String,String>>> filterOptions(BookkeepingRecordPageDto dto) {
        dto.setCategoryState(null);dto.setCategoryNames(null);dto.setTagNames(null);dto.setRecordType(null);dto.setTagIdList(null);
        processBookkeepingRecordPageDto(dto);
        Set<String> categories=new TreeSet<>(),expenseTags=new TreeSet<>(),incomeTags=new TreeSet<>();
        for(var row:getBaseDao().getBaseMapper().historyForFilters(dto)) {
            if(Objects.equals(row.getDictionarySnapshotStatus(),1)&&row.getRecordTypeName()!=null)categories.add(row.getRecordTypeName());
            for(var tag:snapshots.tags(row.getId()))(tag.getType()==4001?expenseTags:incomeTags).add(tag.getName());
        }
        Map<String,List<Map<String,String>>> result=new LinkedHashMap<>();
        result.put("categories",categories.stream().map(n->Map.of("name",n)).toList());
        result.put("expenseTags",expenseTags.stream().map(n->Map.of("name",n)).toList());
        result.put("incomeTags",incomeTags.stream().map(n->Map.of("name",n)).toList());return result;
    }
    @Override
    public List<Map<String,Object>> dictionarySources(Integer id) {
        BookkeepingRecordDetailVo bill=detail(id); // Authorization precedes every source lookup.
        List<com.itwray.iw.web.bookkeeping.DictionaryTagSnapshot> refs=new ArrayList<>(bill.getRecordTagSnapshots());
        if(bill.getRecordTypeId()!=null)refs.add(new com.itwray.iw.web.bookkeeping.DictionaryTagSnapshot(bill.getRecordTypeId(),4002,bill.getRecordTypeName()));
        var sourceMap=dictionaries.sources(refs.stream().map(com.itwray.iw.web.bookkeeping.DictionaryTagSnapshot::getId).toList());
        List<Map<String,Object>> result=new ArrayList<>();
        for(var ref:refs){
            BaseDictEntity source=sourceMap.stream().filter(d->Objects.equals(d.getId(),ref.getId())).findFirst().orElse(null);
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",ref.getId());item.put("type",ref.getType());item.put("name",ref.getName());item.put("sourceExists",source!=null);
            item.put("scope",source==null?null:Objects.equals(source.getScopeType(),1)?"FAMILY":"PERSONAL");
            item.put("selectable",dictionaries.options(UserUtils.getUserId(),ref.getType()).stream().anyMatch(d->Objects.equals(d.getId(),ref.getId())));result.add(item);
        }return result;
    }

    private void processBookkeepingRecordPageDto(BookkeepingRecordPageDto dto) {
        com.itwray.iw.bookkeeping.support.BookkeepingCategoryFilter.validate(dto.getCategoryNames(),dto.getCategoryState());
        if (dto.getRecordStartDate() == null) {
            if (dto.getRecordEndDate() != null) {
                dto.setRecordStartDate(DateUtils.startDateOfMonth(dto.getRecordEndDate()));
            }
        }
        if (dto.getRecordEndDate() == null) {
            if (dto.getRecordStartDate() != null) {
                dto.setRecordEndDate(DateUtils.endDateOfMonth(dto.getRecordStartDate()));
            }
        }
        if(dto.getRecordType()!=null || CollUtil.isNotEmpty(dto.getTagIdList())) throw new BusinessException("请更新客户端后重试：历史筛选须使用名称");
    }

    @Override
    public BookkeepingRecordsStatisticsVo statistics(BookkeepingRecordsStatisticsDto dto) {
        if (dto.getRecordStartDate() == null && dto.getRecordEndDate() == null) {
            dto.setRecordStartDate(DateUtils.startDateOfNowMonth());
            dto.setRecordEndDate(DateUtils.endDateOfNowMonth());
        }
        this.processBookkeepingRecordPageDto(dto);

        // 查询记录类型对应的总金额
        Map<Integer, BigDecimal> statisticsMap = getBaseDao().getBaseMapper().statistics(dto)
                .stream()
                .collect(Collectors.toMap(RecordsStatisticsBo::getRecordCategory, RecordsStatisticsBo::getTotalAmount));

        BookkeepingRecordsStatisticsVo statisticsVo = new BookkeepingRecordsStatisticsVo();
        // 消费金额
        statisticsVo.setConsume(statisticsMap.getOrDefault(RecordCategoryEnum.CONSUME.getCode(), BigDecimal.ZERO));
        // 收入金额
        statisticsVo.setIncome(statisticsMap.getOrDefault(RecordCategoryEnum.INCOME.getCode(), BigDecimal.ZERO));
        return statisticsVo;
    }

    @Override
    @Transactional
    public void importRecords(MultipartFile file) {
        Integer userId = UserUtils.getUserId();
        FamilySharedSavePolicyVo sharedSavePolicy = familyService.querySharedSavePolicy(userId);
        Integer currentGroupId = sharedSavePolicy.getCurrentGroupId();
        ShareStateEnum defaultShareState = this.resolveRecordShareState(currentGroupId, sharedSavePolicy, null);
        // 查询当前用户的记账-记录分类字典项
        List<BaseDictEntity> dictEntityList = dictionaries.options(userId,4002);
        Map<String, Integer> dictNameMap = dictEntityList.stream().collect(Collectors.toMap(BaseDictEntity::getDictName, BaseDictEntity::getId));
        BookkeepingRecordsImportDataListener listener = new BookkeepingRecordsImportDataListener(userId, currentGroupId, defaultShareState, dictNameMap);
        try {
            FastExcel.read(file.getInputStream(), BookkeepingRecordsImportBo.class, listener)
                    .sheet()
                    .doRead();
        } catch (IOException e) {
            log.error("importRecords 导入记账记录异常", e);
            throw new IwWebException(e);
        }
    }

    @Override
    public void processImportData(BookkeepingRecordsImportBo bo, Map<String, Integer> dictNameMap) {
        if (bo == null) {
            return;
        }
        if (bo.getRecordCategory() == null) {
            return;
        }
        String categoryName=bo.getRecordTypeDesc();
        Integer categoryId=null;
        if(categoryName!=null && !categoryName.isBlank()) {
            categoryName=com.itwray.iw.web.bookkeeping.DictionaryNames.normalize(categoryName);
            categoryId=dictNameMap.get(categoryName);
            if(categoryId==null)throw new BusinessException("导入分类不存在："+categoryName+"，请先配置；记录时间："+bo.getRecordTime());
        }
        BookkeepingRecordsEntity recordsEntity = new BookkeepingRecordsEntity();
        recordsEntity.setOrderNo(OrderNoUtils.getAndIncrement(OrderNoEnum.BOOKKEEPING_RECORDS));
        recordsEntity.setRecordDate(bo.getRecordTime().toLocalDate());
        recordsEntity.setRecordTime(bo.getRecordTime());
        recordsEntity.setRecordCategory(bo.getRecordCategory());
        boolean isFillRemark = true;
        if (bo.getRemark() == null) {
            recordsEntity.setRecordSource("消费");
        } else if (bo.getRemark().length() < 50) {
            recordsEntity.setRecordSource(bo.getRemark());
            isFillRemark = false;
        } else {
            recordsEntity.setRecordSource(bo.getRemark().substring(0, 50));
        }
        recordsEntity.setAmount(bo.getAmount());
        BookkeepingRecordAddDto sourceDto=new BookkeepingRecordAddDto();
        sourceDto.setRecordTypeId(categoryId);
        var importedTags=snapshots.prepareNew(recordsEntity,sourceDto);
        if (isFillRemark && bo.getRemark()!=null) {
            recordsEntity.setRemark(bo.getRemark().length() < 255 ? bo.getRemark() : bo.getRemark().substring(0, 255));
        }
        recordsEntity.setUserId(bo.getUserId());
        recordsEntity.setExpenseUserId(RecordCategoryEnum.CONSUME.equals(bo.getRecordCategory()) ? bo.getUserId() : null);
        recordsEntity.setGroupId(Optional.ofNullable(bo.getGroupId()).orElse(0));
        recordsEntity.setShareState(Optional.ofNullable(bo.getShareState()).orElse(ShareStateEnum.NOT_SHARED));
        if(!getBaseDao().save(recordsEntity))throw new BusinessException("导入保存失败");
        snapshots.replace(recordsEntity.getId(),importedTags);
        // Import retains its existing balance policy; this change only adds trusted dictionary facts.
    }

    @Override
    public void syncBookkeepingPointsByBudget(List<BookkeepingBudgetEntity> monthBudgetList) {
        if (CollectionUtil.isEmpty(monthBudgetList)) {
            return;
        }
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("[yyyy年MM月]");
        // 根据用户维度, 统计每个用户不同记账分类下的支出统计
        Map<Integer, List<BookkeepingBudgetEntity>> userBudgetMap = monthBudgetList.stream()
                .collect(Collectors.groupingBy(BookkeepingBudgetEntity::getUserId));
        for (Map.Entry<Integer, List<BookkeepingBudgetEntity>> entry : userBudgetMap.entrySet()) {
            Integer userId = entry.getKey();
            String userToken = authUserClient.genericUserToken(userId);
            try {
                UserUtils.setUserId(userId);
                UserUtils.setToken(userToken);
                for (BookkeepingBudgetEntity budgetEntity : entry.getValue()) {
                    BookkeepingRecordsStatisticsDto statisticsDto = new BookkeepingRecordsStatisticsDto();
                    statisticsDto.setRecordStartDate(DateUtils.startDateOfMonth(budgetEntity.getBudgetMonth()));
                    statisticsDto.setRecordEndDate(DateUtils.endDateOfMonth(budgetEntity.getBudgetMonth()));
                    statisticsDto.setCategoryNames(java.util.List.of(budgetEntity.getRecordTypeName()));
                    statisticsDto.setQueryOnlyMyself(BoolEnum.TRUE.getCode());
                    // 统计预算所在月份下, 指定记账分类的实际支出情况
                    BookkeepingRecordsStatisticsVo statisticsVo = this.statistics(statisticsDto);
                    // 判断是否满足预算
                    boolean stayBudget = statisticsVo.getConsume().compareTo(budgetEntity.getBudgetAmount()) <= 0;
                    // 根据预算结果确定积分变动数量
                    Integer points = stayBudget ? budgetEntity.getRewardPoints() : budgetEntity.getPunishPoints();
                    PointsRecordsAddDto pointsRecordsAddDto = new PointsRecordsAddDto();
                    pointsRecordsAddDto.setTransactionType(PointsTransactionTypeEnum.getCodeByPoints(points));
                    pointsRecordsAddDto.setPoints(points);
                    pointsRecordsAddDto.setSource(
                            budgetEntity.getBudgetMonth().format(dateTimeFormatter) + "\"" +
                                    budgetEntity.getRecordTypeName() + "\"" +
                                    (stayBudget ? "符合预算" : "超出预算")
                    );
                    pointsRecordsAddDto.setSourceType(PointsSourceTypeEnum.BOOKKEEPING_BUDGET_MONTH.getCode());
                    pointsRecordsAddDto.setUserId(UserUtils.getUserId());
                    MQProducerHelper.send(PointsRecordsTopicEnum.BOOKKEEPING_SERVICE, pointsRecordsAddDto);
                }
            } finally {
                UserUtils.removeUserId();
                UserUtils.removeUserToken();
            }
        }
    }

    @Override
    public BookkeepingRecordsYearStatisticsOverviewVo yearStatisticsOverview(BookkeepingRecordsYearStatisticsQueryDto dto) {
        return this.queryYearStatisticsOverview(dto);
    }

    private BookkeepingRecordsYearStatisticsOverviewVo queryYearStatisticsOverview(BookkeepingRecordsYearStatisticsQueryDto dto) {
        this.computeYearStatisticsParam(dto);
        dto.setRecordCategories(new HashSet<>(Arrays.asList(RecordCategoryEnum.CONSUME, RecordCategoryEnum.INCOME)));

        BookkeepingRecordsYearStatisticsOverviewVo overviewVo = new BookkeepingRecordsYearStatisticsOverviewVo();

        // 查询年度总览-汇总数据
        BookkeepingRecordsOverviewSummaryVo summaryVo = getBaseDao().queryYearlyOverviewSummary(dto);
        overviewVo.setYearStatistics(summaryVo);

        // 查询年度总览-月度趋势数据
        BookkeepingRecordsOverviewMonthlyVo monthlyVo = getBaseDao().queryYearlyOverviewMonthly(dto);
        overviewVo.setMonthlyData(monthlyVo);

        // 查询年度总览-记账习惯数据
        BookkeepingRecordsOverviewHabitsVo habitsVo = BookkeepingRecordsOverviewHabitsVo.empty();
        // 一年内的记账天数
        Integer recordingDays = getBaseDao().getStatisticsMapper().statisticsRecordingDays(dto);
        habitsVo.setRecordingDays(recordingDays == null ? 0 : recordingDays);
        // 连续记账最长天数
        BookkeepingRecordsOverviewHabitsVo maxContinuousDaysVo = getBaseDao().getStatisticsMapper().statisticsMaxContinuousDays(dto);
        if (maxContinuousDaysVo != null) {
            habitsVo.setMaxContinuousDays(maxContinuousDaysVo.getMaxContinuousDays());
            habitsVo.setMaxContinuousStartDate(maxContinuousDaysVo.getMaxContinuousStartDate());
            habitsVo.setMaxContinuousEndDate(maxContinuousDaysVo.getMaxContinuousEndDate());
        }
        // 记账次数最多的月份
        BookkeepingRecordsOverviewHabitsVo peakMonthVo = getBaseDao().getStatisticsMapper().statisticsPeakMonth(dto);
        if (peakMonthVo != null) {
            habitsVo.setPeakMonth(peakMonthVo.getPeakMonth() + "月");
            habitsVo.setPeakCount(peakMonthVo.getPeakCount());
        }
        // 遗漏次数、平均每天记账次数、文案
        Integer missingCount = getBaseDao().getStatisticsMapper().statisticsMissingCount(dto);
        habitsVo.setMissingCount(missingCount == null ? 0 : missingCount);
        Long recordingCount = getBaseDao().lambdaQuery()
                .eq(BookkeepingRecordsEntity::getUserId, UserUtils.getUserId())
                .between(BookkeepingRecordsEntity::getRecordDate, dto.getStartDate(), dto.getEndDate())
                .eq(dto.getIgnoreNotStatistics() != null && dto.getIgnoreNotStatistics() == 0, BookkeepingRecordsEntity::getIsStatistics, 1)
                .count();
        if (habitsVo.getRecordingDays() == 0 || habitsVo.getMissingCount() == 0 || recordingCount == 0) {
            habitsVo.setMissingRate(BigDecimal.ZERO);
        } else {
            habitsVo.setMissingRate(new BigDecimal(habitsVo.getMissingCount() * 100).divide(new BigDecimal(recordingCount), 2, RoundingMode.HALF_UP));
        }
        habitsVo.setRecordingCount(recordingCount);
        if (recordingCount == 0) {
            habitsVo.setAvgPerDay(BigDecimal.ZERO);
        } else {
            habitsVo.setAvgPerDay(new BigDecimal(recordingCount).divide(new BigDecimal("365"), 2, RoundingMode.HALF_UP));
        }
        if (habitsVo.getMaxContinuousDays() == 0) {
            habitsVo.setEvaluation("一天天的，帐都不记，行不行啊小老弟❓");
        } else {
            habitsVo.setEvaluation(String.format("坚持记账的好习惯！连续记账超过%d天是值得表扬的成就。保持这个节奏，你会更好地掌握自己的财务状况。💪", habitsVo.getMaxContinuousDays()));
        }
        overviewVo.setRecordingHabits(habitsVo);

        return overviewVo;
    }

    @Override
    public BookkeepingRecordsYearStatisticsConsumeVo yearStatisticsConsume(BookkeepingRecordsYearStatisticsQueryDto dto) {
        return this.queryYearStatisticsConsume(dto);
    }

    private BookkeepingRecordsYearStatisticsConsumeVo queryYearStatisticsConsume(BookkeepingRecordsYearStatisticsQueryDto dto) {
        this.computeYearStatisticsParam(dto);
        dto.setRecordCategories(new HashSet<>(Collections.singleton(RecordCategoryEnum.CONSUME)));

        BookkeepingRecordsYearStatisticsConsumeVo consumeVo = new BookkeepingRecordsYearStatisticsConsumeVo();

        // 查询年度支出-汇总数据
        BookkeepingRecordsOverviewSummaryVo overviewSummaryVo = getBaseDao().queryYearlyOverviewSummary(dto);
        BookkeepingRecordsConsumeSummaryVo consumeSummaryVo = new BookkeepingRecordsConsumeSummaryVo();
        consumeSummaryVo.setTotalConsume(overviewSummaryVo.getTotalConsume());
        consumeSummaryVo.setConsumeCount(overviewSummaryVo.getConsumeCount());
        consumeVo.setYearStatistics(consumeSummaryVo);

        // 查询年度支出-月度趋势
        BookkeepingRecordsOverviewMonthlyVo monthlyVo = getBaseDao().queryYearlyOverviewMonthly(dto);
        consumeVo.setMonthlyData(monthlyVo.getConsumeTrendData());

        // 查询年度支出-支出分类占比
        BookkeepingConsumeCategoryStatisticsDto categoryStatisticsDto = new BookkeepingConsumeCategoryStatisticsDto();
        categoryStatisticsDto.setRecordCategory(RecordCategoryEnum.CONSUME);
        categoryStatisticsDto.setCurrentStartMonth(dto.getStartDate());
        categoryStatisticsDto.setCurrentEndMonth(dto.getEndDate());
        categoryStatisticsDto.setIsSearchAll(dto.getIgnoreNotStatistics());
        categoryStatisticsDto.setQueryOnlyMyself(dto.getQueryOnlyMyself());
        List<BookkeepingConsumeStatisticsCategoryVo> statisticsCategoryVos = getBaseDao().getBaseMapper().categoryStatistics(categoryStatisticsDto);
        if (CollectionUtil.isNotEmpty(statisticsCategoryVos)) {
            BigDecimal totalAmount = statisticsCategoryVos.stream().map(BookkeepingConsumeStatisticsCategoryVo::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            List<BookkeepingRecordsConsumeCategoriesVo> categoryVoList = new ArrayList<>();
            for (BookkeepingConsumeStatisticsCategoryVo statisticsCategoryVo : statisticsCategoryVos) {
                BookkeepingRecordsConsumeCategoriesVo categoriesVo = new BookkeepingRecordsConsumeCategoriesVo();
                categoriesVo.setName(snapshotLabel(statisticsCategoryVo.getRecordTypeName(), statisticsCategoryVo.getDictionarySnapshotStatus()));
                categoriesVo.setAmount(statisticsCategoryVo.getAmount());
                categoriesVo.setRatio(totalAmount.signum()==0?BigDecimal.ZERO:statisticsCategoryVo.getAmount().multiply(new BigDecimal("100")).divide(totalAmount, 2, RoundingMode.HALF_UP));
                categoryVoList.add(categoriesVo);
            }
            consumeVo.setConsumeCategories(categoryVoList);
        }

        // 查询年度支出-支出标签占比
        List<BookkeepingRecordsConsumeTagsVo> consumeTagsVos = getBaseDao().getStatisticsMapper().statisticsTagConsume(dto);
        if (CollectionUtil.isNotEmpty(consumeTagsVos)) {
            BigDecimal totalAmount = consumeTagsVos.stream().map(BookkeepingRecordsConsumeTagsVo::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            int totalCount = consumeTagsVos.stream().map(BookkeepingRecordsConsumeTagsVo::getCount).reduce(0, Integer::sum);

            for (BookkeepingRecordsConsumeTagsVo consumeTagsVo : consumeTagsVos) {
                consumeTagsVo.setRatio(totalCount==0?BigDecimal.ZERO:new BigDecimal(consumeTagsVo.getCount() * 100).divide(new BigDecimal(totalCount), 2, RoundingMode.HALF_UP));
                consumeTagsVo.setAmountRatio(totalAmount.signum()==0?BigDecimal.ZERO:consumeTagsVo.getAmount().multiply(new BigDecimal("100")).divide(totalAmount, 2, RoundingMode.HALF_UP));
            }
        }
        consumeVo.setConsumeTags(consumeTagsVos);

        // 查询年度支出-Top10
        BookkeepingStatisticsDto statisticsDto = new BookkeepingStatisticsDto();
        statisticsDto.setRecordCategory(RecordCategoryEnum.CONSUME);
        statisticsDto.setCurrentStartMonth(dto.getStartDate());
        statisticsDto.setCurrentEndMonth(dto.getEndDate());
        statisticsDto.setIsSearchAll(dto.getIgnoreNotStatistics());
        statisticsDto.setQueryOnlyMyself(dto.getQueryOnlyMyself());
        List<BookkeepingStatisticsRankVo> rankVoList = getBaseDao().getBaseMapper().rankStatistics(statisticsDto);
        if (CollectionUtil.isNotEmpty(rankVoList)) {
            BookkeepingExpenseFillSupport.fill(rankVoList);
            List<BookkeepingRecordsConsumeTopVo> topConsumeList = new ArrayList<>();
            for (BookkeepingStatisticsRankVo rankVo : rankVoList) {
                BookkeepingRecordsConsumeTopVo topVo = new BookkeepingRecordsConsumeTopVo();
                topVo.setExpenseUserId(rankVo.getExpenseUserId());
                topVo.setExpenseUserName(rankVo.getExpenseUserName());
                topVo.setCategory(snapshotLabel(rankVo.getRecordTypeName(), rankVo.getDictionarySnapshotStatus()));
                topVo.setDescription(rankVo.getRecordSource());
                topVo.setDate(rankVo.getRecordDate());
                topVo.setAmount(rankVo.getAmount());
                topVo.setId(rankVo.getId());
                topVo.setUserId(rankVo.getUserId());
                topVo.setUserName(rankVo.getUserName());
                topVo.setCanEdit(rankVo.getCanEdit());
                topConsumeList.add(topVo);
            }
            consumeVo.setTopConsumeList(topConsumeList);
        }

        // 查询年度支出-支出洞察
        BookkeepingRecordsConsumeInsightsVo insightsVo = new BookkeepingRecordsConsumeInsightsVo();
        BookkeepingRecordsConsumeInsightsVo consumeDaysVo = getBaseDao().getStatisticsMapper().statisticsMaxDay(dto);
        if (consumeDaysVo != null) {
            insightsVo.setMaxDayAmount(consumeDaysVo.getMaxDayAmount());
            insightsVo.setMaxDayDate(consumeDaysVo.getMaxDayDate());
        }
        BookkeepingRecordsConsumeInsightsVo consumeMonthVo = getBaseDao().getStatisticsMapper().statisticsMaxMonth(dto);
        if (consumeMonthVo != null) {
            insightsVo.setMaxMonthAmount(consumeMonthVo.getMaxMonthAmount());
            insightsVo.setMaxMonthName(consumeMonthVo.getMaxMonthName() + "月");
        }
        if (CollectionUtil.isNotEmpty(consumeVo.getConsumeTags())) {
            BookkeepingRecordsConsumeTagsVo tagsOneVo = consumeVo.getConsumeTags().get(0);
            BookkeepingRecordsConsumeTagsVo tagsLastVo = consumeVo.getConsumeTags().get(consumeVo.getConsumeTags().size() - 1);
            insightsVo.setTopTag(tagsOneVo.getName());
            insightsVo.setTopTagCount(tagsOneVo.getCount());
            insightsVo.setTopTagAmount(tagsOneVo.getAmount());
            insightsVo.setBottomTag(tagsLastVo.getName());
            insightsVo.setBottomTagCount(tagsLastVo.getCount());
            insightsVo.setBottomTagAmount(tagsLastVo.getAmount());
        }
        Long count = getBaseDao().lambdaQuery()
                .between(BookkeepingRecordsEntity::getRecordDate, dto.getStartDate(), dto.getEndDate())
                .eq(dto.getIgnoreNotStatistics() != null && dto.getIgnoreNotStatistics() == 0, BookkeepingRecordsEntity::getIsStatistics, 1)
                .eq(BookkeepingRecordsEntity::getRecordCategory, RecordCategoryEnum.CONSUME)
                .ge(BookkeepingRecordsEntity::getAmount, new BigDecimal("100"))
                .count();
        if (count == 0) {
            insightsVo.setLargeExpenseRatio(BigDecimal.ZERO);
        } else {
            insightsVo.setLargeExpenseRatio(new BigDecimal(count * 100).divide(new BigDecimal(consumeVo.getYearStatistics().getConsumeCount()), 2, RoundingMode.HALF_UP));
        }
        insightsVo.setAvgMonthAmount(consumeVo.getYearStatistics().getTotalConsume().divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP));
        consumeVo.setInsights(insightsVo);

        return consumeVo;
    }

    @Override
    public BookkeepingRecordsYearStatisticsIncomeVo yearStatisticsIncome(BookkeepingRecordsYearStatisticsQueryDto dto) {
        return this.queryYearStatisticsIncome(dto);
    }

    private BookkeepingRecordsYearStatisticsIncomeVo queryYearStatisticsIncome(BookkeepingRecordsYearStatisticsQueryDto dto) {
        this.computeYearStatisticsParam(dto);
        dto.setRecordCategories(new HashSet<>(Collections.singleton(RecordCategoryEnum.INCOME)));

        BookkeepingRecordsYearStatisticsIncomeVo incomeVo = new BookkeepingRecordsYearStatisticsIncomeVo();

        // 查询年度收入-汇总数据
        BookkeepingRecordsOverviewSummaryVo overviewSummaryVo = getBaseDao().queryYearlyOverviewSummary(dto);
        BookkeepingRecordsIncomeSummaryVo incomeSummaryVo = new BookkeepingRecordsIncomeSummaryVo();
        incomeSummaryVo.setTotalIncome(overviewSummaryVo.getTotalIncome());
        incomeSummaryVo.setIncomeCount(overviewSummaryVo.getIncomeCount());
        incomeVo.setYearStatistics(incomeSummaryVo);

        // 查询年度收入-月度趋势
        BookkeepingRecordsOverviewMonthlyVo monthlyVo = getBaseDao().queryYearlyOverviewMonthly(dto);
        incomeVo.setMonthlyData(monthlyVo.getIncomeTrendData());

        // 查询年度收入-收入分类占比
        BookkeepingConsumeCategoryStatisticsDto categoryStatisticsDto = new BookkeepingConsumeCategoryStatisticsDto();
        categoryStatisticsDto.setRecordCategory(RecordCategoryEnum.INCOME);
        categoryStatisticsDto.setCurrentStartMonth(dto.getStartDate());
        categoryStatisticsDto.setCurrentEndMonth(dto.getEndDate());
        categoryStatisticsDto.setIsSearchAll(dto.getIgnoreNotStatistics());
        categoryStatisticsDto.setQueryOnlyMyself(dto.getQueryOnlyMyself());
        List<BookkeepingConsumeStatisticsCategoryVo> statisticsCategoryVos = getBaseDao().getBaseMapper().categoryStatistics(categoryStatisticsDto);
        if (CollectionUtil.isNotEmpty(statisticsCategoryVos)) {
            BigDecimal totalAmount = statisticsCategoryVos.stream().map(BookkeepingConsumeStatisticsCategoryVo::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            List<BookkeepingRecordsIncomeCategoriesVo> categoryVoList = new ArrayList<>();
            for (BookkeepingConsumeStatisticsCategoryVo statisticsCategoryVo : statisticsCategoryVos) {
                BookkeepingRecordsIncomeCategoriesVo categoriesVo = new BookkeepingRecordsIncomeCategoriesVo();
                categoriesVo.setName(snapshotLabel(statisticsCategoryVo.getRecordTypeName(), statisticsCategoryVo.getDictionarySnapshotStatus()));
                categoriesVo.setAmount(statisticsCategoryVo.getAmount());
                categoriesVo.setRatio(totalAmount.signum()==0?BigDecimal.ZERO:statisticsCategoryVo.getAmount().multiply(new BigDecimal("100")).divide(totalAmount, 2, RoundingMode.HALF_UP));
                categoryVoList.add(categoriesVo);
            }
            incomeVo.setIncomeCategories(categoryVoList);
        }

        // 查询年度收入-Top10
        BookkeepingStatisticsDto statisticsDto = new BookkeepingStatisticsDto();
        statisticsDto.setRecordCategory(RecordCategoryEnum.INCOME);
        statisticsDto.setCurrentStartMonth(dto.getStartDate());
        statisticsDto.setCurrentEndMonth(dto.getEndDate());
        statisticsDto.setIsSearchAll(dto.getIgnoreNotStatistics());
        statisticsDto.setQueryOnlyMyself(dto.getQueryOnlyMyself());
        List<BookkeepingStatisticsRankVo> rankVoList = getBaseDao().getBaseMapper().rankStatistics(statisticsDto);
        if (CollectionUtil.isNotEmpty(rankVoList)) {
            BookkeepingExpenseFillSupport.fill(rankVoList);
            List<BookkeepingRecordsIncomeTopVo> topIncomeList = new ArrayList<>();
            for (BookkeepingStatisticsRankVo rankVo : rankVoList) {
                BookkeepingRecordsIncomeTopVo topVo = new BookkeepingRecordsIncomeTopVo();
                topVo.setCategory(snapshotLabel(rankVo.getRecordTypeName(), rankVo.getDictionarySnapshotStatus()));
                topVo.setDescription(rankVo.getRecordSource());
                topVo.setDate(rankVo.getRecordDate());
                topVo.setAmount(rankVo.getAmount());
                topVo.setId(rankVo.getId());
                topVo.setUserId(rankVo.getUserId());
                topVo.setUserName(rankVo.getUserName());
                topVo.setCanEdit(rankVo.getCanEdit());
                topIncomeList.add(topVo);
            }
            incomeVo.setTopIncomeList(topIncomeList);
        }

        // 查询年度收入-收入洞察
        BookkeepingRecordsIncomeInsightsVo insightsVo = new BookkeepingRecordsIncomeInsightsVo();
        BookkeepingRecordsConsumeInsightsVo consumeDaysVo = getBaseDao().getStatisticsMapper().statisticsMaxDay(dto);
        if (consumeDaysVo != null) {
            insightsVo.setMaxDayAmount(consumeDaysVo.getMaxDayAmount());
            insightsVo.setMaxDayDate(consumeDaysVo.getMaxDayDate());
        }
        BookkeepingRecordsConsumeInsightsVo consumeMonthVo = getBaseDao().getStatisticsMapper().statisticsMaxMonth(dto);
        if (consumeMonthVo != null) {
            insightsVo.setMaxMonthAmount(consumeMonthVo.getMaxMonthAmount());
            insightsVo.setMaxMonthName(consumeMonthVo.getMaxMonthName() + "月");
        }
        Long count = getBaseDao().lambdaQuery()
                .between(BookkeepingRecordsEntity::getRecordDate, dto.getStartDate(), dto.getEndDate())
                .eq(dto.getIgnoreNotStatistics() != null && dto.getIgnoreNotStatistics() == 0, BookkeepingRecordsEntity::getIsStatistics, 1)
                .eq(BookkeepingRecordsEntity::getRecordCategory, RecordCategoryEnum.INCOME)
                .ge(BookkeepingRecordsEntity::getAmount, new BigDecimal("100"))
                .count();
        if (count == 0) {
            insightsVo.setLargeIncomeRatio(BigDecimal.ZERO);
        } else {
            insightsVo.setLargeIncomeRatio(new BigDecimal(count * 100).divide(new BigDecimal(incomeVo.getYearStatistics().getIncomeCount()), 2, RoundingMode.HALF_UP));
        }
        insightsVo.setAvgMonthAmount(incomeVo.getYearStatistics().getTotalIncome().divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP));
        incomeVo.setInsights(insightsVo);

        return incomeVo;
    }

    private String snapshotLabel(String name,Integer status){return !Objects.equals(status,1)?"分类待核对":name==null?"未分类":name;}

    private void computeYearStatisticsParam(BookkeepingRecordsYearStatisticsQueryDto dto) {
        dto.setUserId(UserUtils.getUserId());
        LocalDate startStatisticsDate;
        if (StringUtils.isEmpty(dto.getYear())) {
            startStatisticsDate = DateUtils.startDateOfNowYear();
        } else {
            startStatisticsDate = LocalDate.parse(dto.getYear() + "-01-01", DateUtils.DATE_FORMATTER);
        }
        LocalDate endStatisticsDate = DateUtils.endDateOfYear(startStatisticsDate);
        dto.setStartDate(startStatisticsDate);
        dto.setEndDate(endStatisticsDate);
    }

    private ShareStateEnum resolveRecordShareState(Integer recordGroupId, FamilySharedSavePolicyVo sharedSavePolicy, Integer requestShared) {
        if (recordGroupId == null || recordGroupId <= 0 || sharedSavePolicy == null) {
            return ShareStateEnum.NOT_SHARED;
        }
        if (!Objects.equals(recordGroupId, sharedSavePolicy.getCurrentGroupId())) {
            return ShareStateEnum.NOT_SHARED;
        }
        if (BoolEnum.TRUE.getCode().equals(sharedSavePolicy.getForceShared())) {
            return ShareStateEnum.SHARED;
        }
        if (requestShared != null) {
            return BoolEnum.TRUE.getCode().equals(requestShared) ? ShareStateEnum.SHARED : ShareStateEnum.NOT_SHARED;
        }
        return BoolEnum.TRUE.getCode().equals(sharedSavePolicy.getDefaultShared()) ? ShareStateEnum.SHARED : ShareStateEnum.NOT_SHARED;
    }

    private Integer resolveSharedCode(ShareStateEnum shareState) {
        return ShareStateEnum.SHARED.equals(shareState) ? BoolEnum.TRUE.getCode() : BoolEnum.FALSE.getCode();
    }

    private BookkeepingRecordsEntity queryEditableRecord(Integer id, String actionName) {
        BookkeepingRecordsEntity bookkeepingRecordsEntity = getBaseDao().lambdaQuery()
                .eq(BookkeepingRecordsEntity::getId, id)
                .eq(BookkeepingRecordsEntity::getUserId, UserUtils.getUserId()).last("FOR UPDATE").one();
        if (bookkeepingRecordsEntity == null) throw new BusinessException("记账记录不存在或无权" + actionName);
        if (!Objects.equals(bookkeepingRecordsEntity.getUserId(), UserUtils.getUserId())) {
            throw new BusinessException("不能" + actionName + "他人记账记录");
        }
        return bookkeepingRecordsEntity;
    }

    private BookkeepingRecordsEntity queryRecordByIdIgnorePermission(Integer id) {
        try {
            UserUtils.setUserDataPermission(Boolean.FALSE);
            return getBaseDao().queryById(id);
        } finally {
            UserUtils.removeUserDataPermission();
        }
    }

    private void addPointsRecordsByExcitation(String orderNo) {
        PointsRecordsAddDto pointsRecordsAddDto = new PointsRecordsAddDto();
        pointsRecordsAddDto.setTransactionType(PointsTransactionTypeEnum.INCREASE.getCode());
        pointsRecordsAddDto.setPoints(1);
        pointsRecordsAddDto.setSource("记账收入: " + orderNo);
        pointsRecordsAddDto.setSourceType(PointsSourceTypeEnum.BOOKKEEPING.getCode());
        pointsRecordsAddDto.setUserId(UserUtils.getUserId());
        MQProducerHelper.send(PointsRecordsTopicEnum.BOOKKEEPING_SERVICE, pointsRecordsAddDto);
    }

    private void deductPointsRecordsByExcitation(String orderNo) {
        PointsRecordsAddDto pointsRecordsAddDto = new PointsRecordsAddDto();
        pointsRecordsAddDto.setTransactionType(PointsTransactionTypeEnum.DEDUCT.getCode());
        pointsRecordsAddDto.setPoints(-1);
        pointsRecordsAddDto.setSource("记账收入被删除: " + orderNo);
        pointsRecordsAddDto.setSourceType(PointsSourceTypeEnum.BOOKKEEPING.getCode());
        pointsRecordsAddDto.setUserId(UserUtils.getUserId());
        MQProducerHelper.send(PointsRecordsTopicEnum.BOOKKEEPING_SERVICE, pointsRecordsAddDto);
    }

}

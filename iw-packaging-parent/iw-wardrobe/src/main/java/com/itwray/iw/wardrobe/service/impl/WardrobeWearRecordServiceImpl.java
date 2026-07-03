package com.itwray.iw.wardrobe.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.itwray.iw.wardrobe.dao.WardrobeOutfitDao;
import com.itwray.iw.wardrobe.dao.WardrobeOutfitItemDao;
import com.itwray.iw.wardrobe.dao.WardrobeWearRecordDao;
import com.itwray.iw.wardrobe.dao.WardrobeWearRecordItemDao;
import com.itwray.iw.wardrobe.model.dto.WardrobeMarkWornDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeWearRecordAddDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeWearRecordCopyDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeWearRecordMonthDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeWearRecordUpdateDto;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeOutfitEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeOutfitItemEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeWearRecordEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeWearRecordItemEntity;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitItemVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeWearRecordVo;
import com.itwray.iw.wardrobe.service.WardrobeItemService;
import com.itwray.iw.wardrobe.service.WardrobeOutfitService;
import com.itwray.iw.wardrobe.service.WardrobeWearRecordService;
import com.itwray.iw.web.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 穿着记录服务实现
 *
 * @author codex
 * @since 2026-07-02
 */
@Service
public class WardrobeWearRecordServiceImpl implements WardrobeWearRecordService {

    private static final int RECORD_TYPE_PLAN = 1;
    private static final int RECORD_TYPE_WORN = 2;

    private final WardrobeWearRecordDao wearRecordDao;
    private final WardrobeWearRecordItemDao wearRecordItemDao;
    private final WardrobeOutfitDao wardrobeOutfitDao;
    private final WardrobeOutfitItemDao outfitItemDao;
    private final WardrobeItemService wardrobeItemService;
    private final WardrobeOutfitService wardrobeOutfitService;

    public WardrobeWearRecordServiceImpl(WardrobeWearRecordDao wearRecordDao,
                                         WardrobeWearRecordItemDao wearRecordItemDao,
                                         WardrobeOutfitDao wardrobeOutfitDao,
                                         WardrobeOutfitItemDao outfitItemDao,
                                         WardrobeItemService wardrobeItemService,
                                         @Lazy WardrobeOutfitService wardrobeOutfitService) {
        this.wearRecordDao = wearRecordDao;
        this.wearRecordItemDao = wearRecordItemDao;
        this.wardrobeOutfitDao = wardrobeOutfitDao;
        this.outfitItemDao = outfitItemDao;
        this.wardrobeItemService = wardrobeItemService;
        this.wardrobeOutfitService = wardrobeOutfitService;
    }

    @Override
    @Transactional
    public Integer add(WardrobeWearRecordAddDto dto) {
        LocalDate wearDate = Objects.requireNonNullElse(dto.getWearDate(), LocalDate.now());
        Integer recordType = Objects.requireNonNullElse(dto.getRecordType(), RECORD_TYPE_WORN);
        List<WardrobeOutfitItemVo> itemList = this.resolveRecordItems(dto.getOutfitId(), dto.getItemIds());
        if (itemList.isEmpty()) {
            throw new BusinessException("请至少选择一件衣物");
        }
        WardrobeWearRecordEntity entity = BeanUtil.copyProperties(dto, WardrobeWearRecordEntity.class);
        entity.setWearDate(wearDate);
        entity.setRecordType(recordType);
        entity.setOutfitId(Objects.requireNonNullElse(dto.getOutfitId(), 0));
        entity.setOutfitName(this.resolveOutfitName(dto.getOutfitId()));
        entity.setSceneTags(StringUtils.defaultString(dto.getSceneTags()));
        entity.setWeatherText(StringUtils.defaultString(dto.getWeatherText()));
        entity.setMoodText(StringUtils.defaultString(dto.getMoodText()));
        entity.setRemark(StringUtils.defaultString(dto.getRemark()));
        entity.setItemCount(itemList.size());
        wearRecordDao.save(entity);
        this.saveRecordItems(entity.getId(), itemList);
        if (Objects.equals(recordType, RECORD_TYPE_WORN)) {
            this.increaseWearCount(entity.getOutfitId(), itemList, wearDate);
        }
        return entity.getId();
    }

    @Override
    @Transactional
    public void update(WardrobeWearRecordUpdateDto dto) {
        wearRecordDao.queryById(dto.getId());
        List<WardrobeOutfitItemVo> itemList = this.resolveRecordItems(dto.getOutfitId(), dto.getItemIds());
        if (itemList.isEmpty()) {
            throw new BusinessException("请至少选择一件衣物");
        }
        WardrobeWearRecordEntity entity = BeanUtil.copyProperties(dto, WardrobeWearRecordEntity.class);
        entity.setWearDate(Objects.requireNonNullElse(dto.getWearDate(), LocalDate.now()));
        entity.setRecordType(Objects.requireNonNullElse(dto.getRecordType(), RECORD_TYPE_PLAN));
        entity.setOutfitId(Objects.requireNonNullElse(dto.getOutfitId(), 0));
        entity.setOutfitName(this.resolveOutfitName(dto.getOutfitId()));
        entity.setItemCount(itemList.size());
        wearRecordDao.updateById(entity);
        wearRecordItemDao.lambdaUpdate().eq(WardrobeWearRecordItemEntity::getRecordId, dto.getId()).remove();
        this.saveRecordItems(dto.getId(), itemList);
    }

    @Override
    @Transactional
    public void delete(Integer id) {
        wearRecordDao.queryById(id);
        wearRecordItemDao.lambdaUpdate().eq(WardrobeWearRecordItemEntity::getRecordId, id).remove();
        wearRecordDao.removeById(id);
    }

    @Override
    public WardrobeWearRecordVo detail(Integer id) {
        WardrobeWearRecordVo vo = BeanUtil.copyProperties(wearRecordDao.queryById(id), WardrobeWearRecordVo.class);
        vo.setItemList(this.queryRecordItemVos(id));
        return vo;
    }

    @Override
    @Transactional
    public Integer copy(WardrobeWearRecordCopyDto dto) {
        WardrobeWearRecordVo source = this.detail(dto.getId());
        WardrobeWearRecordAddDto addDto = new WardrobeWearRecordAddDto();
        addDto.setWearDate(dto.getTargetDate());
        addDto.setOutfitId(source.getOutfitId());
        addDto.setItemIds(source.getItemList().stream().map(WardrobeOutfitItemVo::getItemId).toList());
        addDto.setSceneTags(source.getSceneTags());
        addDto.setWeatherText(source.getWeatherText());
        addDto.setMoodText(source.getMoodText());
        addDto.setRecordType(Objects.requireNonNullElse(dto.getRecordType(), source.getRecordType()));
        addDto.setRemark(source.getRemark());
        return this.add(addDto);
    }

    @Override
    public List<WardrobeWearRecordVo> month(WardrobeWearRecordMonthDto dto) {
        YearMonth yearMonth = StringUtils.isBlank(dto.getMonth()) ? YearMonth.now() : YearMonth.parse(dto.getMonth());
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.atEndOfMonth();
        List<WardrobeWearRecordVo> recordList = wearRecordDao.lambdaQuery()
                .ge(WardrobeWearRecordEntity::getWearDate, startDate)
                .le(WardrobeWearRecordEntity::getWearDate, endDate)
                .orderByAsc(WardrobeWearRecordEntity::getWearDate)
                .orderByDesc(WardrobeWearRecordEntity::getId)
                .list()
                .stream()
                .map(entity -> BeanUtil.copyProperties(entity, WardrobeWearRecordVo.class))
                .toList();
        this.fillRecordItems(recordList);
        return recordList;
    }

    @Override
    public List<WardrobeWearRecordVo> today() {
        List<WardrobeWearRecordVo> recordList = wearRecordDao.lambdaQuery()
                .eq(WardrobeWearRecordEntity::getWearDate, LocalDate.now())
                .orderByDesc(WardrobeWearRecordEntity::getId)
                .list()
                .stream()
                .map(entity -> BeanUtil.copyProperties(entity, WardrobeWearRecordVo.class))
                .toList();
        this.fillRecordItems(recordList);
        return recordList;
    }

    @Override
    public List<WardrobeWearRecordVo> recent(int limit) {
        List<WardrobeWearRecordVo> recordList = wearRecordDao.lambdaQuery()
                .eq(WardrobeWearRecordEntity::getRecordType, RECORD_TYPE_WORN)
                .orderByDesc(WardrobeWearRecordEntity::getWearDate)
                .orderByDesc(WardrobeWearRecordEntity::getId)
                .last("limit " + Math.max(limit, 1))
                .list()
                .stream()
                .map(entity -> BeanUtil.copyProperties(entity, WardrobeWearRecordVo.class))
                .toList();
        this.fillRecordItems(recordList);
        return recordList;
    }

    @Override
    public Integer markWorn(WardrobeMarkWornDto dto) {
        WardrobeWearRecordAddDto addDto = new WardrobeWearRecordAddDto();
        addDto.setWearDate(Objects.requireNonNullElse(dto.getWearDate(), LocalDate.now()));
        addDto.setOutfitId(dto.getOutfitId());
        addDto.setItemIds(dto.getItemIds());
        addDto.setSceneTags(dto.getSceneTags());
        addDto.setWeatherText(dto.getWeatherText());
        addDto.setMoodText(dto.getMoodText());
        addDto.setRemark(dto.getRemark());
        addDto.setRecordType(RECORD_TYPE_WORN);
        return this.add(addDto);
    }

    @Override
    @Transactional
    public void markRecordWorn(Integer id) {
        WardrobeWearRecordEntity entity = wearRecordDao.queryById(id);
        if (Objects.equals(entity.getRecordType(), RECORD_TYPE_WORN)) {
            return;
        }
        List<WardrobeOutfitItemVo> itemList = this.queryRecordItemVos(id);
        entity.setRecordType(RECORD_TYPE_WORN);
        wearRecordDao.updateById(entity);
        this.increaseWearCount(entity.getOutfitId(), itemList, entity.getWearDate());
    }

    private List<WardrobeOutfitItemVo> resolveRecordItems(Integer outfitId, List<Integer> itemIds) {
        if (outfitId != null && outfitId > 0) {
            wardrobeOutfitDao.queryById(outfitId);
            return outfitItemDao.lambdaQuery()
                    .eq(WardrobeOutfitItemEntity::getOutfitId, outfitId)
                    .orderByAsc(WardrobeOutfitItemEntity::getSort)
                    .list()
                    .stream()
                    .map(this::toOutfitItemVo)
                    .toList();
        }
        List<WardrobeItemEntity> itemList = wardrobeItemService.queryActiveItemsByIds(itemIds);
        if (itemIds != null && itemList.size() != itemIds.stream().filter(Objects::nonNull).distinct().count()) {
            throw new BusinessException("存在不可用的衣物，请刷新后重试");
        }
        return itemList.stream().map(this::toOutfitItemVo).toList();
    }

    private String resolveOutfitName(Integer outfitId) {
        if (outfitId == null || outfitId <= 0) {
            return "";
        }
        WardrobeOutfitEntity outfit = wardrobeOutfitDao.queryById(outfitId);
        return outfit.getOutfitName();
    }

    private void saveRecordItems(Integer recordId, List<WardrobeOutfitItemVo> itemList) {
        List<WardrobeWearRecordItemEntity> relationList = new ArrayList<>();
        for (int i = 0; i < itemList.size(); i++) {
            WardrobeOutfitItemVo item = itemList.get(i);
            WardrobeWearRecordItemEntity relation = new WardrobeWearRecordItemEntity();
            relation.setRecordId(recordId);
            relation.setItemId(item.getItemId());
            relation.setItemName(item.getItemName());
            relation.setItemImage(item.getItemImage());
            relation.setCategory(item.getCategory());
            relation.setSort(i + 1);
            relationList.add(relation);
        }
        wearRecordItemDao.saveBatch(relationList);
    }

    private void increaseWearCount(Integer outfitId, List<WardrobeOutfitItemVo> itemList, LocalDate wearDate) {
        List<Integer> itemIds = itemList.stream().map(WardrobeOutfitItemVo::getItemId).filter(Objects::nonNull).toList();
        wardrobeItemService.increaseWearCount(itemIds, wearDate);
        wardrobeOutfitService.increaseWearCount(outfitId, wearDate);
    }

    private void fillRecordItems(List<WardrobeWearRecordVo> recordList) {
        if (recordList == null || recordList.isEmpty()) {
            return;
        }
        List<Integer> recordIds = recordList.stream().map(WardrobeWearRecordVo::getId).toList();
        Map<Integer, List<WardrobeOutfitItemVo>> itemMap = wearRecordItemDao.lambdaQuery()
                .in(WardrobeWearRecordItemEntity::getRecordId, recordIds)
                .orderByAsc(WardrobeWearRecordItemEntity::getSort)
                .list()
                .stream()
                .collect(Collectors.groupingBy(WardrobeWearRecordItemEntity::getRecordId,
                        Collectors.mapping(this::toOutfitItemVo, Collectors.toList())));
        recordList.forEach(record -> record.setItemList(itemMap.getOrDefault(record.getId(), Collections.emptyList())));
    }

    private List<WardrobeOutfitItemVo> queryRecordItemVos(Integer recordId) {
        return wearRecordItemDao.lambdaQuery()
                .eq(WardrobeWearRecordItemEntity::getRecordId, recordId)
                .orderByAsc(WardrobeWearRecordItemEntity::getSort)
                .list()
                .stream()
                .map(this::toOutfitItemVo)
                .toList();
    }

    private WardrobeOutfitItemVo toOutfitItemVo(WardrobeOutfitItemEntity entity) {
        WardrobeOutfitItemVo vo = new WardrobeOutfitItemVo();
        vo.setItemId(entity.getItemId());
        vo.setItemName(entity.getItemName());
        vo.setItemImage(entity.getItemImage());
        vo.setCategory(entity.getCategory());
        vo.setSort(entity.getSort());
        return vo;
    }

    private WardrobeOutfitItemVo toOutfitItemVo(WardrobeWearRecordItemEntity entity) {
        WardrobeOutfitItemVo vo = new WardrobeOutfitItemVo();
        vo.setItemId(entity.getItemId());
        vo.setItemName(entity.getItemName());
        vo.setItemImage(entity.getItemImage());
        vo.setCategory(entity.getCategory());
        vo.setSort(entity.getSort());
        return vo;
    }

    private WardrobeOutfitItemVo toOutfitItemVo(WardrobeItemEntity entity) {
        WardrobeOutfitItemVo vo = new WardrobeOutfitItemVo();
        vo.setItemId(entity.getId());
        vo.setItemName(entity.getItemName());
        vo.setItemImage(entity.getItemImage());
        vo.setCategory(entity.getCategory());
        return vo;
    }
}

package com.itwray.iw.wardrobe.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.itwray.iw.wardrobe.dao.WardrobeItemDao;
import com.itwray.iw.wardrobe.dao.WardrobeOutfitDao;
import com.itwray.iw.wardrobe.dao.WardrobeOutfitItemDao;
import com.itwray.iw.wardrobe.model.dto.WardrobeMarkWornDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeOutfitAddDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeOutfitPageDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeOutfitSuggestDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeOutfitUpdateDto;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.model.enums.WardrobeItemStatusEnum;
import com.itwray.iw.wardrobe.model.entity.WardrobeOutfitEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeOutfitItemEntity;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitDetailVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitItemVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitPageVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitSuggestionVo;
import com.itwray.iw.wardrobe.service.WardrobeItemImageService;
import com.itwray.iw.wardrobe.service.WardrobeOutfitService;
import com.itwray.iw.wardrobe.service.WardrobeWearRecordService;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.vo.PageVo;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 搭配服务实现
 *
 * @author codex
 * @since 2026-07-02
 */
@Service
public class WardrobeOutfitServiceImpl implements WardrobeOutfitService {

    private static final int OUTFIT_STATUS_ACTIVE = 1;
    private static final int CATEGORY_TOP = 1;
    private static final int CATEGORY_BOTTOM = 2;
    private static final int CATEGORY_DRESS = 3;
    private static final int CATEGORY_SHOES = 6;
    private static final int CATEGORY_ACCESSORY = 7;
    private static final int CATEGORY_HAT = 8;
    private static final int CATEGORY_BAG = 9;
    private static final int CATEGORY_JEWELRY = 10;

    private final WardrobeOutfitDao wardrobeOutfitDao;
    private final WardrobeOutfitItemDao outfitItemDao;
    private final WardrobeItemDao wardrobeItemDao;
    private final WardrobeWearRecordService wearRecordService;
    private final WardrobeItemImageService wardrobeItemImageService;

    public WardrobeOutfitServiceImpl(WardrobeOutfitDao wardrobeOutfitDao,
                                     WardrobeOutfitItemDao outfitItemDao,
                                     WardrobeItemDao wardrobeItemDao,
                                     @Lazy WardrobeWearRecordService wearRecordService,
                                     WardrobeItemImageService wardrobeItemImageService) {
        this.wardrobeOutfitDao = wardrobeOutfitDao;
        this.outfitItemDao = outfitItemDao;
        this.wardrobeItemDao = wardrobeItemDao;
        this.wearRecordService = wearRecordService;
        this.wardrobeItemImageService = wardrobeItemImageService;
    }

    @Override
    @Transactional
    public Integer add(WardrobeOutfitAddDto dto) {
        List<WardrobeItemEntity> itemList = this.querySelectedItems(dto.getItemIds());
        WardrobeOutfitEntity entity = BeanUtil.copyProperties(dto, WardrobeOutfitEntity.class);
        this.fillOutfitDefaults(entity, itemList);
        wardrobeOutfitDao.save(entity);
        this.saveOutfitItems(entity.getId(), itemList);
        return entity.getId();
    }

    @Override
    @Transactional
    public void update(WardrobeOutfitUpdateDto dto) {
        wardrobeOutfitDao.queryById(dto.getId());
        List<WardrobeItemEntity> itemList = this.querySelectedItems(dto.getItemIds());
        WardrobeOutfitEntity entity = BeanUtil.copyProperties(dto, WardrobeOutfitEntity.class);
        this.fillOutfitDefaults(entity, itemList);
        wardrobeOutfitDao.updateById(entity);
        outfitItemDao.lambdaUpdate().eq(WardrobeOutfitItemEntity::getOutfitId, dto.getId()).remove();
        this.saveOutfitItems(dto.getId(), itemList);
    }

    @Override
    @Transactional
    public void delete(Integer id) {
        wardrobeOutfitDao.queryById(id);
        outfitItemDao.lambdaUpdate().eq(WardrobeOutfitItemEntity::getOutfitId, id).remove();
        wardrobeOutfitDao.removeById(id);
    }

    @Override
    @Transactional
    public Integer copy(Integer id) {
        WardrobeOutfitDetailVo detailVo = this.detail(id);
        WardrobeOutfitAddDto dto = new WardrobeOutfitAddDto();
        dto.setOutfitName(detailVo.getOutfitName() + " 副本");
        dto.setCoverImage(detailVo.getCoverImage());
        dto.setSeasonTags(detailVo.getSeasonTags());
        dto.setSceneTags(detailVo.getSceneTags());
        dto.setStyleTags(detailVo.getStyleTags());
        dto.setCustomTags(detailVo.getCustomTags());
        dto.setRemark(detailVo.getRemark());
        dto.setItemIds(detailVo.getItemList().stream().map(WardrobeOutfitItemVo::getItemId).toList());
        return this.add(dto);
    }

    @Override
    public PageVo<WardrobeOutfitPageVo> page(WardrobeOutfitPageDto dto) {
        LambdaQueryWrapper<WardrobeOutfitEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StringUtils.isNotBlank(dto.getOutfitName()), WardrobeOutfitEntity::getOutfitName, dto.getOutfitName())
                .like(StringUtils.isNotBlank(dto.getSeason()), WardrobeOutfitEntity::getSeasonTags, dto.getSeason())
                .like(StringUtils.isNotBlank(dto.getScene()), WardrobeOutfitEntity::getSceneTags, dto.getScene())
                .like(StringUtils.isNotBlank(dto.getStyle()), WardrobeOutfitEntity::getStyleTags, dto.getStyle())
                .like(StringUtils.isNotBlank(dto.getCustomTag()), WardrobeOutfitEntity::getCustomTags, dto.getCustomTag())
                .eq(Objects.nonNull(dto.getStatus()), WardrobeOutfitEntity::getStatus, dto.getStatus());
        this.applySort(queryWrapper, dto.getSortType());
        PageVo<WardrobeOutfitPageVo> pageVo = wardrobeOutfitDao.page(dto, queryWrapper, WardrobeOutfitPageVo.class);
        this.fillOutfitItems(pageVo.getRecords());
        return pageVo;
    }

    @Override
    public WardrobeOutfitDetailVo detail(Integer id) {
        WardrobeOutfitDetailVo detailVo = BeanUtil.copyProperties(wardrobeOutfitDao.queryById(id), WardrobeOutfitDetailVo.class);
        detailVo.setItemList(this.queryOutfitItemVos(id));
        return detailVo;
    }

    @Override
    public List<WardrobeOutfitSuggestionVo> suggest(WardrobeOutfitSuggestDto dto) {
        int limit = dto.getLimit() == null || dto.getLimit() <= 0 ? 5 : Math.min(dto.getLimit(), 10);
        List<WardrobeItemEntity> candidates = this.querySuggestCandidates(dto);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<WardrobeItemEntity>> categoryMap = candidates.stream()
                .sorted(Comparator.comparing(WardrobeItemEntity::getWearCount, Comparator.nullsFirst(Integer::compareTo))
                        .thenComparing(WardrobeItemEntity::getId, Comparator.reverseOrder()))
                .collect(Collectors.groupingBy(WardrobeItemEntity::getCategory, LinkedHashMap::new, Collectors.toList()));
        WardrobeItemEntity lockedItem = this.resolveLockedItem(dto.getLockedItemId());

        List<WardrobeOutfitSuggestionVo> result = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            List<WardrobeItemEntity> items = this.buildSuggestionItems(categoryMap, lockedItem, i);
            if (items.size() < 2) {
                continue;
            }
            WardrobeOutfitSuggestionVo vo = new WardrobeOutfitSuggestionVo();
        vo.setSuggestionName("灵感搭配 " + (result.size() + 1));
            vo.setReason(this.buildSuggestionReason(dto, lockedItem));
            vo.setItemList(items.stream().map(this::toOutfitItemVo).toList());
            result.add(vo);
        }
        return result;
    }

    @Override
    public Integer markWorn(WardrobeMarkWornDto dto) {
        return wearRecordService.markWorn(dto);
    }

    @Override
    @Transactional
    public void increaseWearCount(Integer outfitId, LocalDate wearDate) {
        if (outfitId == null || outfitId <= 0) {
            return;
        }
        wardrobeOutfitDao.lambdaUpdate()
                .eq(WardrobeOutfitEntity::getId, outfitId)
                .set(WardrobeOutfitEntity::getLastWearDate, wearDate)
                .setSql("wear_count = wear_count + 1")
                .update();
    }

    private List<WardrobeItemEntity> querySelectedItems(List<Integer> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            throw new BusinessException("请至少选择一件衣物");
        }
        List<Integer> distinctIds = itemIds.stream().filter(Objects::nonNull).distinct().toList();
        List<WardrobeItemEntity> itemList = wardrobeItemDao.lambdaQuery()
                .in(WardrobeItemEntity::getId, distinctIds)
                .in(WardrobeItemEntity::getStatus, WardrobeItemStatusEnum.availableCodes())
                .list();
        if (itemList.size() != distinctIds.size()) {
            throw new BusinessException("存在不可用的衣物，请刷新后重试");
        }
        wardrobeItemImageService.applyCoverImages(itemList);
        Map<Integer, WardrobeItemEntity> itemMap = itemList.stream()
                .collect(Collectors.toMap(WardrobeItemEntity::getId, Function.identity()));
        return distinctIds.stream().map(itemMap::get).filter(Objects::nonNull).toList();
    }

    private void fillOutfitDefaults(WardrobeOutfitEntity entity, List<WardrobeItemEntity> itemList) {
        if (StringUtils.isBlank(entity.getOutfitName())) {
            throw new BusinessException("搭配名称不能为空");
        }
        entity.setOutfitName(entity.getOutfitName().trim());
        entity.setSeasonTags(StringUtils.defaultString(entity.getSeasonTags()));
        entity.setSceneTags(StringUtils.defaultString(entity.getSceneTags()));
        entity.setStyleTags(StringUtils.defaultString(entity.getStyleTags()));
        entity.setCustomTags(StringUtils.defaultString(entity.getCustomTags()));
        entity.setStatus(Objects.requireNonNullElse(entity.getStatus(), OUTFIT_STATUS_ACTIVE));
        entity.setRemark(StringUtils.defaultString(entity.getRemark()));
        entity.setItemCount(itemList.size());
        entity.setColorSummary(itemList.stream()
                .map(WardrobeItemEntity::getColorName)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.joining("、")));
        if (StringUtils.isBlank(entity.getCoverImage())) {
            entity.setCoverImage(itemList.stream()
                    .map(WardrobeItemEntity::getItemImage)
                    .filter(StringUtils::isNotBlank)
                    .findFirst()
                    .orElse(""));
        }
    }

    private void saveOutfitItems(Integer outfitId, List<WardrobeItemEntity> itemList) {
        List<WardrobeOutfitItemEntity> relationList = new ArrayList<>();
        for (int i = 0; i < itemList.size(); i++) {
            WardrobeItemEntity item = itemList.get(i);
            WardrobeOutfitItemEntity relation = new WardrobeOutfitItemEntity();
            relation.setOutfitId(outfitId);
            relation.setItemId(item.getId());
            relation.setItemName(item.getItemName());
            relation.setItemImage(item.getItemImage());
            relation.setCategory(item.getCategory());
            relation.setItemStyle(item.getItemStyle());
            relation.setSort(i + 1);
            relationList.add(relation);
        }
        outfitItemDao.saveBatch(relationList);
    }

    private void fillOutfitItems(List<WardrobeOutfitPageVo> outfitList) {
        if (outfitList == null || outfitList.isEmpty()) {
            return;
        }
        List<Integer> outfitIds = outfitList.stream().map(WardrobeOutfitPageVo::getId).toList();
        Map<Integer, List<WardrobeOutfitItemVo>> itemMap = outfitItemDao.lambdaQuery()
                .in(WardrobeOutfitItemEntity::getOutfitId, outfitIds)
                .orderByAsc(WardrobeOutfitItemEntity::getSort)
                .list()
                .stream()
                .collect(Collectors.groupingBy(WardrobeOutfitItemEntity::getOutfitId,
                        Collectors.mapping(this::toOutfitItemVo, Collectors.toList())));
        outfitList.forEach(outfit -> outfit.setItemList(itemMap.getOrDefault(outfit.getId(), List.of())));
    }

    private List<WardrobeOutfitItemVo> queryOutfitItemVos(Integer outfitId) {
        return outfitItemDao.lambdaQuery()
                .eq(WardrobeOutfitItemEntity::getOutfitId, outfitId)
                .orderByAsc(WardrobeOutfitItemEntity::getSort)
                .list()
                .stream()
                .map(this::toOutfitItemVo)
                .toList();
    }

    private WardrobeOutfitItemVo toOutfitItemVo(WardrobeOutfitItemEntity entity) {
        return BeanUtil.copyProperties(entity, WardrobeOutfitItemVo.class);
    }

    private WardrobeOutfitItemVo toOutfitItemVo(WardrobeItemEntity entity) {
        WardrobeOutfitItemVo vo = new WardrobeOutfitItemVo();
        vo.setItemId(entity.getId());
        vo.setItemName(entity.getItemName());
        vo.setItemImage(entity.getItemImage());
        vo.setCategory(entity.getCategory());
        vo.setItemStyle(entity.getItemStyle());
        return vo;
    }

    private List<WardrobeItemEntity> querySuggestCandidates(WardrobeOutfitSuggestDto dto) {
        LambdaQueryWrapper<WardrobeItemEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(WardrobeItemEntity::getStatus, WardrobeItemStatusEnum.availableCodes())
                .like(StringUtils.isNotBlank(dto.getSeason()), WardrobeItemEntity::getSeasonTags, dto.getSeason())
                .like(StringUtils.isNotBlank(dto.getScene()), WardrobeItemEntity::getSceneTags, dto.getScene())
                .like(StringUtils.isNotBlank(dto.getStyle()), WardrobeItemEntity::getStyleTags, dto.getStyle());
        if (dto.getExcludeItemIds() != null && !dto.getExcludeItemIds().isEmpty()) {
            List<Integer> excludeIds = dto.getExcludeItemIds().stream().filter(Objects::nonNull).toList();
            if (!excludeIds.isEmpty()) {
                queryWrapper.notIn(WardrobeItemEntity::getId, excludeIds);
            }
        }
        if (dto.getAvoidRecentDays() != null && dto.getAvoidRecentDays() > 0) {
            LocalDate avoidAfter = LocalDate.now().minusDays(dto.getAvoidRecentDays());
            queryWrapper.and(wrapper -> wrapper.isNull(WardrobeItemEntity::getLastWearDate)
                    .or()
                    .lt(WardrobeItemEntity::getLastWearDate, avoidAfter));
        }
        if (Boolean.TRUE.equals(dto.getPreferIdle())) {
            queryWrapper.orderByAsc(WardrobeItemEntity::getLastWearDate)
                    .orderByAsc(WardrobeItemEntity::getWearCount)
                    .orderByDesc(WardrobeItemEntity::getId);
        } else {
            queryWrapper.orderByAsc(WardrobeItemEntity::getWearCount)
                    .orderByDesc(WardrobeItemEntity::getId);
        }
        List<WardrobeItemEntity> itemList = wardrobeItemDao.list(queryWrapper);
        wardrobeItemImageService.applyCoverImages(itemList);
        return itemList;
    }

    private WardrobeItemEntity resolveLockedItem(Integer lockedItemId) {
        if (lockedItemId == null || lockedItemId <= 0) {
            return null;
        }
        WardrobeItemEntity item = wardrobeItemDao.queryById(lockedItemId);
        if (!WardrobeItemStatusEnum.isAvailable(item.getStatus())) {
            throw new BusinessException("锁定衣物不可用");
        }
        wardrobeItemImageService.applyCoverImages(List.of(item));
        return item;
    }

    private List<WardrobeItemEntity> buildSuggestionItems(Map<Integer, List<WardrobeItemEntity>> categoryMap,
                                                         WardrobeItemEntity lockedItem,
                                                         int offset) {
        LinkedHashMap<Integer, WardrobeItemEntity> selected = new LinkedHashMap<>();
        if (lockedItem != null) {
            selected.put(lockedItem.getId(), lockedItem);
        }
        boolean hasDress = selected.values().stream().anyMatch(item -> Objects.equals(item.getCategory(), CATEGORY_DRESS));
        if (!hasDress) {
            this.addByCategory(selected, categoryMap, CATEGORY_TOP, offset);
            this.addByCategory(selected, categoryMap, CATEGORY_BOTTOM, offset);
        }
        this.addByCategory(selected, categoryMap, CATEGORY_DRESS, offset);
        this.addByCategory(selected, categoryMap, CATEGORY_SHOES, offset);
        this.addByCategory(selected, categoryMap, CATEGORY_BAG, offset);
        this.addByCategory(selected, categoryMap, CATEGORY_ACCESSORY, offset);
        this.addByCategory(selected, categoryMap, CATEGORY_HAT, offset);
        this.addByCategory(selected, categoryMap, CATEGORY_JEWELRY, offset);
        return new ArrayList<>(selected.values());
    }

    private void addByCategory(Map<Integer, WardrobeItemEntity> selected,
                               Map<Integer, List<WardrobeItemEntity>> categoryMap,
                               int category,
                               int offset) {
        List<WardrobeItemEntity> items = categoryMap.get(category);
        if (items == null || items.isEmpty()) {
            return;
        }
        WardrobeItemEntity item = items.get(offset % items.size());
        selected.putIfAbsent(item.getId(), item);
    }

    private void applySort(LambdaQueryWrapper<WardrobeOutfitEntity> queryWrapper, String sortType) {
        if (StringUtils.equals(sortType, "recentWear")) {
            queryWrapper.orderByDesc(WardrobeOutfitEntity::getLastWearDate).orderByDesc(WardrobeOutfitEntity::getId);
        } else if (StringUtils.equals(sortType, "leastWear")) {
            queryWrapper.orderByAsc(WardrobeOutfitEntity::getWearCount).orderByDesc(WardrobeOutfitEntity::getId);
        } else if (StringUtils.equals(sortType, "mostWear")) {
            queryWrapper.orderByDesc(WardrobeOutfitEntity::getWearCount).orderByDesc(WardrobeOutfitEntity::getId);
        } else {
            queryWrapper.orderByDesc(WardrobeOutfitEntity::getId);
        }
    }

    private String buildSuggestionReason(WardrobeOutfitSuggestDto dto, WardrobeItemEntity lockedItem) {
        List<String> reasons = new ArrayList<>();
        if (lockedItem != null) {
            reasons.add("围绕「" + lockedItem.getItemName() + "」生成");
        }
        if (StringUtils.isNotBlank(dto.getWeatherText())) {
            reasons.add("参考天气：" + dto.getWeatherText());
        }
        if (Boolean.TRUE.equals(dto.getPreferIdle())) {
            reasons.add("优先唤醒闲置衣物");
        }
        if (dto.getAvoidRecentDays() != null && dto.getAvoidRecentDays() > 0) {
            reasons.add("避开近" + dto.getAvoidRecentDays() + "天穿过的衣物");
        }
        if (reasons.isEmpty()) {
            reasons.add("按少穿优先和分类完整度生成");
        }
        return String.join("；", reasons);
    }
}

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
import com.itwray.iw.wardrobe.model.vo.WardrobeMarkWornVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitSuggestionVo;
import com.itwray.iw.wardrobe.service.WardrobeItemAccessService;
import com.itwray.iw.wardrobe.service.WardrobeItemImageService;
import com.itwray.iw.wardrobe.service.WardrobeItemService;
import com.itwray.iw.wardrobe.service.WardrobeOutfitService;
import com.itwray.iw.wardrobe.service.WardrobeWearRecordService;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.UserUtils;
import com.itwray.iw.web.model.vo.PageVo;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    @org.springframework.beans.factory.annotation.Autowired
    private com.itwray.iw.wardrobe.dictionary.WardrobeSnapshotService snapshots;
    @org.springframework.beans.factory.annotation.Autowired
    private com.itwray.iw.wardrobe.dictionary.WardrobeDetailEditor detailEditor;


    private static final int OUTFIT_STATUS_ACTIVE = 1;
    private static final int CATEGORY_TOP = 1;
    private static final int CATEGORY_BOTTOM = 2;
    private static final int CATEGORY_DRESS = 3;
    private static final int CATEGORY_SHOES = 6;
    private static final int CATEGORY_ACCESSORY = 7;
    private static final int CATEGORY_HAT = 8;
    private static final int CATEGORY_BAG = 9;
    private static final int CATEGORY_JEWELRY = 10;
    private static final int SCORE_SEASON_MATCH = 5;
    private static final int SCORE_SCENE_MATCH = 3;
    private static final int SCORE_STYLE_MATCH = 2;
    private static final int SCORE_IDLE = 1;
    private static final int SCORE_RECENT_WEAR = -2;

    private final WardrobeOutfitDao wardrobeOutfitDao;
    private final WardrobeOutfitItemDao outfitItemDao;
    private final WardrobeItemDao wardrobeItemDao;
    private final WardrobeWearRecordService wearRecordService;
    private final WardrobeItemImageService wardrobeItemImageService;
    private final WardrobeItemService wardrobeItemService;
    private final WardrobeItemAccessService wardrobeItemAccessService;

    public WardrobeOutfitServiceImpl(WardrobeOutfitDao wardrobeOutfitDao,
                                     WardrobeOutfitItemDao outfitItemDao,
                                     WardrobeItemDao wardrobeItemDao,
                                     @Lazy WardrobeWearRecordService wearRecordService,
                                     WardrobeItemImageService wardrobeItemImageService,
                                     WardrobeItemService wardrobeItemService,
                                     WardrobeItemAccessService wardrobeItemAccessService) {
        this.wardrobeOutfitDao = wardrobeOutfitDao;
        this.outfitItemDao = outfitItemDao;
        this.wardrobeItemDao = wardrobeItemDao;
        this.wearRecordService = wearRecordService;
        this.wardrobeItemImageService = wardrobeItemImageService;
        this.wardrobeItemService = wardrobeItemService;
        this.wardrobeItemAccessService = wardrobeItemAccessService;
    }

    @Override
    @Transactional
    public Integer add(WardrobeOutfitAddDto dto) {
        com.itwray.iw.wardrobe.dictionary.WardrobeSnapshotService.rejectLegacyValues(dto, "sceneTags", "styleTags");
        snapshots.lockItemForWrite(UserUtils.getUserId(), null, UserUtils.getUserId(), null);
        List<WardrobeItemEntity> itemList = this.querySelectedItems(dto.getItemIds());
        WardrobeOutfitEntity entity = BeanUtil.copyProperties(dto, WardrobeOutfitEntity.class);
        this.fillOutfitDefaults(entity, itemList);
        var prepared = snapshots.prepareHead(UserUtils.getUserId(), true, dto.getDictionarySelection(), dto.getDictionaryChanges(), dto.getDictionaryContext(), true);
        entity.setDictionarySnapshotStatus(1);
        wardrobeOutfitDao.save(entity);
        snapshots.save("OUTFIT", entity.getId(), prepared);
        this.saveOutfitItems(entity.getId(), itemList);
        return entity.getId();
    }

    @Override
    @Transactional
    public void update(WardrobeOutfitUpdateDto dto) {
        com.itwray.iw.wardrobe.dictionary.WardrobeSnapshotService.rejectLegacyValues(dto, "sceneTags", "styleTags");
        snapshots.lockItemForWrite(UserUtils.getUserId(), null, UserUtils.getUserId(), null);
        WardrobeOutfitEntity current = wardrobeOutfitDao.queryById(dto.getId());
        if (dto.getItemIds() != null) throw new BusinessException("编辑明细请刷新后使用新选择方式");
        var prepared = snapshots.prepareHead(UserUtils.getUserId(), false, dto.getDictionarySelection(), dto.getDictionaryChanges(), dto.getDictionaryContext(), true);
        int count = detailEditor.replace(com.itwray.iw.wardrobe.mapper.WardrobeDetailMapper.Kind.OUTFIT,
                dto.getId(), UserUtils.getUserId(), dto.getItems(), dto.getItemSetVersion());
        WardrobeOutfitEntity entity = BeanUtil.copyProperties(current, WardrobeOutfitEntity.class);
        BeanUtil.copyProperties(dto, entity, cn.hutool.core.bean.copier.CopyOptions.create().ignoreNullValue());
        if (StringUtils.isBlank(entity.getOutfitName())) throw new BusinessException("搭配名称不能为空");
        entity.setSceneTags(current.getSceneTags()); entity.setStyleTags(current.getStyleTags());
        entity.setItemCount(count); entity.setDictionarySnapshotStatus(1);
        wardrobeOutfitDao.updateById(entity); snapshots.save("OUTFIT", entity.getId(), prepared);
    }

    @Override
    @Transactional
    public void delete(Integer id) {
        snapshots.lockItemForWrite(UserUtils.getUserId(), null, UserUtils.getUserId(), null);
        wardrobeOutfitDao.queryById(id);
        outfitItemDao.lambdaUpdate().eq(WardrobeOutfitItemEntity::getOutfitId, id).remove();
        wardrobeOutfitDao.removeById(id);
    }

    @Override
    @Transactional
    public Integer copy(Integer id) {
        snapshots.lockItemForWrite(UserUtils.getUserId(), null, UserUtils.getUserId(), null);
        WardrobeOutfitEntity source = wardrobeOutfitDao.queryById(id);
        WardrobeOutfitEntity target = BeanUtil.copyProperties(source, WardrobeOutfitEntity.class);
        target.setId(null); target.setWearCount(0); target.setLastWearDate(null);
        String name = source.getOutfitName();
        target.setOutfitName(name.substring(0, Math.min(name.length(), 60)) + " 副本");
        wardrobeOutfitDao.save(target);
        target.setItemCount(detailEditor.copy(com.itwray.iw.wardrobe.mapper.WardrobeDetailMapper.Kind.OUTFIT, id, target.getId(), UserUtils.getUserId()));
        wardrobeOutfitDao.updateById(target);
        snapshots.copyTags("OUTFIT", id, target.getId()); return target.getId();
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
        com.itwray.iw.wardrobe.dictionary.WardrobeHistoryFilters.tag(queryWrapper, dto.getSceneFilterKey(), "SCENE", com.itwray.iw.wardrobe.dictionary.WardrobeOptionsController.BusinessType.OUTFIT);
        com.itwray.iw.wardrobe.dictionary.WardrobeHistoryFilters.tag(queryWrapper, dto.getStyleFilterKey(), "STYLE", com.itwray.iw.wardrobe.dictionary.WardrobeOptionsController.BusinessType.OUTFIT);
        this.applySort(queryWrapper, dto.getSortType());
        PageVo<WardrobeOutfitPageVo> pageVo = wardrobeOutfitDao.page(dto, queryWrapper, WardrobeOutfitPageVo.class);
        this.fillOutfitItems(pageVo.getRecords());
        snapshots.attachAll(pageVo.getRecords(), "OUTFIT");
        return pageVo;
    }

    @Override
    public WardrobeOutfitDetailVo detail(Integer id) {
        WardrobeOutfitDetailVo detailVo = BeanUtil.copyProperties(wardrobeOutfitDao.queryById(id), WardrobeOutfitDetailVo.class);
        List<WardrobeOutfitItemVo> itemList = this.queryOutfitItemVos(id);
        this.fillItemAvailability(itemList);
        detailVo.setItemList(itemList);
        detailVo.setItemSetVersion(com.itwray.iw.wardrobe.dictionary.WardrobeDetailEditor.version(itemList));
        snapshots.attachAll(List.of(detailVo), "OUTFIT");
        return detailVo;
    }

    @Override
    public List<WardrobeOutfitSuggestionVo> suggest(WardrobeOutfitSuggestDto dto) {
        int limit = dto.getLimit() == null || dto.getLimit() <= 0 ? 5 : Math.min(dto.getLimit(), 10);
        List<WardrobeItemEntity> candidates = this.querySuggestCandidates(dto);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<WardrobeItemEntity>> categoryMap = rankSuggestCandidates(
                candidates, dto, LocalDate.now()).stream()
                .collect(Collectors.groupingBy(WardrobeOutfitServiceImpl::matchKind, LinkedHashMap::new, Collectors.toList()));
        WardrobeItemEntity lockedItem = this.resolveLockedItem(dto.getLockedItemId());

        List<List<WardrobeItemEntity>> suggestionItems = this.buildSuggestionItemSets(categoryMap, lockedItem, limit);
        List<WardrobeOutfitSuggestionVo> result = new ArrayList<>();
        for (List<WardrobeItemEntity> items : suggestionItems) {
            WardrobeOutfitSuggestionVo vo = new WardrobeOutfitSuggestionVo();
            vo.setSuggestionName("灵感搭配 " + (result.size() + 1));
            vo.setReason(this.buildSuggestionReason(dto, lockedItem));
            vo.setItemList(items.stream().map(this::toOutfitItemVo).toList());
            result.add(vo);
        }
        return result;
    }

    @Override
    public WardrobeMarkWornVo markWorn(WardrobeMarkWornDto dto) {
        snapshots.lockItemForWrite(UserUtils.getUserId(), null, UserUtils.getUserId(), null);
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
                .in(WardrobeItemEntity::getUserId, wardrobeItemAccessService.resolveVisibleOwnerIds(false))
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
            snapshots.copyScalar(relation, item);
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
        List<WardrobeOutfitItemVo> allItems = itemMap.values().stream().flatMap(List::stream).toList();
        this.fillItemAvailability(allItems);
        outfitList.forEach(outfit -> outfit.setItemList(itemMap.getOrDefault(outfit.getId(), List.of())));
    }

    private void fillItemAvailability(List<WardrobeOutfitItemVo> itemList) {
        if (itemList == null || itemList.isEmpty()) {
            return;
        }
        List<Integer> itemIds = itemList.stream().map(WardrobeOutfitItemVo::getItemId)
                .filter(Objects::nonNull).distinct().toList();
        Set<Integer> availableItemIds = wardrobeItemService.queryActiveItemsByIds(itemIds).stream()
                .map(WardrobeItemEntity::getId).collect(Collectors.toSet());
        Map<Integer, Integer> activeOwnerIds = wardrobeItemService.queryHistoricalActiveOwnerIds(itemIds);
        itemList.forEach(item -> item.setAvailability(availableItemIds.contains(item.getItemId())
                ? "available" : activeOwnerIds.containsKey(item.getItemId()) ? "transferred" : "deleted"));
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
        WardrobeOutfitItemVo vo = BeanUtil.copyProperties(entity, WardrobeOutfitItemVo.class);
        vo.setDetailId(entity.getId()); return vo;
    }

    private WardrobeOutfitItemVo toOutfitItemVo(WardrobeItemEntity entity) {
        WardrobeOutfitItemVo vo = new WardrobeOutfitItemVo();
        vo.setItemId(entity.getId());
        vo.setItemName(entity.getItemName());
        vo.setItemImage(entity.getItemImage());
        vo.setCategory(entity.getCategory());
        vo.setItemStyle(entity.getItemStyle());
        snapshots.copyScalar(vo, entity);
        return vo;
    }

    private List<WardrobeItemEntity> querySuggestCandidates(WardrobeOutfitSuggestDto dto) {
        LambdaQueryWrapper<WardrobeItemEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(WardrobeItemEntity::getStatus, WardrobeItemStatusEnum.availableCodes())
                .in(WardrobeItemEntity::getUserId, wardrobeItemAccessService.resolveVisibleOwnerIds(false));
        if (dto.getExcludeItemIds() != null && !dto.getExcludeItemIds().isEmpty()) {
            List<Integer> excludeIds = dto.getExcludeItemIds().stream().filter(Objects::nonNull).toList();
            if (!excludeIds.isEmpty()) {
                queryWrapper.notIn(WardrobeItemEntity::getId, excludeIds);
            }
        }
        queryWrapper.orderByDesc(WardrobeItemEntity::getId);
        List<WardrobeItemEntity> itemList = wardrobeItemDao.list(queryWrapper);
        wardrobeItemImageService.applyCoverImages(itemList);
        var tags = snapshots.tagsFor("ITEM", itemList.stream().map(WardrobeItemEntity::getId).toList());
        java.util.Map<Integer,java.util.List<com.itwray.iw.wardrobe.mapper.WardrobeSnapshotMapper.StoredTag>> byId = tags.stream().collect(Collectors.groupingBy(com.itwray.iw.wardrobe.mapper.WardrobeSnapshotMapper.StoredTag::businessId));
        for (var item : itemList) {
            var stored = byId.getOrDefault(item.getId(), List.of());
            item.setWardrobeSceneNames(stored.stream().filter(t -> t.dictType() == 5004).map(t -> t.nameSnapshot()).toList());
            item.setWardrobeStyleNames(stored.stream().filter(t -> t.dictType() == 5005).map(t -> t.nameSnapshot()).toList());
        }
        return itemList;
    }

    static List<WardrobeItemEntity> rankSuggestCandidates(List<WardrobeItemEntity> candidates,
                                                           WardrobeOutfitSuggestDto dto,
                                                           LocalDate currentDate) {
        LocalDate today = Objects.requireNonNullElseGet(currentDate, LocalDate::now);
        int recentDays = dto.getAvoidRecentDays() == null ? 0 : Math.max(dto.getAvoidRecentDays(), 0);
        LocalDate recentCutoff = recentDays > 0 ? today.minusDays(recentDays) : null;
        Comparator<WardrobeItemEntity> comparator = Comparator
                .comparingInt((WardrobeItemEntity item) -> scoreSuggestCandidate(item, dto, recentCutoff))
                .reversed()
                .thenComparing(item -> Objects.requireNonNullElse(item.getWearCount(), 0))
                .thenComparing(WardrobeItemEntity::getLastWearDate,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(WardrobeItemEntity::getId,
                        Comparator.nullsLast(Comparator.reverseOrder()));
        return candidates.stream().sorted(comparator).toList();
    }

    private static int scoreSuggestCandidate(WardrobeItemEntity item,
                                             WardrobeOutfitSuggestDto dto,
                                             LocalDate recentCutoff) {
        int score = 0;
        if (containsAnyExactTag(item.getSeasonTags(), dto.getSeason())) {
            score += SCORE_SEASON_MATCH;
        }
        if (item.getWardrobeSceneNames() != null ? item.getWardrobeSceneNames().contains(dto.getScene()) : containsAnyExactTag(item.getSceneTags(), dto.getScene())) {
            score += SCORE_SCENE_MATCH;
        }
        if (item.getWardrobeStyleNames() != null ? item.getWardrobeStyleNames().contains(dto.getStyle()) : containsAnyExactTag(item.getStyleTags(), dto.getStyle())) {
            score += SCORE_STYLE_MATCH;
        }
        if (Boolean.TRUE.equals(dto.getPreferIdle()) && WardrobeItemStatusEnum.isIdle(item.getStatus())) {
            score += SCORE_IDLE;
        }
        if (isRecentlyWorn(item, recentCutoff)) {
            score += SCORE_RECENT_WEAR;
        }
        return score;
    }

    static boolean containsAnyExactTag(String itemTags, String requestedTags) {
        Set<String> itemTagSet = parseTags(itemTags);
        if (itemTagSet.isEmpty()) {
            return false;
        }
        return parseTags(requestedTags).stream().anyMatch(itemTagSet::contains);
    }

    private static Set<String> parseTags(String tags) {
        if (StringUtils.isBlank(tags)) {
            return Set.of();
        }
        return java.util.Arrays.stream(StringUtils.split(tags, ','))
                .map(StringUtils::trim)
                .filter(StringUtils::isNotBlank)
                .map(StringUtils::lowerCase)
                .collect(Collectors.toSet());
    }

    private static boolean isRecentlyWorn(WardrobeItemEntity item, LocalDate recentCutoff) {
        return recentCutoff != null && item.getLastWearDate() != null
                && !item.getLastWearDate().isBefore(recentCutoff);
    }

    private WardrobeItemEntity resolveLockedItem(Integer lockedItemId) {
        if (lockedItemId == null || lockedItemId <= 0) {
            return null;
        }
        WardrobeItemEntity item = wardrobeItemDao.queryByIdInOwnerIds(
                lockedItemId, wardrobeItemAccessService.resolveVisibleOwnerIds(false));
        if (!WardrobeItemStatusEnum.isAvailable(item.getStatus())) {
            throw new BusinessException("锁定衣物不可用");
        }
        wardrobeItemImageService.applyCoverImages(List.of(item));
        return item;
    }

    private List<List<WardrobeItemEntity>> buildSuggestionItemSets(
            Map<Integer, List<WardrobeItemEntity>> categoryMap,
            WardrobeItemEntity lockedItem,
            int limit) {
        List<List<WardrobeItemEntity>> separateCores = this.buildSeparateCores(categoryMap, lockedItem, limit);
        List<List<WardrobeItemEntity>> dressCores = this.buildDressCores(categoryMap, lockedItem, limit);
        List<List<WardrobeItemEntity>> orderedCores = new ArrayList<>();
        int coreCount = Math.max(separateCores.size(), dressCores.size());
        for (int i = 0; i < coreCount; i++) {
            if (i < separateCores.size()) {
                orderedCores.add(separateCores.get(i));
            }
            if (i < dressCores.size()) {
                orderedCores.add(dressCores.get(i));
            }
        }

        List<List<WardrobeItemEntity>> result = new ArrayList<>();
        Set<List<Integer>> fingerprints = new LinkedHashSet<>();
        for (int offset = 0; offset < limit && result.size() < limit; offset++) {
            for (List<WardrobeItemEntity> core : orderedCores) {
                List<WardrobeItemEntity> items = this.completeSuggestion(core, categoryMap, lockedItem, offset);
                List<Integer> fingerprint = items.stream().map(WardrobeItemEntity::getId).sorted().toList();
                if (fingerprints.add(fingerprint)) {
                    result.add(items);
                    if (result.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return result;
    }

    private List<List<WardrobeItemEntity>> buildSeparateCores(
            Map<Integer, List<WardrobeItemEntity>> categoryMap,
            WardrobeItemEntity lockedItem,
            int limit) {
        if (lockedItem != null && Objects.equals(matchKind(lockedItem), CATEGORY_DRESS)) {
            return List.of();
        }
        List<WardrobeItemEntity> tops = lockedItem != null && Objects.equals(matchKind(lockedItem), CATEGORY_TOP)
                ? List.of(lockedItem) : categoryMap.getOrDefault(CATEGORY_TOP, List.of());
        List<WardrobeItemEntity> bottoms = lockedItem != null && Objects.equals(matchKind(lockedItem), CATEGORY_BOTTOM)
                ? List.of(lockedItem) : categoryMap.getOrDefault(CATEGORY_BOTTOM, List.of());
        if (tops.isEmpty() || bottoms.isEmpty()) {
            return List.of();
        }

        List<List<WardrobeItemEntity>> result = new ArrayList<>();
        for (int diagonal = 0; diagonal < tops.size() + bottoms.size() - 1 && result.size() < limit; diagonal++) {
            for (int topIndex = 0; topIndex <= diagonal && result.size() < limit; topIndex++) {
                int bottomIndex = diagonal - topIndex;
                if (topIndex < tops.size() && bottomIndex < bottoms.size()) {
                    result.add(List.of(tops.get(topIndex), bottoms.get(bottomIndex)));
                }
            }
        }
        return result;
    }

    private List<List<WardrobeItemEntity>> buildDressCores(
            Map<Integer, List<WardrobeItemEntity>> categoryMap,
            WardrobeItemEntity lockedItem,
            int limit) {
        if (lockedItem != null) {
            if (Objects.equals(matchKind(lockedItem), CATEGORY_DRESS)) {
                return List.of(List.of(lockedItem));
            }
            if (Objects.equals(matchKind(lockedItem), CATEGORY_TOP)
                    || Objects.equals(matchKind(lockedItem), CATEGORY_BOTTOM)) {
                return List.of();
            }
        }
        return categoryMap.getOrDefault(CATEGORY_DRESS, List.of()).stream()
                .limit(limit)
                .map(List::of)
                .toList();
    }

    private List<WardrobeItemEntity> completeSuggestion(
            List<WardrobeItemEntity> core,
            Map<Integer, List<WardrobeItemEntity>> categoryMap,
            WardrobeItemEntity lockedItem,
            int offset) {
        LinkedHashMap<Integer, WardrobeItemEntity> selected = new LinkedHashMap<>();
        if (lockedItem != null) {
            selected.put(lockedItem.getId(), lockedItem);
        }
        core.forEach(item -> selected.putIfAbsent(item.getId(), item));
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
        if (selected.values().stream().anyMatch(item -> Objects.equals(matchKind(item), category))) {
            return;
        }
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
        if (StringUtils.isNotBlank(dto.getSeason()) || StringUtils.isNotBlank(dto.getScene())
                || StringUtils.isNotBlank(dto.getStyle())) {
            reasons.add("按季节、场景和风格的精确标签匹配度排序");
        }
        if (StringUtils.isNotBlank(dto.getWeatherText())) {
            reasons.add("参考天气：" + dto.getWeatherText());
        }
        if (Boolean.TRUE.equals(dto.getPreferIdle())) {
            reasons.add("优先唤醒闲置衣物");
        }
        if (dto.getAvoidRecentDays() != null && dto.getAvoidRecentDays() > 0) {
            reasons.add("降低近" + dto.getAvoidRecentDays() + "天已穿衣物的优先级");
        }
        if (reasons.isEmpty()) {
            reasons.add("按少穿优先和分类完整度生成");
        }
        return String.join("；", reasons);
    }
    private static Integer matchKind(WardrobeItemEntity item) {
        return item.getCategoryMatchKindSnapshot() == null ? item.getCategory() : item.getCategoryMatchKindSnapshot();
    }
}

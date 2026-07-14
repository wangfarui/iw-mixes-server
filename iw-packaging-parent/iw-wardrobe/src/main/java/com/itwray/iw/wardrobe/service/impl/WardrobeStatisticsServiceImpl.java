package com.itwray.iw.wardrobe.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.itwray.iw.wardrobe.dao.WardrobeItemDao;
import com.itwray.iw.wardrobe.dao.WardrobeOutfitDao;
import com.itwray.iw.wardrobe.dao.WardrobeWearRecordDao;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeOutfitEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeWearRecordEntity;
import com.itwray.iw.wardrobe.model.enums.WardrobeItemStatusEnum;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemPageVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeStatisticItemVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeStatisticsOverviewVo;
import com.itwray.iw.wardrobe.service.WardrobeStatisticsService;
import com.itwray.iw.wardrobe.service.WardrobeWearRecordService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 衣柜统计服务实现
 *
 * @author codex
 * @since 2026-07-02
 */
@Service
public class WardrobeStatisticsServiceImpl implements WardrobeStatisticsService {

    private static final int OUTFIT_STATUS_ACTIVE = 1;
    private static final int RECORD_TYPE_PLAN = 1;
    private static final int RECORD_TYPE_WORN = 2;

    private final WardrobeItemDao wardrobeItemDao;
    private final WardrobeOutfitDao wardrobeOutfitDao;
    private final WardrobeWearRecordDao wearRecordDao;
    private final WardrobeWearRecordService wearRecordService;

    public WardrobeStatisticsServiceImpl(WardrobeItemDao wardrobeItemDao,
                                         WardrobeOutfitDao wardrobeOutfitDao,
                                         WardrobeWearRecordDao wearRecordDao,
                                         WardrobeWearRecordService wearRecordService) {
        this.wardrobeItemDao = wardrobeItemDao;
        this.wardrobeOutfitDao = wardrobeOutfitDao;
        this.wearRecordDao = wearRecordDao;
        this.wearRecordService = wearRecordService;
    }

    @Override
    public WardrobeStatisticsOverviewVo overview() {
        List<WardrobeItemEntity> activeItems = wardrobeItemDao.lambdaQuery()
                .in(WardrobeItemEntity::getStatus, WardrobeItemStatusEnum.availableCodes())
                .list();
        Long totalItems = wardrobeItemDao.lambdaQuery().count();
        Long totalOutfits = wardrobeOutfitDao.lambdaQuery()
                .eq(WardrobeOutfitEntity::getStatus, OUTFIT_STATUS_ACTIVE)
                .count();
        Long totalWearRecords = wearRecordDao.lambdaQuery()
                .eq(WardrobeWearRecordEntity::getRecordType, RECORD_TYPE_WORN)
                .count();
        Long plannedRecords = wearRecordDao.lambdaQuery()
                .eq(WardrobeWearRecordEntity::getRecordType, RECORD_TYPE_PLAN)
                .count();
        BigDecimal totalValue = activeItems.stream()
                .map(WardrobeItemEntity::getPrice)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalWearCount = activeItems.stream()
                .map(WardrobeItemEntity::getWearCount)
                .filter(Objects::nonNull)
                .reduce(0, Integer::sum);

        WardrobeStatisticsOverviewVo vo = new WardrobeStatisticsOverviewVo();
        vo.setTotalItems(totalItems);
        vo.setActiveItems((long) activeItems.size());
        vo.setTotalOutfits(totalOutfits);
        vo.setTotalWearRecords(totalWearRecords);
        vo.setNeverWornItems(activeItems.stream().filter(item -> Objects.equals(item.getWearCount(), 0)).count());
        vo.setIdleItems(activeItems.stream().filter(this::isIdleItem).count());
        vo.setEliminatedItems(this.countByStatus(WardrobeItemStatusEnum.ELIMINATED.getCode()));
        vo.setPlannedRecords(plannedRecords);
        vo.setTotalValue(totalValue);
        vo.setAvgItemPrice(activeItems.isEmpty() ? BigDecimal.ZERO : totalValue.divide(BigDecimal.valueOf(activeItems.size()), 2, RoundingMode.HALF_UP));
        vo.setAvgCostPerWear(totalWearCount <= 0 ? BigDecimal.ZERO : totalValue.divide(BigDecimal.valueOf(totalWearCount), 2, RoundingMode.HALF_UP));
        vo.setCategoryStats(this.buildCodeStats(activeItems.stream().map(WardrobeItemEntity::getCategory).toList()));
        vo.setItemStyleStats(this.buildCodeStats(activeItems.stream().map(WardrobeItemEntity::getItemStyle).toList()));
        vo.setColorStats(this.buildColorStats(activeItems));
        vo.setSeasonStats(this.buildTagStats(activeItems.stream().map(WardrobeItemEntity::getSeasonTags).toList()));
        vo.setSceneStats(this.buildTagStats(activeItems.stream().map(WardrobeItemEntity::getSceneTags).toList()));
        vo.setStyleStats(this.buildTagStats(activeItems.stream().map(WardrobeItemEntity::getStyleTags).toList()));
        vo.setStatusStats(this.buildStatusStats());
        vo.setBrandStats(this.buildTextStats(activeItems.stream().map(WardrobeItemEntity::getBrand).toList(), 8));
        vo.setStorageStats(this.buildTextStats(activeItems.stream().map(WardrobeItemEntity::getStorageLocation).toList(), 8));
        vo.setMostWornItems(activeItems.stream()
                .sorted(Comparator.comparing(WardrobeItemEntity::getWearCount, Comparator.nullsFirst(Integer::compareTo)).reversed())
                .limit(5)
                .map(item -> BeanUtil.copyProperties(item, WardrobeItemPageVo.class))
                .toList());
        vo.setLeastWornItems(activeItems.stream()
                .sorted(Comparator.comparing(WardrobeItemEntity::getWearCount, Comparator.nullsFirst(Integer::compareTo)))
                .limit(5)
                .map(item -> BeanUtil.copyProperties(item, WardrobeItemPageVo.class))
                .toList());
        vo.setIdleItemList(activeItems.stream()
                .filter(this::isIdleItem)
                .sorted(Comparator.comparing(WardrobeItemEntity::getLastWearDate, Comparator.nullsFirst(LocalDate::compareTo)))
                .limit(8)
                .map(item -> BeanUtil.copyProperties(item, WardrobeItemPageVo.class))
                .toList());
        vo.setHighCostLowWearItems(activeItems.stream()
                .sorted(Comparator.comparing(WardrobeItemEntity::getWearCount, Comparator.nullsFirst(Integer::compareTo))
                        .thenComparing(WardrobeItemEntity::getPrice, Comparator.nullsFirst(BigDecimal::compareTo).reversed()))
                .limit(8)
                .map(item -> BeanUtil.copyProperties(item, WardrobeItemPageVo.class))
                .toList());
        vo.setRecentRecords(wearRecordService.recent(5));
        return vo;
    }

    private List<WardrobeStatisticItemVo> buildCodeStats(List<Integer> codeList) {
        Map<Integer, Long> countMap = codeList.stream()
                .collect(Collectors.groupingBy(code -> Objects.requireNonNullElse(code, 0), Collectors.counting()));
        return countMap.entrySet().stream()
                .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                .map(entry -> new WardrobeStatisticItemVo(String.valueOf(entry.getKey()), entry.getValue().intValue()))
                .toList();
    }

    private List<WardrobeStatisticItemVo> buildColorStats(List<WardrobeItemEntity> itemList) {
        Map<String, Long> countMap = itemList.stream()
                .collect(Collectors.groupingBy(item -> StringUtils.defaultIfBlank(item.getColorName(), "未设置"), Collectors.counting()));
        return countMap.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(8)
                .map(entry -> new WardrobeStatisticItemVo(entry.getKey(), entry.getValue().intValue()))
                .toList();
    }

    private List<WardrobeStatisticItemVo> buildTagStats(List<String> tagValues) {
        List<String> tags = new ArrayList<>();
        tagValues.stream()
                .filter(StringUtils::isNotBlank)
                .forEach(value -> {
                    for (String tag : value.split(",")) {
                        if (StringUtils.isNotBlank(tag)) {
                            tags.add(tag.trim());
                        }
                    }
                });
        return this.buildTextStats(tags, 10);
    }

    private List<WardrobeStatisticItemVo> buildTextStats(List<String> values, int limit) {
        Map<String, Long> countMap = values.stream()
                .map(value -> StringUtils.defaultIfBlank(value, "未设置"))
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
        return countMap.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> new WardrobeStatisticItemVo(entry.getKey(), entry.getValue().intValue()))
                .toList();
    }

    private List<WardrobeStatisticItemVo> buildStatusStats() {
        return List.of(
                new WardrobeStatisticItemVo(WardrobeItemStatusEnum.WEARING.getName(), this.countByStatus(WardrobeItemStatusEnum.WEARING.getCode()).intValue()),
                new WardrobeStatisticItemVo(WardrobeItemStatusEnum.IDLE.getName(), this.countByStatus(WardrobeItemStatusEnum.IDLE.getCode()).intValue()),
                new WardrobeStatisticItemVo(WardrobeItemStatusEnum.ELIMINATED.getName(), this.countByStatus(WardrobeItemStatusEnum.ELIMINATED.getCode()).intValue())
        );
    }

    private Long countByStatus(int status) {
        return wardrobeItemDao.lambdaQuery()
                .eq(WardrobeItemEntity::getStatus, status)
                .count();
    }

    private boolean isIdleItem(WardrobeItemEntity item) {
        if (WardrobeItemStatusEnum.isIdle(item.getStatus())) {
            return true;
        }
        LocalDate lastWearDate = item.getLastWearDate();
        return lastWearDate == null || lastWearDate.isBefore(LocalDate.now().minusDays(60));
    }
}

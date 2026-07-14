package com.itwray.iw.wardrobe.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 衣柜统计概览 VO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@Schema(name = "衣柜统计概览VO")
public class WardrobeStatisticsOverviewVo {

    private Long totalItems;

    private Long activeItems;

    private Long totalOutfits;

    private Long totalWearRecords;

    private Long neverWornItems;

    private Long idleItems;

    private Long eliminatedItems;

    private Long plannedRecords;

    private BigDecimal totalValue;

    private BigDecimal avgItemPrice;

    private BigDecimal avgCostPerWear;

    private List<WardrobeStatisticItemVo> categoryStats;

    private List<WardrobeStatisticItemVo> itemStyleStats;

    private List<WardrobeStatisticItemVo> colorStats;

    private List<WardrobeStatisticItemVo> seasonStats;

    private List<WardrobeStatisticItemVo> sceneStats;

    private List<WardrobeStatisticItemVo> styleStats;

    private List<WardrobeStatisticItemVo> statusStats;

    private List<WardrobeStatisticItemVo> brandStats;

    private List<WardrobeStatisticItemVo> storageStats;

    private List<WardrobeItemPageVo> mostWornItems;

    private List<WardrobeItemPageVo> leastWornItems;

    private List<WardrobeItemPageVo> idleItemList;

    private List<WardrobeItemPageVo> highCostLowWearItems;

    private List<WardrobeWearRecordVo> recentRecords;
}

package com.itwray.iw.wardrobe.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 衣柜标签汇总 VO
 *
 * @author codex
 * @since 2026-07-03
 */
@Data
@Schema(name = "衣柜标签汇总VO")
public class WardrobeTagSummaryVo {

    private List<String> brands;

    private List<String> colors;

    private List<String> materials;

    private List<String> purchaseChannels;

    private List<String> storageLocations;

    private List<String> customTags;
}

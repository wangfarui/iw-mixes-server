package com.itwray.iw.wardrobe.controller;

import com.itwray.iw.wardrobe.model.vo.WardrobeStatisticsOverviewVo;
import com.itwray.iw.wardrobe.service.WardrobeStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 衣柜统计接口
 *
 * @author codex
 * @since 2026-07-02
 */
@RestController
@RequestMapping("/wardrobe/statistics")
@Tag(name = "衣柜统计接口")
public class WardrobeStatisticsController {

    private final WardrobeStatisticsService wardrobeStatisticsService;

    public WardrobeStatisticsController(WardrobeStatisticsService wardrobeStatisticsService) {
        this.wardrobeStatisticsService = wardrobeStatisticsService;
    }

    @GetMapping("/overview")
    @Operation(summary = "衣柜统计概览")
    public WardrobeStatisticsOverviewVo overview() {
        return wardrobeStatisticsService.overview();
    }
}

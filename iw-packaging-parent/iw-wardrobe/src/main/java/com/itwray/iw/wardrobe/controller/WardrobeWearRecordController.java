package com.itwray.iw.wardrobe.controller;

import com.itwray.iw.wardrobe.model.dto.WardrobeMarkWornDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeWearRecordAddDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeWearRecordCopyDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeWearRecordMonthDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeWearRecordUpdateDto;
import com.itwray.iw.wardrobe.model.vo.WardrobeWearRecordVo;
import com.itwray.iw.wardrobe.service.WardrobeWearRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 穿着记录接口
 *
 * @author codex
 * @since 2026-07-02
 */
@RestController
@RequestMapping("/wardrobe/wear-record")
@Validated
@Tag(name = "穿着记录接口")
public class WardrobeWearRecordController {

    private final WardrobeWearRecordService wearRecordService;

    public WardrobeWearRecordController(WardrobeWearRecordService wearRecordService) {
        this.wearRecordService = wearRecordService;
    }

    @PostMapping("/add")
    @Operation(summary = "新增穿着计划或记录")
    public Integer add(@RequestBody @Valid WardrobeWearRecordAddDto dto) {
        return wearRecordService.add(dto);
    }

    @PutMapping("/update")
    @Operation(summary = "修改穿着计划或记录")
    public void update(@RequestBody @Valid WardrobeWearRecordUpdateDto dto) {
        wearRecordService.update(dto);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除穿着计划或记录")
    public void delete(@RequestParam("id") Integer id) {
        wearRecordService.delete(id);
    }

    @GetMapping("/detail")
    @Operation(summary = "查询穿着记录详情")
    public WardrobeWearRecordVo detail(@RequestParam("id") Integer id) {
        return wearRecordService.detail(id);
    }

    @PostMapping("/copy")
    @Operation(summary = "复制穿着计划或记录")
    public Integer copy(@RequestBody @Valid WardrobeWearRecordCopyDto dto) {
        return wearRecordService.copy(dto);
    }

    @PostMapping("/month")
    @Operation(summary = "按月查询穿着计划和记录")
    public List<WardrobeWearRecordVo> month(@RequestBody WardrobeWearRecordMonthDto dto) {
        return wearRecordService.month(dto);
    }

    @GetMapping("/today")
    @Operation(summary = "查询今日穿搭")
    public List<WardrobeWearRecordVo> today() {
        return wearRecordService.today();
    }

    @PostMapping("/markWorn")
    @Operation(summary = "直接标记已穿")
    public Integer markWorn(@RequestBody WardrobeMarkWornDto dto) {
        return wearRecordService.markWorn(dto);
    }

    @PutMapping("/markRecordWorn")
    @Operation(summary = "将计划标记为已穿")
    public void markRecordWorn(@RequestParam("id") Integer id) {
        wearRecordService.markRecordWorn(id);
    }
}

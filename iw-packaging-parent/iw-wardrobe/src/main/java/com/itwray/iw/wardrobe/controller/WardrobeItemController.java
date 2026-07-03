package com.itwray.iw.wardrobe.controller;

import com.itwray.iw.wardrobe.model.dto.WardrobeItemAddDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemBatchAddDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemPageDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemUpdateDto;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemDetailVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemPageVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeTagSummaryVo;
import com.itwray.iw.wardrobe.service.WardrobeItemService;
import com.itwray.iw.web.model.vo.PageVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 衣物接口
 *
 * @author codex
 * @since 2026-07-02
 */
@RestController
@RequestMapping("/wardrobe/item")
@Validated
@Tag(name = "衣物接口")
public class WardrobeItemController {

    private final WardrobeItemService wardrobeItemService;

    public WardrobeItemController(WardrobeItemService wardrobeItemService) {
        this.wardrobeItemService = wardrobeItemService;
    }

    @PostMapping("/add")
    @Operation(summary = "新增衣物")
    public Integer add(@RequestBody @Valid WardrobeItemAddDto dto) {
        return wardrobeItemService.add(dto);
    }

    @PostMapping("/batchAdd")
    @Operation(summary = "批量新增衣物")
    public java.util.List<Integer> batchAdd(@RequestBody @Valid WardrobeItemBatchAddDto dto) {
        return wardrobeItemService.batchAdd(dto);
    }

    @PutMapping("/update")
    @Operation(summary = "修改衣物")
    public void update(@RequestBody @Valid WardrobeItemUpdateDto dto) {
        wardrobeItemService.update(dto);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除衣物")
    public void delete(@RequestParam("id") Integer id) {
        wardrobeItemService.delete(id);
    }

    @PostMapping("/page")
    @Operation(summary = "分页查询衣物")
    public PageVo<WardrobeItemPageVo> page(@RequestBody @Valid WardrobeItemPageDto dto) {
        return wardrobeItemService.page(dto);
    }

    @GetMapping("/detail")
    @Operation(summary = "查询衣物详情")
    public WardrobeItemDetailVo detail(@RequestParam("id") Integer id) {
        return wardrobeItemService.detail(id);
    }

    @GetMapping("/tags")
    @Operation(summary = "查询衣柜标签汇总")
    public WardrobeTagSummaryVo tagSummary() {
        return wardrobeItemService.tagSummary();
    }
}

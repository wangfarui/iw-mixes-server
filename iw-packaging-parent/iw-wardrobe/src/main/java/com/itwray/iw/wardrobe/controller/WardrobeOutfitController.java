package com.itwray.iw.wardrobe.controller;

import com.itwray.iw.wardrobe.model.dto.WardrobeMarkWornDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeOutfitAddDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeOutfitPageDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeOutfitSuggestDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeOutfitUpdateDto;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitDetailVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitPageVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitSuggestionVo;
import com.itwray.iw.wardrobe.service.WardrobeOutfitService;
import com.itwray.iw.web.model.vo.PageVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 搭配接口
 *
 * @author codex
 * @since 2026-07-02
 */
@RestController
@RequestMapping("/wardrobe/outfit")
@Validated
@Tag(name = "搭配接口")
public class WardrobeOutfitController {

    private final WardrobeOutfitService wardrobeOutfitService;

    public WardrobeOutfitController(WardrobeOutfitService wardrobeOutfitService) {
        this.wardrobeOutfitService = wardrobeOutfitService;
    }

    @PostMapping("/add")
    @Operation(summary = "新增搭配")
    public Integer add(@RequestBody @Valid WardrobeOutfitAddDto dto) {
        return wardrobeOutfitService.add(dto);
    }

    @PutMapping("/update")
    @Operation(summary = "修改搭配")
    public void update(@RequestBody @Valid WardrobeOutfitUpdateDto dto) {
        wardrobeOutfitService.update(dto);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除搭配")
    public void delete(@RequestParam("id") Integer id) {
        wardrobeOutfitService.delete(id);
    }

    @PostMapping("/copy")
    @Operation(summary = "复制搭配")
    public Integer copy(@RequestParam("id") Integer id) {
        return wardrobeOutfitService.copy(id);
    }

    @PostMapping("/page")
    @Operation(summary = "分页查询搭配")
    public PageVo<WardrobeOutfitPageVo> page(@RequestBody @Valid WardrobeOutfitPageDto dto) {
        return wardrobeOutfitService.page(dto);
    }

    @GetMapping("/detail")
    @Operation(summary = "查询搭配详情")
    public WardrobeOutfitDetailVo detail(@RequestParam("id") Integer id) {
        return wardrobeOutfitService.detail(id);
    }

    @PostMapping("/suggest")
    @Operation(summary = "规则生成搭配建议")
    public List<WardrobeOutfitSuggestionVo> suggest(@RequestBody WardrobeOutfitSuggestDto dto) {
        return wardrobeOutfitService.suggest(dto);
    }

    @PostMapping("/markWorn")
    @Operation(summary = "标记搭配已穿")
    public Integer markWorn(@RequestBody WardrobeMarkWornDto dto) {
        return wardrobeOutfitService.markWorn(dto);
    }
}

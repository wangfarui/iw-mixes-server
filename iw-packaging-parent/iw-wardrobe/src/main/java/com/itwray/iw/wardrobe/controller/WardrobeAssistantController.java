package com.itwray.iw.wardrobe.controller;

import com.itwray.iw.wardrobe.model.dto.WardrobeAiSuggestDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemImageOptimizeDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemRecognizeDto;
import com.itwray.iw.wardrobe.model.vo.WardrobeAiSuggestVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemDraftVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemImageOptimizeTaskVo;
import com.itwray.iw.wardrobe.service.WardrobeAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 衣柜 AI 助手接口
 *
 * @author codex
 * @since 2026-07-03
 */
@RestController
@RequestMapping("/wardrobe/assistant")
@Tag(name = "衣柜AI助手接口")
public class WardrobeAssistantController {

    private final WardrobeAssistantService wardrobeAssistantService;

    public WardrobeAssistantController(WardrobeAssistantService wardrobeAssistantService) {
        this.wardrobeAssistantService = wardrobeAssistantService;
    }

    @PostMapping("/suggest")
    @Operation(summary = "AI或规则生成搭配建议")
    public WardrobeAiSuggestVo suggest(@RequestBody WardrobeAiSuggestDto dto) {
        return wardrobeAssistantService.suggest(dto);
    }

    @PostMapping("/item-draft")
    @Operation(summary = "根据衣物图片生成新增衣物草稿")
    public WardrobeItemDraftVo recognizeItemDraft(@RequestBody @Valid WardrobeItemRecognizeDto dto) {
        return wardrobeAssistantService.recognizeItemDraft(dto);
    }

    @PostMapping("/item-image/optimize/start")
    @Operation(summary = "启动当前衣物图片优化任务")
    public WardrobeItemImageOptimizeTaskVo startOptimizeItemImage(@RequestBody @Valid WardrobeItemImageOptimizeDto dto) {
        return wardrobeAssistantService.startOptimizeItemImage(dto);
    }

    @GetMapping("/item-image/optimize/status")
    @Operation(summary = "查询当前衣物图片优化任务")
    public WardrobeItemImageOptimizeTaskVo getOptimizeItemImageStatus(@RequestParam("taskId") String taskId) {
        return wardrobeAssistantService.getOptimizeItemImageStatus(taskId);
    }
}

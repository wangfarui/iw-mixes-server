package com.itwray.iw.wardrobe.controller;

import com.itwray.iw.wardrobe.model.dto.WardrobeAiSuggestDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemImageOptimizeDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemRecognizeDto;
import com.itwray.iw.wardrobe.model.vo.WardrobeAiSuggestVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemDraftVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemImageOptimizeTaskVo;
import com.itwray.iw.wardrobe.service.WardrobeAssistantService;
import com.itwray.iw.wardrobe.service.WardrobeImageOptimizationTaskService;
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
    private final WardrobeImageOptimizationTaskService imageOptimizationTaskService;

    public WardrobeAssistantController(WardrobeAssistantService wardrobeAssistantService,
                                       WardrobeImageOptimizationTaskService imageOptimizationTaskService) {
        this.wardrobeAssistantService = wardrobeAssistantService;
        this.imageOptimizationTaskService = imageOptimizationTaskService;
    }

    @PostMapping("/suggest")
    @Operation(summary = "AI生成搭配建议")
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
        return imageOptimizationTaskService.start(dto);
    }

    @PostMapping("/item-image/optimize/retry")
    @Operation(summary = "重试失败的衣物图片优化任务")
    public WardrobeItemImageOptimizeTaskVo retryOptimizeItemImage(@RequestParam("taskId") String taskId) {
        return imageOptimizationTaskService.retry(taskId);
    }

    @GetMapping("/item-image/optimize/status")
    @Operation(summary = "查询当前衣物图片优化任务")
    public WardrobeItemImageOptimizeTaskVo getOptimizeItemImageStatus(@RequestParam("taskId") String taskId) {
        return imageOptimizationTaskService.get(taskId);
    }

    @GetMapping("/item-image/optimize/latest")
    @Operation(summary = "查询衣物最近图片优化任务")
    public WardrobeItemImageOptimizeTaskVo getLatestOptimizeItemImageTask(@RequestParam("itemId") Integer itemId) {
        return imageOptimizationTaskService.getCurrent(itemId);
    }
}

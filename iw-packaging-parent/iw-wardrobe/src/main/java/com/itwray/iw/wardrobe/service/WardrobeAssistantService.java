package com.itwray.iw.wardrobe.service;

import com.itwray.iw.wardrobe.model.dto.WardrobeAiSuggestDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemImageOptimizeDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemRecognizeDto;
import com.itwray.iw.wardrobe.model.vo.WardrobeAiSuggestVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemDraftVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemImageOptimizeTaskVo;

/**
 * 衣柜 AI 助手服务
 *
 * @author codex
 * @since 2026-07-03
 */
public interface WardrobeAssistantService {

    WardrobeAiSuggestVo suggest(WardrobeAiSuggestDto dto);

    WardrobeItemDraftVo recognizeItemDraft(WardrobeItemRecognizeDto dto);

    WardrobeItemImageOptimizeTaskVo startOptimizeItemImage(WardrobeItemImageOptimizeDto dto);

    WardrobeItemImageOptimizeTaskVo getOptimizeItemImageStatus(String taskId);

    WardrobeItemImageOptimizeTaskVo getLatestOptimizeItemImageTask(Integer itemId);
}

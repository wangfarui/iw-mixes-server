package com.itwray.iw.wardrobe.service;

import com.itwray.iw.external.model.dto.AiImageReferenceGenerateDto;
import com.itwray.iw.external.model.dto.AiStructuredChatDto;
import com.itwray.iw.external.model.vo.AiImageReferenceGenerateVo;
import com.itwray.iw.external.model.vo.AiStructuredChatVo;

/**
 * 衣柜远程 AI 能力
 *
 * @author codex
 * @since 2026-07-03
 */
public interface WardrobeAssistantRemoteService {

    AiStructuredChatVo structuredChat(AiStructuredChatDto dto);

    AiImageReferenceGenerateVo startReferenceGenerateImage(AiImageReferenceGenerateDto dto);

    AiImageReferenceGenerateVo getReferenceGenerateImageStatus(String taskId);
}

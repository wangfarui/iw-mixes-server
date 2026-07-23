package com.itwray.iw.wardrobe.service.impl;

import com.itwray.iw.external.client.InternalApiClient;
import com.itwray.iw.external.model.dto.AiStructuredChatDto;
import com.itwray.iw.external.model.vo.AiStructuredChatVo;
import com.itwray.iw.wardrobe.service.WardrobeAssistantRemoteService;
import org.springframework.stereotype.Service;

/**
 * 衣柜远程 AI 能力实现
 *
 * @author codex
 * @since 2026-07-03
 */
@Service
public class WardrobeAssistantRemoteServiceImpl implements WardrobeAssistantRemoteService {

    private final InternalApiClient internalApiClient;

    public WardrobeAssistantRemoteServiceImpl(InternalApiClient internalApiClient) {
        this.internalApiClient = internalApiClient;
    }

    @Override
    public AiStructuredChatVo structuredChat(AiStructuredChatDto dto) {
        return internalApiClient.structuredChat(dto);
    }
}

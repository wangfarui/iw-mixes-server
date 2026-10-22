package com.itwray.iw.wardrobe.service.impl;

import com.itwray.iw.external.model.dto.AiChatMessageDto;
import com.itwray.iw.external.model.dto.AiStructuredChatDto;
import com.itwray.iw.external.model.vo.AiStructuredChatVo;
import com.itwray.iw.wardrobe.dictionary.WardrobeRecognitionService;
import com.itwray.iw.wardrobe.model.dto.*;
import com.itwray.iw.wardrobe.model.vo.*;
import com.itwray.iw.wardrobe.service.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Suggestions use saved item snapshots; recognition uses the authorized owner's current sources. */
@Slf4j
@Service
public class WardrobeAssistantServiceImpl implements WardrobeAssistantService {
    private final WardrobeOutfitService wardrobeOutfitService;
    private final WardrobeAssistantRemoteService remoteService;
    private final WardrobeRecognitionService recognition;
    public WardrobeAssistantServiceImpl(WardrobeOutfitService wardrobeOutfitService,
                                       WardrobeAssistantRemoteService remoteService, WardrobeRecognitionService recognition) {
        this.wardrobeOutfitService = wardrobeOutfitService; this.remoteService = remoteService; this.recognition = recognition;
    }

    public WardrobeAiSuggestVo suggest(WardrobeAiSuggestDto dto) {
        WardrobeAiSuggestDto request = dto == null ? new WardrobeAiSuggestDto() : dto;
        WardrobeOutfitSuggestDto ruleDto = this.buildRuleDto(request);
        List<WardrobeOutfitSuggestionVo> suggestions = wardrobeOutfitService.suggest(ruleDto);

        WardrobeAiSuggestVo vo = new WardrobeAiSuggestVo();
        vo.setSource("candidate");
        vo.setSuggestions(suggestions);
        vo.setSummary(this.buildRuleSummary(request, suggestions));
        if (Boolean.FALSE.equals(request.getUseAi()) || suggestions.isEmpty()) {
            return vo;
        }

        try {
            AiStructuredChatVo aiResponse = remoteService.structuredChat(this.buildAiRequest(request, suggestions));
            String content = aiResponse == null ? "" : StringUtils.trimToEmpty(aiResponse.getContent());
            if (StringUtils.isBlank(content) || StringUtils.containsAny(content, "服务请求失败", "服务请求超时")) {
                return vo;
            }
            vo.setSource("ai");
            vo.setRawAiContent(content);
            vo.setSummary(content);
        } catch (Exception e) {
            log.warn("WardrobeAssistantService#suggest AI unavailable, fallback to rules", e);
        }
        return vo;
    }

    public WardrobeItemDraftVo recognizeItemDraft(WardrobeItemRecognizeDto dto) {
        return recognition.recognize(dto);
    }

    private WardrobeOutfitSuggestDto buildRuleDto(WardrobeAiSuggestDto request) {
        String prompt = StringUtils.defaultString(request.getPrompt());
        WardrobeOutfitSuggestDto dto = new WardrobeOutfitSuggestDto();
        dto.setLockedItemId(request.getLockedItemId());
        dto.setSeason(StringUtils.defaultIfBlank(request.getSeason(), inferOutfitSeason(prompt, LocalDate.now())));
        dto.setScene(StringUtils.defaultString(request.getScene()));
        dto.setStyle(StringUtils.defaultString(request.getStyle()));
        dto.setWeatherText(StringUtils.defaultIfBlank(request.getWeatherText(), this.inferWeather(prompt)));
        dto.setLimit(request.getLimit());
        dto.setPreferIdle(Boolean.TRUE);
        dto.setAvoidRecentDays(3);
        List<Integer> excludeIds = new ArrayList<>();
        if (request.getExcludeItemIds() != null) {
            excludeIds.addAll(request.getExcludeItemIds().stream().filter(Objects::nonNull).toList());
        }
        if (request.getReplaceItemId() != null) {
            excludeIds.add(request.getReplaceItemId());
        }
        dto.setExcludeItemIds(excludeIds);
        return dto;
    }

    private AiStructuredChatDto buildAiRequest(WardrobeAiSuggestDto request, List<WardrobeOutfitSuggestionVo> suggestions) {
        AiChatMessageDto system = new AiChatMessageDto();
        system.setRole("system");
        system.setContent("你是私人穿搭助手。只能基于用户已有衣物候选给建议，回答要简短、可执行，不要编造不存在的单品。");

        AiChatMessageDto user = new AiChatMessageDto();
        user.setRole("user");
        user.setContent("""
                用户需求：%s
                天气：%s
                候选搭配：
                %s
                请给出一句总体建议，并说明最推荐哪一套、可以换哪一件。
                """.formatted(
                StringUtils.defaultIfBlank(request.getPrompt(), "日常穿搭"),
                StringUtils.defaultIfBlank(request.getWeatherText(), "未提供"),
                this.formatSuggestions(suggestions)
        ));

        AiStructuredChatDto dto = new AiStructuredChatDto();
        dto.setMessages(List.of(system, user));
        dto.setMaxTokens(500);
        dto.setTemperature(0.4D);
        return dto;
    }

    private String formatSuggestions(List<WardrobeOutfitSuggestionVo> suggestions) {
        return suggestions.stream()
                .map(suggestion -> suggestion.getSuggestionName() + "：" + suggestion.getItemList().stream()
                        .map(WardrobeOutfitItemVo::getItemName)
                        .collect(Collectors.joining("、")))
                .collect(Collectors.joining("\n"));
    }

    private String buildRuleSummary(WardrobeAiSuggestDto request, List<WardrobeOutfitSuggestionVo> suggestions) {
        if (suggestions.isEmpty()) {
            return "当前衣物不足，先补充不同分类衣物后再生成搭配。";
        }
        String prompt = StringUtils.defaultIfBlank(request.getPrompt(), "你的衣橱");
        return "已按「" + prompt + "」生成 " + suggestions.size() + " 套候选，优先使用较少穿和最近未穿的衣物。";
    }

    static String inferOutfitSeason(String prompt, LocalDate currentDate) {
        String text = StringUtils.defaultString(prompt);
        if (StringUtils.contains(text, "春")) return "spring";
        if (StringUtils.contains(text, "夏")) return "summer";
        if (StringUtils.contains(text, "秋")) return "autumn";
        if (StringUtils.contains(text, "冬")) return "winter";

        int month = Objects.requireNonNullElseGet(currentDate, LocalDate::now).getMonthValue();
        if (month >= 3 && month <= 5) return "spring";
        if (month >= 6 && month <= 8) return "summer";
        if (month >= 9 && month <= 11) return "autumn";
        return "winter";
    }

    private String inferWeather(String prompt) {
        if (StringUtils.contains(prompt, "雨")) return "雨天";
        if (StringUtils.containsAny(prompt, "冷", "降温")) return "偏冷";
        if (StringUtils.containsAny(prompt, "热", "高温")) return "偏热";
        if (StringUtils.containsAny(prompt, "风", "大风")) return "有风";
        return "";
    }
}

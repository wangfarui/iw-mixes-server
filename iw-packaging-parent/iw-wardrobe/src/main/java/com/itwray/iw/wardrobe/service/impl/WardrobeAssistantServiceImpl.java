package com.itwray.iw.wardrobe.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.itwray.iw.auth.client.BaseDictClient;
import com.itwray.iw.auth.model.vo.DictListVo;
import com.itwray.iw.external.model.dto.AiChatMessageDto;
import com.itwray.iw.external.model.dto.AiStructuredChatDto;
import com.itwray.iw.external.model.vo.AiStructuredChatVo;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.enums.DictTypeEnum;
import com.itwray.iw.wardrobe.dao.WardrobeItemDao;
import com.itwray.iw.wardrobe.model.dto.WardrobeAiSuggestDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemRecognizeDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeOutfitSuggestDto;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.model.enums.WardrobeItemStatusEnum;
import com.itwray.iw.wardrobe.model.vo.WardrobeAiSuggestVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemDraftVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitItemVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitSuggestionVo;
import com.itwray.iw.wardrobe.service.WardrobeAssistantRemoteService;
import com.itwray.iw.wardrobe.service.WardrobeAssistantService;
import com.itwray.iw.wardrobe.service.WardrobeOutfitService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 衣柜 AI 助手服务实现
 *
 * @author codex
 * @since 2026-07-03
 */
@Slf4j
@Service
public class WardrobeAssistantServiceImpl implements WardrobeAssistantService {

    private static final Map<Integer, String> FALLBACK_CATEGORY_NAMES = Map.ofEntries(
            Map.entry(1, "上装"),
            Map.entry(2, "下装"),
            Map.entry(3, "连衣裙"),
            Map.entry(4, "内衣"),
            Map.entry(5, "袜子"),
            Map.entry(6, "鞋履"),
            Map.entry(7, "配饰"),
            Map.entry(8, "帽子"),
            Map.entry(9, "包袋"),
            Map.entry(10, "首饰"),
            Map.entry(11, "其他")
    );

    private static final Map<String, String> FALLBACK_COLOR_HEX = Map.ofEntries(
            Map.entry("黑色", "#1f2329"),
            Map.entry("白色", "#f8f8f8"),
            Map.entry("灰色", "#8a8f99"),
            Map.entry("蓝色", "#2f80ed"),
            Map.entry("绿色", "#2e7d32"),
            Map.entry("红色", "#d93026"),
            Map.entry("黄色", "#f6c343"),
            Map.entry("粉色", "#e88aa8"),
            Map.entry("紫色", "#7e57c2"),
            Map.entry("棕色", "#8d6e63"),
            Map.entry("米色", "#d8c7a3"),
            Map.entry("卡其色", "#c3a36b"),
            Map.entry("牛仔蓝", "#3f6f9f"),
            Map.entry("藏青色", "#1f2a44"),
            Map.entry("彩色", "#6c8cff")
    );

    private final WardrobeOutfitService wardrobeOutfitService;
    private final WardrobeAssistantRemoteService remoteService;
    private final WardrobeItemDao wardrobeItemDao;
    private final BaseDictClient baseDictClient;

    public WardrobeAssistantServiceImpl(WardrobeOutfitService wardrobeOutfitService,
                                        WardrobeAssistantRemoteService remoteService,
                                        WardrobeItemDao wardrobeItemDao,
                                        BaseDictClient baseDictClient) {
        this.wardrobeOutfitService = wardrobeOutfitService;
        this.remoteService = remoteService;
        this.wardrobeItemDao = wardrobeItemDao;
        this.baseDictClient = baseDictClient;
    }

    @Override
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

    @Override
    public WardrobeItemDraftVo recognizeItemDraft(WardrobeItemRecognizeDto dto) {
        WardrobeItemRecognizeDto request = dto == null ? new WardrobeItemRecognizeDto() : dto;
        String prompt = this.buildItemDraftPrompt(request);
        WardrobeItemDraftVo fallback = this.buildRuleItemDraft(request, prompt);
        if (StringUtils.isBlank(request.getImageUrl()) || Boolean.FALSE.equals(request.getUseAi())) {
            return fallback;
        }

        try {
            AiStructuredChatVo aiResponse = remoteService.structuredChat(this.buildItemDraftAiRequest(request, prompt));
            String content = aiResponse == null ? "" : StringUtils.trimToEmpty(aiResponse.getContent());
            if (StringUtils.isBlank(content) || StringUtils.containsAny(content, "服务请求失败", "服务请求超时")) {
                return fallback;
            }
            WardrobeItemDraftVo draft = this.parseItemDraft(content, request, prompt, fallback);
            if (draft == null) {
                fallback.setRawAiContent(content);
                return fallback;
            }
            draft.setSource("ai");
            draft.setRawAiContent(content);
            return draft;
        } catch (Exception e) {
            log.warn("WardrobeAssistantService#recognizeItemDraft AI unavailable, fallback to rules", e);
            return fallback;
        }
    }


    private WardrobeOutfitSuggestDto buildRuleDto(WardrobeAiSuggestDto request) {
        String prompt = StringUtils.defaultString(request.getPrompt());
        WardrobeOutfitSuggestDto dto = new WardrobeOutfitSuggestDto();
        dto.setLockedItemId(request.getLockedItemId());
        dto.setSeason(StringUtils.defaultIfBlank(request.getSeason(), this.inferSeason(prompt)));
        dto.setScene(StringUtils.defaultIfBlank(request.getScene(), this.inferScene(prompt)));
        dto.setStyle(StringUtils.defaultIfBlank(request.getStyle(), this.inferStyle(prompt)));
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

    private AiStructuredChatDto buildItemDraftAiRequest(WardrobeItemRecognizeDto request, String prompt) {
        AiChatMessageDto system = new AiChatMessageDto();
        system.setRole("system");
        system.setContent("你是衣柜建档助手，只返回合法JSON，不输出额外说明。");

        AiChatMessageDto user = new AiChatMessageDto();
        user.setRole("user");
        user.setContent(List.of(
                Map.of("type", "text", "text", prompt),
                Map.of("type", "image_url", "image_url", Map.of("url", request.getImageUrl()))
        ));

        AiStructuredChatDto dto = new AiStructuredChatDto();
        dto.setMessages(List.of(system, user));
        dto.setMaxTokens(800);
        dto.setTemperature(0.2D);
        return dto;
    }

    private String buildItemDraftPrompt(WardrobeItemRecognizeDto request) {
        return """
                请观察用户提供的衣物图片，为新增衣物表单生成草稿。
                只能返回JSON对象，字段如下：
                itemName、category、categoryName、itemStyle、itemStyleName、colorName、colorHex、seasonTags、sceneTags、styleTags、brand、size、material、purchaseChannel、storageLocation、purchaseDate、price、customTags、status、remark。
                category只能使用用户衣物品类字典编码：%s。
                itemStyle只能使用用户衣物款式字典编码，优先选择与category品类匹配的款式；无法判断时返回0：%s。
                colorName只能使用用户衣物颜色字典名称：%s。
                seasonTags只能使用spring,summer,autumn,winter。
                sceneTags只能使用用户衣物场景字典编码，多个用英文逗号拼接：%s。
                styleTags只能使用用户衣物风格字典编码，多个用英文逗号拼接：%s。
                status只能使用1在穿、2闲置、5已淘汰，无法判断时默认1。无法从图片判断的其它字段返回空字符串，price默认0。名称要短，优先使用颜色+款式，其次使用颜色+品类。
                用户补充提示：%s
                """.formatted(
                this.formatCodeOptions(DictTypeEnum.WARDROBE_ITEM_CATEGORY),
                this.formatCodeOptions(DictTypeEnum.WARDROBE_ITEM_SUBCATEGORY),
                this.formatNameOptions(DictTypeEnum.WARDROBE_ITEM_COLOR),
                this.formatCodeOptions(DictTypeEnum.WARDROBE_ITEM_SCENE),
                this.formatCodeOptions(DictTypeEnum.WARDROBE_ITEM_STYLE),
                StringUtils.defaultIfBlank(request.getPrompt(), "无")
        );
    }


    private WardrobeItemDraftVo buildRuleItemDraft(WardrobeItemRecognizeDto request, String prompt) {
        String userPrompt = request == null ? "" : StringUtils.defaultString(request.getPrompt());
        WardrobeItemDraftVo vo = new WardrobeItemDraftVo();
        vo.setItemImage(request == null ? "" : StringUtils.defaultString(request.getImageUrl()));
        vo.setCategory(this.inferItemCategory(userPrompt));
        vo.setCategoryName(this.categoryName(vo.getCategory()));
        vo.setItemStyle(this.inferItemStyle(userPrompt));
        vo.setItemStyleName(this.itemStyleName(vo.getItemStyle()));
        vo.setColorName(this.inferColorName(userPrompt));
        vo.setColorHex(StringUtils.defaultString(FALLBACK_COLOR_HEX.get(vo.getColorName())));
        vo.setSeasonTags(this.inferSeason(userPrompt));
        vo.setSceneTags(this.inferScene(userPrompt));
        vo.setStyleTags(this.inferStyle(userPrompt));
        vo.setItemName(this.defaultItemName("", vo.getColorName(), vo.getCategoryName(), vo.getItemStyleName()));
        vo.setStatus(1);
        vo.setPrice(BigDecimal.ZERO);
        vo.setPrompt(prompt);
        vo.setSource("candidate");
        vo.setRemark("图片已上传，可核对名称、分类、颜色后保存。");
        return vo;
    }

    private WardrobeItemDraftVo parseItemDraft(String content,
                                               WardrobeItemRecognizeDto request,
                                               String prompt,
                                               WardrobeItemDraftVo fallback) {
        try {
            JSONObject json = JSONUtil.parseObj(this.extractJson(content));
            WardrobeItemDraftVo vo = new WardrobeItemDraftVo();
            vo.setItemImage(StringUtils.defaultIfBlank(this.cleanText(json.get("itemImage")), request.getImageUrl()));

            Integer category = this.normalizeCategory(this.firstNonBlank(
                    this.cleanText(json.get("category")),
                    this.cleanText(json.get("categoryName"))
            ));
            vo.setCategory(category == null ? fallback.getCategory() : category);
            vo.setCategoryName(this.categoryName(vo.getCategory()));

            Integer itemStyle = this.normalizeItemStyle(this.firstNonBlank(
                    this.cleanText(json.get("itemStyle")),
                    this.cleanText(json.get("itemStyleName"))
            ));
            vo.setItemStyle(itemStyle == null ? fallback.getItemStyle() : itemStyle);
            vo.setItemStyleName(this.itemStyleName(vo.getItemStyle()));

            String colorName = this.normalizeColorName(json.get("colorName"), json.get("color"));
            vo.setColorName(StringUtils.defaultIfBlank(colorName, fallback.getColorName()));
            vo.setColorHex(this.normalizeColorHex(vo.getColorName(), json.get("colorHex")));

            vo.setSeasonTags(StringUtils.defaultIfBlank(this.normalizeTags(json.get("seasonTags"), "season"), fallback.getSeasonTags()));
            vo.setSceneTags(StringUtils.defaultIfBlank(this.normalizeTags(json.get("sceneTags"), "scene"), fallback.getSceneTags()));
            vo.setStyleTags(StringUtils.defaultIfBlank(this.normalizeTags(json.get("styleTags"), "style"), fallback.getStyleTags()));
            vo.setItemName(this.defaultItemName(this.cleanText(json.get("itemName")), vo.getColorName(), vo.getCategoryName(), vo.getItemStyleName()));
            vo.setBrand(this.cleanText(json.get("brand")));
            vo.setSize(this.cleanText(json.get("size")));
            vo.setMaterial(this.cleanText(json.get("material")));
            vo.setPurchaseChannel(this.cleanText(json.get("purchaseChannel")));
            vo.setStorageLocation(this.cleanText(json.get("storageLocation")));
            vo.setPurchaseDate(this.cleanText(json.get("purchaseDate")));
            vo.setPrice(this.normalizePrice(json.get("price")));
            vo.setCustomTags(this.normalizeCustomTags(json.get("customTags")));
            vo.setStatus(this.normalizeStatus(json.get("status")));
            vo.setRemark(StringUtils.defaultIfBlank(this.cleanText(json.get("remark")), fallback.getRemark()));
            vo.setPrompt(prompt);
            return vo;
        } catch (Exception e) {
            log.warn("WardrobeAssistantService#parseItemDraft invalid AI response: {}", content, e);
            return null;
        }
    }

    private String extractJson(String content) {
        String text = StringUtils.trimToEmpty(content);
        int jsonStart = text.indexOf('{');
        int jsonEnd = text.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return text.substring(jsonStart, jsonEnd + 1);
        }
        return text;
    }

    private String cleanText(Object value) {
        if (value == null) {
            return "";
        }
        String text = StringUtils.trimToEmpty(String.valueOf(value));
        if (StringUtils.equalsAnyIgnoreCase(text, "null", "undefined", "未知", "无法判断", "无", "none", "unknown")) {
            return "";
        }
        return text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private Integer normalizeCategory(String value) {
        String text = StringUtils.trimToEmpty(value);
        if (StringUtils.isBlank(text)) {
            return null;
        }
        Integer dictCode = this.matchDictCode(DictTypeEnum.WARDROBE_ITEM_CATEGORY, text);
        if (dictCode != null) {
            return dictCode;
        }
        if (StringUtils.containsAny(text, "项链", "耳环", "耳钉", "戒指", "手链", "手镯", "胸针", "脚链", "手表", "首饰", "饰品")) return 10;
        if (StringUtils.containsAny(text, "包", "背包", "手袋", "卡包", "钱包")) return 9;
        if (StringUtils.containsAny(text, "帽", "棒球帽", "渔夫帽", "贝雷帽")) return 8;
        if (StringUtils.containsAny(text, "围巾", "披肩", "腰带", "手套", "领带", "领结", "丝巾", "发饰", "眼镜", "墨镜", "配饰")) return 7;
        if (StringUtils.containsAny(text, "鞋", "靴", "拖鞋", "凉鞋", "高跟鞋")) return 6;
        if (StringUtils.containsAny(text, "袜", "连裤袜", "打底袜")) return 5;
        if (StringUtils.containsAny(text, "内衣", "文胸", "内裤", "睡衣", "家居服", "泳衣")) return 4;
        if (StringUtils.containsAny(text, "连衣", "旗袍", "连体裤", "礼服裙")) return 3;
        if (StringUtils.containsAny(text, "裤", "半裙")) return 2;
        if (StringUtils.containsAny(text, "上装", "上衣", "衬衫", "T恤", "短袖", "卫衣", "毛衣", "背心", "外套", "大衣", "风衣", "羽绒服", "夹克", "西装")) return 1;
        if (StringUtils.contains(text, "其他")) return 11;
        return null;
    }

    private Integer inferItemCategory(String prompt) {
        return Objects.requireNonNullElse(this.normalizeCategory(prompt), 11);
    }

    private String categoryName(Integer category) {
        if (category == null) {
            return "其他";
        }
        return this.getDictList(DictTypeEnum.WARDROBE_ITEM_CATEGORY).stream()
                .filter(dict -> Objects.equals(dict.getDictCode(), category))
                .map(DictListVo::getDictName)
                .findFirst()
                .orElse(FALLBACK_CATEGORY_NAMES.getOrDefault(category, "其他"));
    }

    private Integer normalizeItemStyle(String value) {
        String text = StringUtils.trimToEmpty(value);
        if (StringUtils.isBlank(text)) {
            return null;
        }
        Integer dictCode = this.matchDictCode(DictTypeEnum.WARDROBE_ITEM_SUBCATEGORY, text);
        if (dictCode != null) {
            return dictCode;
        }
        if (StringUtils.contains(text, "五分裤") || StringUtils.contains(text, "七分裤")) return 202;
        if (StringUtils.contains(text, "T恤") || StringUtils.containsAny(text, "短袖", "长袖T")) return 101;
        if (StringUtils.contains(text, "衬衫")) return 102;
        if (StringUtils.contains(text, "Polo")) return 103;
        if (StringUtils.contains(text, "卫衣")) return 104;
        if (StringUtils.contains(text, "毛衣")) return 105;
        if (StringUtils.contains(text, "针织")) return 106;
        if (StringUtils.contains(text, "打底衫")) return 107;
        if (StringUtils.contains(text, "背心")) return 108;
        if (StringUtils.contains(text, "吊带")) return 109;
        if (StringUtils.contains(text, "抹胸")) return 110;
        if (StringUtils.contains(text, "雪纺")) return 111;
        if (StringUtils.contains(text, "马甲")) return 112;
        if (StringUtils.contains(text, "开衫")) return 113;
        if (StringUtils.contains(text, "防晒衣")) return 114;
        if (StringUtils.contains(text, "牛仔裤")) return 201;
        if (StringUtils.contains(text, "休闲裤")) return 202;
        if (StringUtils.contains(text, "西裤")) return 203;
        if (StringUtils.contains(text, "运动裤")) return 204;
        if (StringUtils.contains(text, "工装裤")) return 205;
        if (StringUtils.contains(text, "阔腿裤")) return 206;
        if (StringUtils.contains(text, "短裤")) return 207;
        if (StringUtils.contains(text, "半身裙")) return 208;
        if (StringUtils.contains(text, "短裙")) return 209;
        if (StringUtils.contains(text, "长裙")) return 210;
        if (StringUtils.contains(text, "打底裤")) return 211;
        if (StringUtils.contains(text, "瑜伽裤")) return 212;
        if (StringUtils.contains(text, "背带裤")) return 213;
        if (StringUtils.contains(text, "连衣裙")) return 301;
        if (StringUtils.contains(text, "衬衫裙")) return 302;
        if (StringUtils.contains(text, "针织裙")) return 303;
        if (StringUtils.contains(text, "吊带裙")) return 304;
        if (StringUtils.contains(text, "背心裙")) return 305;
        if (StringUtils.contains(text, "背带裙")) return 306;
        if (StringUtils.contains(text, "礼服裙")) return 307;
        if (StringUtils.contains(text, "旗袍")) return 308;
        if (StringUtils.contains(text, "连体裤")) return 309;
        if (StringUtils.contains(text, "套装裙")) return 310;
        return null;
    }

    private Integer inferItemStyle(String prompt) {
        return Objects.requireNonNullElse(this.normalizeItemStyle(prompt), 0);
    }

    private String itemStyleName(Integer itemStyle) {
        if (itemStyle == null || itemStyle <= 0) {
            return "";
        }
        return this.getDictList(DictTypeEnum.WARDROBE_ITEM_SUBCATEGORY).stream()
                .filter(dict -> Objects.equals(dict.getDictCode(), itemStyle))
                .map(DictListVo::getDictName)
                .findFirst()
                .orElse("");
    }

    private String normalizeColorName(Object... values) {
        List<DictListVo> colorDictList = this.getDictList(DictTypeEnum.WARDROBE_ITEM_COLOR);
        for (Object value : values) {
            String text = this.cleanText(value);
            if (StringUtils.isBlank(text)) {
                continue;
            }
            for (DictListVo colorDict : colorDictList) {
                String color = colorDict.getDictName();
                if (StringUtils.equals(text, color) || StringUtils.contains(text, color)) {
                    return color;
                }
            }
            for (String color : FALLBACK_COLOR_HEX.keySet()) {
                if (StringUtils.equals(text, color) || StringUtils.contains(text, color)) {
                    return color;
                }
            }
            if (StringUtils.containsAny(text, "黑", "玄")) return "黑色";
            if (StringUtils.containsAny(text, "白", "乳白")) return "白色";
            if (StringUtils.containsAny(text, "灰", "银")) return "灰色";
            if (StringUtils.contains(text, "牛仔蓝")) return "牛仔蓝";
            if (StringUtils.contains(text, "藏青")) return "藏青色";
            if (StringUtils.containsAny(text, "卡其")) return "卡其色";
            if (StringUtils.containsAny(text, "蓝", "牛仔")) return "蓝色";
            if (StringUtils.containsAny(text, "绿", "军绿")) return "绿色";
            if (StringUtils.containsAny(text, "红", "酒红")) return "红色";
            if (StringUtils.containsAny(text, "黄", "金")) return "黄色";
            if (StringUtils.containsAny(text, "粉", "玫")) return "粉色";
            if (StringUtils.containsAny(text, "紫")) return "紫色";
            if (StringUtils.containsAny(text, "棕", "咖", "褐")) return "棕色";
            if (StringUtils.containsAny(text, "米", "杏")) return "米色";
            if (StringUtils.containsAny(text, "彩", "拼色", "多色")) return "彩色";
        }
        return "";
    }

    private String inferColorName(String prompt) {
        return this.normalizeColorName(prompt);
    }

    private String normalizeColorHex(String colorName, Object value) {
        String text = this.cleanText(value);
        if (StringUtils.isNotBlank(text) && text.matches("^#?[0-9a-fA-F]{6}$")) {
            return text.startsWith("#") ? text : "#" + text;
        }
        return StringUtils.defaultString(FALLBACK_COLOR_HEX.get(colorName));
    }

    private String normalizeTags(Object value, String type) {
        Set<String> tags = new LinkedHashSet<>();
        for (String token : this.toTextList(value)) {
            String tag = this.normalizeTagValue(token, type);
            if (StringUtils.isNotBlank(tag)) {
                tags.add(tag);
            }
        }
        return String.join(",", tags);
    }

    private List<String> toTextList(Object value) {
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof JSONArray jsonArray) {
            for (Object item : jsonArray) {
                this.addSplitTokens(result, this.cleanText(item));
            }
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                this.addSplitTokens(result, this.cleanText(item));
            }
            return result;
        }
        String text = this.cleanText(value);
        if (StringUtils.startsWith(text, "[") && StringUtils.endsWith(text, "]")) {
            try {
                JSONArray jsonArray = JSONUtil.parseArray(text);
                for (Object item : jsonArray) {
                    this.addSplitTokens(result, this.cleanText(item));
                }
                return result;
            } catch (Exception ignored) {
                // Continue with plain text splitting.
            }
        }
        this.addSplitTokens(result, text);
        return result;
    }

    private void addSplitTokens(List<String> result, String text) {
        if (StringUtils.isBlank(text)) {
            return;
        }
        for (String token : text.split("[,，、/\\s]+")) {
            if (StringUtils.isNotBlank(token)) {
                result.add(StringUtils.trim(token));
            }
        }
    }

    private String normalizeTagValue(String value, String type) {
        String text = StringUtils.trimToEmpty(value);
        String lower = StringUtils.lowerCase(text);
        return switch (type) {
            case "season" -> this.normalizeSeasonTag(text, lower);
            case "scene" -> this.normalizeSceneTag(text, lower);
            case "style" -> this.normalizeStyleTag(text, lower);
            default -> "";
        };
    }

    private String normalizeSeasonTag(String text, String lower) {
        if (StringUtils.equals(lower, "spring") || StringUtils.contains(text, "春")) return "spring";
        if (StringUtils.equals(lower, "summer") || StringUtils.containsAny(text, "夏", "热")) return "summer";
        if (StringUtils.equals(lower, "autumn") || StringUtils.contains(text, "秋")) return "autumn";
        if (StringUtils.equals(lower, "winter") || StringUtils.containsAny(text, "冬", "冷")) return "winter";
        return "";
    }

    private String normalizeSceneTag(String text, String lower) {
        Integer dictCode = this.matchDictCode(DictTypeEnum.WARDROBE_ITEM_SCENE, text);
        if (dictCode != null) return String.valueOf(dictCode);
        if (StringUtils.equals(lower, "daily") || StringUtils.containsAny(text, "日常", "休闲")) return "1";
        if (StringUtils.equals(lower, "work") || StringUtils.containsAny(text, "通勤", "上班", "办公")) return "2";
        if (StringUtils.equals(lower, "date") || StringUtils.contains(text, "约会")) return "3";
        if (StringUtils.equals(lower, "sport") || StringUtils.containsAny(text, "运动", "健身")) return "4";
        if (StringUtils.equals(lower, "travel") || StringUtils.containsAny(text, "旅行", "出差")) return "5";
        if (StringUtils.equals(lower, "formal") || StringUtils.containsAny(text, "正式", "会议", "面试")) return "6";
        if (StringUtils.equals(lower, "home") || StringUtils.containsAny(text, "居家", "在家")) return "7";
        if (StringUtils.equals(lower, "outdoor") || StringUtils.containsAny(text, "户外", "露营")) return "8";
        if (StringUtils.equals(lower, "party") || StringUtils.containsAny(text, "聚会", "派对")) return "9";
        return "";
    }

    private String normalizeStyleTag(String text, String lower) {
        Integer dictCode = this.matchDictCode(DictTypeEnum.WARDROBE_ITEM_STYLE, text);
        if (dictCode != null) return String.valueOf(dictCode);
        if (StringUtils.equals(lower, "casual") || StringUtils.contains(text, "休闲")) return "1";
        if (StringUtils.equals(lower, "simple") || StringUtils.containsAny(text, "简约", "基础")) return "2";
        if (StringUtils.equals(lower, "smart") || StringUtils.containsAny(text, "利落", "干练")) return "3";
        if (StringUtils.equals(lower, "sweet") || StringUtils.contains(text, "甜美")) return "4";
        if (StringUtils.equals(lower, "street") || StringUtils.contains(text, "街头")) return "5";
        if (StringUtils.equals(lower, "retro") || StringUtils.contains(text, "复古")) return "6";
        if (StringUtils.equals(lower, "outdoor") || StringUtils.containsAny(text, "户外", "露营")) return "7";
        if (StringUtils.equals(lower, "sport") || StringUtils.containsAny(text, "运动", "健身")) return "8";
        if (StringUtils.equals(lower, "commute") || StringUtils.containsAny(text, "通勤", "职场")) return "9";
        if (StringUtils.equals(lower, "elegant") || StringUtils.containsAny(text, "优雅", "精致")) return "10";
        if (StringUtils.equals(lower, "neutral") || StringUtils.containsAny(text, "中性", "无性别")) return "11";
        if (StringUtils.equals(lower, "college") || StringUtils.containsAny(text, "学院", "校园")) return "12";
        if (StringUtils.equals(lower, "vacation") || StringUtils.containsAny(text, "度假", "海边")) return "13";
        return "";
    }

    private String defaultItemName(String itemName, String colorName, String categoryName, String itemStyleName) {
        return this.defaultItemName(itemName, colorName, StringUtils.defaultIfBlank(itemStyleName, categoryName));
    }

    private String defaultItemName(String itemName, String colorName, String categoryName) {
        if (StringUtils.isNotBlank(itemName)) {
            return itemName;
        }
        if (StringUtils.isNotBlank(colorName) && StringUtils.isNotBlank(categoryName) && !"其他".equals(categoryName)) {
            return colorName + categoryName;
        }
        if (StringUtils.isNotBlank(categoryName) && !"其他".equals(categoryName)) {
            return categoryName;
        }
        return "待识别衣物";
    }


    private BigDecimal normalizePrice(Object value) {
        String text = this.cleanText(value)
                .replace(",", "")
                .replace("￥", "")
                .replace("¥", "")
                .replace("元", "");
        if (StringUtils.isBlank(text)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(text);
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private String normalizeCustomTags(Object value) {
        return this.toTextList(value).stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.joining(","));
    }

    private Integer normalizeStatus(Object value) {
        String text = this.cleanText(value);
        if (StringUtils.isBlank(text)) {
            return WardrobeItemStatusEnum.defaultCode();
        }
        try {
            int status = new BigDecimal(text).intValue();
            return WardrobeItemStatusEnum.normalizeCode(status);
        } catch (Exception ignored) {
            // Continue with semantic matching.
        }
        if (StringUtils.containsAny(text, "淘汰", "捐赠", "丢弃")) return WardrobeItemStatusEnum.ELIMINATED.getCode();
        if (StringUtils.contains(text, "闲置")) return WardrobeItemStatusEnum.IDLE.getCode();
        if (StringUtils.containsAny(text, "清洗", "维修", "修补")) return WardrobeItemStatusEnum.IDLE.getCode();
        return WardrobeItemStatusEnum.defaultCode();
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

    private String inferSeason(String prompt) {
        if (StringUtils.contains(prompt, "春")) return "spring";
        if (StringUtils.containsAny(prompt, "夏", "热", "高温")) return "summer";
        if (StringUtils.contains(prompt, "秋")) return "autumn";
        if (StringUtils.containsAny(prompt, "冬", "冷", "降温")) return "winter";
        return "";
    }

    private String inferScene(String prompt) {
        return this.normalizeSceneTag(StringUtils.defaultString(prompt), StringUtils.lowerCase(prompt));
    }

    private String inferStyle(String prompt) {
        return this.normalizeStyleTag(StringUtils.defaultString(prompt), StringUtils.lowerCase(prompt));
    }

    private String inferWeather(String prompt) {
        if (StringUtils.contains(prompt, "雨")) return "雨天";
        if (StringUtils.containsAny(prompt, "冷", "降温")) return "偏冷";
        if (StringUtils.containsAny(prompt, "热", "高温")) return "偏热";
        if (StringUtils.containsAny(prompt, "风", "大风")) return "有风";
        return "";
    }

    private List<DictListVo> getDictList(DictTypeEnum dictTypeEnum) {
        try {
            List<DictListVo> dictList = baseDictClient.getDictListByType(dictTypeEnum.getCode());
            if (dictList != null && !dictList.isEmpty()) {
                return dictList;
            }
        } catch (Exception e) {
            log.warn("WardrobeAssistantService#getDictList fallback, dictType: {}", dictTypeEnum.getCode(), e);
        }
        return this.fallbackDictList(dictTypeEnum);
    }

    private Integer matchDictCode(DictTypeEnum dictTypeEnum, String value) {
        String text = StringUtils.trimToEmpty(value);
        if (StringUtils.isBlank(text)) {
            return null;
        }
        List<DictListVo> dictList = this.getDictList(dictTypeEnum);
        try {
            int number = new BigDecimal(text).intValue();
            boolean exists = dictList.stream().anyMatch(dict -> Objects.equals(dict.getDictCode(), number));
            if (exists) {
                return number;
            }
        } catch (Exception ignored) {
            // Continue with name matching.
        }
        return dictList.stream()
                .filter(dict -> StringUtils.isNotBlank(dict.getDictName()))
                .filter(dict -> StringUtils.equals(text, dict.getDictName()) || StringUtils.contains(text, dict.getDictName()))
                .map(DictListVo::getDictCode)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private String formatCodeOptions(DictTypeEnum dictTypeEnum) {
        return this.getDictList(dictTypeEnum).stream()
                .map(dict -> dict.getDictCode() + dict.getDictName())
                .collect(Collectors.joining("、"));
    }

    private String formatNameOptions(DictTypeEnum dictTypeEnum) {
        return this.getDictList(dictTypeEnum).stream()
                .map(DictListVo::getDictName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("、"));
    }

    private String dictTagNames(DictTypeEnum dictTypeEnum, String tags) {
        List<String> values = this.toTextList(tags);
        if (values.isEmpty()) {
            return "未设置";
        }
        List<DictListVo> dictList = this.getDictList(dictTypeEnum);
        return values.stream()
                .map(value -> {
                    Integer code = this.matchDictCode(dictTypeEnum, value);
                    if (code == null) {
                        return value;
                    }
                    return dictList.stream()
                            .filter(dict -> Objects.equals(dict.getDictCode(), code))
                            .map(DictListVo::getDictName)
                            .findFirst()
                            .orElse(value);
                })
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("、"));
    }

    private List<DictListVo> fallbackDictList(DictTypeEnum dictTypeEnum) {
        return switch (dictTypeEnum) {
            case WARDROBE_ITEM_CATEGORY -> List.of(
                    this.dict(1, "上装"),
                    this.dict(2, "下装"),
                    this.dict(3, "连衣裙"),
                    this.dict(4, "内衣"),
                    this.dict(5, "袜子"),
                    this.dict(6, "鞋履"),
                    this.dict(7, "配饰"),
                    this.dict(8, "帽子"),
                    this.dict(9, "包袋"),
                    this.dict(10, "首饰"),
                    this.dict(11, "其他")
            );
            case WARDROBE_ITEM_COLOR -> List.of(
                    this.dict(1, "黑色"),
                    this.dict(2, "白色"),
                    this.dict(3, "灰色"),
                    this.dict(4, "蓝色"),
                    this.dict(5, "绿色"),
                    this.dict(6, "红色"),
                    this.dict(7, "黄色"),
                    this.dict(8, "粉色"),
                    this.dict(9, "紫色"),
                    this.dict(10, "棕色"),
                    this.dict(11, "米色"),
                    this.dict(12, "卡其色"),
                    this.dict(13, "牛仔蓝"),
                    this.dict(14, "藏青色"),
                    this.dict(15, "彩色")
            );
            case WARDROBE_ITEM_SCENE -> List.of(
                    this.dict(1, "日常"),
                    this.dict(2, "通勤"),
                    this.dict(3, "约会"),
                    this.dict(4, "运动"),
                    this.dict(5, "旅行"),
                    this.dict(6, "正式"),
                    this.dict(7, "居家"),
                    this.dict(8, "户外"),
                    this.dict(9, "聚会")
            );
            case WARDROBE_ITEM_STYLE -> List.of(
                    this.dict(1, "休闲"),
                    this.dict(2, "简约"),
                    this.dict(3, "利落"),
                    this.dict(4, "甜美"),
                    this.dict(5, "街头"),
                    this.dict(6, "复古"),
                    this.dict(7, "户外"),
                    this.dict(8, "运动"),
                    this.dict(9, "通勤"),
                    this.dict(10, "优雅"),
                    this.dict(11, "中性"),
                    this.dict(12, "学院"),
                    this.dict(13, "度假")
            );
            case WARDROBE_ITEM_SUBCATEGORY -> List.of(
                    this.dict(101, "T恤"),
                    this.dict(102, "衬衫"),
                    this.dict(103, "Polo衫"),
                    this.dict(104, "卫衣"),
                    this.dict(105, "毛衣"),
                    this.dict(106, "针织衫"),
                    this.dict(107, "打底衫"),
                    this.dict(108, "背心"),
                    this.dict(109, "吊带"),
                    this.dict(110, "抹胸"),
                    this.dict(111, "雪纺衫"),
                    this.dict(112, "马甲"),
                    this.dict(113, "开衫"),
                    this.dict(114, "防晒衣"),
                    this.dict(115, "其他上装"),
                    this.dict(201, "牛仔裤"),
                    this.dict(202, "休闲裤"),
                    this.dict(203, "西裤"),
                    this.dict(204, "运动裤"),
                    this.dict(205, "工装裤"),
                    this.dict(206, "阔腿裤"),
                    this.dict(207, "短裤"),
                    this.dict(208, "半身裙"),
                    this.dict(209, "短裙"),
                    this.dict(210, "长裙"),
                    this.dict(211, "打底裤"),
                    this.dict(212, "瑜伽裤"),
                    this.dict(213, "背带裤"),
                    this.dict(214, "其他下装"),
                    this.dict(301, "连衣裙"),
                    this.dict(302, "衬衫裙"),
                    this.dict(303, "针织裙"),
                    this.dict(304, "吊带裙"),
                    this.dict(305, "背心裙"),
                    this.dict(306, "背带裙"),
                    this.dict(307, "礼服裙"),
                    this.dict(308, "旗袍"),
                    this.dict(309, "连体裤"),
                    this.dict(310, "套装裙"),
                    this.dict(311, "其他连衣裙"),
                    this.dict(401, "文胸"),
                    this.dict(402, "内裤"),
                    this.dict(403, "保暖内衣"),
                    this.dict(404, "睡衣"),
                    this.dict(405, "家居服"),
                    this.dict(406, "塑身衣"),
                    this.dict(407, "打底背心"),
                    this.dict(408, "抹胸内衣"),
                    this.dict(409, "泳衣"),
                    this.dict(410, "其他内衣"),
                    this.dict(501, "短袜"),
                    this.dict(502, "中筒袜"),
                    this.dict(503, "长筒袜"),
                    this.dict(504, "船袜"),
                    this.dict(505, "隐形袜"),
                    this.dict(506, "堆堆袜"),
                    this.dict(507, "连裤袜"),
                    this.dict(508, "打底袜"),
                    this.dict(509, "运动袜"),
                    this.dict(510, "保暖袜"),
                    this.dict(511, "其他袜子"),
                    this.dict(601, "运动鞋"),
                    this.dict(602, "休闲鞋"),
                    this.dict(603, "板鞋"),
                    this.dict(604, "帆布鞋"),
                    this.dict(605, "皮鞋"),
                    this.dict(606, "乐福鞋"),
                    this.dict(607, "靴子"),
                    this.dict(608, "短靴"),
                    this.dict(609, "长靴"),
                    this.dict(610, "凉鞋"),
                    this.dict(611, "拖鞋"),
                    this.dict(612, "高跟鞋"),
                    this.dict(613, "雨鞋"),
                    this.dict(614, "其他鞋履"),
                    this.dict(701, "围巾"),
                    this.dict(702, "披肩"),
                    this.dict(703, "腰带"),
                    this.dict(704, "手套"),
                    this.dict(705, "领带"),
                    this.dict(706, "领结"),
                    this.dict(707, "丝巾"),
                    this.dict(708, "发饰"),
                    this.dict(709, "眼镜"),
                    this.dict(710, "墨镜"),
                    this.dict(711, "口罩"),
                    this.dict(712, "其他配饰"),
                    this.dict(801, "棒球帽"),
                    this.dict(802, "渔夫帽"),
                    this.dict(803, "贝雷帽"),
                    this.dict(804, "毛线帽"),
                    this.dict(805, "鸭舌帽"),
                    this.dict(806, "遮阳帽"),
                    this.dict(807, "礼帽"),
                    this.dict(808, "草帽"),
                    this.dict(809, "空顶帽"),
                    this.dict(810, "其他帽子"),
                    this.dict(901, "双肩包"),
                    this.dict(902, "托特包"),
                    this.dict(903, "斜挎包"),
                    this.dict(904, "单肩包"),
                    this.dict(905, "手提包"),
                    this.dict(906, "腰包"),
                    this.dict(907, "胸包"),
                    this.dict(908, "钱包"),
                    this.dict(909, "卡包"),
                    this.dict(910, "化妆包"),
                    this.dict(911, "旅行包"),
                    this.dict(912, "其他包袋"),
                    this.dict(1001, "项链"),
                    this.dict(1002, "耳钉"),
                    this.dict(1003, "耳环"),
                    this.dict(1004, "戒指"),
                    this.dict(1005, "手链"),
                    this.dict(1006, "手镯"),
                    this.dict(1007, "胸针"),
                    this.dict(1008, "脚链"),
                    this.dict(1009, "手表"),
                    this.dict(1010, "其他首饰"),
                    this.dict(1101, "其他款式"),
                    this.dict(1102, "待分类")
            );
            default -> List.of();
        };
    }

    private DictListVo dict(Integer code, String name) {
        DictListVo vo = new DictListVo();
        vo.setDictCode(code);
        vo.setDictName(name);
        return vo;
    }

}

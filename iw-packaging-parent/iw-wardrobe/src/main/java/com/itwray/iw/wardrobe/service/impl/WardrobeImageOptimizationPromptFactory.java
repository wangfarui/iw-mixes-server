package com.itwray.iw.wardrobe.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WardrobeImageOptimizationPromptFactory {

    static final String RULE_VERSION = "wardrobe-item-image-optimize/v1";

    public Input create(WardrobeItemEntity item, String userPrompt) {
        String normalizedUserPrompt = StringUtils.trimToEmpty(userPrompt);
        String prompt = """
                参考用户提供的衣物图片，为衣柜应用生成一张新的单品图片。
                只保留并重绘这一件衣物，不要生成真人、模特、全身穿搭、其它衣物或配饰。
                衣物名称：%s
                品类编码：%s
                款式编码：%s
                颜色：%s
                品牌：%s
                材质：%s
                季节：%s
                场景：%s
                风格：%s
                标签：%s
                备注：%s
                用户补充要求：%s
                生成要求：白色或浅灰纯净背景，衣物完整居中，保留原图可见的颜色、材质、版型、领型、袖长、图案和装饰细节，适合衣柜列表缩略图。不要添加文字、水印、价格、吊牌或不存在的品牌标识。
                """.formatted(
                StringUtils.defaultIfBlank(item.getItemName(), "未命名衣物"),
                item.getCategory() == null ? "未设置" : item.getCategory(),
                item.getItemStyle() == null ? "未设置" : item.getItemStyle(),
                StringUtils.defaultIfBlank(item.getColorName(), "按参考图判断"),
                StringUtils.defaultIfBlank(item.getBrand(), "无"),
                StringUtils.defaultIfBlank(item.getMaterial(), "按参考图判断"),
                StringUtils.defaultIfBlank(item.getSeasonTags(), "未设置"),
                StringUtils.defaultIfBlank(item.getSceneTags(), "未设置"),
                StringUtils.defaultIfBlank(item.getStyleTags(), "未设置"),
                StringUtils.defaultIfBlank(item.getCustomTags(), "无"),
                StringUtils.defaultIfBlank(item.getRemark(), "无"),
                StringUtils.defaultIfBlank(normalizedUserPrompt, "无")
        ).trim();
        String sourceImageUrl = StringUtils.trimToEmpty(item.getItemImage());
        String fingerprint = DigestUtil.sha256Hex(String.join("|",
                String.valueOf(item.getId()), sourceImageUrl, prompt, RULE_VERSION));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("itemName", item.getItemName());
        snapshot.put("category", item.getCategory());
        snapshot.put("itemStyle", item.getItemStyle());
        snapshot.put("colorName", item.getColorName());
        snapshot.put("seasonTags", item.getSeasonTags());
        snapshot.put("sceneTags", item.getSceneTags());
        snapshot.put("styleTags", item.getStyleTags());
        snapshot.put("brand", item.getBrand());
        snapshot.put("material", item.getMaterial());
        snapshot.put("customTags", item.getCustomTags());
        snapshot.put("remark", item.getRemark());
        return new Input(sourceImageUrl, normalizedUserPrompt, prompt, fingerprint, JSONUtil.toJsonStr(snapshot));
    }

    public record Input(String sourceImageUrl,
                        String userPrompt,
                        String normalizedPrompt,
                        String fingerprint,
                        String snapshotJson) {
    }
}

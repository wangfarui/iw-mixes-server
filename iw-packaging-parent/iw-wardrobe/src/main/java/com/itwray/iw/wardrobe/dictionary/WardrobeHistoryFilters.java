package com.itwray.iw.wardrobe.dictionary;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.wardrobe.dao.*;
import com.itwray.iw.wardrobe.model.entity.*;
import com.itwray.iw.wardrobe.service.WardrobeItemAccessService;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.UserUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Filter keys encode snapshot values, never authorization or an active dictionary reference. */
@RestController
@RequestMapping("/wardrobe")
public class WardrobeHistoryFilters {
    public record Key(String type, String name, String parent, Integer matchKind) { }
    public record Option(String value, String text, String parentKey) { }
    public record Filters(List<Option> categories, List<Option> itemStyles, List<Option> colors, List<Option> scenes, List<Option> styles) { }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final WardrobeItemDao items;
    private final WardrobeOutfitDao outfits;
    private final WardrobeWearRecordDao records;
    private final WardrobeItemAccessService access;
    private final WardrobeSnapshotService snapshots;
    public WardrobeHistoryFilters(WardrobeItemDao items, WardrobeOutfitDao outfits, WardrobeWearRecordDao records,
                                   WardrobeItemAccessService access, WardrobeSnapshotService snapshots) {
        this.items = items; this.outfits = outfits; this.records = records; this.access = access; this.snapshots = snapshots;
    }
    @GetMapping("/filters")
    public Filters filters(@RequestParam WardrobeOptionsController.BusinessType businessType,
                           @RequestParam(required=false) Integer ownerUserId, @RequestParam(required=false) Boolean queryOnlyMyself) {
        Map<String,Option> category = new LinkedHashMap<>(), style = new LinkedHashMap<>(), color = new LinkedHashMap<>(), scene = new LinkedHashMap<>(), tags = new LinkedHashMap<>();
        List<?> rows;
        if (businessType == WardrobeOptionsController.BusinessType.ITEM) {
            List<Integer> owners = access.resolveVisibleOwnerIds(Boolean.TRUE.equals(queryOnlyMyself));
            if (ownerUserId != null && !owners.contains(ownerUserId)) throw new BusinessException("不能查看该成员的衣物");
            rows = items.lambdaQuery().in(WardrobeItemEntity::getUserId, owners)
                    .eq(ownerUserId != null, WardrobeItemEntity::getUserId, ownerUserId).list();
            for (var raw : rows) {
                WardrobeItemEntity row = (WardrobeItemEntity) raw;
                Key parent = new Key("CATEGORY", row.getCategoryNameSnapshot(), null, row.getCategoryMatchKindSnapshot());
                boolean ambiguous = rows.stream().map(WardrobeItemEntity.class::cast).anyMatch(other ->
                        Objects.equals(other.getCategoryNameSnapshot(), row.getCategoryNameSnapshot())
                        && !Objects.equals(other.getCategoryMatchKindSnapshot(), row.getCategoryMatchKindSnapshot()));
                String categoryLabel = row.getCategoryNameSnapshot();
                if (ambiguous) categoryLabel += "（" + kindName(row.getCategoryMatchKindSnapshot()) + "）";
                put(category, parent, categoryLabel, null);
                if (row.getItemStyleNameSnapshot() != null && !row.getItemStyleNameSnapshot().isEmpty())
                    put(style, new Key("ITEM_STYLE", row.getItemStyleNameSnapshot(), row.getCategoryNameSnapshot(), row.getCategoryMatchKindSnapshot()),
                            categoryLabel + " / " + row.getItemStyleNameSnapshot(), encode(parent));
                if (row.getColorName() != null && !row.getColorName().isEmpty()) put(color, new Key("COLOR", row.getColorName(), null, null), row.getColorName(), null);
            }
        } else {
            if (ownerUserId != null && !Objects.equals(ownerUserId, UserUtils.getUserId())) throw new BusinessException("不能查看该成员的记录");
            rows = businessType == WardrobeOptionsController.BusinessType.OUTFIT
                    ? outfits.lambdaQuery().eq(WardrobeOutfitEntity::getUserId, UserUtils.getUserId()).list()
                    : records.lambdaQuery().eq(WardrobeWearRecordEntity::getUserId, UserUtils.getUserId()).list();
        }
        List<Integer> ids = rows.stream().map(row -> (Integer)new BeanWrapperImpl(row).getPropertyValue("id")).toList();
        for (var tag : snapshots.tagsFor(businessType.name(), ids)) {
            String type = tag.dictType() == 5004 ? "SCENE" : "STYLE";
            put(tag.dictType() == 5004 ? scene : tags, new Key(type, tag.nameSnapshot(), null, null), tag.nameSnapshot(), null);
        }
        return new Filters(List.copyOf(category.values()), List.copyOf(style.values()), List.copyOf(color.values()), List.copyOf(scene.values()), List.copyOf(tags.values()));
    }
    private String kindName(Integer kind) {
        List<String> labels = List.of("上装", "下装", "连衣裙", "内衣", "袜子", "鞋履", "配饰", "帽子", "包袋", "首饰", "其他");
        return kind != null && kind >= 1 && kind <= labels.size() ? labels.get(kind - 1) : "未归类";
    }
    private void put(Map<String,Option> values, Key key, String text, String parentKey) {
        if (key.name == null || key.name.isEmpty()) return;
        String encoded = encode(key); values.putIfAbsent(encoded, new Option(encoded, text, parentKey));
    }
    public static String encode(Key key) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(key)); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    public static Key decode(String value, String expectedType) {
        try {
            if (value == null || value.length() > 1024) throw new IllegalArgumentException();
            Key key = JSON.readValue(Base64.getUrlDecoder().decode(value), Key.class);
            if (!expectedType.equals(key.type) || key.name == null || key.name.codePointCount(0,key.name.length()) > 32) throw new IllegalArgumentException();
            return key;
        } catch (Exception ex) { throw new BusinessException("筛选条件已失效，请重新选择"); }
    }
    public static <T> void scalar(LambdaQueryWrapper<T> query, String key, String type) {
        if (key == null || key.isEmpty()) return; Key filter = decode(key, type);
        switch (type) {
            case "CATEGORY" -> query.apply("CAST(category_name_snapshot AS BINARY)=CAST({0} AS BINARY) AND category_match_kind_snapshot={1}", filter.name, filter.matchKind);
            case "ITEM_STYLE" -> query.apply("CAST(item_style_name_snapshot AS BINARY)=CAST({0} AS BINARY) AND CAST(category_name_snapshot AS BINARY)=CAST({1} AS BINARY) AND category_match_kind_snapshot={2}", filter.name, filter.parent, filter.matchKind);
            case "COLOR" -> query.apply("CAST(color_name AS BINARY)=CAST({0} AS BINARY)", filter.name);
            default -> throw new BusinessException("不支持的筛选类型");
        }
    }
    public static <T> void tag(LambdaQueryWrapper<T> query, String key, String type, WardrobeOptionsController.BusinessType business) {
        if (key == null || key.isEmpty()) return; Key filter = decode(key, type);
        String table = business == WardrobeOptionsController.BusinessType.ITEM ? "wardrobe_item" : business == WardrobeOptionsController.BusinessType.OUTFIT ? "wardrobe_outfit" : "wardrobe_wear_record";
        query.apply("EXISTS (SELECT 1 FROM wardrobe_dictionary_tag_snapshot wt WHERE wt.business_type={0} AND wt.business_id=" + table + ".id AND wt.dict_type={1} AND CAST(wt.name_snapshot AS BINARY)=CAST({2} AS BINARY))",
                business.name(), type.equals("SCENE") ? 5004 : 5005, filter.name);
    }
}

package com.itwray.iw.eat.dictionary;

import com.fasterxml.jackson.databind.JsonNode;
import com.itwray.iw.eat.model.entity.EatFridgeFoodEntity;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.kitchen.KitchenDictionaryAccess;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Resolves new fridge selections only. Preserved snapshots never follow dictionary renames. */
@Service
public class KitchenSnapshotService {
    private final KitchenDictionaryAccess dictionaries;

    public KitchenSnapshotService(KitchenDictionaryAccess dictionaries) {
        this.dictionaries = dictionaries;
    }

    public static void rejectLegacyValues(Object dto, String... fields) {
        BeanWrapper value = new BeanWrapperImpl(dto);
        for (String field : fields) if (value.getPropertyValue(field) != null)
            throw new BusinessException("请使用当前厨房选项重新选择后保存");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void applyCreate(EatFridgeFoodEntity target, JsonNode selection, Map<String, String> context) {
        if (selection == null || !selection.isObject()) throw error("请刷新厨房选项后保存");
        requireKnownFields(selection);
        Map<Integer, BaseDictEntity> sources = resolve(target.getUserId(), selection, context);
        applyField(target, sources, selection, "categorySourceId", 3010, true);
        applyField(target, sources, selection, "sectionSourceId", 3011, true);
        target.setDictionarySnapshotStatus(1);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void applyUpdate(EatFridgeFoodEntity target, EatFridgeFoodEntity current, JsonNode changes, Map<String, String> context) {
        preserve(target, current);
        if (changes == null) return;
        if (!changes.isObject()) throw error("字典变更必须是对象");
        if (changes.isNull()) throw error("字典变更不能为 null");
        if (changes.isEmpty()) return;
        requireKnownFields(changes);
        Map<Integer, BaseDictEntity> sources = resolve(target.getUserId(), changes, context);
        if (changes.has("categorySourceId")) applyField(target, sources, changes, "categorySourceId", 3010, false);
        if (changes.has("sectionSourceId")) applyField(target, sources, changes, "sectionSourceId", 3011, false);
        target.setDictionarySnapshotStatus(1);
    }

    public Map<String, String> currentContext(Integer owner) {
        return Map.of("contextToken", dictionaries.context(owner).contextToken());
    }

    private void applyField(EatFridgeFoodEntity target, Map<Integer, BaseDictEntity> sources, JsonNode patch,
                            String key, int type, boolean create) {
        JsonNode node = patch.get(key);
        Integer id = scalar(node);
        if (id == null) {
            if (type == 3010) {
                target.setCategorySourceId(null); target.setCategoryNameSnapshot(null); target.setCategory(null);
            } else {
                target.setSectionSourceId(null); target.setSectionNameSnapshot(null); target.setSection(null);
            }
            return;
        }
        BaseDictEntity row = required(sources, id, type);
        if (type == 3010) {
            target.setCategorySourceId(row.getId());
            target.setCategoryNameSnapshot(row.getDictName());
            target.setCategory(row.getDictCode());
        } else {
            target.setSectionSourceId(row.getId());
            target.setSectionNameSnapshot(row.getDictName());
            target.setSection(row.getDictCode());
        }
    }

    private void preserve(EatFridgeFoodEntity target, EatFridgeFoodEntity current) {
        target.setCategorySourceId(current.getCategorySourceId());
        target.setCategoryNameSnapshot(current.getCategoryNameSnapshot());
        target.setSectionSourceId(current.getSectionSourceId());
        target.setSectionNameSnapshot(current.getSectionNameSnapshot());
        target.setCategory(current.getCategory());
        target.setSection(current.getSection());
        target.setDictionarySnapshotStatus(current.getDictionarySnapshotStatus());
    }

    private Map<Integer, BaseDictEntity> resolve(Integer owner, JsonNode patch, Map<String, String> context) {
        if (context == null || context.get("contextToken") == null)
            throw error("厨房选项上下文已失效，请刷新后重试");
        Set<Integer> ids = new LinkedHashSet<>();
        for (String key : List.of("categorySourceId", "sectionSourceId")) {
            Integer id = scalar(patch.get(key));
            if (id != null) ids.add(id);
        }
        Map<Integer, BaseDictEntity> result = new HashMap<>();
        if (!ids.isEmpty())
            dictionaries.lockSelectable(owner, context.get("contextToken"), ids).forEach(row -> result.put(row.getId(), row));
        return result;
    }

    private void requireKnownFields(JsonNode patch) {
        Set<String> allowed = Set.of("categorySourceId", "sectionSourceId");
        patch.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw error("不支持的字典变更字段");
        });
    }

    private Integer scalar(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() <= 0) throw error("选项ID无效");
        return node.intValue();
    }

    private BaseDictEntity required(Map<Integer, BaseDictEntity> sources, Integer id, int type) {
        BaseDictEntity row = sources.get(id);
        if (row == null || !Objects.equals(row.getDictType(), type)) throw error("字典类型或来源无效");
        return row;
    }

    private BusinessException error(String message) { return new BusinessException(message); }
}

package com.itwray.iw.wardrobe.dictionary;

import com.itwray.iw.wardrobe.mapper.WardrobeSnapshotMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import com.itwray.iw.web.wardrobe.WardrobeDictionaryAccess;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Resolves new selections only. Preserved business snapshots never follow dictionary renames. */
@Service
public class WardrobeSnapshotService {
    public record Tag(Integer sourceId, String nameSnapshot) { }
    public record Prepared(Map<Integer,List<Tag>> tags) { }
    private final WardrobeDictionaryAccess dictionaries;
    private final WardrobeSnapshotMapper mapper;
    public WardrobeSnapshotService(WardrobeDictionaryAccess dictionaries, WardrobeSnapshotMapper mapper) {
        this.dictionaries = dictionaries; this.mapper = mapper;
    }
    public static void rejectLegacyValues(Object dto, String... fields) {
        BeanWrapper value = new BeanWrapperImpl(dto);
        for (String field : fields) if (value.getPropertyValue(field) != null)
            throw new BusinessException("请使用当前衣柜选项重新选择后保存");
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public WardrobeItemEntity lockItemForWrite(Integer operator, Integer originalOwner, Integer targetOwner, Integer itemId) {
        List<Integer> ids = new ArrayList<>(List.of(operator, targetOwner)); if (originalOwner != null) ids.add(originalOwner);
        Map<Integer,WardrobeDictionaryAccess.Identity> identities = new HashMap<>();
        dictionaries.lockOwners(ids).forEach(identity -> identities.put(identity.userId(), identity));
        var actor = identities.get(operator); var target = identities.get(targetOwner);
        if (!Objects.equals(operator, targetOwner) && (actor.child() || actor.familyGroupId() <= 0
                || !Objects.equals(actor.familyGroupId(), target.familyGroupId()))) throw error("所属人或家庭权限已变化，请刷新后重试");
        if (itemId == null) return null;
        var original = identities.get(originalOwner);
        if (!Objects.equals(operator, originalOwner) && (!actor.manager() || actor.familyGroupId() <= 0
                || !Objects.equals(actor.familyGroupId(), original.familyGroupId()))) throw error("只能管理自己的衣物");
        WardrobeItemEntity current = mapper.lockItem(itemId, originalOwner);
        if (current == null) throw error("衣物所属人已变化，请刷新后重试"); return current;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Prepared prepareItem(WardrobeItemEntity target, WardrobeItemEntity current, JsonNode selection,
                                JsonNode changes, Map<String,String> context) {
        boolean create = current == null;
        JsonNode patch = create ? selection : changes;
        if (!create) preserve(target, current);
        if (create && (patch == null || !patch.isObject() || !patch.has("categoryId"))) throw error("请选择品类后保存");
        if (!create && patch == null) return new Prepared(Map.of());
        requireObject(patch);
        if (!create && patch.isEmpty()) return new Prepared(Map.of());
        Set<String> allowed = Set.of("categoryId", "itemStyleId", "colorId", "sceneIds", "styleIds");
        patch.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw error("不支持的字典变更字段"); });
        Map<Integer,BaseDictEntity> sources = resolve(target.getUserId(), patch, context);
        if (patch.has("categoryId")) {
            BaseDictEntity category = required(sources, scalar(patch.get("categoryId")), 5002);
            if (!create && !patch.has("itemStyleId")) throw error("更换品类时请同时选择或清空款式");
            target.setCategorySourceId(category.getId()); target.setCategoryNameSnapshot(category.getDictName());
            target.setCategoryMatchKindSnapshot(category.getWardrobeMatchKind());
            target.setCategory(legacyCategory(category));
        }
        if (create || patch.has("itemStyleId")) {
            Integer id = scalar(patch.get("itemStyleId"));
            if (id == null) { target.setItemStyleSourceId(null); target.setItemStyleNameSnapshot(null); target.setItemStyle(0); }
            else {
                BaseDictEntity style = required(sources, id, 5006);
                if (!Objects.equals(style.getParentId(), target.getCategorySourceId())) throw error("款式不属于所选品类，请同时重新选择品类和款式");
                target.setItemStyleSourceId(id); target.setItemStyleNameSnapshot(style.getDictName()); target.setItemStyle(style.getDictCode());
            }
        }
        if (create || patch.has("colorId")) {
            Integer id = scalar(patch.get("colorId"));
            BaseDictEntity color = id == null ? null : required(sources, id, 5003);
            target.setColorSourceId(id); target.setColorName(color == null ? "" : color.getDictName());
            if (color == null) target.setColorHex("");
        }
        target.setDictionarySnapshotStatus(1);
        return prepareTags(patch, sources, create, true);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Prepared prepareHead(Integer owner, boolean create, JsonNode selection, JsonNode changes,
                                Map<String,String> context, boolean hasStyle) {
        JsonNode patch = create ? selection : changes;
        if (patch == null && !create) return new Prepared(Map.of());
        if (patch == null) throw error("请刷新衣柜选项后保存");
        requireObject(patch);
        patch.fieldNames().forEachRemaining(key -> {
            if (!key.equals("sceneIds") && !(hasStyle && key.equals("styleIds"))) throw error("不支持的字典变更字段");
        });
        if (patch.isEmpty() && !create) return new Prepared(Map.of());
        return prepareTags(patch, resolve(owner, patch, context), create, hasStyle);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(String kind, Integer id, Prepared prepared) {
        for (Map.Entry<Integer,List<Tag>> entry : prepared.tags.entrySet()) {
            mapper.clear(kind, id, entry.getKey());
            int ordinal = 0;
            for (Tag tag : entry.getValue()) mapper.insert(kind, id, entry.getKey(), ++ordinal, tag.sourceId, tag.nameSnapshot);
        }
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void copyTags(String kind, Integer sourceId, Integer targetId) {
        for (WardrobeSnapshotMapper.StoredTag tag : mapper.tags(kind, sourceId))
            mapper.insert(kind, targetId, tag.dictType(), tag.ordinal(), tag.sourceId(), tag.nameSnapshot());
    }
    public Map<String,String> currentContext(Integer owner) {
        return Map.of("contextToken", dictionaries.context(owner).contextToken());
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void copyScenes(String sourceKind, Integer sourceId, String targetKind, Integer targetId) {
        for (var tag : mapper.tags(sourceKind, sourceId)) if (tag.dictType() == 5004)
            mapper.insert(targetKind, targetId, 5004, tag.ordinal(), tag.sourceId(), tag.nameSnapshot());
    }
    public List<WardrobeSnapshotMapper.StoredTag> tagsFor(String kind, Collection<Integer> ids) {
        return ids == null || ids.isEmpty() ? List.of() : mapper.tagsBatch(kind, List.copyOf(ids));
    }
    public void attach(Object vo, String kind, Integer id) {
        attachTags(vo, mapper.tags(kind, id));
    }
    public void attachAll(Collection<?> rows, String kind) {
        if (rows == null || rows.isEmpty()) return;
        List<Integer> ids = rows.stream().map(row -> (Integer)new BeanWrapperImpl(row).getPropertyValue("id")).toList();
        Map<Integer,List<WardrobeSnapshotMapper.StoredTag>> tags = mapper.tagsBatch(kind, ids).stream().collect(java.util.stream.Collectors.groupingBy(WardrobeSnapshotMapper.StoredTag::businessId));
        for (Object row : rows) attachTags(row, tags.getOrDefault((Integer)new BeanWrapperImpl(row).getPropertyValue("id"), List.of()));
    }
    private void attachTags(Object vo, List<WardrobeSnapshotMapper.StoredTag> tags) {
        BeanWrapper bean = new BeanWrapperImpl(vo);
        List<Tag> scene = tags.stream().filter(t -> t.dictType() == 5004).map(t -> new Tag(t.sourceId(), t.nameSnapshot())).toList();
        List<Tag> style = tags.stream().filter(t -> t.dictType() == 5005).map(t -> new Tag(t.sourceId(), t.nameSnapshot())).toList();
        bean.setPropertyValue("sceneSnapshots", scene); bean.setPropertyValue("styleSnapshots", style);
        Map<String,Object> snapshot = new LinkedHashMap<>();
        if (bean.isReadableProperty("categorySourceId")) {
            snapshot.put("category", source(bean, "categorySourceId", "categoryNameSnapshot"));
            snapshot.put("itemStyle", source(bean, "itemStyleSourceId", "itemStyleNameSnapshot"));
            snapshot.put("matchKind", bean.getPropertyValue("categoryMatchKindSnapshot"));
            if (bean.isReadableProperty("colorSourceId")) snapshot.put("color", source(bean, "colorSourceId", "colorName"));
        }
        snapshot.put("scenes", scene); snapshot.put("styles", style); bean.setPropertyValue("dictionarySnapshot", snapshot);
    }
    public void copyScalar(Object target, Object source) {
        BeanWrapper to = new BeanWrapperImpl(target), from = new BeanWrapperImpl(source);
        for (String key : List.of("categorySourceId", "categoryNameSnapshot", "categoryMatchKindSnapshot", "itemStyleSourceId", "itemStyleNameSnapshot", "dictionarySnapshotStatus"))
            to.setPropertyValue(key, from.getPropertyValue(key));
    }
    private Map<String,Object> source(BeanWrapper bean, String id, String name) {
        Map<String,Object> value = new LinkedHashMap<>(); value.put("sourceId", bean.getPropertyValue(id)); value.put("nameSnapshot", bean.getPropertyValue(name)); return value;
    }
    private void preserve(WardrobeItemEntity target, WardrobeItemEntity current) {
        copyScalar(target, current); target.setColorSourceId(current.getColorSourceId()); target.setColorName(current.getColorName());
        target.setCategory(current.getCategory()); target.setItemStyle(current.getItemStyle());
        target.setSceneTags(current.getSceneTags()); target.setStyleTags(current.getStyleTags());
    }
    private Prepared prepareTags(JsonNode patch, Map<Integer,BaseDictEntity> sources, boolean create, boolean hasStyle) {
        Map<Integer,List<Tag>> result = new LinkedHashMap<>();
        if (create || patch.has("sceneIds")) result.put(5004, tags(patch.get("sceneIds"), sources, 5004));
        if (hasStyle && (create || patch.has("styleIds"))) result.put(5005, tags(patch.get("styleIds"), sources, 5005));
        return new Prepared(result);
    }
    private Map<Integer,BaseDictEntity> resolve(Integer owner, JsonNode patch, Map<String,String> context) {
        if (context == null || context.get("contextToken") == null) throw error("衣柜选项上下文已失效，请刷新后重试");
        Set<Integer> ids = new LinkedHashSet<>();
        for (String key : List.of("categoryId", "itemStyleId", "colorId")) {
            Integer id = scalar(patch.get(key)); if (id != null) ids.add(id);
        }
        for (String key : List.of("sceneIds", "styleIds")) ids.addAll(array(patch.get(key)));
        Map<Integer,BaseDictEntity> result = new HashMap<>();
        dictionaries.lockSelectable(owner, context.get("contextToken"), ids).forEach(row -> result.put(row.getId(), row)); return result;
    }
    private List<Tag> tags(JsonNode node, Map<Integer,BaseDictEntity> sources, int type) {
        return array(node).stream().map(id -> { BaseDictEntity row = required(sources, id, type); return new Tag(id, row.getDictName()); }).toList();
    }
    private List<Integer> array(JsonNode node) {
        if (node == null) return List.of();
        if (!node.isArray()) throw error("多选项必须是数组，清空请传空数组");
        Set<Integer> ids = new LinkedHashSet<>(); for (JsonNode value : node) { Integer id = scalar(value); if (id == null) throw error("选项ID无效"); ids.add(id); }
        return List.copyOf(ids);
    }
    private Integer scalar(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() <= 0) throw error("选项ID无效"); return node.intValue();
    }
    private BaseDictEntity required(Map<Integer,BaseDictEntity> sources, Integer id, int type) {
        BaseDictEntity row = sources.get(id); if (row == null || !Objects.equals(row.getDictType(), type)) throw error("字典类型或来源无效"); return row;
    }
    private int legacyCategory(BaseDictEntity row) { Integer code = row.getDictCode(); return code != null && code >= 1 && code <= 11 ? code : 0; }
    private void requireObject(JsonNode patch) { if (!patch.isObject()) throw error("字典选择或变更必须是对象"); }
    private BusinessException error(String message) { return new BusinessException(message); }
}

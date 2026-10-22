package com.itwray.iw.wardrobe.dictionary;

import com.itwray.iw.wardrobe.dao.WardrobeItemDao;
import com.itwray.iw.wardrobe.dao.WardrobeOutfitDao;
import com.itwray.iw.wardrobe.dao.WardrobeWearRecordDao;
import com.itwray.iw.wardrobe.service.WardrobeItemAccessService;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import com.itwray.iw.web.utils.UserUtils;
import com.itwray.iw.web.wardrobe.WardrobeDictionaryAccess;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/wardrobe")
public class WardrobeOptionsController {
    public enum BusinessType { ITEM, OUTFIT, WEAR_RECORD }
    public record Source(Integer id, String scope, Integer revision) { }
    public record Option(Integer sourceId, String name, String scope, Integer revision) { }
    public record ItemStyle(String name, Integer sourceId, Integer categorySourceId, String scope, Integer revision) { }
    public record Category(String key, String name, Integer matchKind, Integer preferredSourceId,
                           List<Source> sources, List<ItemStyle> itemStyles) { }
    public record Options(Integer ownerUserId, Integer familyGroupId, String personalScopeVersion,
                          String familyScopeVersion, String contextToken, List<Category> categories,
                          List<Option> colors, List<Option> scenes, List<Option> styleTags) { }
    private record CategoryKey(String name, Integer kind) { }
    private final WardrobeDictionaryAccess dictionaries;
    private final WardrobeItemAccessService access;
    private final WardrobeItemDao items;
    private final WardrobeOutfitDao outfits;
    private final WardrobeWearRecordDao records;

    public WardrobeOptionsController(WardrobeDictionaryAccess dictionaries, WardrobeItemAccessService access,
                                     WardrobeItemDao items, WardrobeOutfitDao outfits, WardrobeWearRecordDao records) {
        this.dictionaries = dictionaries; this.access = access; this.items = items; this.outfits = outfits; this.records = records;
    }
    @GetMapping("/options")
    public Options options(@RequestParam BusinessType businessType, @RequestParam(required = false) Integer ownerUserId,
                           @RequestParam(required = false) Integer businessId) {
        Integer owner = UserUtils.getUserId();
        if (businessType == BusinessType.ITEM) {
            if (businessId != null) {
                var item = items.queryByIdInOwnerIds(businessId, access.resolveFamilyOwnerIds()); access.requireManage(item);
                owner = ownerUserId == null ? item.getUserId() : access.resolveOwnerForSave(ownerUserId);
            } else owner = access.resolveOwnerForSave(ownerUserId);
        } else {
            if (ownerUserId != null && !owner.equals(ownerUserId)) throw new BusinessException("不能代建该成员的搭配或记录");
            if (businessId != null) {
                if (businessType == BusinessType.OUTFIT) outfits.queryById(businessId); else records.queryById(businessId);
            }
        }
        return options(dictionaries.context(owner));
    }
    private Options options(WardrobeDictionaryAccess.Context context) {
        List<BaseDictEntity> rows = context.items().stream().sorted(Comparator.comparing(BaseDictEntity::getScopeType)
                .thenComparing(BaseDictEntity::getSort, Comparator.nullsLast(Integer::compareTo)).thenComparing(BaseDictEntity::getId)).toList();
        Map<Integer,BaseDictEntity> byId = new HashMap<>(); rows.forEach(r -> byId.put(r.getId(), r));
        Map<CategoryKey,List<BaseDictEntity>> groups = new LinkedHashMap<>();
        rows.stream().filter(r -> r.getDictType() == 5002).forEach(r -> groups.computeIfAbsent(new CategoryKey(r.getDictName(), r.getWardrobeMatchKind()), k -> new ArrayList<>()).add(r));
        List<Category> categories = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            List<BaseDictEntity> parents = entry.getValue(); Set<Integer> parentIds = new HashSet<>(parents.stream().map(BaseDictEntity::getId).toList());
            Map<String,ItemStyle> styles = new LinkedHashMap<>();
            rows.stream().filter(r -> r.getDictType() == 5006 && parentIds.contains(r.getParentId())).forEach(r ->
                    styles.putIfAbsent(r.getDictName(), new ItemStyle(r.getDictName(), r.getId(), r.getParentId(), scope(r), r.getRevision())));
            CategoryKey key = entry.getKey();
            categories.add(new Category(Base64.getUrlEncoder().withoutPadding().encodeToString((key.kind + ":" + key.name).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    key.name, key.kind, parents.get(0).getId(), parents.stream().map(r -> new Source(r.getId(), scope(r), r.getRevision())).toList(), List.copyOf(styles.values())));
        }
        return new Options(context.ownerUserId(), context.familyGroupId(), context.personalScopeVersion(), context.familyScopeVersion(),
                context.contextToken(), categories, options(rows, 5003), options(rows, 5004), options(rows, 5005));
    }
    private List<Option> options(List<BaseDictEntity> rows, int type) {
        Map<String,Option> result = new LinkedHashMap<>(); rows.stream().filter(r -> r.getDictType() == type)
                .forEach(r -> result.putIfAbsent(r.getDictName(), new Option(r.getId(), r.getDictName(), scope(r), r.getRevision()))); return List.copyOf(result.values());
    }
    private String scope(BaseDictEntity row) { return row.getScopeType() == 1 ? "FAMILY" : "PERSONAL"; }
}

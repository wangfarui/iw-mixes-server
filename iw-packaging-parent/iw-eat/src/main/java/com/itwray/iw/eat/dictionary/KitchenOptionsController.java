package com.itwray.iw.eat.dictionary;

import com.itwray.iw.eat.dao.EatFridgeFoodDao;
import com.itwray.iw.eat.model.entity.EatFridgeFoodEntity;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.kitchen.KitchenDictionaryAccess;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import com.itwray.iw.web.utils.UserUtils;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/fridge/food")
public class KitchenOptionsController {
    public record Option(Integer sourceId, String name, String scope) { }
    public record Options(Integer ownerUserId, Integer familyGroupId, String personalScopeVersion,
                          String familyScopeVersion, String contextToken,
                          List<Option> categories, List<Option> sections) { }
    public record Filters(List<String> categories, List<String> sections) { }

    private final KitchenDictionaryAccess dictionaries;
    private final EatFridgeFoodDao foods;

    public KitchenOptionsController(KitchenDictionaryAccess dictionaries, EatFridgeFoodDao foods) {
        this.dictionaries = dictionaries;
        this.foods = foods;
    }

    @GetMapping("/options")
    public Options options(@RequestParam(required = false) Integer foodId) {
        Integer owner = UserUtils.getUserId();
        if (foodId != null) {
            EatFridgeFoodEntity food = foods.queryById(foodId);
            if (food == null) throw new BusinessException("食材不存在");
            if (!Objects.equals(food.getUserId(), owner))
                throw new BusinessException("无权使用该食材所属人的私人选项");
            owner = food.getUserId();
        }
        return options(dictionaries.context(owner));
    }

    @GetMapping("/filters")
    public Filters filters() {
        Integer owner = UserUtils.getUserId();
        List<EatFridgeFoodEntity> rows = foods.lambdaQuery().eq(EatFridgeFoodEntity::getUserId, owner).list();
        Map<String, String> categories = new LinkedHashMap<>();
        Map<String, String> sections = new LinkedHashMap<>();
        for (EatFridgeFoodEntity row : rows) {
            putName(categories, row.getCategoryNameSnapshot());
            putName(sections, row.getSectionNameSnapshot());
        }
        return new Filters(List.copyOf(categories.values()), List.copyOf(sections.values()));
    }

    private Options options(KitchenDictionaryAccess.Context context) {
        List<BaseDictEntity> rows = context.items().stream()
                .sorted(Comparator.comparing(BaseDictEntity::getScopeType)
                        .thenComparing(BaseDictEntity::getSort, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(BaseDictEntity::getId))
                .toList();
        return new Options(context.ownerUserId(), context.familyGroupId(), context.personalScopeVersion(),
                context.familyScopeVersion(), context.contextToken(),
                merge(rows, 3010), merge(rows, 3011));
    }

    private List<Option> merge(List<BaseDictEntity> rows, int type) {
        Map<String, Option> result = new LinkedHashMap<>();
        rows.stream().filter(r -> Objects.equals(r.getDictType(), type))
                .forEach(r -> result.putIfAbsent(r.getDictName(),
                        new Option(r.getId(), r.getDictName(), r.getScopeType() != null && r.getScopeType() == 1 ? "FAMILY" : "PERSONAL")));
        return List.copyOf(result.values());
    }

    private void putName(Map<String, String> values, String name) {
        if (name == null || name.isEmpty()) return;
        values.putIfAbsent(name, name);
    }
}

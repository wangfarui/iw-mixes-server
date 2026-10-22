package com.itwray.iw.wardrobe.dictionary;

import com.itwray.iw.web.model.entity.BaseDictEntity;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WardrobeRecognitionServiceTest {
    private BaseDictEntity row(int id, int type, String name, int scope, int parent, Integer kind) {
        BaseDictEntity value = new BaseDictEntity(); value.setId(id); value.setDictType(type); value.setDictName(name);
        value.setScopeType(scope); value.setParentId(parent); value.setWardrobeMatchKind(kind); value.setSort(1); return value;
    }
    @Test void sameNameTagsPreferPersonalAndKeepDistinctTypes() {
        var rows = WardrobeRecognitionService.canonicalSources(List.of(row(1,5004,"通勤",1,0,null), row(2,5004,"通勤",0,0,null), row(3,5005,"通勤",1,0,null)));
        assertEquals(List.of(2,3),List.copyOf(rows.keySet()));
    }
    @Test void preferredFamilyChildKeepsItsRealParentAfterCategoryMerge() {
        var rows = WardrobeRecognitionService.canonicalSources(List.of(row(1,5002,"外套",0,0,1),row(2,5002,"外套",1,0,1),row(3,5006,"风衣",1,2,null)));
        assertTrue(rows.containsKey(1)); assertTrue(rows.containsKey(2)); assertEquals(2,rows.get(3).getParentId());
    }
    @Test void styleDedupeUsesParentNameAndKindAndPrefersPersonal() {
        var rows = WardrobeRecognitionService.canonicalSources(List.of(row(1,5002,"自定义",0,0,1),row(2,5002,"自定义",1,0,1),row(3,5002,"自定义",1,0,2),
                row(4,5006,"休闲",1,2,null),row(5,5006,"休闲",0,1,null),row(6,5006,"休闲",1,3,null)));
        assertFalse(rows.containsKey(4)); assertTrue(rows.containsKey(5)); assertTrue(rows.containsKey(6));
    }
}

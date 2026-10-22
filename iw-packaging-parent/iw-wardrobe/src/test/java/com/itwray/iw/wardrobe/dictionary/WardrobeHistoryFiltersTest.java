package com.itwray.iw.wardrobe.dictionary;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WardrobeHistoryFiltersTest {
    @Test void newContractRejectsLegacyWritesEvenWhenMixedWithSelections() {
        var dto = new com.itwray.iw.wardrobe.model.dto.WardrobeItemAddDto();
        dto.setCategory(1);
        assertThrows(com.itwray.iw.web.exception.BusinessException.class, () ->
                WardrobeSnapshotService.rejectLegacyValues(dto, "category", "itemStyle", "colorName", "sceneTags", "styleTags"));
        dto.setCategory(null);
        assertDoesNotThrow(() -> WardrobeSnapshotService.rejectLegacyValues(dto, "category", "sceneTags"));
        dto.setSceneTags("");
        assertThrows(com.itwray.iw.web.exception.BusinessException.class, () -> WardrobeSnapshotService.rejectLegacyValues(dto, "sceneTags"));
    }
    @Test void snapshotFiltersRemainParseableByTheDataPermissionInterceptor() throws Exception {
        var query = new LambdaQueryWrapper<WardrobeItemEntity>();
        WardrobeHistoryFilters.scalar(query, WardrobeHistoryFilters.encode(new WardrobeHistoryFilters.Key("CATEGORY","外套",null,1)),"CATEGORY");
        WardrobeHistoryFilters.scalar(query, WardrobeHistoryFilters.encode(new WardrobeHistoryFilters.Key("ITEM_STYLE","风衣","外套",1)),"ITEM_STYLE");
        WardrobeHistoryFilters.scalar(query, WardrobeHistoryFilters.encode(new WardrobeHistoryFilters.Key("COLOR","蓝色",null,null)),"COLOR");
        WardrobeHistoryFilters.tag(query, WardrobeHistoryFilters.encode(new WardrobeHistoryFilters.Key("SCENE","通勤",null,null)),"SCENE",WardrobeOptionsController.BusinessType.ITEM);
        String sql = "SELECT id FROM wardrobe_item WHERE deleted=0 AND " + query.getSqlSegment().replaceAll("#\\{[^}]+}", "?");
        assertNotNull(CCJSqlParserUtil.parse(sql));
        assertEquals(9,query.getParamNameValuePairs().size());
    }
}

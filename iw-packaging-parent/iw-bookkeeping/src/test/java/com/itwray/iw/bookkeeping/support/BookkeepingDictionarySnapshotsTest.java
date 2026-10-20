package com.itwray.iw.bookkeeping.support;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.bookkeeping.model.dto.*;
import com.itwray.iw.bookkeeping.model.entity.BookkeepingRecordsEntity;
import com.itwray.iw.bookkeeping.model.enums.RecordCategoryEnum;
import com.itwray.iw.web.bookkeeping.*;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import com.itwray.iw.web.utils.UserUtils;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
class BookkeepingDictionarySnapshotsTest {
    @Test void jsonPresenceRetainsOrExplicitlyClears() throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        var omitted=mapper.readValue("{}",BookkeepingDictionaryChangesDto.class);
        assertFalse(omitted.isCategoryPresent());assertFalse(omitted.isTagsPresent());
        var clear=mapper.readValue("{\"categoryId\":null,\"tagIds\":[]}",BookkeepingDictionaryChangesDto.class);
        assertTrue(clear.isCategoryPresent());assertNull(clear.getCategoryId());assertTrue(clear.isTagsPresent());assertTrue(clear.getTagIds().isEmpty());
    }
    @Test void newFactsUseServerNameAndGlobalLockOrder() {
        var access=mock(BookkeepingDictionaryAccess.class);var service=new BookkeepingDictionarySnapshots(access,mock(JdbcTemplate.class));
        UserUtils.setUserId(7);
        try{
            BaseDictEntity category=new BaseDictEntity();category.setId(20);category.setDictName("餐饮");
            BaseDictEntity tag=new BaseDictEntity();tag.setId(10);tag.setDictName("外卖");
            when(access.lockSelectable(7,4001,List.of(10))).thenReturn(List.of(tag));
            when(access.lockSelectable(7,4002,List.of(20))).thenReturn(List.of(category));
            var dto=new BookkeepingRecordAddDto();dto.setRecordTypeId(20);dto.setRecordTags(List.of(10,10));
            var entity=new BookkeepingRecordsEntity();entity.setRecordCategory(RecordCategoryEnum.CONSUME);
            var tags=service.prepareNew(entity,dto);
            assertEquals("餐饮",entity.getRecordTypeName());assertEquals(1,entity.getDictionarySnapshotStatus());assertEquals(1,tags.size());
            var order=inOrder(access);order.verify(access).lockSelectable(7,4001,List.of(10));order.verify(access).lockSelectable(7,4002,List.of(20));
        }finally{UserUtils.removeUserId();}
    }
    @Test void updateWithoutDictionaryChangesPreservesDeletedSourceFacts() {
        var access=mock(BookkeepingDictionaryAccess.class);var service=spy(new BookkeepingDictionarySnapshots(access,mock(JdbcTemplate.class)));
        var original=new BookkeepingRecordsEntity();original.setId(4);original.setRecordTypeId(8);original.setRecordTypeName("旧餐饮");original.setDictionarySnapshotStatus(1);
        var tag=new DictionaryTagSnapshot(9,4001,"旧标签");doReturn(List.of(tag)).when(service).tags(4);
        var changed=new BookkeepingRecordsEntity();changed.setRecordCategory(RecordCategoryEnum.CONSUME);
        var result=service.prepareUpdate(changed,original,null,new BookkeepingRecordAddDto());
        assertEquals(8,changed.getRecordTypeId());assertEquals("旧餐饮",changed.getRecordTypeName());assertEquals(List.of(tag),result);verifyNoInteractions(access);
    }
    @Test void tagEditKeepsOldNameAndOnlyValidatesNewSources() {
        var access=mock(BookkeepingDictionaryAccess.class);var service=spy(new BookkeepingDictionarySnapshots(access,mock(JdbcTemplate.class)));
        var oldTag=new DictionaryTagSnapshot(9,4001,"已删除旧名称");doReturn(List.of(oldTag)).when(service).tags(4);
        var source=new BaseDictEntity();source.setId(12);source.setDictName("新标签");
        UserUtils.setUserId(7);
        try{
            when(access.lockSelectable(7,4001,List.of(12))).thenReturn(List.of(source));
            var original=new BookkeepingRecordsEntity();original.setId(4);original.setRecordTypeId(8);original.setRecordTypeName("旧分类");original.setDictionarySnapshotStatus(1);
            var changed=new BookkeepingRecordsEntity();changed.setRecordCategory(RecordCategoryEnum.CONSUME);
            var changes=new BookkeepingDictionaryChangesDto();changes.setTagIds(List.of(9,12));
            var result=service.prepareUpdate(changed,original,changes,new BookkeepingRecordAddDto());
            assertEquals("旧分类",changed.getRecordTypeName());assertEquals(oldTag,result.get(0));assertEquals("新标签",result.get(1).getName());
            verify(access).lockSelectable(7,4001,List.of(12));verifyNoMoreInteractions(access);
        }finally{UserUtils.removeUserId();}
    }
    @Test void explicitNullClearsCategoryWithoutConsultingDeletedSource() {
        var access=mock(BookkeepingDictionaryAccess.class);var service=spy(new BookkeepingDictionarySnapshots(access,mock(JdbcTemplate.class)));
        doReturn(List.of()).when(service).tags(4);
        var original=new BookkeepingRecordsEntity();original.setId(4);original.setRecordTypeId(8);original.setRecordTypeName("旧分类");original.setDictionarySnapshotStatus(1);
        var changed=new BookkeepingRecordsEntity();changed.setRecordCategory(RecordCategoryEnum.CONSUME);
        var changes=new BookkeepingDictionaryChangesDto();changes.setCategoryId(null);
        service.prepareUpdate(changed,original,changes,new BookkeepingRecordAddDto());
        assertNull(changed.getRecordTypeId());assertNull(changed.getRecordTypeName());assertEquals(1,changed.getDictionarySnapshotStatus());verifyNoInteractions(access);
    }

    @Test void categoryStateCannotBeConfusedWithLiteralNames() {
        assertDoesNotThrow(()->BookkeepingCategoryFilter.validate(List.of(),"UNCATEGORIZED"));
        assertDoesNotThrow(()->BookkeepingCategoryFilter.validate(null,"PENDING"));
        assertThrows(com.itwray.iw.web.exception.BusinessException.class,()->BookkeepingCategoryFilter.validate(List.of("未分类"),"UNCATEGORIZED"));
        assertThrows(com.itwray.iw.web.exception.BusinessException.class,()->BookkeepingCategoryFilter.validate(null,"ALL"));
    }

}

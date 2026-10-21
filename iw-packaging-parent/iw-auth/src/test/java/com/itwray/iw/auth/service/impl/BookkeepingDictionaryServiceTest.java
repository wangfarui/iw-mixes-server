package com.itwray.iw.auth.service.impl;

import com.itwray.iw.auth.mapper.BookkeepingDictionaryMapper;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import static com.itwray.iw.auth.service.impl.BookkeepingDictionaryService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Isolated contract tests; no Spring context, database or Redis. */
class BookkeepingDictionaryServiceTest {
    private BookkeepingDictionaryMapper mapper;
    private BookkeepingDictionaryService service;
    @BeforeEach void setup() {
        mapper=mock(BookkeepingDictionaryMapper.class); service=new BookkeepingDictionaryService(mapper);
        when(mapper.currentGroup(7)).thenReturn(3); when(mapper.lockUser(7)).thenReturn(3);
        when(mapper.lockGroup(3)).thenReturn(3); when(mapper.lockRole(3,7)).thenReturn(1);
        when(mapper.lockState(3)).thenReturn(3);
        when(mapper.reader(7)).thenReturn(new BookkeepingDictionaryMapper.Reader(7,3,3,1,3));
        when(mapper.listScope(anyInt(),anyInt(),anyInt(),anyInt())).thenReturn(List.of());
    }
    @Test void enabledPersonalNameWinsButDisabledPersonalDoesNotHideFamily() {
        BaseDictEntity personal=row(10,"餐饮",0,7,0), family=row(20,"餐饮",1,0,3);
        when(mapper.listScope(0,0,7,4002)).thenReturn(List.of(personal));
        when(mapper.listScope(1,3,0,4002)).thenReturn(List.of(family));
        assertEquals(List.of(10),service.options(7,4002).stream().map(BaseDictEntity::getId).toList());
        personal.setDictStatus(0);
        assertEquals(List.of(20),service.options(7,4002).stream().map(BaseDictEntity::getId).toList());
        verify(mapper,never()).lockUser(anyInt());
        verify(mapper,never()).lockGroup(anyInt());
        verify(mapper,never()).lockRole(anyInt(),anyInt());
        verify(mapper,never()).insertState(anyInt());
    }
    @Test void renameRetiresWithoutMutatingHistoricalNameAndCreatesNewVersion() {
        BaseDictEntity old=row(10,"餐饮",0,7,0);
        when(mapper.lockOwned(List.of(10),7,3)).thenReturn(List.of(old));
        when(mapper.listScope(0,0,7,4002)).thenReturn(List.of(old));
        when(mapper.retire(old)).thenReturn(1);
        doAnswer(call->{ ((BaseDictEntity)call.getArgument(0)).setId(11); return null; }).when(mapper).insert(any());
        Rename result=service.rename(7,10,"\u00a0用餐\u3000",0);
        assertEquals("餐饮",old.getDictName()); assertEquals(10,result.oldId()); assertEquals(11,result.item().id());
        ArgumentCaptor<BaseDictEntity> created=ArgumentCaptor.forClass(BaseDictEntity.class);
        verify(mapper).insert(created.capture()); assertEquals(10,created.getValue().getPreviousId());
        assertEquals("用餐",created.getValue().getDictName()); assertEquals(0,created.getValue().getRevision());
    }
    @Test void staleRenameCannotCreateBranchAndUnchangedNameDoesNotCreateVersion() {
        BaseDictEntity old=row(10,"餐饮",0,7,0);
        when(mapper.lockOwned(List.of(10),7,3)).thenReturn(List.of(old));
        assertThrows(BusinessException.class,()->service.rename(7,10,"用餐",9));
        assertEquals(10,service.rename(7,10," 餐饮 ",0).item().id());
        verify(mapper,never()).insert(any()); verify(mapper,never()).retire(any());
    }
    @Test void memberCannotWriteFamilyAndDisabledNameStillConflicts() {
        when(mapper.lockRole(3,7)).thenReturn(3);
        assertThrows(BusinessException.class,()->service.create(7,Type.CATEGORY,Scope.FAMILY,"餐饮"));
        BaseDictEntity disabled=row(10,"餐饮",0,7,0); disabled.setDictStatus(0);
        when(mapper.listScope(0,0,7,4002)).thenReturn(List.of(disabled));
        assertThrows(BusinessException.class,()->service.create(7,Type.CATEGORY,Scope.PERSONAL,"餐饮"));
        verify(mapper,never()).insert(any());
    }
    @Test void successorFollowsSameOwnerChainAndNeverMapsAnUnrelatedName() {
        BaseDictEntity old=row(10,"餐饮",1,0,3), next=row(11,"用餐",1,0,3); old.setDeleted(true);
        when(mapper.lockOwned(List.of(10),7,3)).thenReturn(List.of(old));
        when(mapper.successor(10,1,3,0,4002)).thenReturn(next);
        assertEquals(11,service.resolveSuccessor(7,4002,10).getId());
        next.setDictStatus(0);
        assertThrows(BusinessException.class,()->service.resolveSuccessor(7,4002,10));
        verify(mapper,never()).listScope(anyInt(),anyInt(),anyInt(),eq(4002));
    }
    @Test void referenceValidationSortsIdsAndRejectsWrongTypeOrLostMembership() {
        BaseDictEntity category=row(10,"餐饮",0,7,0);
        when(mapper.lockOwned(List.of(10,20),7,3)).thenReturn(List.of(category));
        assertThrows(BusinessException.class,()->service.lockSelectable(7,4002,List.of(20,10)));
        verify(mapper).lockOwned(List.of(10,20),7,3);
        when(mapper.lockRole(3,7)).thenReturn(null);
        assertThrows(BusinessException.class,()->service.lockSelectable(7,4002,List.of(10)));
        when(mapper.reader(7)).thenReturn(new BookkeepingDictionaryMapper.Reader(7,3,3,null,3));
        assertThrows(BusinessException.class,()->service.options(7,4002));
    }
    @Test void batchResolvesSharedReferencesWithOneOwnershipReadAndNoWriteLocks() {
        BaseDictEntity old=row(10,"旧餐饮",1,0,3), next=row(11,"用餐",1,0,3);
        old.setDeleted(true); next.setPreviousId(10);
        BaseDictEntity tag=row(20,"外卖",0,7,0); tag.setDictType(4001);
        when(mapper.readOwned(List.of(10,20),7,3)).thenReturn(List.of(old,tag));
        when(mapper.readSuccessors(List.of(10),7,3)).thenReturn(List.of(next));
        Map<Integer,BaseDictEntity> result=service.resolveSuccessors(7,List.of(20,10,20,10));
        assertEquals(11,result.get(10).getId()); assertEquals(20,result.get(20).getId());
        verify(mapper).reader(7);
        verify(mapper).readOwned(List.of(10,20),7,3);
        verify(mapper).readSuccessors(List.of(10),7,3);
        verify(mapper,never()).lockOwned(anyList(),anyInt(),anyInt());
        verify(mapper,never()).lockUser(anyInt());
        verify(mapper,never()).lockGroup(anyInt());
        verify(mapper,never()).successor(anyInt(),anyInt(),anyInt(),anyInt(),anyInt());
    }
    @Test void batchOmitsDisabledDeletedMissingAndWrongOwnerSuccessors() {
        BaseDictEntity disabled=row(10,"停用",0,7,0); disabled.setDictStatus(0);
        BaseDictEntity deleted=row(20,"已删除",0,7,0); deleted.setDeleted(true);
        BaseDictEntity old=row(30,"旧分类",0,7,0); old.setDeleted(true);
        BaseDictEntity foreign=row(31,"别人的新分类",0,8,0); foreign.setPreviousId(30);
        when(mapper.readOwned(List.of(10,20,30,99),7,3)).thenReturn(List.of(disabled,deleted,old));
        when(mapper.readSuccessors(List.of(20,30),7,3)).thenReturn(List.of(foreign));
        assertTrue(service.resolveSuccessors(7,List.of(10,20,30,99)).isEmpty());
    }
    @Test void batchFollowsMultipleRenamesButRejectsCyclesAndChangedTypes() {
        BaseDictEntity a=row(10,"A",0,7,0), b=row(11,"B",0,7,0), c=row(12,"C",0,7,0);
        a.setDeleted(true); b.setDeleted(true); b.setPreviousId(10); c.setPreviousId(11);
        when(mapper.readOwned(List.of(10),7,3)).thenReturn(List.of(a));
        when(mapper.readSuccessors(List.of(10),7,3)).thenReturn(List.of(b));
        when(mapper.readSuccessors(List.of(11),7,3)).thenReturn(List.of(c));
        assertEquals(12,service.resolveSuccessors(7,List.of(10)).get(10).getId());
        c.setDictType(4001);
        assertTrue(service.resolveSuccessors(7,List.of(10)).isEmpty());
        a.setPreviousId(11);
        when(mapper.readSuccessors(List.of(11),7,3)).thenReturn(List.of(a));
        assertTrue(service.resolveSuccessors(7,List.of(10)).isEmpty());
    }
    @Test void readsRejectLostFamilyMembershipAndMissingInitializationWithoutWriting() {
        when(mapper.reader(7)).thenReturn(new BookkeepingDictionaryMapper.Reader(7,3,null,null,null));
        assertThrows(BusinessException.class,()->service.resolveSuccessors(7,List.of(10)));
        when(mapper.reader(7)).thenReturn(new BookkeepingDictionaryMapper.Reader(7,3,3,1,null));
        assertThrows(BusinessException.class,()->service.options(7,4002));
        verify(mapper,never()).readOwned(anyList(),anyInt(),anyInt());
        verify(mapper,never()).insertState(anyInt());
    }
    @Test void orderRejectsMissingIdsAndScopeVersionChangesAfterRevisionUpdate() {
        BaseDictEntity a=row(10,"餐饮",0,7,0), b=row(11,"日用",0,7,0);
        when(mapper.listScope(0,0,7,4002)).thenReturn(List.of(a,b));
        String old=version(List.of(a,b));
        assertThrows(BusinessException.class,()->service.order(7,Type.CATEGORY,Scope.PERSONAL,List.of(10),old));
        a.setRevision(1);
        assertNotEquals(old,version(List.of(a,b)));
        assertThrows(BusinessException.class,()->service.order(7,Type.CATEGORY,Scope.PERSONAL,List.of(11,10),old));
        verify(mapper,never()).updateCurrent(any());
    }
    @Test void initializedEmptyFamilyIsNeverRepopulated() {
        service.initializeFamily(3);
        verify(mapper,never()).insertState(anyInt()); verify(mapper,never()).insert(any());
    }
    private static BaseDictEntity row(int id,String name,int scope,int user,int family) {
        BaseDictEntity row=new BaseDictEntity(); row.setId(id); row.setDictName(name); row.setDictType(4002);
        row.setScopeType(scope); row.setUserId(user); row.setFamilyGroupId(family); row.setDictStatus(1);
        row.setDeleted(false); row.setSort(id); row.setRevision(0); return row;
    }
}

package com.itwray.iw.auth.service.impl;

import com.itwray.iw.auth.mapper.BookkeepingDictionaryMapper;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
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
        when(mapper.listScope(anyInt(),anyInt(),anyInt(),anyInt())).thenReturn(List.of());
    }
    @Test void enabledPersonalNameWinsButDisabledPersonalDoesNotHideFamily() {
        BaseDictEntity personal=row(10,"餐饮",0,7,0), family=row(20,"餐饮",1,0,3);
        when(mapper.listScope(0,0,7,4002)).thenReturn(List.of(personal));
        when(mapper.listScope(1,3,0,4002)).thenReturn(List.of(family));
        assertEquals(List.of(10),service.options(7,4002).stream().map(BaseDictEntity::getId).toList());
        personal.setDictStatus(0);
        assertEquals(List.of(20),service.options(7,4002).stream().map(BaseDictEntity::getId).toList());
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
        assertThrows(BusinessException.class,()->service.options(7,4002));
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

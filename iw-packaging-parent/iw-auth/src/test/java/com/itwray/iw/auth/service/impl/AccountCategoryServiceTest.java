package com.itwray.iw.auth.service.impl;

import com.itwray.iw.auth.mapper.AccountCategoryMapper;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountCategoryServiceTest {
    private AccountCategoryMapper mapper;
    private AccountCategoryService service;
    private BaseDictEntity row;
    @BeforeEach void setup() {
        mapper=mock(AccountCategoryMapper.class); service=new AccountCategoryService(mapper);
        row=new BaseDictEntity(); row.setId(10); row.setUserId(7); row.setDictCode(3);
        row.setDictName("工作"); row.setDictStatus(1); row.setRevision(0); row.setSort(1);
        when(mapper.lockUser(7)).thenReturn(7);
        when(mapper.list(7)).thenReturn(List.of(row)); when(mapper.references(7)).thenReturn(List.of());
        when(mapper.update(any())).thenReturn(1);
    }
    private String version() { return service.items(7).scopeVersion(); }
    @Test void renamePreservesIdentityAndCode() {
        service.rename(7,10," 办公 ",0,version());
        assertEquals("办公",row.getDictName()); assertEquals(10,row.getId()); assertEquals(3,row.getDictCode());
        verify(mapper).update(row); verify(mapper,never()).insert(any());
    }
    @Test void referencedCategoryCannotBeDeleted() {
        when(mapper.countReferences(7,3)).thenReturn(1L);
        assertThrows(BusinessException.class,()->service.delete(7,10,0,version()));
        verify(mapper,never()).delete(any());
    }
    @Test void staleVersionCannotMutate() {
        assertThrows(BusinessException.class,()->service.status(7,10,false,0,"old"));
        verify(mapper,never()).update(any());
    }
    @Test void foreignRowCannotBeMutated() {
        when(mapper.lockUser(8)).thenReturn(8); when(mapper.list(8)).thenReturn(List.of()); when(mapper.references(8)).thenReturn(List.of());
        String expected=service.items(8).scopeVersion();
        assertThrows(BusinessException.class,()->service.rename(8,10,"越权",0,expected));
        verify(mapper,never()).update(any());
    }
    @Test void disabledCategoryMayBeKeptButNotNewlySelected() {
        row.setDictStatus(0);
        assertDoesNotThrow(()->service.validateSelection(7,3,3));
        assertThrows(BusinessException.class,()->service.validateSelection(7,3,null));
        assertDoesNotThrow(()->service.validateSelection(7,null,3));
    }
    @Test void duplicateNamesIncludeDisabledItems() {
        row.setDictStatus(0);
        assertThrows(BusinessException.class,()->service.create(7,"工作",version()));
        verify(mapper,never()).insert(any());
    }
    @Test void orderingRequiresExactCompleteSet() {
        assertThrows(BusinessException.class,()->service.order(7,List.of(10,10),version()));
        assertThrows(BusinessException.class,()->service.order(7,List.of(),version()));
        verify(mapper,never()).update(any());
    }
    @Test void namesUseUnicodeCodePointsAndTrimUnicodeSpaces() {
        assertEquals("中文",AccountCategoryService.normalizeName("\u3000中文\u00a0"));
        assertEquals(32,AccountCategoryService.normalizeName("😀".repeat(32)).codePointCount(0,64));
        assertThrows(BusinessException.class,()->AccountCategoryService.normalizeName("😀".repeat(33)));
    }
}

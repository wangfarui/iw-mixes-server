package com.itwray.iw.auth.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.auth.mapper.SystemOptionsMapper;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SystemOptionsServiceTest {
    SystemOptionsMapper mapper;
    SystemOptionsService service;
    List<BaseDictEntity> rows;
    @BeforeEach void setup() {
        mapper=mock(SystemOptionsMapper.class);
        PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service=spy(new SystemOptionsService(mapper,new ObjectMapper().findAndRegisterModules(),tx));
        doReturn(true).when(service).refreshCaches(anyList());
        when(mapper.role(7)).thenReturn(10); when(mapper.lockRole(7)).thenReturn(10);
        when(mapper.users()).thenReturn(List.of(7,8)); rows=new ArrayList<>();
        when(mapper.rows(anyInt())).thenAnswer(i -> rows.stream().filter(r -> Objects.equals(r.getDictType(),i.getArgument(0))).toList());
        doAnswer(i -> { BaseDictEntity r=i.getArgument(0); r.setId(rows.size()+1); r.setRevision(0); r.setParentId(0); r.setScopeType(0); r.setFamilyGroupId(0); r.setDictStatus(1); rows.add(r); return null; }).when(mapper).insert(any());
        when(mapper.update(any())).thenAnswer(i -> { BaseDictEntity r=i.getArgument(0); rows.removeIf(old -> old.getId().equals(r.getId())); r.setRevision(r.getRevision()+1); rows.add(r); rows.sort(Comparator.comparing(BaseDictEntity::getId)); return 1; });
    }
    SystemOptionsService.Preview preview() { return service.preview(7,"kitchen",3003); }
    @Test void previewNeverWritesAndIncludesTemplateAndAllUsers() {
        var p=preview(); assertEquals(9,p.addCount()); assertEquals(2,p.userCount());
        verify(mapper,never()).insert(any()); verify(mapper,never()).update(any());
    }
    @Test void syncIsScopedAndIdempotentAndPreservesIdentityOnCorrection() {
        var initial=preview(); service.sync(7,"kitchen",3003,initial.scopeVersion());
        assertEquals(9,rows.size()); assertTrue(rows.stream().allMatch(r -> r.getDictType()==3003));
        var settled=preview(); assertEquals(0,settled.addCount()+settled.correctCount());
        clearInvocations(mapper); service.sync(7,"kitchen",3003,settled.scopeVersion());
        verify(mapper,never()).insert(any()); verify(mapper,never()).update(any());
        BaseDictEntity old=rows.get(0); int id=old.getId(),code=old.getDictCode(); old.setDictName("漂移"); old.setDictStatus(0);
        var changed=preview(); assertEquals(1,changed.correctCount()); service.sync(7,"kitchen",3003,changed.scopeVersion());
        assertTrue(rows.stream().anyMatch(r -> r.getId()==id&&r.getDictCode()==code&&r.getDictStatus()==1&&!r.getDictName().equals("漂移")));
    }
    @Test void staleOrCrossTypeVersionCannotWrite() {
        String version=preview().scopeVersion(); when(mapper.users()).thenReturn(List.of(7,8,9));
        assertThrows(BusinessException.class,()->service.sync(7,"kitchen",3003,version));
        assertThrows(BusinessException.class,()->service.sync(7,"kitchen",3004,version));
        verify(mapper,never()).insert(any());
    }
    @Test void ordinaryUnknownAndRevokedRolesFailClosed() {
        for(Integer role:Arrays.asList(1,99,null)) {
            when(mapper.role(7)).thenReturn(role); assertFalse(service.capabilities(7).manageSystemOptions());
            assertThrows(BusinessException.class,this::preview);
        }
        when(mapper.role(7)).thenReturn(10); String version=preview().scopeVersion(); when(mapper.lockRole(7)).thenReturn(1);
        assertThrows(BusinessException.class,()->service.sync(7,"kitchen",3003,version)); verify(mapper,never()).insert(any());
    }
    @Test void duplicatesUnknownCodesAndBadOwnershipBlockAllWrites() {
        service.sync(7,"kitchen",3003,preview().scopeVersion());
        BaseDictEntity duplicate=new BaseDictEntity(); org.springframework.beans.BeanUtils.copyProperties(rows.get(0),duplicate); duplicate.setId(99); rows.add(duplicate);
        var p=preview(); assertFalse(p.issues().isEmpty()); clearInvocations(mapper);
        assertThrows(BusinessException.class,()->service.sync(7,"kitchen",3003,p.scopeVersion())); verify(mapper,never()).insert(any()); verify(mapper,never()).update(any());
        rows.remove(duplicate); rows.get(0).setDictCode(99); assertTrue(preview().issues().stream().anyMatch(s -> s.contains("未知编码")));
        rows.get(0).setFamilyGroupId(4); assertTrue(preview().issues().stream().anyMatch(s -> s.contains("归属或结构")));
    }
    @Test void superAdminRepairRequiresPrivilegeAndPreviewAndCoversOnlySixTypes() {
        assertThrows(BusinessException.class,()->service.preview(7,"all",null));
        when(mapper.role(7)).thenReturn(20); when(mapper.lockRole(7)).thenReturn(20);
        var p=service.preview(7,"all",null); assertEquals(6,p.definitions().size());
        assertEquals(66,p.addCount()); service.sync(7,"all",null,p.scopeVersion()); assertEquals(66,rows.size());
    }
    @Test void wrongDomainOrMissingTypeAndModuleMarkersCannotSync() {
        assertThrows(BusinessException.class,()->service.preview(7,"kitchen",4003));
        assertThrows(BusinessException.class,()->service.preview(7,"kitchen",null));
        assertThrows(BusinessException.class,()->service.preview(7,"kitchen",3001));
        assertTrue(SystemOptionDefinitions.retired(1001)); assertFalse(SystemOptionDefinitions.supports(1001));
    }
    @Test void cacheFailureAfterCommitReturnsCommittedResultAndRefreshDoesNotWrite() {
        doReturn(false).when(service).refreshCaches(anyList()); var result=service.sync(7,"kitchen",3003,preview().scopeVersion());
        assertEquals(9,result.changedCount()); assertFalse(result.cacheRefreshed()); clearInvocations(mapper);
        service.refresh(7,"kitchen"); verify(mapper,never()).insert(any()); verify(mapper,never()).update(any());
    }
    @Test void updateConflictAbortsTransaction() {
        service.sync(7,"kitchen",3003,preview().scopeVersion()); rows.get(0).setDictName("漂移");
        doReturn(0).when(mapper).update(any()); assertThrows(BusinessException.class,()->service.sync(7,"kitchen",3003,preview().scopeVersion()));
    }
    @Test void registrationUsesCanonicalDefinitionsAndDoesNotTouchAnotherOwner() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.initialize(8); assertEquals(22,rows.size()); assertTrue(rows.stream().allMatch(r -> r.getUserId()==8));
            clearInvocations(mapper); service.initialize(8); verify(mapper,never()).insert(any()); verify(mapper,never()).update(any());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void fullRepairDoesNotPartiallyWriteWhenOneTypeHasUnknownCode() {
        when(mapper.role(7)).thenReturn(20); when(mapper.lockRole(7)).thenReturn(20);
        service.sync(7,"kitchen",3003,preview().scopeVersion()); rows.get(0).setDictCode(999);
        var all=service.preview(7,"all",null); assertFalse(all.issues().isEmpty()); clearInvocations(mapper);
        assertThrows(BusinessException.class,()->service.sync(7,"all",null,all.scopeVersion()));
        verify(mapper,never()).insert(any()); verify(mapper,never()).update(any());
    }
    @Test void everyFixedTypeAndModuleMarkerRejectsLegacyMaintenanceBeforeWriting() throws Exception {
        var service=new com.itwray.iw.auth.service.impl.BaseDictServiceImpl(mock(com.itwray.iw.web.dao.BaseDictDao.class));
        for(int type:List.of(3002,3003,3004,4003,4005,4006,1001,2001,3001,5001)) {
            var dto=new com.itwray.iw.auth.model.dto.DictAddDto(); dto.setDictType(type);
            assertThrows(BusinessException.class,()->service.add(dto));
        }
        assertThrows(BusinessException.class,service::repairUserVisibleDictData);
    }

    @Test void unauthenticatedControllerRequestUsesAuthorizationError() {
        var controller=new com.itwray.iw.auth.controller.SystemOptionsController(service);
        assertThrows(com.itwray.iw.web.exception.AuthorizedException.class,controller::capabilities);
        verify(mapper,never()).role(anyInt());
    }

}

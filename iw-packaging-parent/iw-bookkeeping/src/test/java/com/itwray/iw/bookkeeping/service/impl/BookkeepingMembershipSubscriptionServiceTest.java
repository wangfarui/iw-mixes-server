package com.itwray.iw.bookkeeping.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.bookkeeping.dao.BookkeepingMembershipSubscriptionDao;
import com.itwray.iw.bookkeeping.mapper.BookkeepingMembershipSubscriptionMapper;
import com.itwray.iw.bookkeeping.model.dto.*;
import com.itwray.iw.bookkeeping.model.entity.BookkeepingMembershipSubscriptionEntity;
import com.itwray.iw.bookkeeping.model.enums.MembershipBillingCycleEnum;
import com.itwray.iw.web.bookkeeping.BookkeepingDictionaryAccess;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import com.itwray.iw.web.utils.UserUtils;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookkeepingMembershipSubscriptionServiceTest {
    BookkeepingMembershipSubscriptionDao dao;
    BookkeepingMembershipSubscriptionMapper mapper;
    BookkeepingDictionaryAccess access;
    BookkeepingMembershipSubscriptionServiceImpl service;
    @BeforeEach void setup() {
        dao=mock(BookkeepingMembershipSubscriptionDao.class);mapper=mock(BookkeepingMembershipSubscriptionMapper.class);
        access=mock(BookkeepingDictionaryAccess.class); service=new BookkeepingMembershipSubscriptionServiceImpl(dao);
        ReflectionTestUtils.setField(service,"dictionaries",access);
        when(dao.getBaseMapper()).thenReturn(mapper); UserUtils.setUserId(7);
    }
    @AfterEach void cleanup() { UserUtils.removeUserId(); }
    BaseDictEntity source() {
        BaseDictEntity source=new BaseDictEntity();source.setId(12);source.setDictCode(0);source.setDictName("新视频");return source;
    }
    BookkeepingMembershipSubscriptionEntity original() {
        var row=new BookkeepingMembershipSubscriptionEntity();row.setId(1);row.setUserId(7);row.setMembershipType(2);
        row.setMembershipTypeId(10);row.setMembershipTypeName("旧视频");row.setMembershipTypeSnapshotStatus(1);return row;
    }
    BookkeepingMembershipSubscriptionUpdateDto update() {
        var dto=new BookkeepingMembershipSubscriptionUpdateDto();dto.setId(1);dto.setMembershipName("改其它字段");
        dto.setStartDate(LocalDate.of(2026,10,21));dto.setBillingCycle(MembershipBillingCycleEnum.values()[0]);return dto;
    }
    @Test void createUsesLockedServerSnapshotAndNeverStoresIdInLegacyCode() {
        var dto=new BookkeepingMembershipSubscriptionAddDto();dto.setMembershipTypeId(12);dto.setMembershipType(999);
        dto.setStartDate(LocalDate.of(2026,10,21));dto.setBillingCycle(MembershipBillingCycleEnum.values()[0]);
        when(access.lockSelectable(7,4004,List.of(12))).thenReturn(List.of(source()));
        service.add(dto);
        var capture=ArgumentCaptor.forClass(BookkeepingMembershipSubscriptionEntity.class);verify(dao).save(capture.capture());
        var row=capture.getValue();assertEquals(12,row.getMembershipTypeId());assertEquals("新视频",row.getMembershipTypeName());
        assertEquals(0,row.getMembershipType());assertEquals(1,row.getMembershipTypeSnapshotStatus());assertEquals(7,row.getUserId());
    }
    @Test void omittedChangeKeepsRetiredSourceSnapshotWithoutConsultingDictionary() {
        when(mapper.lockOwned(1,7)).thenReturn(original());service.update(update());
        var capture=ArgumentCaptor.forClass(BookkeepingMembershipSubscriptionEntity.class);verify(dao).updateById(capture.capture());
        assertEquals(10,capture.getValue().getMembershipTypeId());assertEquals("旧视频",capture.getValue().getMembershipTypeName());
        assertEquals(1,capture.getValue().getMembershipTypeSnapshotStatus());verifyNoInteractions(access);
    }
    @Test void explicitChangeReplacesSnapshotButNullAndUnavailableNeverWrite() {
        var dto=update();var change=new BookkeepingMembershipSubscriptionUpdateDto.TypeChange();change.setSourceId(12);dto.setMembershipTypeChange(change);
        when(mapper.lockOwned(1,7)).thenReturn(original());when(access.lockSelectable(7,4004,List.of(12))).thenReturn(List.of(source()));
        service.update(dto);
        var capture=ArgumentCaptor.forClass(BookkeepingMembershipSubscriptionEntity.class);verify(dao).updateById(capture.capture());
        assertEquals("新视频",capture.getValue().getMembershipTypeName());assertEquals(12,capture.getValue().getMembershipTypeId());
        clearInvocations(dao);change.setSourceId(null);assertThrows(BusinessException.class,()->service.update(dto));
        change.setSourceId(12);when(access.lockSelectable(7,4004,List.of(12))).thenThrow(new BusinessException("无效来源"));
        assertThrows(BusinessException.class,()->service.update(dto));verify(dao,never()).updateById(any(BookkeepingMembershipSubscriptionEntity.class));
    }
    @Test void pendingSnapshotCanKeepOtherFieldsAndLegacyMutationRequiresUpgrade() {
        var old=original();old.setMembershipTypeId(null);old.setMembershipTypeName(null);old.setMembershipTypeSnapshotStatus(0);
        when(mapper.lockOwned(1,7)).thenReturn(old);
        var dto=update();dto.setMembershipType(2);service.update(dto);
        var capture=ArgumentCaptor.forClass(BookkeepingMembershipSubscriptionEntity.class);verify(dao).updateById(capture.capture());
        assertNull(capture.getValue().getMembershipTypeId());assertNull(capture.getValue().getMembershipTypeName());assertEquals(0,capture.getValue().getMembershipTypeSnapshotStatus());
        dto.setMembershipType(3);assertThrows(BusinessException.class,()->service.update(dto));
        assertThrows(BusinessException.class,()->service.add(new BookkeepingMembershipSubscriptionAddDto()));
    }
    @Test void otherUsersCannotUpdateOrDeleteAndExplicitNullIsNotOmission() throws Exception {
        assertThrows(BusinessException.class,()->service.update(update()));assertThrows(BusinessException.class,()->service.delete(1));
        var dto=new ObjectMapper().readValue("{\"membershipTypeChange\":null}",BookkeepingMembershipSubscriptionUpdateDto.class);
        assertTrue(dto.isMembershipTypeChangePresent());assertThrows(BusinessException.class,()->service.update(dto));
        assertFalse(new ObjectMapper().readValue("{}",BookkeepingMembershipSubscriptionUpdateDto.class).isMembershipTypeChangePresent());
        verify(dao,never()).removeById(anyInt());
    }
    @Test void renewalChangesDateOnlyAndDoesNotRevalidateRetiredTypes() {
        com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<BookkeepingMembershipSubscriptionEntity> query=mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class,RETURNS_SELF);
        when(dao.lambdaQuery()).thenReturn(query);
        when(query.eq(any(),any())).thenReturn(query);
        var row=original();row.setEndDate(LocalDate.now());row.setStartDate(LocalDate.now().minusMonths(1));
        row.setBillingCycle(MembershipBillingCycleEnum.values()[0]);
        when(query.list()).thenReturn(List.of(row));
        when(dao.updateById(any(BookkeepingMembershipSubscriptionEntity.class))).thenReturn(true);
        assertEquals(1,service.autoRenew());
        var capture=ArgumentCaptor.forClass(BookkeepingMembershipSubscriptionEntity.class);verify(dao).updateById(capture.capture());
        assertNotNull(capture.getValue().getEndDate());assertNull(capture.getValue().getMembershipTypeId());
        assertNull(capture.getValue().getMembershipTypeName());assertNull(capture.getValue().getMembershipTypeSnapshotStatus());
        verifyNoInteractions(access);
    }
    @Test void filtersKeepLiteralPendingNameDistinctFromPendingStateAndDeduplicateHistory() {
        com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<BookkeepingMembershipSubscriptionEntity> query=mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class,RETURNS_SELF);
        when(dao.lambdaQuery()).thenReturn(query);
        when(query.eq(any(),any())).thenReturn(query);
        var a=original();var b=original();b.setMembershipTypeName("类型待核对");var pending=original();pending.setMembershipTypeSnapshotStatus(0);pending.setMembershipTypeName(null);
        when(query.list()).thenReturn(List.of(a,original(),b,pending));
        var filters=service.filterOptions();assertEquals(3,filters.size());
        assertTrue(filters.stream().anyMatch(f->"类型待核对".equals(f.name()) && f.state()==null));
        assertTrue(filters.stream().anyMatch(f->f.name()==null && "PENDING".equals(f.state())));
        var dto=new BookkeepingMembershipSubscriptionListDto();dto.setMembershipTypeName("视频");dto.setMembershipTypeState("PENDING");
        assertThrows(BusinessException.class,()->service.list(dto));
    }
    @Test void updateValidationDoesNotRequireNewTypeWhileCreateDoes() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();assertTrue(validator.validate(update()).isEmpty());
            var dto=new BookkeepingMembershipSubscriptionAddDto();
            assertTrue(validator.validate(dto).stream().anyMatch(v->v.getPropertyPath().toString().equals("membershipTypeId")));
        }
    }
}

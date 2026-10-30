package com.itwray.iw.points.service;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.itwray.iw.points.dao.PointsTaskPlanDao;
import com.itwray.iw.points.model.dto.plan.*;
import com.itwray.iw.points.model.entity.PointsTaskPlanEntity;
import com.itwray.iw.points.model.enums.TaskPlanCycleEnum;
import com.itwray.iw.points.service.impl.PointsTaskPlanServiceImpl;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.UserUtils;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskPlanCalendarPersistenceTest {
    private PointsTaskPlanDao dao;
    private PointsTaskPlanGenerator generator;
    private PointsTaskPlanServiceImpl service;
    private PointsTaskPlanCalendar calendar;
    private LambdaQueryChainWrapper<PointsTaskPlanEntity> query;
    private LambdaUpdateChainWrapper<PointsTaskPlanEntity> update;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        UserUtils.setUserId(1);
        calendar = new PointsTaskPlanCalendar(Clock.fixed(Instant.parse("2026-02-16T01:00:00Z"), ZoneId.of("Asia/Shanghai")));
        dao = mock(PointsTaskPlanDao.class);
        generator = mock(PointsTaskPlanGenerator.class);
        service = new PointsTaskPlanServiceImpl(dao);
        service.setCalendar(calendar); service.setTaskPlanGenerator(generator);
        query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        when(dao.lambdaQuery()).thenReturn(query);
        when(query.eq(any(), any())).thenReturn(query); when(query.last(anyString())).thenReturn(query);
        update = mock(LambdaUpdateChainWrapper.class, RETURNS_SELF);
        when(dao.lambdaUpdate()).thenReturn(update);
        when(update.eq(any(), any())).thenReturn(update); when(update.set(any(), any())).thenReturn(update);
        when(update.update()).thenReturn(true); when(dao.updateById(any())).thenReturn(true);
        when(dao.save(any(PointsTaskPlanEntity.class))).thenAnswer(call -> {
            PointsTaskPlanEntity p = call.getArgument(0); p.setId(5); p.setUserId(1); return true;
        });
    }
    @AfterEach void clear() { UserUtils.clearContext(); }
    private void rule(PointsTaskPlanScheduleDto dto) {
        dto.setCalendarType("LUNAR"); dto.setPlanCycle(TaskPlanCycleEnum.YEAR);
        dto.setLunarYear(2026); dto.setLunarMonth(1); dto.setLunarDay(1); dto.setLeapMonth(false); dto.setRemindDays(3);
    }

    @Test void saveDuringReminderWindowGeneratesCurrentOccurrenceAndAdvances() {
        var dto = new PointsTaskPlanAddDto(); rule(dto); dto.setTaskName("春节准备");
        assertEquals(5, service.add(dto));
        var generated = ArgumentCaptor.forClass(PointsTaskPlanEntity.class);
        verify(generator).generate(generated.capture(), eq(LocalDate.of(2026, 2, 17)), isNull());
        assertEquals("LUNAR", generated.getValue().getCalendarType());
        assertEquals(1, generated.getValue().getUserId());
        LocalDate next = calendar.next(generated.getValue());
        verify(update).set(any(), eq(next)); verify(update).set(any(), eq(next.minusDays(3)));
    }

    @Test void metadataOnlyEditPreservesAdvancedLunarPeriodAndNeverGeneratesAgain() {
        var input = new PointsTaskPlanUpdateDto(); rule(input); input.setId(5); input.setTaskName("改名称");
        var old = calendar.prepare(input, null); old.setId(5); old.setUserId(1); old.setStatus(1);
        old.setPlanDate(calendar.next(old)); old.setNextPlanDate(old.getPlanDate().minusDays(3));
        when(query.one()).thenReturn(old);
        service.update(input);
        var saved = ArgumentCaptor.forClass(PointsTaskPlanEntity.class);
        verify(dao).updateById(saved.capture());
        assertEquals(old.getPlanDate(), saved.getValue().getPlanDate());
        assertEquals(old.getNextPlanDate(), saved.getValue().getNextPlanDate());
        assertEquals(old.getAnchorDate(), saved.getValue().getAnchorDate());
        verifyNoInteractions(generator);
    }

    @Test void disabledPlanStaysDisabledWhenDateRuleChanges() {
        var input = new PointsTaskPlanUpdateDto(); rule(input); input.setId(5); input.setTaskName("禁用计划");
        var old = calendar.prepare(input, null); old.setStatus(0); old.setRemindDays(0);
        when(query.one()).thenReturn(old);
        service.update(input);
        var saved = ArgumentCaptor.forClass(PointsTaskPlanEntity.class);
        verify(dao).updateById(saved.capture()); assertEquals(0, saved.getValue().getStatus());
        verifyNoInteractions(generator);
    }

    @Test void missingOwnedPreviewPlanIsRejectedWithoutGeneratingOrWriting() {
        var input = new PointsTaskPlanPreviewDto(); rule(input); input.setId(999);
        assertThrows(BusinessException.class, () -> service.preview(input));
        verify(query).eq(any(), eq(1));
        verifyNoInteractions(generator); verify(dao, never()).save(any(PointsTaskPlanEntity.class));
    }

    @Test void failedDateAdvanceThrowsInsteadOfSilentlyKeepingDuplicateDuePeriod() {
        when(update.update()).thenReturn(false);
        var dto = new PointsTaskPlanAddDto(); rule(dto); dto.setTaskName("推进失败");
        assertThrows(BusinessException.class, () -> service.add(dto));
    }
}

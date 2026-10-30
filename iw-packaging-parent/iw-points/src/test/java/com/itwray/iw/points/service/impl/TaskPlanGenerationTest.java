package com.itwray.iw.points.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.itwray.iw.points.dao.PointsTaskBasicsDao;
import com.itwray.iw.points.dao.PointsTaskBookkeepingDao;
import com.itwray.iw.points.dao.PointsTaskPlanDao;
import com.itwray.iw.points.mapper.PointsTaskBasicsMapper;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanGenerateDto;
import com.itwray.iw.points.model.dto.task.PointsTaskRelationAddDto;
import com.itwray.iw.points.model.dto.task.TaskBasicsAddDto;
import com.itwray.iw.points.model.entity.PointsTaskBasicsEntity;
import com.itwray.iw.points.model.entity.PointsTaskBookkeepingEntity;
import com.itwray.iw.points.model.entity.PointsTaskPlanEntity;
import com.itwray.iw.points.model.vo.plan.PointsTaskPlanGenerateVo;
import com.itwray.iw.points.service.PointsTaskBasicsService;
import com.itwray.iw.points.service.PointsTaskPlanGenerator;
import com.itwray.iw.points.service.PointsTaskRelationService;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.UserUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskPlanGenerationTest {
    private PointsTaskPlanGenerator generator;
    private PointsTaskBasicsDao tasks;
    private PointsTaskBasicsMapper mapper;
    private PointsTaskBasicsService taskService;
    private PointsTaskRelationService relations;
    private PointsTaskBookkeepingDao bookkeeping;
    private PointsTaskPlanEntity plan;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        UserUtils.setUserId(1);
        tasks = mock(PointsTaskBasicsDao.class);
        mapper = mock(PointsTaskBasicsMapper.class);
        when(tasks.getBaseMapper()).thenReturn(mapper);
        LambdaUpdateChainWrapper<PointsTaskBasicsEntity> update = mock(LambdaUpdateChainWrapper.class, RETURNS_SELF);
        when(tasks.lambdaUpdate()).thenReturn(update);
        when(update.eq(any(), any())).thenReturn(update);
        when(update.set(any(), any())).thenReturn(update);
        when(update.update()).thenReturn(true);
        taskService = mock(PointsTaskBasicsService.class);
        when(taskService.add(any())).thenReturn(101, 102, 103);
        relations = mock(PointsTaskRelationService.class);
        bookkeeping = mock(PointsTaskBookkeepingDao.class);
        when(bookkeeping.save(any(PointsTaskBookkeepingEntity.class))).thenReturn(true);
        generator = new PointsTaskPlanGenerator(tasks, taskService, relations, bookkeeping);
        plan = new PointsTaskPlanEntity();
        plan.setId(10);
        plan.setUserId(1);
        plan.setTaskName("交电费");
        plan.setTaskRemark("保留凭证");
        plan.setPlanDate(LocalDate.of(2026, 10, 20));
        plan.setNextPlanDate(LocalDate.of(2026, 10, 17));
        plan.setRemindDays(3);
        plan.setDeadlineDays(2);
        plan.setRewardPoints(10);
        plan.setPunishPoints(5);
        plan.setBookkeepingEnabled(true);
        plan.setBookkeepingTemplate("{\"amount\":100,\"recordSource\":\"电费\"}");
        plan.setStatus(0);
    }

    @AfterEach
    void tearDown() { UserUtils.clearContext(); }

    @Test
    @SuppressWarnings("unchecked")
    void manualGenerationOfDisabledPlanUsesTodayAndPreservesScheduleAndSnapshot() {
        PointsTaskPlanDao plans = mock(PointsTaskPlanDao.class);
        LambdaQueryChainWrapper<PointsTaskPlanEntity> query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        when(plans.lambdaQuery()).thenReturn(query);
        when(query.eq(any(), any())).thenReturn(query);
        when(query.last(anyString())).thenReturn(query);
        when(query.one()).thenReturn(plan);
        PointsTaskPlanServiceImpl service = new PointsTaskPlanServiceImpl(plans);
        service.setTaskPlanGenerator(generator);
        PointsTaskPlanGenerateDto request = new PointsTaskPlanGenerateDto();
        request.setId(10);
        request.setRequestId("manual_request_0001");
        PointsTaskPlanGenerateVo result = service.generateNow(request);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        assertEquals(today, result.getPlanDate());
        assertEquals(today.plusDays(2), result.getDeadlineDate());
        assertEquals(LocalDate.of(2026, 10, 20), plan.getPlanDate());
        assertEquals(LocalDate.of(2026, 10, 17), plan.getNextPlanDate());
        assertEquals(0, plan.getStatus());
        verify(plans, never()).lambdaUpdate();
        ArgumentCaptor<TaskBasicsAddDto> task = ArgumentCaptor.forClass(TaskBasicsAddDto.class);
        verify(taskService).add(task.capture());
        assertEquals(0, task.getValue().getTaskGroupId());
        assertEquals("交电费", task.getValue().getTaskName());
        assertEquals("保留凭证", task.getValue().getTaskRemark());
        assertEquals(today.plusDays(2), task.getValue().getDeadlineDate());
        ArgumentCaptor<PointsTaskRelationAddDto> relation = ArgumentCaptor.forClass(PointsTaskRelationAddDto.class);
        verify(relations).add(relation.capture());
        assertEquals(10, relation.getValue().getRewardPoints());
        assertEquals(5, relation.getValue().getPunishPoints());
        ArgumentCaptor<PointsTaskBookkeepingEntity> snapshot = ArgumentCaptor.forClass(PointsTaskBookkeepingEntity.class);
        verify(bookkeeping).save(snapshot.capture());
        assertEquals(101, snapshot.getValue().getTaskId());
        assertEquals(1, snapshot.getValue().getUserId());
        assertEquals(today, snapshot.getValue().getSourcePlanDate());
        assertEquals(plan.getBookkeepingTemplate(), snapshot.getValue().getTemplateSnapshot());
    }

    @Test
    void retryReturnsOriginalDatesEvenAcrossMidnightAndPlanChanges() {
        PointsTaskBasicsEntity previous = new PointsTaskBasicsEntity();
        previous.setId(99);
        previous.setSourcePlanDate(LocalDate.of(2026, 10, 13));
        previous.setDeadlineDate(LocalDate.of(2026, 10, 15));
        when(mapper.queryManualGeneration(10, "manual_request_0001", 1)).thenReturn(previous);
        plan.setDeadlineDays(10);
        PointsTaskPlanGenerateVo result = generator.generate(plan, LocalDate.of(2026, 10, 14), "manual_request_0001");
        assertEquals(99, result.getTaskId());
        assertEquals(LocalDate.of(2026, 10, 13), result.getPlanDate());
        assertEquals(LocalDate.of(2026, 10, 15), result.getDeadlineDate());
        assertFalse(result.isTaskDeleted());
        previous.setDeleted(true);
        assertTrue(generator.generate(plan, LocalDate.of(2026, 10, 14), "manual_request_0001").isTaskDeleted());
        verifyNoInteractions(taskService, relations, bookkeeping);
    }

    @Test
    void newManualRequestsAndScheduledPeriodCreateSeparateTasksOnSameDay() {
        LocalDate today = LocalDate.of(2026, 10, 13);
        assertEquals(101, generator.generate(plan, today, "manual_request_0001").getTaskId());
        assertEquals(102, generator.generate(plan, today, "manual_request_0002").getTaskId());
        assertEquals(103, generator.generate(plan, today, null).getTaskId());
        verify(taskService, times(3)).add(any());
        verify(mapper, times(2)).queryManualGeneration(eq(10), anyString(), eq(1));
    }

    @Test
    void scheduledRetryIncludingDeletedTaskReturnsOriginalWithoutCreatingAnotherTask() {
        PointsTaskBasicsEntity existing = new PointsTaskBasicsEntity();
        existing.setId(88); existing.setSourcePlanDate(plan.getPlanDate());
        existing.setDeadlineDate(plan.getPlanDate().plusDays(2)); existing.setDeleted(true);
        when(mapper.queryScheduledGeneration(10, plan.getPlanDate(), 1)).thenReturn(existing);
        PointsTaskPlanGenerateVo result = generator.generate(plan, plan.getPlanDate(), null);
        assertEquals(88, result.getTaskId()); assertTrue(result.isTaskDeleted());
        verifyNoInteractions(taskService, relations, bookkeeping);
    }

    @Test
    void missingOptionalNumbersUseZeroAndDisabledBookkeepingCreatesNoSnapshot() {
        plan.setDeadlineDays(null);
        plan.setRewardPoints(null);
        plan.setPunishPoints(null);
        plan.setBookkeepingEnabled(false);
        LocalDate today = LocalDate.of(2026, 10, 13);
        assertEquals(today, generator.generate(plan, today, "manual_request_0001").getDeadlineDate());
        verifyNoInteractions(relations, bookkeeping);
    }

    @Test
    void snapshotFailureAbortsGenerationBeforeCreatingPointsRelation() {
        when(bookkeeping.save(any(PointsTaskBookkeepingEntity.class))).thenReturn(false);
        assertThrows(BusinessException.class, () -> generator.generate(plan, LocalDate.of(2026, 10, 13), "manual_request_0001"));
        verifyNoInteractions(relations);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingOrUnownedPlanIsRejectedBeforeGeneration() {
        PointsTaskPlanDao plans = mock(PointsTaskPlanDao.class);
        LambdaQueryChainWrapper<PointsTaskPlanEntity> query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        when(plans.lambdaQuery()).thenReturn(query);
        when(query.eq(any(), any())).thenReturn(query);
        when(query.last(anyString())).thenReturn(query);
        PointsTaskPlanServiceImpl service = new PointsTaskPlanServiceImpl(plans);
        PointsTaskPlanGenerator mockedGenerator = mock(PointsTaskPlanGenerator.class);
        service.setTaskPlanGenerator(mockedGenerator);
        PointsTaskPlanGenerateDto dto = new PointsTaskPlanGenerateDto();
        dto.setId(10);
        dto.setRequestId("manual_request_0001");
        assertThrows(BusinessException.class, () -> service.generateNow(dto));
        verify(query).eq(any(), eq(UserUtils.getUserId()));
        verifyNoInteractions(mockedGenerator);
    }
}

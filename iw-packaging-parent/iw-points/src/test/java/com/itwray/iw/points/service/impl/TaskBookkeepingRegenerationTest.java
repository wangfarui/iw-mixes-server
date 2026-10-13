package com.itwray.iw.points.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.itwray.iw.points.bookkeeping.TaskBookkeepingGateway;
import com.itwray.iw.points.bookkeeping.TaskBookkeepingRecordVo;
import com.itwray.iw.points.bookkeeping.TaskBookkeepingTemplateDto;
import com.itwray.iw.points.dao.*;
import com.itwray.iw.points.mapper.PointsTaskBasicsMapper;
import com.itwray.iw.points.model.dto.task.*;
import com.itwray.iw.points.model.entity.PointsTaskBasicsEntity;
import com.itwray.iw.points.model.entity.PointsTaskBookkeepingEntity;
import com.itwray.iw.points.model.enums.TaskStatusEnum;
import com.itwray.iw.points.model.vo.task.TaskBasicsDetailVo;
import com.itwray.iw.points.model.vo.task.TaskBasicsListVo;
import com.itwray.iw.points.model.vo.task.TaskStatusResultVo;
import com.itwray.iw.points.service.PointsRecordsService;
import com.itwray.iw.web.dao.BaseBusinessFileDao;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.UserUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskBookkeepingRegenerationTest {
    private PointsTaskBasicsServiceImpl service;
    private PointsTaskBasicsDao tasks;
    private PointsTaskBookkeepingDao links;
    private TaskBookkeepingGateway gateway;
    private PointsTaskBasicsEntity task;
    private PointsTaskBookkeepingEntity link;
    private Map<Integer, TaskBookkeepingRecordVo> records;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        UserUtils.setUserId(1);
        tasks = mock(PointsTaskBasicsDao.class);
        links = mock(PointsTaskBookkeepingDao.class);
        gateway = mock(TaskBookkeepingGateway.class);
        PointsTaskGroupDao groups = mock(PointsTaskGroupDao.class);
        PointsTaskRelationDao relations = mock(PointsTaskRelationDao.class);
        service = new PointsTaskBasicsServiceImpl(tasks, groups, relations);
        service.setTaskBookkeepingDao(links);
        service.setBookkeepingGateway(gateway);
        service.setPointsRecordsService(mock(PointsRecordsService.class));
        BaseBusinessFileDao files = mock(BaseBusinessFileDao.class);
        service.setBaseBusinessFileDao(files);

        task = new PointsTaskBasicsEntity();
        task.setId(10);
        task.setUserId(1);
        task.setTaskName("吃饭");
        task.setTaskGroupId(0);
        task.setTaskStatus(TaskStatusEnum.WAIT);
        task.setCompletionRewardPoints(0);
        link = new PointsTaskBookkeepingEntity();
        link.setId(20);
        link.setTaskId(10);
        link.setUserId(1);
        link.setTemplateSnapshot("{\"recordCategory\":1,\"recordSource\":\"午饭\",\"amount\":10,\"recordType\":1,\"schemaVersion\":1}");
        link.setBookkeepingRecordId(100);
        link.setRecordedAt(LocalDateTime.of(2026, 10, 12, 12, 0));
        records = new HashMap<>();
        records.put(100, record(100, "AVAILABLE"));

        LambdaQueryChainWrapper<PointsTaskBasicsEntity> taskQuery = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        when(tasks.lambdaQuery()).thenReturn(taskQuery);
        when(taskQuery.eq(any(), any())).thenReturn(taskQuery);
        when(taskQuery.last(anyString())).thenReturn(taskQuery);
        when(taskQuery.one()).thenAnswer(ignored -> task);
        LambdaUpdateChainWrapper<PointsTaskBasicsEntity> taskUpdate = mock(LambdaUpdateChainWrapper.class, RETURNS_SELF);
        when(tasks.lambdaUpdate()).thenReturn(taskUpdate);
        when(taskUpdate.eq(any(), any())).thenReturn(taskUpdate);
        when(taskUpdate.set(any(), any())).thenReturn(taskUpdate);
        when(taskUpdate.update()).thenReturn(true);
        when(tasks.queryById(10)).thenAnswer(ignored -> task);
        when(links.byTaskId(10)).thenAnswer(ignored -> link);
        when(links.lockByTaskId(10)).thenAnswer(ignored -> link);
        when(links.updateById(any(PointsTaskBookkeepingEntity.class))).thenReturn(true);
        LambdaQueryChainWrapper<PointsTaskBookkeepingEntity> linkQuery = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        when(links.lambdaQuery()).thenReturn(linkQuery);
        when(linkQuery.in(any(), anyCollection())).thenReturn(linkQuery);
        when(linkQuery.list()).thenAnswer(ignored -> List.of(link));
        PointsTaskBasicsMapper mapper = mock(PointsTaskBasicsMapper.class);
        when(tasks.getBaseMapper()).thenReturn(mapper);
        when(mapper.queryList(any())).thenAnswer(ignored -> List.of(task));
        when(groups.queryTaskGroupNameMap(anyList())).thenReturn(Map.of(0, "收集箱"));
        when(gateway.queryRecords(anyCollection())).thenAnswer(ignored -> records);
        when(gateway.create(any(), any())).thenAnswer(ignored -> {
            TaskBookkeepingRecordVo created = record(101, "AVAILABLE");
            records.put(101, created);
            return created;
        });
        when(gateway.validateAndBind(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() { UserUtils.clearContext(); }

    @Test
    void deletedBillReturnsToPendingAndIsRecreatedOnce() {
        records.get(100).setAvailability("DELETED");
        LocalDateTime previousRecordedAt = link.getRecordedAt();
        TaskBasicsListVo listed = service.queryList(new TaskBasicsListDto()).get(0);
        assertEquals("PENDING", listed.getBookkeepingStatus());
        assertEquals("午饭", listed.getBookkeepingRecordSource());
        assertEquals(new BigDecimal("10"), listed.getBookkeepingAmount());
        assertNull(listed.getBookkeepingRecordId());
        TaskBasicsDetailVo detail = service.detail(10);
        assertEquals("PENDING", detail.getBookkeepingStatus());
        assertEquals("DELETED", detail.getBookkeepingRecordAvailability());
        assertNull(detail.getBookkeepingRecordId());

        TaskStatusResultVo completed = status(TaskStatusEnum.DONE);
        assertEquals("CREATED", completed.getBookkeepingAction());
        assertEquals(101, completed.getBookkeepingRecordId());
        assertEquals(101, link.getBookkeepingRecordId());
        assertTrue(link.getRecordedAt().isAfter(previousRecordedAt));
        assertEquals("KEPT", status(TaskStatusEnum.DONE).getBookkeepingAction());
        status(TaskStatusEnum.WAIT);
        assertEquals("KEPT", status(TaskStatusEnum.DONE).getBookkeepingAction());
        verify(gateway, times(1)).create(any(), any());
    }

    @Test
    void existingBillIsKeptWhenTaskIsCompletedAgain() {
        task.setTaskStatus(TaskStatusEnum.DONE);
        status(TaskStatusEnum.WAIT);
        assertEquals("KEPT", status(TaskStatusEnum.DONE).getBookkeepingAction());
        assertEquals(100, link.getBookkeepingRecordId());
        verify(gateway, never()).create(any(), any());
    }

    @Test
    void inaccessibleOrUnknownBillDoesNotAllowReplacement() {
        records.get(100).setAvailability("INACCESSIBLE");
        assertEquals("KEPT", status(TaskStatusEnum.DONE).getBookkeepingAction());
        task.setTaskStatus(TaskStatusEnum.WAIT);
        records.clear();
        assertEquals("KEPT", status(TaskStatusEnum.DONE).getBookkeepingAction());
        verify(gateway, never()).create(any(), any());
    }

    @Test
    void repeatedCompletionOfDoneTaskDoesNotRecreateDeletedBill() {
        task.setTaskStatus(TaskStatusEnum.DONE);
        records.get(100).setAvailability("DELETED");
        TaskStatusResultVo result = status(TaskStatusEnum.DONE);
        assertEquals("NONE", result.getBookkeepingAction());
        assertNull(result.getBookkeepingRecordId());
        verify(gateway, never()).create(any(), any());
    }

    @Test
    void deletedBillAllowsCorrectingPendingTaskTemplate() {
        records.get(100).setAvailability("DELETED");
        TaskBookkeepingTemplateDto template = new TaskBookkeepingTemplateDto();
        template.setAmount(new BigDecimal("20"));
        TaskBasicsUpdateBookkeepingTemplateDto dto = new TaskBasicsUpdateBookkeepingTemplateDto();
        dto.setId(10);
        dto.setBookkeepingTemplate(template);
        service.updateBookkeepingTemplate(dto);
        assertTrue(link.getTemplateSnapshot().contains("20"));
        verify(links).updateById(link);
        records.get(100).setAvailability("AVAILABLE");
        assertThrows(BusinessException.class, () -> service.updateBookkeepingTemplate(dto));
    }

    @Test
    void failedCreationKeepsTaskPendingAndOldAssociation() {
        records.get(100).setAvailability("DELETED");
        when(gateway.create(any(), any())).thenThrow(new BusinessException("余额更新失败"));
        assertThrows(BusinessException.class, () -> status(TaskStatusEnum.DONE));
        assertEquals(TaskStatusEnum.WAIT, task.getTaskStatus());
        assertEquals(100, link.getBookkeepingRecordId());
        verify(links, never()).updateById(any(PointsTaskBookkeepingEntity.class));
    }

    private TaskStatusResultVo status(TaskStatusEnum target) {
        TaskBasicsUpdateStatusDto dto = new TaskBasicsUpdateStatusDto();
        dto.setId(10);
        dto.setTaskStatus(target);
        return service.updateTaskStatus(dto);
    }

    private TaskBookkeepingRecordVo record(int id, String availability) {
        TaskBookkeepingRecordVo record = new TaskBookkeepingRecordVo();
        record.setId(id);
        record.setAvailability(availability);
        record.setAmount(new BigDecimal("10"));
        record.setRecordDate(LocalDate.now());
        return record;
    }
}

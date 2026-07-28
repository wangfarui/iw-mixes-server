package com.itwray.iw.auth.service.impl;

import com.itwray.iw.auth.dao.BaseAiTaskDao;
import com.itwray.iw.auth.model.dto.AiTaskAddDto;
import com.itwray.iw.auth.model.dto.AiTaskActiveUpdateDto;
import com.itwray.iw.auth.model.dto.AiTaskTopUpdateDto;
import com.itwray.iw.auth.model.dto.AiTaskUpdateDto;
import com.itwray.iw.auth.model.entity.BaseAiTaskEntity;
import com.itwray.iw.auth.model.enums.AiTaskStatusEnum;
import com.itwray.iw.auth.model.enums.AiToolTypeEnum;
import com.itwray.iw.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaseAiTaskServiceImplTest {

    @Test
    void duplicateSessionIsReportedAsBusinessConflict() {
        BaseAiTaskDao taskDao = mock(BaseAiTaskDao.class);
        doThrow(new DuplicateKeyException(
                "Duplicate entry '7-1-01900000-1234-7000-8000-000000000001' "
                        + "for key 'base_ai_task.uk_user_tool_session_key'"))
                .when(taskDao)
                .save(any(BaseAiTaskEntity.class));
        BaseAiTaskServiceImpl service = new BaseAiTaskServiceImpl(taskDao);

        AiTaskAddDto dto = new AiTaskAddDto();
        dto.setTitle("评审会话任务");
        dto.setToolType(AiToolTypeEnum.CODEX);
        dto.setSessionKey("01900000-1234-7000-8000-000000000001");
        dto.setTaskStatus(AiTaskStatusEnum.IN_PROGRESS);
        dto.setWorkspacePath("/tmp/iw-ai-workspace");

        BusinessException exception = assertThrows(BusinessException.class, () -> service.add(dto));

        assertEquals("该会话任务已存在，请编辑原记录", exception.getMessage());
    }

    @Test
    void topTaskRecordsTopTime() {
        BaseAiTaskDao taskDao = mock(BaseAiTaskDao.class);
        when(taskDao.queryById(anyInt())).thenReturn(new BaseAiTaskEntity());
        BaseAiTaskServiceImpl service = new BaseAiTaskServiceImpl(taskDao);

        AiTaskTopUpdateDto dto = new AiTaskTopUpdateDto();
        dto.setId(42);
        dto.setIsTop(1);

        service.updateTop(dto);

        verify(taskDao).updateTop(eq(42), eq(1), notNull());
    }

    @Test
    void untopTaskClearsTopTime() {
        BaseAiTaskDao taskDao = mock(BaseAiTaskDao.class);
        when(taskDao.queryById(anyInt())).thenReturn(new BaseAiTaskEntity());
        BaseAiTaskServiceImpl service = new BaseAiTaskServiceImpl(taskDao);

        AiTaskTopUpdateDto dto = new AiTaskTopUpdateDto();
        dto.setId(42);
        dto.setIsTop(0);

        service.updateTop(dto);

        verify(taskDao).updateTop(eq(42), eq(0), isNull());
    }

    @Test
    void updateWithSameStatusKeepsLastActiveTime() {
        BaseAiTaskDao taskDao = mock(BaseAiTaskDao.class);
        BaseAiTaskEntity originalEntity = new BaseAiTaskEntity();
        originalEntity.setTaskStatus(AiTaskStatusEnum.IN_PROGRESS);
        when(taskDao.queryById(42)).thenReturn(originalEntity);
        BaseAiTaskServiceImpl service = new BaseAiTaskServiceImpl(taskDao);
        AiTaskUpdateDto dto = buildUpdateDto(AiTaskStatusEnum.IN_PROGRESS);

        service.update(dto);

        ArgumentCaptor<BaseAiTaskEntity> entityCaptor = ArgumentCaptor.forClass(BaseAiTaskEntity.class);
        verify(taskDao).updateById(entityCaptor.capture());
        assertNull(entityCaptor.getValue().getLastActiveAt());
    }

    @Test
    void updateWithChangedStatusRefreshesLastActiveTime() {
        BaseAiTaskDao taskDao = mock(BaseAiTaskDao.class);
        BaseAiTaskEntity originalEntity = new BaseAiTaskEntity();
        originalEntity.setTaskStatus(AiTaskStatusEnum.IN_PROGRESS);
        when(taskDao.queryById(42)).thenReturn(originalEntity);
        BaseAiTaskServiceImpl service = new BaseAiTaskServiceImpl(taskDao);
        AiTaskUpdateDto dto = buildUpdateDto(AiTaskStatusEnum.COMPLETED);

        service.update(dto);

        ArgumentCaptor<BaseAiTaskEntity> entityCaptor = ArgumentCaptor.forClass(BaseAiTaskEntity.class);
        verify(taskDao).updateById(entityCaptor.capture());
        assertNotNull(entityCaptor.getValue().getLastActiveAt());
    }

    @Test
    void activeUpdateRefreshesOnlyTheRequestedTask() {
        BaseAiTaskDao taskDao = mock(BaseAiTaskDao.class);
        when(taskDao.queryById(42)).thenReturn(new BaseAiTaskEntity());
        BaseAiTaskServiceImpl service = new BaseAiTaskServiceImpl(taskDao);
        AiTaskActiveUpdateDto dto = new AiTaskActiveUpdateDto();
        dto.setId(42);

        service.updateActive(dto);

        verify(taskDao).updateActive(eq(42), notNull());
    }

    private AiTaskUpdateDto buildUpdateDto(AiTaskStatusEnum taskStatus) {
        AiTaskUpdateDto dto = new AiTaskUpdateDto();
        dto.setId(42);
        dto.setTitle("会话任务");
        dto.setToolType(AiToolTypeEnum.CODEX);
        dto.setSessionKey("01900000-1234-7000-8000-000000000001");
        dto.setTaskStatus(taskStatus);
        dto.setWorkspacePath("/tmp/iw-ai-workspace");
        return dto;
    }
}

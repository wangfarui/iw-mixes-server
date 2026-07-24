package com.itwray.iw.auth.service.impl;

import com.itwray.iw.auth.dao.BaseAiTaskDao;
import com.itwray.iw.auth.model.dto.AiTaskAddDto;
import com.itwray.iw.auth.model.entity.BaseAiTaskEntity;
import com.itwray.iw.auth.model.enums.AiTaskStatusEnum;
import com.itwray.iw.auth.model.enums.AiToolTypeEnum;
import com.itwray.iw.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

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
}

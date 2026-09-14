package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.Preview;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.AsyncTaskSnapshot;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.TaskPhase;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ZhaogangReleaseImportControllerTest {

    @Test
    void bindsBrowserMultipartColumnNameFields() throws Exception {
        ZhaogangSessionManager sessions = mock(ZhaogangSessionManager.class);
        ZhaogangReleaseImportService service = mock(ZhaogangReleaseImportService.class);
        ZhaogangReleaseImportAsyncTaskService asyncTasks = mock(ZhaogangReleaseImportAsyncTaskService.class);
        ZhaogangProperties properties = new ZhaogangProperties();
        ZhaogangReleaseImportController controller = new ZhaogangReleaseImportController(sessions, service, asyncTasks, properties);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(sessions.resolve(any(), any())).thenReturn(
                new ZhaogangSession("token", 22L, "user", "", "team", 11L));
        when(service.recognizeAndMatch(any(), eq(9L), any(byte[].class), eq("image/png"),
                eq("应用归属"), eq("服务名称"))).thenReturn(new Preview(List.of()));

        mvc.perform(multipart("/external-service/api/zhaogang/iterations/9/release-import/recognize")
                        .file(new MockMultipartFile("file", "table.png", "image/png", new byte[]{1}))
                        .param("projectColumnName", "应用归属")
                        .param("planColumnName", "服务名称"))
                .andExpect(status().isOk());

        verify(service).recognizeAndMatch(any(), eq(9L), any(byte[].class), eq("image/png"),
                eq("应用归属"), eq("服务名称"));
    }

    @Test
    void exposesAsyncTaskCreatePollAndCancelEndpoints() throws Exception {
        ZhaogangSessionManager sessions = mock(ZhaogangSessionManager.class);
        ZhaogangReleaseImportService service = mock(ZhaogangReleaseImportService.class);
        ZhaogangReleaseImportAsyncTaskService asyncTasks = mock(ZhaogangReleaseImportAsyncTaskService.class);
        ZhaogangProperties properties = new ZhaogangProperties();
        ZhaogangReleaseImportController controller = new ZhaogangReleaseImportController(sessions, service, asyncTasks, properties);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        ZhaogangSession session = new ZhaogangSession("token", 22L, "user", "", "team", 11L);
        when(sessions.resolve(any(), any())).thenReturn(session);
        AsyncTaskSnapshot snapshot = new AsyncTaskSnapshot("task-1", TaskStatus.QUEUED, TaskPhase.QUEUED,
                0, null, "", "识别任务已提交", false);
        when(asyncTasks.create(any(), eq(9L), any(byte[].class), eq("image/png"), eq("应用归属"), eq("服务名称")))
                .thenReturn(snapshot);
        when(asyncTasks.get(eq(11L), eq(22L), eq(9L), eq("task-1"))).thenReturn(snapshot);
        when(asyncTasks.cancel(eq(11L), eq(22L), eq(9L), eq("task-1"))).thenReturn(
                new AsyncTaskSnapshot("task-1", TaskStatus.CANCELLED, TaskPhase.QUEUED, 0, null,
                        "CANCELLED", "识别任务已取消", false));

        mvc.perform(multipart("/external-service/api/zhaogang/iterations/9/release-import/recognize-tasks")
                        .file(new MockMultipartFile("file", "table.png", "image/png", new byte[]{1}))
                        .param("projectColumnName", "应用归属")
                        .param("planColumnName", "服务名称"))
                .andExpect(status().isOk());
        mvc.perform(get("/external-service/api/zhaogang/iterations/9/release-import/recognize-tasks/task-1"))
                .andExpect(status().isOk());
        mvc.perform(delete("/external-service/api/zhaogang/iterations/9/release-import/recognize-tasks/task-1"))
                .andExpect(status().isOk());

        verify(asyncTasks).create(any(), eq(9L), any(byte[].class), eq("image/png"), eq("应用归属"), eq("服务名称"));
        verify(asyncTasks).get(eq(11L), eq(22L), eq(9L), eq("task-1"));
        verify(asyncTasks).cancel(eq(11L), eq(22L), eq(9L), eq("task-1"));
    }
}

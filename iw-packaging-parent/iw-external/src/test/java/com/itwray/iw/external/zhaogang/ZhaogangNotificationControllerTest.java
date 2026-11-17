package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.zhaogang.notification.WorklogNotificationModule;
import com.itwray.iw.external.zhaogang.notification.WorklogNotificationModule.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ZhaogangNotificationControllerTest {
    @Test void testsDraftAddressWithoutChangingLegacySavedAddressResponse() throws Exception {
        var sessions = mock(ZhaogangSessionManager.class);
        var module = mock(WorklogNotificationModule.class);
        when(sessions.resolve(any(), any())).thenReturn(new ZhaogangSession("fixture", 100L, "管理员", "", "team", 10L));
        var status = new Status(false, true, 1, null, null, "oldxxx0001", List.of());
        when(module.test(any(), eq(1L), eq(1L))).thenReturn(status);
        when(module.test(any(), eq(1L), any(TestCommand.class))).thenReturn(new TestResult(status, "ACCEPTED", "企微已受理"));
        var mvc = MockMvcBuilders.standaloneSetup(new ZhaogangNotificationController(sessions, new ZhaogangProperties(), module)).build();
        String path = "/external-service/api/zhaogang/teams/1/worklog-notification/test";
        mvc.perform(post(path).contentType("application/json").content("{\"revision\":1}"))
                .andExpect(jsonPath("$.data.webhookHint").value("oldxxx0001"))
                .andExpect(jsonPath("$.data.revision").value(1));
        String draft = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=new-fixture-1234";
        mvc.perform(post(path).contentType("application/json").content("{\"revision\":1,\"webhook\":\"" + draft + "\"}"))
                .andExpect(jsonPath("$.data.state").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.status.webhookHint").value("oldxxx0001"));
        verify(module).test(any(), eq(1L), eq(new TestCommand(1, draft)));
    }
}

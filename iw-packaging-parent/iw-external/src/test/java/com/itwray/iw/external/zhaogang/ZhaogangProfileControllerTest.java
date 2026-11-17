package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.mapper.ZhaogangWorkbenchProfileMapper;
import com.itwray.iw.external.zhaogang.profile.WorkbenchMobile;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ZhaogangProfileControllerTest {
    @Test void personalEditUsesSessionIdentityAndRejectsInvalidPhones() throws Exception {
        var sessions = mock(ZhaogangSessionManager.class);
        var profiles = mock(ZhaogangWorkbenchProfileMapper.class);
        when(sessions.resolve(any(), any())).thenReturn(new ZhaogangSession("fixture", 200L, "成员", "", "team", 10L));
        when(profiles.findMobile(10L,200L)).thenReturn("13800000002");
        var mvc = MockMvcBuilders.standaloneSetup(new ZhaogangProfileController(sessions,profiles))
                .setControllerAdvice(new ZhaogangExceptionHandler(sessions)).build();
        String url = "/external-service/api/zhaogang/profile/mobile";
        mvc.perform(get(url)).andExpect(jsonPath("$.data.mobile").value("13800000002"))
                .andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(put(url).contentType("application/json").content("{\"mobile\":\" 13800000001 \",\"userId\":999}"))
                .andExpect(jsonPath("$.code").value(200));
        verify(profiles).saveMobile(10L,200L,"13800000001");
        mvc.perform(put(url).contentType("application/json").content("{\"mobile\":\"@all\"}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));
        verify(profiles, times(1)).saveMobile(anyLong(),anyLong(),any());
        mvc.perform(put(url).contentType("application/json").content("{\"mobile\":\"\"}"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.mobile").isEmpty());
        verify(profiles).saveMobile(10L,200L,null);
        assertThat(new WorkbenchMobile.Update("13800000001").toString()).doesNotContain("13800000001");
    }
}

package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.zhaogang.release.ReleaseReceiptModels.Receipt;
import com.itwray.iw.external.zhaogang.release.ReleaseReceiptModule;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.HttpHeaders;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZhaogangReleaseReceiptControllerTest {

    @Test
    void readsAndAcknowledgesUsingCookieSessionIdentityOnly() {
        ZhaogangSessionManager sessionManager = mock(ZhaogangSessionManager.class);
        ReleaseReceiptModule module = mock(ReleaseReceiptModule.class);
        ZhaogangReleaseReceiptController controller = new ZhaogangReleaseReceiptController(sessionManager, module);
        ZhaogangSession session = new ZhaogangSession("synthetic", 20L, "tester", "", "team", 10L);
        when(sessionManager.resolve(any(), any())).thenReturn(session);
        when(module.receipt(new com.itwray.iw.external.zhaogang.release.ReleaseReceiptModels.Context(10L, 20L), "zg-1"))
                .thenReturn(new Receipt(false, null));
        when(module.acknowledge(new com.itwray.iw.external.zhaogang.release.ReleaseReceiptModels.Context(10L, 20L), "zg-1"))
                .thenReturn(new Receipt(true, LocalDateTime.now()));

        MockHttpServletResponse readResponse = new MockHttpServletResponse();
        assertThat(controller.receipt("zg-1", new MockHttpServletRequest(), readResponse).getData().read()).isFalse();
        assertThat(readResponse.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store, max-age=0");
        assertThat(controller.acknowledge("zg-1", new MockHttpServletRequest(), new MockHttpServletResponse()).getData().read()).isTrue();
        verify(module).receipt(new com.itwray.iw.external.zhaogang.release.ReleaseReceiptModels.Context(10L, 20L), "zg-1");
        verify(module).acknowledge(new com.itwray.iw.external.zhaogang.release.ReleaseReceiptModels.Context(10L, 20L), "zg-1");
    }
}

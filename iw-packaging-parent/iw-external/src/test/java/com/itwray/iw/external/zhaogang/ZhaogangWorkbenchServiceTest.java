package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.zhaogang.ZhaogangModels.SessionStatus;
import com.itwray.iw.external.zhaogang.ZhaogangModels.TokenValue;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class ZhaogangWorkbenchServiceTest {

    @Test
    void sessionStatusOnlyExposesMaskedTokenHint() {
        ZhaogangProperties properties = new ZhaogangProperties();
        ZhaogangWorkbenchService service = new ZhaogangWorkbenchService(mock(CodingOpenApiPort.class), properties);
        String token = "abcd1234567890wxyz";

        SessionStatus status = service.status(new ZhaogangSession(token, 183478L, "tester", "", "g-iijw5014"));

        assertThat(status.tokenHint()).isEqualTo("abcd••••••••wxyz");
        assertThat(status.toString()).doesNotContain(token);
    }

    @Test
    void explicitTokenValueReturnsCurrentBoundToken() {
        ZhaogangWorkbenchService service = new ZhaogangWorkbenchService(mock(CodingOpenApiPort.class),
                new ZhaogangProperties());
        String token = "abcd1234567890wxyz";

        TokenValue value = service.tokenValue(new ZhaogangSession(token, 183478L, "tester", "", "g-iijw5014"));

        assertThat(value.token()).isEqualTo(token);
    }

    @Test
    void legacySessionRequiresCredentialMigration() {
        CodingCredentialService credentials = mock(CodingCredentialService.class);
        when(credentials.exists(10L, 183478L)).thenReturn(false);
        ZhaogangWorkbenchService service = new ZhaogangWorkbenchService(mock(CodingOpenApiPort.class),
                new ZhaogangProperties(), credentials);

        SessionStatus status = service.status(new ZhaogangSession("token", 183478L, "tester", "", "g-iijw5014", 10L));

        assertThat(status.tokenRotationRequired()).isTrue();
    }

    @Test
    void repairsCorruptedSessionIdentityFromCoding() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        ZhaogangWorkbenchService service = new ZhaogangWorkbenchService(coding, new ZhaogangProperties());
        ZhaogangSession corrupted = new ZhaogangSession("test-token", 9292850L, "??????(?????????)", "old", "g-iijw5014");
        when(coding.currentUser("test-token"))
                .thenReturn(new CodingUser(9292850L, "步步(王发瑞)", "avatar", "wangfarui", 1L));
        when(coding.team("test-token"))
                .thenReturn(new CodingOpenApiPort.Team(1L, "产业数字中心", "https://g-iijw5014.coding.net"));

        ZhaogangSession repaired = service.repairSession(corrupted);

        assertThat(repaired.userName()).isEqualTo("步步(王发瑞)");
        assertThat(repaired.avatar()).isEqualTo("avatar");
        verify(coding).currentUser("test-token");
    }

    @Test
    void repairingLegacySessionDoesNotCompleteCredentialMigration() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        CodingCredentialService credentials = mock(CodingCredentialService.class);
        ZhaogangWorkbenchService service = new ZhaogangWorkbenchService(coding, new ZhaogangProperties(), credentials);
        ZhaogangSession legacy = new ZhaogangSession("test-token", 9292850L, "??????(?????????)", "old", "g-iijw5014");
        when(coding.currentUser("test-token"))
                .thenReturn(new CodingUser(9292850L, "步步(王发瑞)", "avatar", "wangfarui", 1L));
        when(coding.team("test-token"))
                .thenReturn(new CodingOpenApiPort.Team(1L, "产业数字中心", "https://g-iijw5014.coding.net"));
        when(credentials.exists(1L, 9292850L)).thenReturn(false);

        ZhaogangSession repaired = service.repairSession(legacy);

        assertThat(service.status(repaired).tokenRotationRequired()).isTrue();
        verify(credentials, never()).upsert(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void keepsValidSessionWithoutCallingCoding() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        ZhaogangWorkbenchService service = new ZhaogangWorkbenchService(coding, new ZhaogangProperties());
        ZhaogangSession session = new ZhaogangSession("test-token", 9292850L, "步步(王发瑞)", "avatar",
                "g-iijw5014", 1L);

        assertThat(service.repairSession(session)).isSameAs(session);
        verifyNoInteractions(coding);
    }
}

package com.itwray.iw.external.zhaogang.agent;

import com.itwray.iw.external.mapper.ZhaogangAgentTokenMapper;
import com.itwray.iw.external.zhaogang.ZhaogangSession;
import com.itwray.iw.external.zhaogang.agent.entity.ZhaogangAgentTokenEntity;
import com.itwray.iw.external.zhaogang.agent.ZhaogangAgentModels.TokenCreated;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZhaogangAgentTokenServiceTest {

    private static final ZhaogangSession SESSION = new ZhaogangSession("coding-pat", 20L, "tester", "avatar", "team", 10L);

    @Test
    void createStoresHashAndReturnsFullTokenOnlyOnce() {
        ZhaogangAgentTokenMapper mapper = mock(ZhaogangAgentTokenMapper.class);
        CodingCredentialService credentials = mock(CodingCredentialService.class);
        when(credentials.exists(10L, 20L)).thenReturn(true);
        when(mapper.find(10L, 20L)).thenReturn(null);
        ZhaogangAgentTokenService service = new ZhaogangAgentTokenService(mapper, credentials);

        TokenCreated created = service.create(SESSION);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hint = ArgumentCaptor.forClass(String.class);
        verify(mapper).upsert(eq(10L), eq(20L), eq("tester"), eq("avatar"), hash.capture(), hint.capture());

        assertThat(created.token()).startsWith("zg_ag_");
        assertThat(created.status().configured()).isTrue();
        assertThat(hash.getValue()).isNotEqualTo(created.token());
        assertThat(hash.getValue()).isEqualTo(ZhaogangAgentTokenService.digest(created.token()));
        assertThat(hint.getValue()).isEqualTo(ZhaogangAgentTokenService.mask(created.token()));
    }

    @Test
    void createRequiresExistingCodingCredentialBackup() {
        ZhaogangAgentTokenService service = new ZhaogangAgentTokenService(mock(ZhaogangAgentTokenMapper.class),
                mock(CodingCredentialService.class));

        assertThatThrownBy(() -> service.create(SESSION))
                .isInstanceOf(ZhaogangAgentCodingCredentialException.class)
                .hasMessageContaining("尚未保存 CODING 令牌")
                .hasMessageContaining("重新绑定 CODING 令牌");
    }

    @Test
    void authenticateUsesOnlyTheStoredUserIdentityAndCodingCredential() {
        ZhaogangAgentTokenMapper mapper = mock(ZhaogangAgentTokenMapper.class);
        CodingCredentialService credentials = mock(CodingCredentialService.class);
        String rawToken = "zg_ag_value";
        ZhaogangAgentTokenEntity entity = entity(ZhaogangAgentTokenService.digest(rawToken));
        when(mapper.findByHash(ZhaogangAgentTokenService.digest(rawToken))).thenReturn(entity);
        when(credentials.token(10L, 20L)).thenReturn(Optional.of("coding-pat"));
        ZhaogangAgentTokenService service = new ZhaogangAgentTokenService(mapper, credentials);

        ZhaogangSession session = service.authenticate("Bearer " + rawToken);

        assertThat(session.token()).isEqualTo("coding-pat");
        assertThat(session.teamId()).isEqualTo(10L);
        assertThat(session.userId()).isEqualTo(20L);
        verify(mapper).touch(eq(10L), eq(20L), org.mockito.ArgumentMatchers.any());
        verify(credentials, never()).upsert(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void staleOrMalformedTokenIsRejectedWithoutClearingCookies() {
        ZhaogangAgentTokenService service = new ZhaogangAgentTokenService(mock(ZhaogangAgentTokenMapper.class),
                mock(CodingCredentialService.class));

        assertThatThrownBy(() -> service.authenticate("Bearer zg_ag_stale"))
                .isInstanceOf(ZhaogangAgentTokenException.class)
                .hasMessageContaining("无效或已失效");
        assertThatThrownBy(() -> service.authenticate("token zg_ag_stale"))
                .isInstanceOf(ZhaogangAgentTokenException.class)
                .hasMessageContaining("格式无效");
    }

    @Test
    void validTokenWithMissingCodingCredentialIsNotReportedAsTokenExpiry() {
        ZhaogangAgentTokenMapper mapper = mock(ZhaogangAgentTokenMapper.class);
        CodingCredentialService credentials = mock(CodingCredentialService.class);
        String rawToken = "zg_ag_value";
        when(mapper.findByHash(ZhaogangAgentTokenService.digest(rawToken)))
                .thenReturn(entity(ZhaogangAgentTokenService.digest(rawToken)));
        when(credentials.token(10L, 20L)).thenReturn(Optional.empty());
        ZhaogangAgentTokenService service = new ZhaogangAgentTokenService(mapper, credentials);

        assertThatThrownBy(() -> service.authenticate("Bearer " + rawToken))
                .isInstanceOf(ZhaogangAgentCodingCredentialException.class)
                .hasMessageContaining("重新绑定 CODING 令牌");
        verify(mapper, never()).touch(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingAuthorizationHeaderIsRejectedWithTokenMessage() {
        ZhaogangAgentTokenService service = new ZhaogangAgentTokenService(mock(ZhaogangAgentTokenMapper.class),
                mock(CodingCredentialService.class));

        assertThatThrownBy(() -> service.authenticate(null))
                .isInstanceOf(ZhaogangAgentTokenException.class)
                .hasMessageContaining("缺少工作台 Agent 令牌");
    }

    @Test
    void deleteRemovesOnlyCurrentUserToken() {
        ZhaogangAgentTokenMapper mapper = mock(ZhaogangAgentTokenMapper.class);
        ZhaogangAgentTokenService service = new ZhaogangAgentTokenService(mapper, mock(CodingCredentialService.class));

        service.delete(SESSION);

        verify(mapper).delete(10L, 20L);
    }

    private static ZhaogangAgentTokenEntity entity(String hash) {
        ZhaogangAgentTokenEntity entity = new ZhaogangAgentTokenEntity();
        entity.setCodingTeamId(10L);
        entity.setCodingUserId(20L);
        entity.setUserName("tester");
        entity.setAvatar("avatar");
        entity.setTokenHash(hash);
        return entity;
    }
}

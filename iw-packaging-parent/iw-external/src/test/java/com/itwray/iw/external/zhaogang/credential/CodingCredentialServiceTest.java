package com.itwray.iw.external.zhaogang.credential;

import com.itwray.iw.external.mapper.ZhaogangCodingCredentialMapper;
import com.itwray.iw.external.zhaogang.credential.entity.CodingCredentialEntity;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodingCredentialServiceTest {

    @Test
    void upsertStoresNormalizedPlaintextAndFingerprint() {
        ZhaogangCodingCredentialMapper mapper = mock(ZhaogangCodingCredentialMapper.class);
        CodingCredentialService service = new CodingCredentialService(mapper);

        service.upsert(10L, 20L, "  pat-value  ", "成员", "avatar");

        verify(mapper).upsert(eq(10L), eq(20L), eq("pat-value"), eq(CodingCredentialService.fingerprint("pat-value")),
                eq("成员"), eq("avatar"));
    }

    @Test
    void tokenReadsOnlyTheCompositeTeamAndUserKey() {
        ZhaogangCodingCredentialMapper mapper = mock(ZhaogangCodingCredentialMapper.class);
        CodingCredentialService service = new CodingCredentialService(mapper);
        CodingCredentialEntity entity = new CodingCredentialEntity();
        entity.setTokenPlaintext("pat-value");
        when(mapper.find(10L, 20L)).thenReturn(entity);

        assertThat(service.token(10L, 20L)).isEqualTo(Optional.of("pat-value"));
        assertThat(service.token(11L, 20L)).isEmpty();
        verify(mapper).find(10L, 20L);
    }

    @Test
    void rejectsIncompleteCredential() {
        CodingCredentialService service = new CodingCredentialService(mock(ZhaogangCodingCredentialMapper.class));

        assertThatThrownBy(() -> service.upsert(10L, 20L, " ", "成员", "avatar"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

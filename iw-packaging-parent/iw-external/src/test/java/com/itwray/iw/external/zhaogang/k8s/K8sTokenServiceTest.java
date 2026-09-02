package com.itwray.iw.external.zhaogang.k8s;

import com.itwray.iw.external.mapper.ZhaogangK8sTokenMapper;
import com.itwray.iw.external.zhaogang.k8s.entity.K8sTokenEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class K8sTokenServiceTest {

    private final ZhaogangK8sTokenMapper mapper = mock(ZhaogangK8sTokenMapper.class);
    private final K8sTokenService service = new K8sTokenService(mapper);

    @Test
    void exposesConfiguredStateForAllEnvironments() {
        K8sTokenEntity entity = new K8sTokenEntity();
        entity.setEnvironment("uat");
        entity.setTokenPlaintext("uat-token");
        when(mapper.findAll(11L, 22L)).thenReturn(List.of(entity));

        assertThat(service.statuses(11L, 22L))
                .containsEntry("test", false)
                .containsEntry("uat", true)
                .containsEntry("prd", false);
    }

    @Test
    void trimsAndStoresTokenUnderNormalizedEnvironment() {
        service.upsert(11L, 22L, " UAT ", "  token-value  ");

        verify(mapper).upsert(11L, 22L, "uat", "token-value");
    }

    @Test
    void rejectsUnsupportedEnvironmentAndMissingToken() {
        assertThatThrownBy(() -> service.upsert(11L, 22L, "dev", "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("test、uat 或 prd");
        assertThatThrownBy(() -> service.upsert(11L, 22L, "test", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Token 信息不完整");
    }

    @Test
    void rejectsTokenReadWhenCurrentUserHasNoStoredToken() {
        when(mapper.find(11L, 22L, "prd")).thenReturn(null);

        assertThatThrownBy(() -> service.token(11L, 22L, "prd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未配置");
    }
}

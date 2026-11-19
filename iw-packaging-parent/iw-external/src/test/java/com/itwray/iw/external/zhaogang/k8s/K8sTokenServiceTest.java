package com.itwray.iw.external.zhaogang.k8s;

import com.itwray.iw.external.mapper.ZhaogangK8sTokenMapper;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.k8s.entity.K8sTokenEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.itwray.iw.external.zhaogang.ZhaogangModels.K8sTokenSource.DEFAULT;
import static com.itwray.iw.external.zhaogang.ZhaogangModels.K8sTokenSource.NONE;
import static com.itwray.iw.external.zhaogang.ZhaogangModels.K8sTokenSource.PERSONAL;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class K8sTokenServiceTest {

    private final ZhaogangK8sTokenMapper mapper = mock(ZhaogangK8sTokenMapper.class);
    private final ZhaogangProperties properties = new ZhaogangProperties();
    private final K8sTokenService service = new K8sTokenService(mapper, properties);

    @Test
    void exposesConfiguredStateForAllEnvironments() {
        K8sTokenEntity entity = new K8sTokenEntity();
        entity.setEnvironment("uat");
        entity.setTokenPlaintext("uat-token");
        when(mapper.findAll(11L, 22L)).thenReturn(List.of(entity));

        assertThat(service.statuses(11L, 22L).configured())
                .containsEntry("test", false)
                .containsEntry("uat", true)
                .containsEntry("prd", false);
    }

    @Test
    void prefersPersonalTokenAndMasksBothSourcesWithoutExposingShortTokens() {
        properties.setK8sDefaultTokens(Map.of("test", "  123-default-abc  ", "uat", "ignored-default", "prd", " "));
        K8sTokenEntity personal = new K8sTokenEntity();
        personal.setEnvironment("uat");
        personal.setTokenPlaintext("secret");
        when(mapper.findAll(11L, 22L)).thenReturn(List.of(personal));
        when(mapper.find(11L, 22L, "uat")).thenReturn(personal);

        var status = service.statuses(11L, 22L);
        assertThat(status.configured()).containsEntry("test", true).containsEntry("uat", true).containsEntry("prd", false);
        assertThat(status.credentials().get("test").source()).isEqualTo(DEFAULT);
        assertThat(status.credentials().get("test").maskedToken()).isEqualTo("123***abc");
        assertThat(status.credentials().get("uat").source()).isEqualTo(PERSONAL);
        assertThat(status.credentials().get("uat").maskedToken()).isEqualTo("***");
        assertThat(status.credentials().get("prd").source()).isEqualTo(NONE);
        assertThat(status.credentials().get("prd").maskedToken()).isNull();
        assertThat(service.token(11L, 22L, "test").token()).isEqualTo("123-default-abc");
        assertThat(service.token(11L, 22L, "uat").token()).isEqualTo("secret");
        assertThat(service.token(11L, 22L, "test").revision()).isEqualTo(status.credentials().get("test").revision());
        assertThat(service.token(11L, 23L, "test").revision()).isNotEqualTo(service.token(11L, 22L, "test").revision());
    }

    @Test
    void fallsBackAfterDeleteAndDoesNotExposeDefaultsToIncompleteIdentity() {
        properties.setK8sDefaultTokens(Map.of("prd", "123-shared-abc"));
        K8sTokenEntity personal = new K8sTokenEntity();
        personal.setTokenPlaintext("123-personal-abc");
        when(mapper.find(11L, 22L, "prd")).thenReturn(personal, (K8sTokenEntity) null);

        var before = service.token(11L, 22L, "prd");
        service.delete(11L, 22L, "prd");

        verify(mapper).delete(11L, 22L, "prd");
        var after = service.token(11L, 22L, "prd");
        assertThat(after.token()).isEqualTo("123-shared-abc");
        assertThat(after.revision()).isNotEqualTo(before.revision());
        assertThat(service.statuses(0L, 22L).credentials().get("prd").source()).isEqualTo(NONE);
        assertThatThrownBy(() -> service.token(0L, 22L, "prd"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("会话信息不完整");
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

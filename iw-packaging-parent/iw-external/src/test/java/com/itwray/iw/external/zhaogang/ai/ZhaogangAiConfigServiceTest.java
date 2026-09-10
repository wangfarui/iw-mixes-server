package com.itwray.iw.external.zhaogang.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.mapper.ZhaogangAiConfigMapper;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.ai.ZhaogangAiModels.ConfigCommand;
import com.itwray.iw.external.zhaogang.ai.entity.ZhaogangAiConfigEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZhaogangAiConfigServiceTest {

    private final ZhaogangAiConfigMapper mapper = mock(ZhaogangAiConfigMapper.class);
    private final ZhaogangProperties properties = new ZhaogangProperties();
    private final ZhaogangAiConfigService service = new ZhaogangAiConfigService(mapper, properties, new ObjectMapper());

    @Test
    void usesTerraAsDefaultModel() {
        assertThat(properties.getAiDefaultModel()).isEqualTo("gpt-5.6-terra");
        assertThat(service.status(11L, 22L).model()).isEqualTo("gpt-5.6-terra");
    }

    @Test
    void normalizesBearerPrefixWithoutChangingStoredValueAgain() {
        assertThat(ZhaogangAiConfigService.normalizeApiKey("  Bearer secret-value  ")).isEqualTo("secret-value");
        assertThat(ZhaogangAiConfigService.normalizeApiKey("secret-value")).isEqualTo("secret-value");
    }

    @Test
    void statusNeverReturnsApiKey() {
        ZhaogangAiConfigEntity entity = new ZhaogangAiConfigEntity();
        entity.setApiUrl("https://ai.example/v1/chat/completions");
        entity.setApiKey("secret-value");
        entity.setModel("vision-model");
        entity.setExecutionLocation("SERVER");
        when(mapper.find(11L, 22L)).thenReturn(entity);

        var status = service.status(11L, 22L);

        assertThat(status.configured()).isTrue();
        assertThat(status.apiUrl()).isEqualTo(entity.getApiUrl());
        assertThat(status.apiKeyMasked()).isEqualTo("secr********alue");
        assertThat(status.apiKeyMasked()).doesNotContain(entity.getApiKey());
        assertThat(status.model()).isEqualTo("vision-model");
        assertThat(status.executionLocation()).isEqualTo(ZhaogangAiModels.ExecutionLocation.SERVER);
    }

    @Test
    void blankApiKeyKeepsPreviousSecret() {
        ZhaogangAiConfigEntity entity = new ZhaogangAiConfigEntity();
        entity.setApiKey("old-secret");
        when(mapper.find(11L, 22L)).thenReturn(entity);

        service.save(11L, 22L, new ConfigCommand("https://ai.example/v1/chat/completions", " ", "", "AUTO"));

        verify(mapper).upsert(11L, 22L, "https://ai.example/v1/chat/completions", "old-secret",
                properties.getAiDefaultModel(), "AUTO");
    }

    @Test
    void masksShortApiKeysWithoutExposingCharacters() {
        assertThat(ZhaogangAiConfigService.maskApiKey("key")).isEqualTo("***");
        assertThat(ZhaogangAiConfigService.maskApiKey("12345678")).isEqualTo("12********78");
    }
}

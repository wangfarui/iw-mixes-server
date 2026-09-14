package com.itwray.iw.external.zhaogang.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.mapper.ZhaogangAiConfigMapper;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.ai.ZhaogangAiModels.ConfigCommand;
import com.itwray.iw.external.zhaogang.ai.entity.ZhaogangAiConfigEntity;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZhaogangAiConfigServiceTest {

    private final ZhaogangAiConfigMapper mapper = mock(ZhaogangAiConfigMapper.class);
    private final ZhaogangProperties properties = new ZhaogangProperties();
    private final HttpClient httpClient = mock(HttpClient.class);
    private final ZhaogangAiConfigService service = new ZhaogangAiConfigService(
            mapper, properties, new ObjectMapper(), httpClient);

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
        entity.setApiUrl("https://ai.example");
        entity.setApiKey("secret-value");
        entity.setModel("vision-model");
        entity.setExecutionLocation("SERVER");
        when(mapper.find(11L, 22L)).thenReturn(entity);

        var status = service.status(11L, 22L);

        assertThat(status.configured()).isTrue();
        assertThat(status.apiUrl()).isEqualTo("https://ai.example");
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

        service.save(11L, 22L, new ConfigCommand("https://ai.example", " ", "", "AUTO"));

        verify(mapper).upsert(11L, 22L, "https://ai.example", "old-secret",
                properties.getAiDefaultModel(), "AUTO");
    }

    @Test
    void storesOnlyHostAndUsesFixedResponsesEndpoint() {
        assertThat(ZhaogangAiConfigService.normalizeApiBaseUrl(" https://ai.example/v1/responses/ "))
                .isEqualTo("https://ai.example");
        assertThat(ZhaogangAiConfigService.responsesEndpoint("https://ai.example"))
                .isEqualTo("https://ai.example/v1/responses");
    }

    @Test
    void rejectsNonRootApiPaths() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.save(11L, 22L,
                        new ConfigCommand("https://ai.example/openai", "secret", "model", "SERVER")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不要填写接口路径");
    }

    @Test
    void serverConnectionTestReturnsNetworkCodeForAutoFallback() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new ConnectException("connection refused"));

        var result = service.test(11L, 22L,
                new ConfigCommand("https://ai.example", "secret", "model", "AUTO"));

        assertThat(result.success()).isFalse();
        assertThat(result.executionLocation()).isEqualTo(ZhaogangAiModels.ExecutionLocation.SERVER);
        assertThat(result.errorCode()).isEqualTo("NETWORK");
    }

    @Test
    @SuppressWarnings("unchecked")
    void serverConnectionTestCallsFixedEndpoint() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        org.mockito.ArgumentCaptor<HttpRequest> request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);

        var result = service.test(11L, 22L,
                new ConfigCommand("https://ai.example", "secret", "model", "SERVER"));

        assertThat(result.success()).isTrue();
        verify(httpClient).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString()).isEqualTo("https://ai.example/v1/responses");
    }

    @Test
    void masksShortApiKeysWithoutExposingCharacters() {
        assertThat(ZhaogangAiConfigService.maskApiKey("key")).isEqualTo("***");
        assertThat(ZhaogangAiConfigService.maskApiKey("12345678")).isEqualTo("12********78");
    }
}

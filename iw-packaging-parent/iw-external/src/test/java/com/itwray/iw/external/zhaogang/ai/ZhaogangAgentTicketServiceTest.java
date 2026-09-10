package com.itwray.iw.external.zhaogang.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.ai.ZhaogangAiModels.ConfigCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZhaogangAgentTicketServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ZhaogangAiConfigService aiConfig = mock(ZhaogangAiConfigService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ZhaogangProperties properties = new ZhaogangProperties();
    private final ZhaogangAgentTicketService service;

    ZhaogangAgentTicketServiceTest() {
        when(redis.opsForValue()).thenReturn(values);
        service = new ZhaogangAgentTicketService(redis, objectMapper, aiConfig, properties);
    }

    @Test
    void ticketBindsIdentityIterationTaskAndExpiresAfterSixtySeconds() throws Exception {
        when(aiConfig.stored(11L, 22L)).thenReturn(new ConfigCommand(
                "https://ai.example/v1/chat/completions", "Bearer secret", "vision-model", "LOCAL_AGENT"));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        var ticket = service.issue(11L, 22L, 33L, "系统归属", "应用名称");

        verify(values).set(anyString(), payload.capture(), eq(Duration.ofSeconds(60)));
        var stored = objectMapper.readValue(payload.getValue(), ZhaogangAiModels.AgentRedeem.class);
        assertThat(ticket.expiresInSeconds()).isEqualTo(60);
        assertThat(ticket.recognitionTaskId()).isNotBlank().isEqualTo(stored.recognitionTaskId());
        assertThat(stored.codingTeamId()).isEqualTo(11L);
        assertThat(stored.codingUserId()).isEqualTo(22L);
        assertThat(stored.iterationId()).isEqualTo(33L);
        assertThat(stored.apiKey()).isEqualTo("secret");
        assertThat(stored.codingDirectory()).isNotBlank();
        assertThat(stored.prompt()).contains("源项目名称列名为 \"系统归属\"")
                .contains("源构建计划列名为 \"应用名称\"");
    }

    @Test
    void ticketPromptUsesDefaultColumnNamesWhenRequestValuesAreBlank() throws Exception {
        when(aiConfig.stored(11L, 22L)).thenReturn(new ConfigCommand(
                "https://ai.example/v1/chat/completions", "secret", "vision-model", "LOCAL_AGENT"));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        service.issue(11L, 22L, 33L, null, " \n ");

        verify(values).set(anyString(), payload.capture(), eq(Duration.ofSeconds(60)));
        var stored = objectMapper.readValue(payload.getValue(), ZhaogangAiModels.AgentRedeem.class);
        assertThat(stored.prompt()).contains("源项目名称列名为 \"系统所属OPS\"")
                .contains("源构建计划列名为 \"系统名字\"");
    }

    @Test
    void redeemUsesAtomicGetAndDeleteAndCannotBeRepeated() throws Exception {
        var payload = objectMapper.writeValueAsString(new ZhaogangAiModels.AgentRedeem(
                "https://ai.example/v1/chat/completions", "secret", "vision-model", "prompt", "team",
                11L, 22L, 33L, "task-1"));
        when(values.getAndDelete("zhaogang:agent-ticket:ticket-1")).thenReturn(payload).thenReturn(null);

        assertThat(service.redeem("ticket-1").recognitionTaskId()).isEqualTo("task-1");
        assertThatThrownBy(() -> service.redeem("ticket-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已过期或已使用");
    }
}

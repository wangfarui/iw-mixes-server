package com.itwray.iw.external.zhaogang.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.ai.ZhaogangAiModels.AgentRedeem;
import com.itwray.iw.external.zhaogang.ai.ZhaogangAiModels.AgentTicket;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportPrompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

@Service
public class ZhaogangAgentTicketService {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String KEY_PREFIX = "zhaogang:agent-ticket:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ZhaogangAiConfigService aiConfig;
    private final ZhaogangProperties properties;
    private final SecureRandom random = new SecureRandom();

    public ZhaogangAgentTicketService(StringRedisTemplate redis, ObjectMapper objectMapper,
                                      ZhaogangAiConfigService aiConfig, ZhaogangProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.aiConfig = aiConfig;
        this.properties = properties;
    }

    public AgentTicket issue(long teamId, long userId, long iterationId,
                             String projectColumnName, String planColumnName) {
        if (teamId <= 0 || userId <= 0 || iterationId <= 0) {
            throw new IllegalArgumentException("找钢工作台票据参数不完整");
        }
        ZhaogangAiModels.ConfigCommand config = aiConfig.stored(teamId, userId);
        if (config == null || config.apiUrl() == null || config.apiUrl().isBlank()
                || config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalArgumentException("请先在设置中配置 AI 识别");
        }
        return store(new AgentRedeem(ZhaogangAiConfigService.responsesEndpoint(config.apiUrl()),
                ZhaogangAiConfigService.normalizeApiKey(config.apiKey()),
                config.model(), ReleaseImportPrompt.build(projectColumnName, planColumnName),
                properties.getTeam(), teamId, userId, iterationId, UUID.randomUUID().toString()));
    }

    public AgentTicket issueConnectionTest(long teamId, long userId, ZhaogangAiModels.ConfigCommand command) {
        ZhaogangAiModels.ConfigCommand config = aiConfig.resolve(teamId, userId, command);
        return store(new AgentRedeem(ZhaogangAiConfigService.responsesEndpoint(config.apiUrl()),
                ZhaogangAiConfigService.normalizeApiKey(config.apiKey()), config.model(),
                "这是 AI 视觉连接测试。忽略图片内容，只返回 OK，不要输出其它内容。", "",
                teamId, userId, 0, UUID.randomUUID().toString()));
    }

    private AgentTicket store(AgentRedeem redeem) {
        String ticket = randomTicket();
        try {
            redis.opsForValue().set(KEY_PREFIX + ticket, objectMapper.writeValueAsString(redeem), TTL);
        } catch (Exception error) {
            throw new IllegalStateException("本机 Agent 票据创建失败", error);
        }
        return new AgentTicket(ticket, redeem.recognitionTaskId(), properties.getAgentBackendUrl(), 60);
    }

    public AgentRedeem redeem(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new IllegalArgumentException("本机 Agent 票据不能为空");
        }
        String payload = redis.opsForValue().getAndDelete(KEY_PREFIX + ticket.trim());
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("本机 Agent 票据已过期或已使用");
        }
        try {
            return objectMapper.readValue(payload, AgentRedeem.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("本机 Agent 票据格式无效");
        }
    }

    private String randomTicket() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

package com.itwray.iw.external.zhaogang.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.common.utils.AESUtils;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

@Component
class WecomWebhook {
    enum Outcome { ACCEPTED, RETRYABLE, FAILED, UNKNOWN }
    record Result(Outcome outcome, String reason) { }
    private final ObjectMapper json;
    private final ZhaogangProperties properties;
    private final HttpClient client;
    @org.springframework.beans.factory.annotation.Autowired
    WecomWebhook(ObjectMapper json, ZhaogangProperties properties) {
        this(json, properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    WecomWebhook(ObjectMapper json, ZhaogangProperties properties, HttpClient client) {
        this.json = json; this.properties = properties; this.client = client;
    }

    static String validate(String input) {
        String url = input == null ? "" : input.trim();
        // Exact origin/path/query; do not accept credentials, ports, fragments, encoded or additional parameters.
        if (!url.matches("https://qyapi\\.weixin\\.qq\\.com/cgi-bin/webhook/send\\?key=[A-Za-z0-9_-]{8,128}"))
            throw new WorkbenchTeamException("请输入有效的企业微信群消息 Webhook 地址");
        return url;
    }
    String encrypt(String url) { return AESUtils.encryptAESGCM(key(), validate(url)); }
    String decrypt(String ciphertext) { return validate(AESUtils.decryptAESGCM(key(), ciphertext)); }
    private javax.crypto.SecretKey key() {
        return AESUtils.generateSecretKey(CodingCredentialService.fingerprint(properties.safeSessionKey() + ":worklog-webhook:v1").substring(0, 32));
    }
    Result send(String ciphertext, String content) {
        if (WorklogMessageFormatter.bytes(content) > 4096) return new Result(Outcome.FAILED, "消息超过企微字节上限");
        try {
            String url = decrypt(ciphertext);
            String body = json.writeValueAsString(Map.of("msgtype", "markdown", "markdown", Map.of("content", content)));
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json;charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // Non-success HTTP or malformed response cannot prove that the message was rejected.
            if (response.statusCode() != 200) return new Result(Outcome.UNKNOWN, "企微响应异常，投递结果未知");
            return interpret(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return new Result(Outcome.UNKNOWN, "发送中断，投递结果未知");
        } catch (Exception e) {
            // Never return remote exception text: it can contain the secret request URI.
            return new Result(Outcome.UNKNOWN, "连接或响应失败，投递结果未知");
        }
    }
    Result interpret(String body) {
        try {
            var node = json.readTree(body);
            if (!node.path("errcode").isIntegralNumber() || !node.path("errcode").canConvertToInt()) return new Result(Outcome.UNKNOWN, "企微未返回有效投递结果");
            int code = node.path("errcode").asInt();
            if (code == 0) return new Result(Outcome.ACCEPTED, "企微已受理");
            if (code == -1 || code == 45009) return new Result(Outcome.RETRYABLE, "企微暂忙或触发频率限制（" + code + "）");
            return new Result(Outcome.FAILED, "企微拒绝发送（" + code + "）");
        } catch (Exception e) { return new Result(Outcome.UNKNOWN, "企微响应无法解析，投递结果未知"); }
    }
}

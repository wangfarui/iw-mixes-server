package com.itwray.iw.external.zhaogang.notification;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.net.http.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.*;
class WecomWebhookTest {
    private final WecomWebhook webhook=new WecomWebhook(new ObjectMapper(),new ZhaogangProperties());
    @Test void restrictsOutboundAddressAndEncryptsWithoutExposingSecret() {
        String url="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=fixture-key-0001";
        String encrypted=webhook.encrypt(url);
        assertThat(encrypted).doesNotContain("fixture-key");
        assertThat(webhook.decrypt(encrypted)).isEqualTo(url);
        assertThat(webhook.hint(encrypted)).isEqualTo("fixxxx0001");
        assertThat(webhook.hint(null)).isNull();
        for (String invalid:List.of("http://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=fixture-key",url+"&x=1",url+"#x",url.replace(".com/", ".com:443/"),url.replace(".com/", ".com.evil/"),url.replace("qyapi.weixin.qq.com","127.0.0.1"),url.replace("https://","https://me@"),url.replace("fixture-key", "%66ixture-key")))
            assertThatThrownBy(()->WecomWebhook.validate(invalid)).hasMessage("请输入有效的企业微信群消息 Webhook 地址");
    }
    @Test void separatesBusinessAcceptanceRejectionRetryAndAmbiguousResponse() {
        assertThat(webhook.interpret("{\"errcode\":0}").outcome()).isEqualTo(WecomWebhook.Outcome.ACCEPTED);
        for (int code:List.of(-1,45009)) assertThat(webhook.interpret("{\"errcode\":"+code+"}").outcome()).isEqualTo(WecomWebhook.Outcome.RETRYABLE);
        assertThat(webhook.interpret("{\"errcode\":93000,\"errmsg\":\"secret-url\"}").reason()).doesNotContain("secret-url");
        assertThat(webhook.interpret("{\"errcode\":93000}").outcome()).isEqualTo(WecomWebhook.Outcome.FAILED);
        for (String invalid:List.of("{}","not-json","{\"errcode\":\"0\"}")) assertThat(webhook.interpret(invalid).outcome()).isEqualTo(WecomWebhook.Outcome.UNKNOWN);
    }
    @Test void sendsOneRequestChecksBusinessCodeAndDoesNotBlindlyRetryTimeout() throws Exception {
        var client=mock(HttpClient.class);
        var response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"errcode\":93000}");
        when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var sender=new WecomWebhook(new ObjectMapper(),new ZhaogangProperties(),client);
        String cipher=sender.encrypt("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=fixture-key-0001");
        assertThat(sender.send(cipher,"report").outcome()).isEqualTo(WecomWebhook.Outcome.FAILED);
        when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("secret-url"));
        var timeout=sender.send(cipher,"report");
        assertThat(timeout.outcome()).isEqualTo(WecomWebhook.Outcome.UNKNOWN);
        assertThat(timeout.reason()).doesNotContain("secret-url");
        verify(client,times(2)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }

    @Test void emitsTextMobileMentionsAndEnforcesTextByteLimitBeforeHttp() throws Exception {
        var client = mock(HttpClient.class);
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"errcode\":0}");
        when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var sender = new WecomWebhook(new ObjectMapper(),new ZhaogangProperties(),client);
        String cipher = sender.encrypt("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=fixture-key-0001");
        var message = new NotificationMessage("text","团队汇总",List.of("13800000001"));
        assertThat(sender.send(cipher,message).outcome()).isEqualTo(WecomWebhook.Outcome.ACCEPTED);
        var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(),any(HttpResponse.BodyHandler.class));
        var bytes = new java.io.ByteArrayOutputStream();
        request.getValue().bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(java.nio.ByteBuffer buffer) { byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part); }
            public void onError(Throwable error) { throw new AssertionError(error); }
            public void onComplete() { }
        });
        var payload = new ObjectMapper().readTree(bytes.toByteArray());
        assertThat(payload.path("msgtype").asText()).isEqualTo("text");
        assertThat(payload.path("text").path("mentioned_mobile_list").get(0).asText()).isEqualTo("13800000001");
        assertThat(payload.toString()).doesNotContain("mentioned_list", "userid", "@all");
        assertThat(sender.send(cipher,NotificationMessage.text("🙂".repeat(513))).outcome()).isEqualTo(WecomWebhook.Outcome.FAILED);
        verify(client,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }

}

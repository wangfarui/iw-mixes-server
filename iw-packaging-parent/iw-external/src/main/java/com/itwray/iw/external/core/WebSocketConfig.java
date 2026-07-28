package com.itwray.iw.external.core;

import com.itwray.iw.external.handler.DeepSeekWebSocketHandler;
import com.itwray.iw.external.handler.RemoteShareWebSocketHandler;
import com.itwray.iw.external.remoteshare.RemoteShareSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Configuration
@EnableWebSocket
@Slf4j
public class WebSocketConfig implements WebSocketConfigurer {

    private final RemoteShareSessionService remoteShareSessionService;

    public WebSocketConfig(RemoteShareSessionService remoteShareSessionService) {
        this.remoteShareSessionService = remoteShareSessionService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(deepSeekWebSocketHandler(), "/wb/chat-ws")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
                        log.info("握手请求头: {}", request.getHeaders());
                        return true;
                    }

                    @Override
                    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {

                    }
                })
                .setAllowedOrigins("*"); // 允许跨域
        registry.addHandler(remoteShareWebSocketHandler(remoteShareSessionService), "/wb/remote-share")
                .setAllowedOriginPatterns("https://*.itwray.com", "http://localhost:*", "http://127.0.0.1:*");
    }

    @Bean
    public DeepSeekWebSocketHandler deepSeekWebSocketHandler() {
        return new DeepSeekWebSocketHandler();
    }

    @Bean
    public RemoteShareWebSocketHandler remoteShareWebSocketHandler(RemoteShareSessionService sessionService) {
        return new RemoteShareWebSocketHandler(sessionService);
    }
}

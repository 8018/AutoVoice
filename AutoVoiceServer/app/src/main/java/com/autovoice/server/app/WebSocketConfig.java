package com.autovoice.server.app;

import com.autovoice.server.gateway.VoiceGatewayHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WS 端点注册：{@code /ws} → {@link VoiceGatewayHandler}（protocol.md §1 传输层约定）。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final VoiceGatewayHandler handler;

    public WebSocketConfig(VoiceGatewayHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws");
    }
}

package com.dji.sample.msdk.control.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.util.StringUtils;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class MsdkControlWebSocketConfiguration implements WebSocketConfigurer {

    private final MsdkControlWebSocketHandler handler;

    @Value("${msdk.control.auth-token}")
    private String authToken;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        if (!StringUtils.hasText(authToken) || authToken.trim().length() < 32) {
            throw new IllegalStateException(
                    "MSDK_CONTROL_AUTH_TOKEN must be configured with at least 32 characters.");
        }
        registry.addHandler(handler, "/api/v1/msdk/control")
                .addInterceptors(new MsdkControlHandshakeInterceptor(authToken.trim()))
                .setAllowedOriginPatterns("*");
    }
}

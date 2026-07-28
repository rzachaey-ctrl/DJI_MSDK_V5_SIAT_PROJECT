package com.dji.sample.msdk.control.websocket;

import com.dji.sample.msdk.control.service.MsdkControlBridgeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class MsdkControlWebSocketHandler extends TextWebSocketHandler {

    private final MsdkControlBridgeService bridgeService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        bridgeService.connected(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            bridgeService.receive(session.getId(), message.getPayload());
        } catch (JsonProcessingException ex) {
            log.warn("Rejected malformed MSDK event from session={}", session.getId());
        } catch (IllegalStateException ex) {
            log.warn("Rejected event from inactive MSDK session={}", session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        bridgeService.disconnected(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("MSDK WebSocket transport error. session={}", session.getId(), exception);
    }
}

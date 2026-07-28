package com.dji.sample.msdk.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A desktop-only replacement for the RC Pro Android app during local development.
 *
 * Run this class from IntelliJ after starting CloudApiSampleApplication. It connects
 * to the same WebSocket endpoint as the Android app and acknowledges every valid
 * command in dry-run mode.
 */
public class MsdkControlSimulator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static void main(String[] args) throws InterruptedException {
        String url = System.getProperty(
                "msdk.ws.url", "ws://127.0.0.1:6789/api/v1/msdk/control");
        String token = System.getProperty("msdk.control.token", "local-dev-only");
        CountDownLatch stopped = new CountDownLatch(1);

        OkHttpClient client = new OkHttpClient.Builder()
                .pingInterval(10, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + token)
                .build();

        client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                System.out.println("Virtual RC Pro connected to " + url);
                webSocket.send(eventJson(
                        "CLIENT_HELLO", null, null, null,
                        "ONLINE", "Desktop simulator connected"));
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    Map<String, Object> command = OBJECT_MAPPER.readValue(
                            text, new TypeReference<Map<String, Object>>() { });
                    String type = String.valueOf(command.get("type"));
                    String requestId = String.valueOf(command.get("request_id"));
                    String controlSessionId = command.get("control_session_id") == null
                            ? null : String.valueOf(command.get("control_session_id"));
                    Long sequence = command.get("sequence") instanceof Number
                            ? ((Number) command.get("sequence")).longValue() : null;
                    System.out.println("Received " + type + ", requestId=" + requestId);
                    webSocket.send(eventJson(
                            "COMMAND_ACK",
                            requestId,
                            controlSessionId,
                            sequence,
                            "ACCEPTED",
                            "validated by desktop simulator"));
                } catch (Exception ex) {
                    System.err.println("Rejected message: " + ex.getMessage());
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                System.out.println("Simulator disconnected: " + code + " " + reason);
                stopped.countDown();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                System.err.println("Simulator connection failed: " + error.getMessage());
                stopped.countDown();
            }
        });

        stopped.await();
        client.dispatcher().executorService().shutdown();
    }

    private static String eventJson(
            String type,
            String requestId,
            String controlSessionId,
            Long sequence,
            String status,
            String message) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("version", 1);
            event.put("type", type);
            event.put("request_id", requestId);
            if (controlSessionId != null) {
                event.put("control_session_id", controlSessionId);
            }
            if (sequence != null) {
                event.put("sequence", sequence);
            }
            event.put("timestamp", System.currentTimeMillis());
            event.put("status", status);
            event.put("message", message);
            event.put("dryRun", true);
            return OBJECT_MAPPER.writeValueAsString(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to create simulator event.", ex);
        }
    }
}

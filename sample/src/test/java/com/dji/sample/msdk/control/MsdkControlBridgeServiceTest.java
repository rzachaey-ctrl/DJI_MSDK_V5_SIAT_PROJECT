package com.dji.sample.msdk.control;

import com.dji.sample.msdk.control.model.MsdkControlCommand;
import com.dji.sample.msdk.control.model.MsdkControlEvent;
import com.dji.sample.msdk.control.model.MsdkStickPayload;
import com.dji.sample.msdk.control.service.MsdkControlBridgeService;
import com.dji.sample.msdk.mission.service.MsdkMissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class MsdkControlBridgeServiceTest {

    private MsdkControlBridgeService bridgeService;
    private WebSocketSession webSocketSession;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        bridgeService = new MsdkControlBridgeService(objectMapper);
        webSocketSession = mock(WebSocketSession.class);
        when(webSocketSession.getId()).thenReturn("simulated-rc-pro");
        when(webSocketSession.isOpen()).thenReturn(true);
        when(webSocketSession.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 18888));
        bridgeService.connected(webSocketSession);
    }

    @AfterEach
    void tearDown() {
        bridgeService.shutdown();
    }

    @Test
    void sendsValidatedStickCommandToConnectedClient() throws Exception {
        String controlSessionId = enableControl();
        MsdkStickPayload payload = new MsdkStickPayload();
        payload.setLeftVertical(100);

        MsdkControlCommand command = new MsdkControlCommand();
        command.setType("stick");
        command.setPayload(payload);
        command.setControlSessionId(controlSessionId);

        MsdkControlCommand sent = bridgeService.send(command);

        assertEquals("STICK", sent.getType());
        assertEquals(1, sent.getVersion());
        assertNotNull(sent.getRequestId());
        assertNotNull(sent.getTimestamp());
        assertEquals("PENDING", bridgeService.commandResult(sent.getRequestId()).getStatus());
        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(webSocketSession, times(2)).sendMessage(messages.capture());
        assertEquals("ENABLE_CONTROL",
                objectMapper.readTree(messages.getAllValues().get(0).getPayload()).path("type").asText());
        assertEquals("STICK",
                objectMapper.readTree(messages.getAllValues().get(1).getPayload()).path("type").asText());
        assertEquals(100,
                objectMapper.readTree(messages.getAllValues().get(1).getPayload())
                        .path("payload").path("left_vertical").asInt());
    }

    @Test
    void changesPendingCommandToAcceptedWhenAckArrives() throws Exception {
        MsdkControlCommand command = new MsdkControlCommand();
        command.setType("HEARTBEAT");
        MsdkControlCommand sent = bridgeService.send(command);

        bridgeService.receive("{\"version\":1,\"type\":\"COMMAND_ACK\",\"request_id\":\""
                + sent.getRequestId()
                + "\",\"status\":\"ACCEPTED\",\"message\":\"simulated\"}");

        assertEquals("ACCEPTED", bridgeService.commandResult(sent.getRequestId()).getStatus());
    }

    @Test
    void changesPendingCommandToTimeoutWhenNoAckArrives() throws Exception {
        MsdkControlBridgeService shortTimeoutService =
                new MsdkControlBridgeService(objectMapper, 25);
        shortTimeoutService.connected(webSocketSession);
        MsdkControlCommand command = new MsdkControlCommand();
        command.setType("HEARTBEAT");

        MsdkControlCommand sent = shortTimeoutService.send(command);
        Thread.sleep(100);

        assertEquals("TIMEOUT", shortTimeoutService.commandResult(sent.getRequestId()).getStatus());
        shortTimeoutService.shutdown();
    }

    @Test
    void changesPendingCommandToInterruptedWhenClientDisconnects() throws Exception {
        MsdkControlCommand command = new MsdkControlCommand();
        command.setType("HEARTBEAT");
        MsdkControlCommand sent = bridgeService.send(command);

        bridgeService.disconnected("simulated-rc-pro");

        MsdkControlEvent result = bridgeService.commandResult(sent.getRequestId());
        assertEquals("INTERRUPTED", result.getStatus());
    }

    @Test
    void rejectsOutOfRangeStickValue() {
        MsdkStickPayload payload = new MsdkStickPayload();
        payload.setRightVertical(661);

        MsdkControlCommand command = new MsdkControlCommand();
        command.setType("STICK");
        command.setPayload(payload);

        assertThrows(IllegalArgumentException.class, () -> bridgeService.send(command));
    }

    @Test
    void allowsOnlyOneActiveControlSession() {
        bridgeService.acquireSession();
        assertThrows(IllegalStateException.class, bridgeService::acquireSession);
    }

    @Test
    void rejectsControlEnableWhileAircraftTelemetryIsUnavailable() {
        MsdkControlCommand command = new MsdkControlCommand();
        command.setType("ENABLE_CONTROL");
        command.setControlSessionId(bridgeService.acquireSession().getId());

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> bridgeService.send(command));

        assertEquals("Aircraft is offline.", error.getMessage());
    }

    @Test
    void acceptsControlEnableWithConnectedAircraftAndFreshTelemetry() throws Exception {
        bridgeService.receive("{\"version\":1,\"type\":\"AIRCRAFT_CONNECTION\","
                + "\"status\":\"ONLINE\",\"aircraft_connected\":true}");
        bridgeService.receive("{\"version\":1,\"type\":\"AIRCRAFT_TELEMETRY\","
                + "\"status\":\"UPDATED\",\"battery_percent\":80,\"timestamp\":"
                + System.currentTimeMillis() + "}");
        MsdkControlCommand command = new MsdkControlCommand();
        command.setType("ENABLE_CONTROL");
        command.setControlSessionId(bridgeService.acquireSession().getId());

        MsdkControlCommand sent = bridgeService.send(command);

        assertEquals("ENABLE_CONTROL", sent.getType());
    }

    @Test
    void reportsConnectionLifecycle() {
        assertTrue(bridgeService.status().isConnected());
        assertEquals("simulated-rc-pro", bridgeService.status().getSessionId());

        bridgeService.disconnected("simulated-rc-pro");

        assertFalse(bridgeService.status().isConnected());
    }

    @Test
    void leaseExpiryActivelyReleasesRcControl() throws Exception {
        MsdkControlBridgeService shortLeaseService =
                new MsdkControlBridgeService(objectMapper, 5_000, 25);
        shortLeaseService.connected(webSocketSession);
        shortLeaseService.acquireSession();

        Thread.sleep(100);
        shortLeaseService.status();

        assertNull(shortLeaseService.status().getControlSession());
        assertNull(shortLeaseService.status().getControlEnabled());
        verify(webSocketSession, atLeast(1)).sendMessage(any(TextMessage.class));
        shortLeaseService.shutdown();
    }

    @Test
    void ignoresAcknowledgementWithMismatchedSequence() throws Exception {
        bridgeService.receive("{\"version\":1,\"type\":\"AIRCRAFT_CONNECTION\","
                + "\"status\":\"ONLINE\",\"aircraft_connected\":true}");
        bridgeService.receive("{\"version\":1,\"type\":\"AIRCRAFT_TELEMETRY\","
                + "\"status\":\"UPDATED\",\"battery_percent\":80,\"timestamp\":"
                + System.currentTimeMillis() + "}");
        String sessionId = bridgeService.acquireSession("operator-a").getId();
        MsdkControlCommand enable = new MsdkControlCommand();
        enable.setType("ENABLE_CONTROL");
        enable.setControlSessionId(sessionId);
        MsdkControlCommand sent = bridgeService.send(enable, "operator-a");

        bridgeService.receive("{\"version\":1,\"type\":\"COMMAND_ACK\","
                + "\"request_id\":\"" + sent.getRequestId() + "\","
                + "\"control_session_id\":\"" + sessionId + "\","
                + "\"sequence\":" + (sent.getSequence() + 1) + ","
                + "\"status\":\"ACCEPTED\",\"control_enabled\":true}");

        assertEquals("PENDING", bridgeService.commandResult(sent.getRequestId()).getStatus());
        assertFalse(Boolean.TRUE.equals(bridgeService.status().getControlEnabled()));
        assertNull(bridgeService.commandResult(sent.getRequestId(), "operator-b"));
        assertNotNull(bridgeService.commandResult(sent.getRequestId(), "operator-a"));
    }

    @Test
    void bindsControlSessionToAuthenticatedOperator() {
        bridgeService.acquireSession("operator-a");

        assertTrue(bridgeService.status("operator-a").getControlSessionOwned());
        assertFalse(bridgeService.status("operator-b").getControlSessionOwned());
        assertNull(bridgeService.status("operator-b").getControlSession().getId());

        MsdkControlCommand command = new MsdkControlCommand();
        command.setType("DISABLE_CONTROL");
        command.setControlSessionId(bridgeService.status("operator-a").getControlSession().getId());

        assertThrows(IllegalStateException.class,
                () -> bridgeService.send(command, "operator-b"));
    }

    @Test
    void failedStopIsARejectedActionRatherThanMissionFailure() throws Exception {
        MsdkMissionService missionService = mock(MsdkMissionService.class);
        ReflectionTestUtils.setField(bridgeService, "missionService", missionService);
        MsdkControlCommand command = new MsdkControlCommand();
        command.setType("MISSION_STOP");
        command.setTaskId("12c3ad2f-d300-4b0c-a1f4-7f7bf706bcf1");

        MsdkControlCommand sent = bridgeService.sendTrusted(command, "operator-a");
        bridgeService.receive("{\"version\":1,\"type\":\"MISSION_STATE\","
                + "\"request_id\":\"" + sent.getRequestId() + "\","
                + "\"task_id\":\"" + sent.getTaskId() + "\","
                + "\"status\":\"FAILED\",\"message\":\"stop rejected\"}");

        MsdkControlEvent result = bridgeService.commandResult(sent.getRequestId());
        assertEquals("COMMAND_ACK", result.getType());
        assertEquals("REJECTED", result.getStatus());
        verify(missionService).failAttempt(
                sent.getTaskId(), sent.getRequestId(), "stop rejected", false);
        verify(missionService, never()).applyClientEvent(any(MsdkControlEvent.class));
    }

    @Test
    void acceptsExplicitMissionActionCommandRejectionContract() throws Exception {
        MsdkMissionService missionService = mock(MsdkMissionService.class);
        ReflectionTestUtils.setField(bridgeService, "missionService", missionService);
        MsdkControlCommand command = new MsdkControlCommand();
        command.setType("MISSION_PAUSE");
        command.setTaskId("7ddc186f-34d3-4691-ab89-4c3229dd96dc");

        MsdkControlCommand sent = bridgeService.sendTrusted(command, "operator-a");
        bridgeService.receive("{\"version\":1,\"type\":\"COMMAND_ACK\","
                + "\"request_id\":\"" + sent.getRequestId() + "\","
                + "\"status\":\"REJECTED\",\"message\":\"pause rejected\"}");

        MsdkControlEvent result = bridgeService.commandResult(sent.getRequestId());
        assertEquals("COMMAND_ACK", result.getType());
        assertEquals("REJECTED", result.getStatus());
        assertEquals(sent.getTaskId(), result.getTaskId());
        verify(missionService).failAttempt(
                sent.getTaskId(), sent.getRequestId(), "pause rejected", false);
    }

    @Test
    void directDisableMarksSessionReleasingAndBlocksStick() throws Exception {
        assertDirectReleaseMarksSessionAndBlocksStick("DISABLE_CONTROL");
    }

    @Test
    void directSafetyReleaseMarksSessionReleasingAndBlocksStick() throws Exception {
        assertDirectReleaseMarksSessionAndBlocksStick("SAFETY_RELEASE");
    }

    @Test
    void directEmergencyStopMarksSessionReleasingAndBlocksStick() throws Exception {
        assertDirectReleaseMarksSessionAndBlocksStick("EMERGENCY_STOP");
    }

    @Test
    void rejectedDirectReleaseCanBeRetried() throws Exception {
        String sessionId = bridgeService.acquireSession("operator-a").getId();
        MsdkControlCommand release = new MsdkControlCommand();
        release.setType("DISABLE_CONTROL");
        release.setControlSessionId(sessionId);
        MsdkControlCommand sent = bridgeService.send(release, "operator-a");

        bridgeService.receive(commandAck(sent, "REJECTED", null));

        assertFalse(bridgeService.status("operator-a").getControlSession().getReleasing());
        MsdkControlCommand retry = new MsdkControlCommand();
        retry.setType("DISABLE_CONTROL");
        retry.setControlSessionId(sessionId);
        assertDoesNotThrow(() -> bridgeService.send(retry, "operator-a"));
        assertTrue(bridgeService.status("operator-a").getControlSession().getReleasing());
    }

    @Test
    void timedOutDirectReleaseCanBeRetried() throws Exception {
        MsdkControlBridgeService shortTimeoutService =
                new MsdkControlBridgeService(objectMapper, 25, 10_000);
        try {
            shortTimeoutService.connected(webSocketSession);
            String sessionId = shortTimeoutService.acquireSession("operator-a").getId();
            MsdkControlCommand release = new MsdkControlCommand();
            release.setType("SAFETY_RELEASE");
            release.setControlSessionId(sessionId);
            shortTimeoutService.send(release, "operator-a");

            Thread.sleep(100);

            assertFalse(shortTimeoutService.status("operator-a")
                    .getControlSession().getReleasing());
            MsdkControlCommand retry = new MsdkControlCommand();
            retry.setType("SAFETY_RELEASE");
            retry.setControlSessionId(sessionId);
            assertDoesNotThrow(() -> shortTimeoutService.send(retry, "operator-a"));
        } finally {
            shortTimeoutService.shutdown();
        }
    }

    @Test
    void deleteReleasePathUsesSameRetryableReservation() throws Exception {
        String sessionId = bridgeService.acquireSession("operator-a").getId();

        bridgeService.releaseSession(sessionId, "operator-a");

        assertTrue(bridgeService.status("operator-a").getControlSession().getReleasing());
        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(webSocketSession).sendMessage(messages.capture());
        MsdkControlCommand sent = objectMapper.readValue(
                messages.getValue().getPayload(), MsdkControlCommand.class);
        bridgeService.receive(commandAck(sent, "REJECTED", null));
        assertFalse(bridgeService.status("operator-a").getControlSession().getReleasing());
        assertDoesNotThrow(() -> bridgeService.releaseSession(sessionId, "operator-a"));
    }

    @Test
    void productionJsonUsesExplicitSnakeCaseStickAxes() throws Exception {
        MsdkStickPayload payload = objectMapper.readValue(
                "{\"left_horizontal\":11,\"left_vertical\":22,"
                        + "\"right_horizontal\":33,\"right_vertical\":44}",
                MsdkStickPayload.class);

        assertEquals(11, payload.getLeftHorizontal());
        assertEquals(22, payload.getLeftVertical());
        assertTrue(objectMapper.writeValueAsString(payload).contains("\"right_vertical\":44"));
    }

    private String enableControl() throws Exception {
        return enableControl(null);
    }

    private String enableControl(String operatorId) throws Exception {
        bridgeService.receive("{\"version\":1,\"type\":\"AIRCRAFT_CONNECTION\","
                + "\"status\":\"ONLINE\",\"aircraft_connected\":true}");
        bridgeService.receive("{\"version\":1,\"type\":\"AIRCRAFT_TELEMETRY\","
                + "\"status\":\"UPDATED\",\"battery_percent\":80,\"timestamp\":"
                + System.currentTimeMillis() + "}");
        String sessionId = operatorId == null
                ? bridgeService.acquireSession().getId()
                : bridgeService.acquireSession(operatorId).getId();
        MsdkControlCommand enable = new MsdkControlCommand();
        enable.setType("ENABLE_CONTROL");
        enable.setControlSessionId(sessionId);
        MsdkControlCommand sent = operatorId == null
                ? bridgeService.send(enable)
                : bridgeService.send(enable, operatorId);
        bridgeService.receive(commandAck(sent, "ACCEPTED", true));
        return sessionId;
    }

    private void assertDirectReleaseMarksSessionAndBlocksStick(String type) throws Exception {
        String sessionId = enableControl("operator-a");
        MsdkControlCommand release = new MsdkControlCommand();
        release.setType(type);
        release.setControlSessionId(sessionId);

        bridgeService.send(release, "operator-a");

        assertTrue(bridgeService.status("operator-a").getControlSession().getReleasing());
        MsdkControlCommand stick = new MsdkControlCommand();
        stick.setType("STICK");
        stick.setControlSessionId(sessionId);
        stick.setPayload(new MsdkStickPayload());
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> bridgeService.send(stick, "operator-a"));
        assertEquals("The control session is being released.", error.getMessage());
    }

    private String commandAck(
            MsdkControlCommand command, String status, Boolean controlEnabled)
            throws Exception {
        MsdkControlEvent event = new MsdkControlEvent();
        event.setVersion(1);
        event.setType("COMMAND_ACK");
        event.setRequestId(command.getRequestId());
        event.setControlSessionId(command.getControlSessionId());
        event.setSequence(command.getSequence());
        event.setStatus(status);
        event.setControlEnabled(controlEnabled);
        return objectMapper.writeValueAsString(event);
    }
}

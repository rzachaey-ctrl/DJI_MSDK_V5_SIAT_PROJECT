package com.dji.sample.msdk.control.service;

import com.dji.sample.msdk.control.model.MsdkControlCommand;
import com.dji.sample.msdk.control.model.MsdkControlEvent;
import com.dji.sample.msdk.control.model.MsdkControlStatus;
import com.dji.sample.msdk.control.model.MsdkStickPayload;
import com.dji.sample.msdk.control.model.MsdkControlSession;
import com.dji.sample.msdk.mission.service.MsdkMissionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MsdkControlBridgeService {

    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final long DEFAULT_COMMAND_TIMEOUT_MS = 5_000L;
    private static final long MINIMUM_MISSION_PREPARE_TIMEOUT_MS = 120_000L;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 64 * 1024;
    private static final int STICK_MIN = -330;
    private static final int STICK_MAX = 330;
    private static final int LOW_BATTERY_PERCENT = 20;
    private static final int MAX_COMMAND_RESULTS = 2_000;
    private static final long DEFAULT_CONTROL_SESSION_TIMEOUT_MS = 10_000L;
    private static final long TELEMETRY_FRESH_MS = 3_000L;
    private static final Set<String> ALLOWED_COMMANDS = new HashSet<>(Arrays.asList(
            "HEARTBEAT",
            "ENABLE_CONTROL",
            "DISABLE_CONTROL",
            "STICK",
            "SAFETY_RELEASE",
            "EMERGENCY_STOP",
            "MISSION_PREPARE",
            "MISSION_START",
            "MISSION_PAUSE",
            "MISSION_RESUME",
            "MISSION_STOP"
    ));
    private static final Set<String> CONTROL_SESSION_COMMANDS = new HashSet<>(Arrays.asList(
            "ENABLE_CONTROL", "DISABLE_CONTROL", "STICK", "SAFETY_RELEASE", "EMERGENCY_STOP"
    ));

    private final ObjectMapper objectMapper;
    private final long commandTimeoutMs;
    private final long controlSessionTimeoutMs;
    private final ScheduledExecutorService timeoutExecutor;
    private final ConcurrentMap<String, MsdkControlEvent> commandResults = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MsdkControlCommand> pendingCommands = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> commandOwners = new ConcurrentHashMap<>();
    private final Queue<String> commandResultOrder = new ConcurrentLinkedQueue<>();

    @Autowired(required = false)
    private MsdkControlAuditService auditService;

    @Autowired(required = false)
    private MsdkMissionService missionService;

    private volatile ConcurrentWebSocketSessionDecorator session;
    private volatile Long connectedAt;
    private volatile Long lastSeenAt;
    private volatile MsdkControlEvent lastEvent;
    private volatile MsdkControlEvent telemetry;
    private volatile Long telemetryReceivedAt;
    private volatile MsdkControlSession controlSession;
    private volatile Boolean aircraftConnected = false;
    private volatile Boolean controlEnabled;

    @Autowired
    public MsdkControlBridgeService(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_COMMAND_TIMEOUT_MS);
    }

    public MsdkControlBridgeService(ObjectMapper objectMapper, long commandTimeoutMs) {
        this(objectMapper, commandTimeoutMs, DEFAULT_CONTROL_SESSION_TIMEOUT_MS);
    }

    public MsdkControlBridgeService(
            ObjectMapper objectMapper,
            long commandTimeoutMs,
            long controlSessionTimeoutMs) {
        this.objectMapper = objectMapper;
        this.commandTimeoutMs = commandTimeoutMs;
        this.controlSessionTimeoutMs = controlSessionTimeoutMs;
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "msdk-command-timeout");
            thread.setDaemon(true);
            return thread;
        });
        this.timeoutExecutor.scheduleAtFixedRate(
                this::expireInactiveSession, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized void connected(WebSocketSession newSession) throws IOException {
        if (session != null && session.isOpen()) {
            interruptPendingCommands("MSDK Android client was replaced.");
            session.close();
        }
        session = new ConcurrentWebSocketSessionDecorator(
                newSession, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES);
        connectedAt = System.currentTimeMillis();
        lastSeenAt = connectedAt;
        lastEvent = null;
        telemetry = null;
        telemetryReceivedAt = null;
        aircraftConnected = false;
        controlEnabled = null;
        controlSession = null;
        log.info("MSDK bridge connected. session={}, remote={}",
                newSession.getId(), newSession.getRemoteAddress());
    }

    public synchronized void disconnected(String sessionId) {
        if (session != null && session.getId().equals(sessionId)) {
            session = null;
            aircraftConnected = false;
            controlEnabled = null;
            controlSession = null;
            telemetry = null;
            telemetryReceivedAt = null;
            interruptPendingCommands("MSDK Android client disconnected.");
            log.info("MSDK bridge disconnected. session={}", sessionId);
        }
    }

    /**
     * Accept an event only from the currently registered RC socket. A delayed
     * callback from a socket that has just been replaced must not mutate the
     * new connection's aircraft or control state.
     */
    public synchronized void receive(String sessionId, String json) throws JsonProcessingException {
        ConcurrentWebSocketSessionDecorator current = session;
        if (current == null || !current.isOpen() || !current.getId().equals(sessionId)) {
            throw new IllegalStateException("Event came from an inactive MSDK session.");
        }
        receive(json);
    }

    public synchronized void receive(String json) throws JsonProcessingException {
        MsdkControlEvent event = objectMapper.readValue(json, MsdkControlEvent.class);
        lastSeenAt = System.currentTimeMillis();
        if ("AIRCRAFT_TELEMETRY".equals(event.getType())) {
            telemetry = event;
            telemetryReceivedAt = lastSeenAt;
        } else {
            lastEvent = event;
        }
        if (event.getAircraftConnected() != null) {
            aircraftConnected = event.getAircraftConnected();
        } else if ("AIRCRAFT_CONNECTION".equals(event.getType())) {
            aircraftConnected = "ONLINE".equalsIgnoreCase(event.getStatus());
        }
        if (event.getControlEnabled() != null
                && !"COMMAND_ACK".equals(event.getType())) {
            controlEnabled = event.getControlEnabled();
        }
        if ("CONTROL_INTERRUPTED".equals(event.getType())
                || ("AIRCRAFT_CONNECTION".equals(event.getType())
                && "OFFLINE".equalsIgnoreCase(event.getStatus()))) {
            if (event.getControlEnabled() == null) {
                controlEnabled = null;
            }
            controlSession = null;
            interruptPendingCommands(event.getMessage());
        }
        MsdkControlCommand acknowledgedCommand = null;
        MsdkControlEvent correlatedEvent = null;
        boolean correlatedResult = false;
        boolean handledMissionResult = false;
        if (StringUtils.hasText(event.getRequestId())) {
            MsdkControlCommand pendingCommand = pendingCommands.get(event.getRequestId());
            if (pendingCommand != null && isCorrelatedResult(pendingCommand, event)) {
                correlatedEvent = normalizeCorrelatedResult(pendingCommand, event);
                commandResults.put(event.getRequestId(), correlatedEvent);
                correlatedResult = true;
                if (isTerminalResult(pendingCommand, correlatedEvent)) {
                    pendingCommands.remove(event.getRequestId(), pendingCommand);
                    acknowledgedCommand = pendingCommand;
                }
                applyAcknowledgedCommand(acknowledgedCommand, correlatedEvent);
                if (missionService != null && pendingCommand.getType().startsWith("MISSION_")) {
                    applyMissionCommandResult(
                            pendingCommand, correlatedEvent);
                    handledMissionResult = true;
                }
            }
        }
        if (correlatedResult && auditService != null) {
            auditService.resultReceived(correlatedEvent);
        }
        if (!handledMissionResult && missionService != null
                && StringUtils.hasText(event.getTaskId())) {
            missionService.applyClientEvent(event);
        }
        if (acknowledgedCommand != null && "STICK".equals(acknowledgedCommand.getType())) {
            log.debug("MSDK stick acknowledgement received. requestId={}, status={}",
                    event.getRequestId(), event.getStatus());
        } else if ("AIRCRAFT_TELEMETRY".equals(event.getType())) {
            log.debug("MSDK aircraft telemetry received.");
        } else {
            log.info("MSDK event received. type={}, requestId={}, status={}, dryRun={}",
                    event.getType(), event.getRequestId(), event.getStatus(), event.getDryRun());
        }
    }

    public synchronized MsdkControlCommand send(MsdkControlCommand command) throws IOException {
        return sendInternal(command, null, true);
    }

    /**
     * Serialize validation, sequence assignment and WebSocket send so a 10 Hz
     * browser stream cannot reorder commands between concurrent HTTP threads.
     */
    public synchronized MsdkControlCommand send(
            MsdkControlCommand command, String operatorId) throws IOException {
        return sendInternal(command, operatorId, false);
    }

    public synchronized MsdkControlCommand sendTrusted(
            MsdkControlCommand command, String operatorId) throws IOException {
        return sendInternal(command, operatorId, true);
    }

    private MsdkControlCommand sendInternal(
            MsdkControlCommand command,
            String operatorId,
            boolean preserveInternalRequestId) throws IOException {
        validateAndComplete(command, operatorId, preserveInternalRequestId);
        ConcurrentWebSocketSessionDecorator current = session;
        if (current == null || !current.isOpen()) {
            makeReleaseRetryable(command);
            throw new IllegalStateException("MSDK Android client is offline.");
        }
        MsdkControlEvent pending = createResult(
                command.getRequestId(), "PENDING", "Waiting for MSDK client acknowledgement.");
        rememberResult(command.getRequestId(), pending);
        pendingCommands.put(command.getRequestId(), command);
        if (StringUtils.hasText(operatorId)) {
            commandOwners.put(command.getRequestId(), operatorId);
        }
        if (auditService != null) {
            auditService.commandSent(command);
        }
        try {
            current.sendMessage(new TextMessage(objectMapper.writeValueAsString(command)));
        } catch (IOException | RuntimeException exception) {
            MsdkControlEvent rejected = createResult(
                    command.getRequestId(), "REJECTED",
                    "Failed to send command: " + exception.getMessage());
            commandResults.put(command.getRequestId(), rejected);
            pendingCommands.remove(command.getRequestId());
            makeReleaseRetryable(command);
            failMissionAttempt(command, rejected.getMessage());
            if (auditService != null) {
                auditService.resultReceived(rejected);
            }
            throw exception;
        }
        timeoutExecutor.schedule(
                () -> markTimedOut(command.getRequestId()),
                timeoutFor(command),
                TimeUnit.MILLISECONDS);
        return command;
    }

    public MsdkControlStatus status() {
        return statusInternal(null, true);
    }

    public MsdkControlStatus status(String operatorId) {
        return statusInternal(operatorId, false);
    }

    private synchronized MsdkControlStatus statusInternal(
            String operatorId, boolean exposeForInternalUse) {
        // Make polling clients observe an expired browser control session immediately.
        expireInactiveSession();
        ConcurrentWebSocketSessionDecorator current = session;
        boolean isConnected = current != null && current.isOpen();
        SocketAddress remoteAddress = isConnected ? current.getRemoteAddress() : null;
        long now = System.currentTimeMillis();
        Long telemetryAgeMs = telemetryReceivedAt == null
                ? null : Math.max(0L, now - telemetryReceivedAt);
        Boolean dryRun = lastEvent != null && lastEvent.getDryRun() != null
                ? lastEvent.getDryRun()
                : telemetry == null ? null : telemetry.getDryRun();
        MsdkControlSession active = controlSession;
        boolean owned = active != null && (exposeForInternalUse
                || (StringUtils.hasText(operatorId) && operatorId.equals(active.getOwnerId())));
        MsdkControlSession visibleSession = active == null ? null : MsdkControlSession.builder()
                .id(owned ? active.getId() : null)
                .acquiredAt(active.getAcquiredAt())
                .lastHeartbeatAt(owned ? active.getLastHeartbeatAt() : null)
                .nextSequence(owned ? active.getNextSequence() : null)
                .releasing(owned ? active.getReleasing() : null)
                .build();
        return MsdkControlStatus.builder()
                .connected(isConnected)
                .sessionId(isConnected ? current.getId() : null)
                .remoteAddress(remoteAddress == null ? null : remoteAddress.toString())
                .connectedAt(connectedAt)
                .lastSeenAt(lastSeenAt)
                .lastEvent(lastEvent)
                .telemetry(telemetry)
                .aircraftConnected(aircraftConnected)
                .controlEnabled(controlEnabled)
                .controlSession(visibleSession)
                .controlSessionOwned(active == null ? null : owned)
                .telemetryAgeMs(telemetryAgeMs)
                .telemetryFresh(telemetryAgeMs != null && telemetryAgeMs <= TELEMETRY_FRESH_MS)
                .dryRun(dryRun)
                .build();
    }

    public synchronized MsdkControlSession acquireSession() {
        return acquireSession("test-operator");
    }

    public synchronized MsdkControlSession acquireSession(String operatorId) {
        if (!StringUtils.hasText(operatorId)) {
            throw new IllegalStateException("An authenticated operator is required.");
        }
        ConcurrentWebSocketSessionDecorator current = session;
        if (current == null || !current.isOpen()) {
            throw new IllegalStateException("MSDK Android client is offline.");
        }
        expireInactiveSession();
        if (controlSession != null) {
            throw new IllegalStateException("Another operator already owns the control session.");
        }
        long now = System.currentTimeMillis();
        controlSession = MsdkControlSession.builder()
                .id(UUID.randomUUID().toString())
                .acquiredAt(now)
                .lastHeartbeatAt(now)
                .nextSequence(1L)
                .releasing(false)
                .ownerId(operatorId)
                .build();
        return controlSession;
    }

    public synchronized void releaseSession(String sessionId) throws IOException {
        releaseSession(sessionId, null);
    }

    public synchronized void releaseSession(String sessionId, String operatorId) throws IOException {
        MsdkControlCommand command = new MsdkControlCommand();
        command.setType("DISABLE_CONTROL");
        command.setControlSessionId(sessionId);
        send(command, operatorId);
    }

    public MsdkControlEvent commandResult(String requestId) {
        return commandResults.get(requestId);
    }

    public MsdkControlEvent commandResult(String requestId, String operatorId) {
        String owner = commandOwners.get(requestId);
        if (!StringUtils.hasText(owner) || !owner.equals(operatorId)) {
            return null;
        }
        return commandResults.get(requestId);
    }

    private synchronized void markTimedOut(String requestId) {
        MsdkControlCommand command = pendingCommands.remove(requestId);
        if (command == null) {
            return;
        }
        MsdkControlEvent timedOut = createResult(requestId, "TIMEOUT",
                "No acknowledgement received within " + timeoutFor(command) + " ms.");
        commandResults.put(requestId, timedOut);
        makeReleaseRetryable(command);
        failMissionAttempt(command, timedOut.getMessage());
        if (auditService != null) {
            auditService.resultReceived(timedOut);
        }
    }

    private synchronized void interruptPendingCommands(String message) {
        pendingCommands.forEach((requestId, command) -> {
            if (pendingCommands.remove(requestId, command)) {
                MsdkControlEvent interrupted = createResult(requestId, "INTERRUPTED", message);
                commandResults.put(requestId, interrupted);
                makeReleaseRetryable(command);
                failMissionAttempt(command, message);
                if (auditService != null) {
                    auditService.resultReceived(interrupted);
                }
            }
        });
    }

    private MsdkControlEvent createResult(String requestId, String status, String message) {
        MsdkControlEvent event = new MsdkControlEvent();
        event.setVersion(1);
        event.setType("COMMAND_ACK");
        event.setRequestId(requestId);
        event.setTimestamp(System.currentTimeMillis());
        event.setStatus(status);
        event.setMessage(message);
        return event;
    }

    private boolean isCorrelatedResult(
            MsdkControlCommand command, MsdkControlEvent event) {
        if (!Objects.equals(command.getRequestId(), event.getRequestId())) {
            return false;
        }
        if (command.getType().startsWith("MISSION_")) {
            if ("MISSION_STATE".equals(event.getType())) {
                return Objects.equals(command.getTaskId(), event.getTaskId())
                        && isAllowedMissionResult(command.getType(), event.getStatus());
            }
            if ("COMMAND_ACK".equals(event.getType())) {
                return ("ACCEPTED".equalsIgnoreCase(event.getStatus())
                        || "REJECTED".equalsIgnoreCase(event.getStatus()))
                        && (!StringUtils.hasText(event.getTaskId())
                        || Objects.equals(command.getTaskId(), event.getTaskId()));
            }
            return "COMMAND_REJECTED".equals(event.getType())
                    && ("ERROR".equalsIgnoreCase(event.getStatus())
                    || "REJECTED".equalsIgnoreCase(event.getStatus()))
                    && (!StringUtils.hasText(event.getTaskId())
                    || Objects.equals(command.getTaskId(), event.getTaskId()));
        }
        if (!"COMMAND_ACK".equals(event.getType())
                || (!"ACCEPTED".equalsIgnoreCase(event.getStatus())
                && !"REJECTED".equalsIgnoreCase(event.getStatus()))) {
            return false;
        }
        if (CONTROL_SESSION_COMMANDS.contains(command.getType())) {
            return Objects.equals(command.getControlSessionId(), event.getControlSessionId())
                    && Objects.equals(command.getSequence(), event.getSequence());
        }
        return true;
    }

    private boolean isTerminalResult(
            MsdkControlCommand command, MsdkControlEvent event) {
        if (!command.getType().startsWith("MISSION_")) {
            return true;
        }
        if ("COMMAND_ACK".equals(event.getType())) {
            return "REJECTED".equalsIgnoreCase(event.getStatus());
        }
        String status = event.getStatus().toUpperCase(Locale.ROOT);
        if ("MISSION_PREPARE".equals(command.getType())) {
            return "READY".equals(status) || "FAILED".equals(status);
        }
        return true;
    }

    /**
     * A failed mission action means that the requested operation was rejected;
     * it does not mean that the aircraft's mission itself entered FAILED.
     * Preparation is different: a failed preparation really does make the
     * prepared artifact unusable and remains a mission-state failure.
     */
    private MsdkControlEvent normalizeCorrelatedResult(
            MsdkControlCommand command, MsdkControlEvent event) {
        if (!command.getType().startsWith("MISSION_")) {
            return event;
        }
        boolean actionFailure = !"MISSION_PREPARE".equals(command.getType())
                && "MISSION_STATE".equals(event.getType())
                && "FAILED".equalsIgnoreCase(event.getStatus());
        boolean explicitRejection = "COMMAND_REJECTED".equals(event.getType());
        if (actionFailure || explicitRejection) {
            event.setType("COMMAND_ACK");
            event.setStatus("REJECTED");
        }
        if ("COMMAND_ACK".equals(event.getType())
                && !StringUtils.hasText(event.getTaskId())) {
            event.setTaskId(command.getTaskId());
        }
        return event;
    }

    private void applyMissionCommandResult(
            MsdkControlCommand command, MsdkControlEvent event) {
        if ("COMMAND_ACK".equals(event.getType())) {
            if ("REJECTED".equalsIgnoreCase(event.getStatus())) {
                missionService.failAttempt(
                        command.getTaskId(),
                        command.getRequestId(),
                        event.getMessage(),
                        "MISSION_PREPARE".equals(command.getType()));
            }
            return;
        }
        missionService.applyClientEvent(event);
    }

    private long timeoutFor(MsdkControlCommand command) {
        return "MISSION_PREPARE".equals(command.getType())
                ? Math.max(commandTimeoutMs, MINIMUM_MISSION_PREPARE_TIMEOUT_MS)
                : commandTimeoutMs;
    }

    private boolean isAllowedMissionResult(String commandType, String status) {
        if (!StringUtils.hasText(status)) {
            return false;
        }
        String normalized = status.toUpperCase(Locale.ROOT);
        switch (commandType) {
            case "MISSION_PREPARE":
                return Arrays.asList(
                        "DOWNLOADING", "UPLOADING_TO_AIRCRAFT", "READY", "FAILED")
                        .contains(normalized);
            case "MISSION_START":
            case "MISSION_RESUME":
                return Arrays.asList("EXECUTING", "FAILED").contains(normalized);
            case "MISSION_PAUSE":
                return Arrays.asList("PAUSED", "FAILED").contains(normalized);
            case "MISSION_STOP":
                return Arrays.asList("INTERRUPTED", "FINISHED", "FAILED").contains(normalized);
            default:
                return false;
        }
    }

    private boolean isReleaseCommand(MsdkControlCommand command) {
        return command != null && Arrays.asList(
                "DISABLE_CONTROL", "SAFETY_RELEASE", "EMERGENCY_STOP")
                .contains(command.getType());
    }

    private void makeReleaseRetryable(MsdkControlCommand command) {
        if (!isReleaseCommand(command)) {
            return;
        }
        MsdkControlSession active = controlSession;
        if (active != null && active.getId().equals(command.getControlSessionId())) {
            active.setReleasing(false);
        }
    }

    private void failMissionAttempt(MsdkControlCommand command, String message) {
        if (missionService == null || command == null
                || !command.getType().startsWith("MISSION_")) {
            return;
        }
        missionService.failAttempt(
                command.getTaskId(),
                command.getRequestId(),
                message,
                "MISSION_PREPARE".equals(command.getType()));
    }

    private void validateAndComplete(
            MsdkControlCommand command,
            String operatorId,
            boolean preserveInternalRequestId) {
        if (command == null || !StringUtils.hasText(command.getType())) {
            throw new IllegalArgumentException("Command type is required.");
        }
        command.setType(command.getType().trim().toUpperCase(Locale.ROOT));
        if (!ALLOWED_COMMANDS.contains(command.getType())) {
            throw new IllegalArgumentException("Unsupported command type: " + command.getType());
        }
        // Browser request ids are never trusted. Internal mission dispatch may
        // reserve an id first so its attempt can be claimed atomically.
        if (!preserveInternalRequestId || !StringUtils.hasText(command.getRequestId())) {
            command.setRequestId(UUID.randomUUID().toString());
        } else {
            try {
                UUID.fromString(command.getRequestId());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Internal requestId must be a UUID.");
            }
            if (commandResults.containsKey(command.getRequestId())) {
                throw new IllegalStateException("Internal requestId is already in use.");
            }
        }
        command.setVersion(1);
        command.setTimestamp(System.currentTimeMillis());

        if ("STICK".equals(command.getType())) {
            validateStick(command.getPayload());
        }
        if ("ENABLE_CONTROL".equals(command.getType()) || "STICK".equals(command.getType())) {
            if (!Boolean.TRUE.equals(aircraftConnected)) {
                throw new IllegalStateException("Aircraft is offline.");
            }
            if (telemetryReceivedAt == null
                    || System.currentTimeMillis() - telemetryReceivedAt > TELEMETRY_FRESH_MS) {
                throw new IllegalStateException("Aircraft telemetry is stale.");
            }
            Integer batteryPercent = telemetry == null ? null : telemetry.getBatteryPercent();
            if (batteryPercent == null) {
                throw new IllegalStateException("Aircraft battery telemetry is unavailable.");
            }
            if (batteryPercent <= LOW_BATTERY_PERCENT) {
                throw new IllegalStateException(
                        "Aircraft battery is too low for remote control.");
            }
        }
        if ("STICK".equals(command.getType()) && !Boolean.TRUE.equals(controlEnabled)) {
            throw new IllegalStateException("Virtual-stick control is not enabled.");
        }
        if (CONTROL_SESSION_COMMANDS.contains(command.getType())) {
            requireActiveSession(command.getControlSessionId(), operatorId);
            if (Boolean.TRUE.equals(controlSession.getReleasing())) {
                throw new IllegalStateException("The control session is being released.");
            }
            if (isReleaseCommand(command)) {
                controlSession.setReleasing(true);
            }
        }
        MsdkControlSession active = controlSession;
        if (CONTROL_SESSION_COMMANDS.contains(command.getType())
                && active != null && active.getId().equals(command.getControlSessionId())) {
            command.setSequence(active.getNextSequence());
            active.setNextSequence(active.getNextSequence() + 1);
            active.setLastHeartbeatAt(System.currentTimeMillis());
        }
    }

    private synchronized void requireActiveSession(String sessionId, String operatorId) {
        expireInactiveSession();
        if (controlSession == null || !StringUtils.hasText(sessionId)
                || !controlSession.getId().equals(sessionId)) {
            throw new IllegalStateException("A valid control session is required.");
        }
        if (StringUtils.hasText(operatorId)
                && !operatorId.equals(controlSession.getOwnerId())) {
            throw new IllegalStateException("The control session belongs to another operator.");
        }
    }

    private synchronized void expireInactiveSession() {
        MsdkControlSession expired = controlSession;
        if (expired == null) {
            return;
        }
        if (System.currentTimeMillis() - expired.getLastHeartbeatAt()
                > controlSessionTimeoutMs) {
            controlSession = null;
            controlEnabled = null;
            interruptPendingCommands("Control session expired.");
            sendSafetyRelease(expired, "Browser control lease expired.");
        }
    }

    /**
     * Lease expiry must be fail-safe: do not merely forget the browser session.
     * Tell the RC client to center the sticks and disable Virtual Stick as well.
     * This deliberately bypasses normal lease validation because the lease is
     * the condition that has just expired.
     */
    private void sendSafetyRelease(MsdkControlSession expired, String reason) {
        MsdkControlCommand command = new MsdkControlCommand();
        command.setVersion(1);
        command.setRequestId(UUID.randomUUID().toString());
        command.setType("SAFETY_RELEASE");
        command.setControlSessionId(expired.getId());
        command.setSequence(expired.getNextSequence());
        command.setTimestamp(System.currentTimeMillis());
        MsdkControlEvent pending = createResult(
                command.getRequestId(), "PENDING",
                "Waiting for MSDK safety-release acknowledgement.");
        rememberResult(command.getRequestId(), pending);
        pendingCommands.put(command.getRequestId(), command);
        if (auditService != null) {
            auditService.commandSent(command);
        }
        ConcurrentWebSocketSessionDecorator current = session;
        if (current == null || !current.isOpen()) {
            MsdkControlEvent rejected = createResult(
                    command.getRequestId(), "REJECTED",
                    "MSDK client was offline during safety release.");
            commandResults.put(command.getRequestId(), rejected);
            pendingCommands.remove(command.getRequestId());
            if (auditService != null) {
                auditService.resultReceived(rejected);
            }
            log.error("MSDK safety release could not be sent because the client is offline. "
                    + "reason={}", reason);
            return;
        }
        try {
            current.sendMessage(new TextMessage(objectMapper.writeValueAsString(command)));
            timeoutExecutor.schedule(
                    () -> markTimedOut(command.getRequestId()),
                    commandTimeoutMs,
                    TimeUnit.MILLISECONDS);
            log.warn("MSDK safety release sent. reason={}, controlSession={}",
                    reason, expired.getId());
        } catch (IOException | RuntimeException exception) {
            MsdkControlEvent rejected = createResult(
                    command.getRequestId(), "REJECTED",
                    "Failed to send safety release: " + exception.getMessage());
            commandResults.put(command.getRequestId(), rejected);
            pendingCommands.remove(command.getRequestId());
            if (auditService != null) {
                auditService.resultReceived(rejected);
            }
            log.error("Failed to send MSDK safety release. reason={}", reason, exception);
        }
    }

    private void validateStick(MsdkStickPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("STICK command requires payload.");
        }
        Integer[] values = {
                payload.getLeftHorizontal(),
                payload.getLeftVertical(),
                payload.getRightHorizontal(),
                payload.getRightVertical()
        };
        for (Integer value : values) {
            if (value == null || value < STICK_MIN || value > STICK_MAX) {
                throw new IllegalArgumentException(
                        "Stick values must be between " + STICK_MIN + " and " + STICK_MAX + ".");
            }
        }
    }

    private void rememberResult(String requestId, MsdkControlEvent result) {
        if (commandResults.put(requestId, result) == null) {
            commandResultOrder.add(requestId);
        }
        while (commandResults.size() > MAX_COMMAND_RESULTS) {
            String oldest = commandResultOrder.poll();
            if (oldest == null) {
                break;
            }
            commandResults.remove(oldest);
            pendingCommands.remove(oldest);
            commandOwners.remove(oldest);
        }
    }

    private synchronized void applyAcknowledgedCommand(
            MsdkControlCommand command, MsdkControlEvent event) {
        if (command == null) {
            return;
        }
        boolean accepted = "ACCEPTED".equalsIgnoreCase(event.getStatus());
        if (isReleaseCommand(command)) {
            if (accepted) {
                controlEnabled = false;
                MsdkControlSession active = controlSession;
                if (active != null && active.getId().equals(command.getControlSessionId())) {
                    controlSession = null;
                }
            } else {
                makeReleaseRetryable(command);
            }
            return;
        }
        if (!accepted) {
            return;
        }
        if ("ENABLE_CONTROL".equals(command.getType())) {
            controlEnabled = true;
            return;
        }
    }

    @PreDestroy
    public void shutdown() {
        timeoutExecutor.shutdownNow();
    }
}

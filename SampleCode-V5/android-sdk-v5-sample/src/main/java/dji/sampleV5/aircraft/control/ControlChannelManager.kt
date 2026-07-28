package dji.sampleV5.aircraft.control

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import dji.sampleV5.aircraft.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Maintains the WebSocket connection from RC Pro to the Java backend.
 *
 * This first implementation is intentionally transport-only. With
 * CONTROL_DRY_RUN=true every valid command is acknowledged and logged without
 * invoking MSDK or changing aircraft state.
 */
object ControlChannelManager {

    private const val TAG = "ControlChannel"
    private const val RECONNECT_DELAY_MS = 3_000L
    private const val NORMAL_CLOSE_CODE = 1000

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var running = false
    @Volatile
    private var appInForeground = false
    private var socket: WebSocket? = null
    private var activeControlSessionId: String? = null
    private var lastSequence = 0L

    private val reconnectTask = Runnable {
        if (running && appInForeground && socket == null) connect()
    }

    fun start(): Boolean {
        if (running) return true
        val configurationError = configurationError()
        if (configurationError != null) {
            Log.e(TAG, configurationError)
            return false
        }
        running = true
        MsdkFlightControlAdapter.start({ connected ->
            runOnMain { sendAircraftConnection(connected) }
        }, { reason ->
            runOnMain {
                activeControlSessionId = null
                lastSequence = 0
                sendEvent(ControlEvent(
                    type = "CONTROL_INTERRUPTED",
                    status = "INTERRUPTED",
                    message = reason,
                    dryRun = BuildConfig.CONTROL_DRY_RUN,
                    aircraftConnected = MsdkFlightControlAdapter.isAircraftConnected(),
                    controlEnabled = MsdkFlightControlAdapter.isControlEnabled()
                ))
            }
        })
        MsdkMissionAdapter.start { telemetry ->
            runOnMain {
                sendEvent(ControlEvent(
                    type = "MISSION_TELEMETRY",
                    status = telemetry.status,
                    message = telemetry.message,
                    dryRun = BuildConfig.CONTROL_DRY_RUN,
                    taskId = telemetry.taskId,
                    waylineId = telemetry.waylineId,
                    waypointIndex = telemetry.waypointIndex
                ))
            }
        }
        MsdkTelemetryAdapter.start { telemetry ->
            runOnMain { sendTelemetry(telemetry) }
        }
        connect()
        return true
    }

    fun stop() {
        running = false
        appInForeground = false
        mainHandler.removeCallbacks(reconnectTask)
        MsdkFlightControlAdapter.stop()
        MsdkMissionAdapter.stop()
        MsdkTelemetryAdapter.stop()
        socket?.close(NORMAL_CLOSE_CODE, "application stopped")
        socket = null
    }

    fun onAppBackgrounded() {
        appInForeground = false
        activeControlSessionId = null
        lastSequence = 0
        MsdkFlightControlAdapter.interrupt("application entered background")
        MsdkMissionAdapter.cancelPendingPreparation()
        sendEvent(ControlEvent(
            type = "CONTROL_INTERRUPTED",
            status = "INTERRUPTED",
            message = "RC application entered background",
            dryRun = BuildConfig.CONTROL_DRY_RUN,
            controlEnabled = MsdkFlightControlAdapter.isControlEnabled()
        ))
        val current = socket
        socket = null
        current?.close(NORMAL_CLOSE_CODE, "application backgrounded")
    }

    fun onAppForegrounded() {
        if (!running) return
        appInForeground = true
        connect()
    }

    private fun connect() {
        if (!running || !appInForeground || socket != null) return

        val requestBuilder = try {
            Request.Builder().url(BuildConfig.CONTROL_WS_URL)
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Invalid CONTROL_WS_URL", error)
            return
        }
        requestBuilder.header("Authorization", "Bearer ${BuildConfig.CONTROL_AUTH_TOKEN}")
        socket = client.newWebSocket(requestBuilder.build(), Listener())
        Log.i(TAG, "Connecting to ${BuildConfig.CONTROL_WS_URL}; dryRun=${BuildConfig.CONTROL_DRY_RUN}")
    }

    private fun scheduleReconnect() {
        socket = null
        mainHandler.removeCallbacks(reconnectTask)
        if (running && appInForeground) {
            mainHandler.postDelayed(reconnectTask, RECONNECT_DELAY_MS)
        }
    }

    private fun sendEvent(event: ControlEvent) {
        socket?.send(gson.toJson(event))
    }

    private fun handleMessage(text: String) {
        val command = runCatching { gson.fromJson(text, ControlCommand::class.java) }.getOrNull()
        if (command == null) {
            sendEvent(ControlEvent(
                    type = "COMMAND_REJECTED",
                    status = "ERROR",
                    message = "invalid JSON",
                    dryRun = BuildConfig.CONTROL_DRY_RUN
            ))
            return
        }

        val validationError = ControlCommandValidator.validate(command)
        if (validationError != null) {
            sendEvent(ControlEvent(
                type = "COMMAND_ACK",
                requestId = command.requestId.takeUnless { it.isNullOrBlank() },
                controlSessionId = command.controlSessionId,
                sequence = command.sequence,
                status = "REJECTED",
                message = validationError,
                dryRun = BuildConfig.CONTROL_DRY_RUN,
                taskId = command.taskId
            ))
            return
        }

        if (command.type.startsWith("MISSION_")) {
            if (command.type == ControlCommandType.MISSION_START.name &&
                !BuildConfig.CONTROL_DRY_RUN) {
                val safetyBlock = MsdkFlightControlAdapter.safetyBlockReason()
                if (safetyBlock != null) {
                    sendEvent(ControlEvent(
                        type = "COMMAND_ACK",
                        requestId = command.requestId,
                        status = "REJECTED",
                        message = safetyBlock,
                        dryRun = false,
                        taskId = command.taskId
                    ))
                    return
                }
            }
            MsdkMissionAdapter.execute(command) { status, detail ->
                runOnMain {
                    sendEvent(ControlEvent(
                        type = if (status == "REJECTED") "COMMAND_ACK" else "MISSION_STATE",
                        requestId = command.requestId,
                        status = status,
                        message = detail,
                        dryRun = BuildConfig.CONTROL_DRY_RUN,
                        taskId = command.taskId
                    ))
                }
            }
            return
        }

        if (command.type != ControlCommandType.HEARTBEAT.name) {
            val incomingSession = command.controlSessionId!!
            if (activeControlSessionId != null && activeControlSessionId != incomingSession) {
                sendCommandResult(command, false, "another control session is active")
                return
            }
            val incomingSequence = command.sequence!!
            if (incomingSequence <= lastSequence && activeControlSessionId == incomingSession) {
                sendCommandResult(command, false, "duplicate or out-of-order command")
                return
            }
            if (command.type == ControlCommandType.ENABLE_CONTROL.name) {
                activeControlSessionId = incomingSession
                lastSequence = 0
            }
            lastSequence = incomingSequence
        }

        if (BuildConfig.CONTROL_DRY_RUN) {
            Log.i(TAG, "Accepted dry-run command ${command.type}, requestId=${command.requestId}")
            if (command.type == ControlCommandType.DISABLE_CONTROL.name
                || command.type == ControlCommandType.SAFETY_RELEASE.name
                || command.type == ControlCommandType.EMERGENCY_STOP.name) {
                activeControlSessionId = null
                lastSequence = 0
            }
            sendCommandResult(command, true, "validated in dry-run mode")
            return
        }

        MsdkFlightControlAdapter.execute(command) { accepted, detail ->
            runOnMain {
                val callbackOwnsCurrentSession =
                    activeControlSessionId == command.controlSessionId
                if (!accepted &&
                    command.type == ControlCommandType.ENABLE_CONTROL.name &&
                    callbackOwnsCurrentSession) {
                    activeControlSessionId = null
                    lastSequence = 0
                } else if (accepted && callbackOwnsCurrentSession &&
                    (command.type == ControlCommandType.DISABLE_CONTROL.name
                            || command.type == ControlCommandType.SAFETY_RELEASE.name
                            || command.type == ControlCommandType.EMERGENCY_STOP.name)) {
                    activeControlSessionId = null
                    lastSequence = 0
                }
                sendCommandResult(command, accepted, detail)
            }
        }
    }

    private fun sendCommandResult(command: ControlCommand, accepted: Boolean, detail: String) {
        sendEvent(ControlEvent(
            type = "COMMAND_ACK",
            requestId = command.requestId,
            controlSessionId = command.controlSessionId,
            sequence = command.sequence,
            status = if (accepted) "ACCEPTED" else "REJECTED",
            message = detail,
            dryRun = BuildConfig.CONTROL_DRY_RUN,
            controlEnabled = when {
                !accepted -> null
                command.type == ControlCommandType.ENABLE_CONTROL.name -> true
                command.type == ControlCommandType.DISABLE_CONTROL.name ||
                    command.type == ControlCommandType.SAFETY_RELEASE.name ||
                    command.type == ControlCommandType.EMERGENCY_STOP.name -> false
                else -> null
            }
        ))
    }

    private class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            mainHandler.post {
                if (socket !== webSocket) return@post
                Log.i(TAG, "WebSocket connected")
                sendEvent(ControlEvent(
                    type = "CLIENT_HELLO",
                    status = "ONLINE",
                    message = "MSDK Android bridge connected",
                    dryRun = BuildConfig.CONTROL_DRY_RUN,
                    aircraftConnected = MsdkFlightControlAdapter.isAircraftConnected(),
                    controlEnabled = MsdkFlightControlAdapter.isControlEnabled()
                ))
                sendAircraftConnection(MsdkFlightControlAdapter.isAircraftConnected())
                sendTelemetry(MsdkTelemetryAdapter.currentTelemetry())
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            mainHandler.post {
                if (socket === webSocket && running && appInForeground) {
                    handleMessage(text)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            mainHandler.post {
                if (socket !== webSocket) return@post
                Log.i(TAG, "WebSocket closed: $code $reason")
                socket = null
                activeControlSessionId = null
                lastSequence = 0
                MsdkFlightControlAdapter.interrupt("control network disconnected")
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            response?.close()
            mainHandler.post {
                if (socket !== webSocket) return@post
                Log.w(TAG, "WebSocket failure", t)
                socket = null
                activeControlSessionId = null
                lastSequence = 0
                MsdkFlightControlAdapter.interrupt("control network failure")
                scheduleReconnect()
            }
        }
    }

    private fun sendAircraftConnection(connected: Boolean) {
        if (!connected) {
            MsdkTelemetryAdapter.clear()
        }
        sendEvent(ControlEvent(
            type = "AIRCRAFT_CONNECTION",
            status = if (connected) "ONLINE" else "OFFLINE",
            message = if (connected) {
                "Aircraft flight controller connected"
            } else {
                "Aircraft flight controller disconnected"
            },
            dryRun = BuildConfig.CONTROL_DRY_RUN,
            aircraftConnected = connected,
            controlEnabled = MsdkFlightControlAdapter.isControlEnabled()
        ))
    }

    private fun sendTelemetry(telemetry: AircraftTelemetry) {
        sendEvent(ControlEvent(
            type = "AIRCRAFT_TELEMETRY",
            status = "UPDATED",
            message = "Aircraft telemetry updated",
            dryRun = BuildConfig.CONTROL_DRY_RUN,
            aircraftConnected = MsdkFlightControlAdapter.isAircraftConnected(),
            controlEnabled = MsdkFlightControlAdapter.isControlEnabled(),
            latitude = telemetry.latitude,
            longitude = telemetry.longitude,
            altitude = telemetry.altitude,
            velocityX = telemetry.velocityX,
            velocityY = telemetry.velocityY,
            velocityZ = telemetry.velocityZ,
            roll = telemetry.roll,
            pitch = telemetry.pitch,
            yaw = telemetry.yaw,
            batteryPercent = telemetry.batteryPercent,
            flightMode = telemetry.flightMode,
            motorsOn = telemetry.motorsOn,
            gpsSatelliteCount = telemetry.gpsSatelliteCount
        ))
    }

    private fun configurationError(): String? {
        if (BuildConfig.CONTROL_AUTH_TOKEN.length < 32) {
            return "MSDK_CONTROL_AUTH_TOKEN must contain at least 32 characters"
        }
        val url = BuildConfig.CONTROL_WS_URL.lowercase()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            return "MSDK_CONTROL_WS_URL must use ws:// or wss://"
        }
        if (!BuildConfig.CONTROL_DRY_RUN && !url.startsWith("wss://")) {
            return "Live control refuses cleartext: MSDK_CONTROL_WS_URL must use wss://"
        }
        return null
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}

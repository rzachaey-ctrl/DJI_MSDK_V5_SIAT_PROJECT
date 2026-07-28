package dji.sampleV5.aircraft.control

import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.flightcontroller.FlightControlAuthorityChangeReason
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import dji.v5.manager.interfaces.IVirtualStickManager

/**
 * Small safety boundary around DJI MSDK V5 virtual-stick APIs.
 *
 * Network code never talks to VirtualStickManager directly. This adapter owns
 * control-authority state and automatically centers all axes if stick packets
 * stop arriving.
 */
object MsdkFlightControlAdapter {

    private const val TAG = "MsdkFlightControl"
    private const val STICK_WATCHDOG_MS = 300L
    private const val INITIAL_STICK_WATCHDOG_MS = 1_500L
    private const val LOW_BATTERY_PERCENT = 20
    private const val MAX_SAFETY_DISABLE_ATTEMPTS = 3

    private val mainHandler = Handler(Looper.getMainLooper())
    private val manager: IVirtualStickManager
        get() = VirtualStickManager.getInstance()
    private val stateLock = Any()

    @Volatile
    private var controlEnabled = false
    @Volatile
    private var batteryPercent: Int? = null
    @Volatile
    private var managerReportsEnabled = false
    @Volatile
    private var releasePending = false
    @Volatile
    private var aircraftConnected = false
    @Volatile
    private var connectionGeneration = 0L
    @Volatile
    private var batteryConnectionGeneration = -1L
    private var controlDesired = false
    private var operationGeneration = 0L
    private var connectionListener: ((Boolean) -> Unit)? = null
    private var safetyEventListener: ((String) -> Unit)? = null

    private val stickWatchdogTask = Runnable {
        val shouldRelease = synchronized(stateLock) {
            controlDesired || controlEnabled
        }
        if (shouldRelease) {
            interrupt("stick command watchdog expired")
            safetyEventListener?.invoke(
                "Stick command watchdog expired; remote control was released"
            )
        }
    }

    private val virtualStickStateListener = object : VirtualStickStateListener {
        override fun onVirtualStickStateUpdate(stickState: VirtualStickState) {
            managerReportsEnabled = stickState.isVirtualStickEnable
            if (!stickState.isVirtualStickEnable && !controlDesired) {
                releasePending = false
            }
            if (controlEnabled && !stickState.isVirtualStickEnable) {
                invalidateControlIntent()
                centerSticks()
                safetyEventListener?.invoke("Physical pilot or flight controller took control")
            }
        }

        override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
            // MSDK_REQUEST means MSDK obtained authority; it is not a loss event.
            if (controlEnabled &&
                reason != FlightControlAuthorityChangeReason.UNKNOWN &&
                reason != FlightControlAuthorityChangeReason.MSDK_REQUEST) {
                interrupt("flight-control authority changed: $reason")
                safetyEventListener?.invoke("Flight-control authority changed: $reason")
            }
        }
    }

    fun start(onConnectionChanged: (Boolean) -> Unit, onSafetyEvent: (String) -> Unit) {
        connectionListener = onConnectionChanged
        safetyEventListener = onSafetyEvent
        synchronized(stateLock) {
            aircraftConnected = FlightControllerKey.KeyConnection.create().get(false)
            connectionGeneration++
            batteryPercent = null
            batteryConnectionGeneration = -1L
        }
        FlightControllerKey.KeyConnection.create().listen(this) { connected ->
            val online = connected == true
            synchronized(stateLock) {
                if (aircraftConnected != online) {
                    connectionGeneration++
                    batteryPercent = null
                    batteryConnectionGeneration = -1L
                }
                aircraftConnected = online
            }
            if (!online) {
                val wasControlling = synchronized(stateLock) {
                    controlDesired || controlEnabled
                }
                interrupt("aircraft flight controller disconnected")
                if (wasControlling) {
                    safetyEventListener?.invoke(
                        "Aircraft disconnected; remote control was released"
                    )
                }
            }
            connectionListener?.invoke(online)
        }
        KeyTools.createKey(
            BatteryKey.KeyChargeRemainingInPercent,
            ComponentIndexType.LEFT_OR_MAIN
        ).listen(this) { percent ->
            synchronized(stateLock) {
                if (aircraftConnected && percent != null) {
                    batteryPercent = percent
                    batteryConnectionGeneration = connectionGeneration
                } else {
                    batteryPercent = null
                    batteryConnectionGeneration = -1L
                }
            }
            if (controlEnabled && percent != null && percent <= LOW_BATTERY_PERCENT) {
                interrupt("low battery: $percent%")
                safetyEventListener?.invoke("Low battery ($percent%) interrupted remote control")
            }
        }
        manager.setVirtualStickStateListener(virtualStickStateListener)
        onConnectionChanged(isAircraftConnected())
    }

    fun stop() {
        interrupt("flight-control adapter stopped")
        KeyManager.getInstance().cancelListen(this)
        manager.removeVirtualStickStateListener(virtualStickStateListener)
        connectionListener = null
        safetyEventListener = null
        batteryPercent = null
        batteryConnectionGeneration = -1L
        aircraftConnected = false
    }

    fun interrupt(reason: String) {
        mainHandler.removeCallbacks(stickWatchdogTask)
        val release = synchronized(stateLock) {
            val activeOrPending =
                controlDesired || controlEnabled || managerReportsEnabled || releasePending
            operationGeneration++
            controlDesired = false
            if (activeOrPending) releasePending = true
            Pair(activeOrPending, operationGeneration)
        }
        // Publish the release state before centering. Any concurrent STICK
        // handler will now reject instead of writing non-zero values after us.
        centerSticks()
        if (release.first) {
            requestSafetyDisable(reason, release.second, 1)
        }
    }

    fun execute(command: ControlCommand, callback: (Boolean, String) -> Unit) {
        when (ControlCommandType.valueOf(command.type)) {
            ControlCommandType.HEARTBEAT ->
                callback(true, "MSDK bridge heartbeat accepted")

            ControlCommandType.ENABLE_CONTROL ->
                enableControl(callback)

            ControlCommandType.DISABLE_CONTROL ->
                disableControl(callback)

            ControlCommandType.STICK ->
                applyStick(command.payload, callback)

            ControlCommandType.SAFETY_RELEASE,
            ControlCommandType.EMERGENCY_STOP ->
                safeStop(callback)

            else ->
                callback(false, "Command is not a virtual-stick command")
        }
    }

    private fun enableControl(callback: (Boolean, String) -> Unit) {
        val safetyBlock = safetyBlockReason()
        if (safetyBlock != null) {
            callback(false, safetyBlock)
            return
        }
        val generation = synchronized(stateLock) {
            operationGeneration++
            controlDesired = true
            controlEnabled = false
            operationGeneration
        }
        manager.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                if (safetyBlockReason() != null) {
                    invalidateControlIntent()
                    forceDisable("safety state changed while enable was pending")
                    callback(false, "Virtual-stick enable cancelled because safety state changed")
                    return
                }
                val accepted = synchronized(stateLock) {
                    if (generation == operationGeneration && controlDesired) {
                        controlEnabled = true
                        true
                    } else {
                        false
                    }
                }
                if (accepted) {
                    centerSticks()
                    mainHandler.removeCallbacks(stickWatchdogTask)
                    mainHandler.postDelayed(stickWatchdogTask, INITIAL_STICK_WATCHDOG_MS)
                    callback(true, "Virtual-stick control enabled")
                } else {
                    val shouldUndo = synchronized(stateLock) { !controlDesired }
                    if (shouldUndo) forceDisable("cancelled stale enable")
                    callback(false, "Virtual-stick enable was cancelled by a newer safety action")
                }
            }

            override fun onFailure(error: IDJIError) {
                synchronized(stateLock) {
                    if (generation == operationGeneration) {
                        controlDesired = false
                        controlEnabled = false
                    }
                }
                callback(false, "Unable to enable virtual stick: $error")
            }
        })
    }

    private fun disableControl(callback: (Boolean, String) -> Unit) {
        mainHandler.removeCallbacks(stickWatchdogTask)
        val generation = synchronized(stateLock) {
            operationGeneration++
            controlDesired = false
            releasePending = true
            operationGeneration
        }
        centerSticks()
        manager.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                synchronized(stateLock) {
                    if (generation == operationGeneration && !controlDesired) {
                        controlEnabled = false
                        releasePending = false
                    }
                }
                managerReportsEnabled = false
                callback(true, "Virtual-stick control released")
            }

            override fun onFailure(error: IDJIError) {
                callback(false, "Unable to release virtual stick: $error")
                retrySafetyDisable("explicit release", generation, 2)
            }
        })
    }

    private fun applyStick(payload: StickPayload?, callback: (Boolean, String) -> Unit) {
        if (!isAircraftConnected()) {
            interrupt("aircraft disconnected before stick command")
            safetyEventListener?.invoke(
                "Aircraft disconnected before stick command; remote control was released"
            )
            callback(false, "Aircraft disconnected before stick command")
            return
        }
        if (payload == null) {
            callback(false, "STICK payload is missing")
            return
        }

        val applied = synchronized(stateLock) {
            if (!controlDesired || !controlEnabled || releasePending) {
                false
            } else {
                // Keep the state check and four-axis write atomic with release
                // state changes. A release that races us will acquire the lock
                // next and center all axes before disabling Virtual Stick.
                manager.leftStick.horizontalPosition = payload.leftHorizontal
                manager.leftStick.verticalPosition = payload.leftVertical
                manager.rightStick.horizontalPosition = payload.rightHorizontal
                manager.rightStick.verticalPosition = payload.rightVertical
                true
            }
        }
        if (!applied) {
            callback(false, "Virtual-stick control is disabled or being released")
            return
        }

        mainHandler.removeCallbacks(stickWatchdogTask)
        mainHandler.postDelayed(stickWatchdogTask, STICK_WATCHDOG_MS)
        callback(true, "Virtual-stick values applied; watchdog armed")
    }

    /**
     * This is deliberately a safe stop, not an in-flight motor stop.
     * It centers every axis and releases virtual-stick authority.
     */
    private fun safeStop(callback: (Boolean, String) -> Unit) {
        mainHandler.removeCallbacks(stickWatchdogTask)
        val hasControl = synchronized(stateLock) {
            controlDesired || controlEnabled || managerReportsEnabled || releasePending
        }
        if (!hasControl) {
            centerSticks()
            callback(true, "Stick values centered; virtual-stick control was already disabled")
            return
        }
        // disableControl publishes releasePending before it centers the axes,
        // closing the window in which a later STICK could undo the center.
        disableControl { success, detail ->
            callback(success, if (success) "Safety stop completed" else detail)
        }
    }

    private fun centerSticks() {
        runCatching {
            manager.leftStick.horizontalPosition = 0
            manager.leftStick.verticalPosition = 0
            manager.rightStick.horizontalPosition = 0
            manager.rightStick.verticalPosition = 0
        }.onFailure {
            Log.w(TAG, "Unable to center virtual sticks", it)
        }
    }

    fun isAircraftConnected(): Boolean =
        FlightControllerKey.KeyConnection.create().get(false)

    fun isControlEnabled(): Boolean =
        controlEnabled || managerReportsEnabled || releasePending

    fun safetyBlockReason(): String? {
        if (releasePending) return "A previous virtual-stick release is still being verified"
        if (!aircraftConnected || !isAircraftConnected()) {
            return "Aircraft flight controller is offline"
        }
        val batteryState = synchronized(stateLock) {
            if (batteryConnectionGeneration == connectionGeneration) batteryPercent else null
        }
        val percent = batteryState ?: return "Fresh aircraft battery telemetry is unavailable"
        if (percent <= LOW_BATTERY_PERCENT) {
            return "Aircraft battery is too low for remote control ($percent%)"
        }
        return null
    }

    private fun invalidateControlIntent() {
        mainHandler.removeCallbacks(stickWatchdogTask)
        synchronized(stateLock) {
            operationGeneration++
            controlDesired = false
            controlEnabled = false
            releasePending = false
        }
    }

    private fun retrySafetyDisable(reason: String, generation: Long, attempt: Int) {
        mainHandler.postDelayed({
            val shouldRetry = synchronized(stateLock) {
                generation == operationGeneration && !controlDesired && releasePending
            }
            if (shouldRetry) {
                requestSafetyDisable(reason, generation, attempt)
            }
        }, 150L * attempt)
    }

    private fun forceDisable(reason: String) {
        val generation = synchronized(stateLock) {
            controlDesired = false
            releasePending = true
            operationGeneration
        }
        centerSticks()
        requestSafetyDisable(reason, generation, 1)
    }

    private fun requestSafetyDisable(reason: String, generation: Long, attempt: Int) {
        manager.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                synchronized(stateLock) {
                    if (generation == operationGeneration && !controlDesired) {
                        controlEnabled = false
                        managerReportsEnabled = false
                        releasePending = false
                    }
                }
                Log.w(TAG, "Safety disable completed: $reason (attempt $attempt)")
            }

            override fun onFailure(error: IDJIError) {
                Log.e(TAG, "Safety disable failed: $reason (attempt $attempt); $error")
                if (attempt < MAX_SAFETY_DISABLE_ATTEMPTS) {
                    retrySafetyDisable(reason, generation, attempt + 1)
                } else {
                    synchronized(stateLock) {
                        if (generation == operationGeneration && !controlDesired) {
                            releasePending = true
                        }
                    }
                    safetyEventListener?.invoke(
                        "CRITICAL: Virtual-stick release could not be confirmed after " +
                            "$MAX_SAFETY_DISABLE_ATTEMPTS attempts"
                    )
                }
            }
        })
    }
}

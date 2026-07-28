package dji.sampleV5.aircraft.control

import android.os.Handler
import android.os.Looper
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.KeyManager

/**
 * Collects frequently changing flight-controller values and publishes one
 * combined snapshot at most twice per second.
 */
object MsdkTelemetryAdapter {

    private const val EMIT_INTERVAL_MS = 500L
    private const val HEARTBEAT_INTERVAL_MS = 1_000L
    private val handler = Handler(Looper.getMainLooper())
    private var listener: ((AircraftTelemetry) -> Unit)? = null
    private var emitScheduled = false

    private var latitude: Double? = null
    private var longitude: Double? = null
    private var altitude: Double? = null
    private var velocityX: Double? = null
    private var velocityY: Double? = null
    private var velocityZ: Double? = null
    private var roll: Double? = null
    private var pitch: Double? = null
    private var yaw: Double? = null
    private var batteryPercent: Int? = null
    private var flightMode: String? = null
    private var motorsOn: Boolean? = null
    private var gpsSatelliteCount: Int? = null

    private val emitTask = Runnable {
        emitScheduled = false
        listener?.invoke(currentTelemetry())
    }
    private val heartbeatTask = object : Runnable {
        override fun run() {
            listener?.invoke(currentTelemetry())
            if (listener != null) {
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun start(onTelemetry: (AircraftTelemetry) -> Unit) {
        stop()
        listener = onTelemetry

        FlightControllerKey.KeyAircraftLocation3D.create().listen(this) { value ->
            latitude = value?.latitude
            longitude = value?.longitude
            altitude = value?.altitude
            scheduleEmit()
        }
        FlightControllerKey.KeyAircraftVelocity.create().listen(this) { value ->
            velocityX = value?.x
            velocityY = value?.y
            velocityZ = value?.z
            scheduleEmit()
        }
        FlightControllerKey.KeyAircraftAttitude.create().listen(this) { value ->
            roll = value?.roll
            pitch = value?.pitch
            yaw = value?.yaw
            scheduleEmit()
        }
        FlightControllerKey.KeyFCFlightMode.create().listen(this) { value ->
            flightMode = value?.toString()
            scheduleEmit()
        }
        FlightControllerKey.KeyAreMotorsOn.create().listen(this) { value ->
            motorsOn = value
            scheduleEmit()
        }
        FlightControllerKey.KeyGPSSatelliteCount.create().listen(this) { value ->
            gpsSatelliteCount = value
            scheduleEmit()
        }
        KeyTools.createKey(
            BatteryKey.KeyChargeRemainingInPercent,
            ComponentIndexType.LEFT_OR_MAIN
        ).listen(this) { value ->
            batteryPercent = value
            scheduleEmit()
        }
        handler.postDelayed(heartbeatTask, HEARTBEAT_INTERVAL_MS)
    }

    fun stop() {
        handler.removeCallbacks(emitTask)
        handler.removeCallbacks(heartbeatTask)
        emitScheduled = false
        KeyManager.getInstance().cancelListen(this)
        listener = null
        clear()
    }

    fun clear() {
        latitude = null
        longitude = null
        altitude = null
        velocityX = null
        velocityY = null
        velocityZ = null
        roll = null
        pitch = null
        yaw = null
        batteryPercent = null
        flightMode = null
        motorsOn = null
        gpsSatelliteCount = null
    }

    private fun scheduleEmit() {
        if (emitScheduled) return
        emitScheduled = true
        handler.postDelayed(emitTask, EMIT_INTERVAL_MS)
    }

    fun currentTelemetry() = AircraftTelemetry(
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        velocityX = velocityX,
        velocityY = velocityY,
        velocityZ = velocityZ,
        roll = roll,
        pitch = pitch,
        yaw = yaw,
        batteryPercent = batteryPercent,
        flightMode = flightMode,
        motorsOn = motorsOn,
        gpsSatelliteCount = gpsSatelliteCount
    )
}

package dji.sampleV5.aircraft.control

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

/**
 * Versioned messages exchanged with the Java backend.
 *
 * The Android client deliberately accepts only the command names listed in
 * [ControlCommandType]. Unknown, malformed, or stale messages are rejected.
 */
@Keep
data class ControlCommand(
    @SerializedName("version")
    val version: Int = 1,
    @SerializedName("request_id")
    val requestId: String = "",
    @SerializedName("control_session_id")
    val controlSessionId: String? = null,
    @SerializedName("sequence")
    val sequence: Long? = null,
    @SerializedName("type")
    val type: String = "",
    @SerializedName("timestamp")
    val timestamp: Long = 0,
    @SerializedName("payload")
    val payload: StickPayload? = null,
    @SerializedName("task_id")
    val taskId: String? = null,
    @SerializedName("download_url")
    val downloadUrl: String? = null,
    @SerializedName("mission_file_name")
    val missionFileName: String? = null
)

@Keep
data class StickPayload(
    @SerializedName("left_horizontal")
    val leftHorizontal: Int = 0,
    @SerializedName("left_vertical")
    val leftVertical: Int = 0,
    @SerializedName("right_horizontal")
    val rightHorizontal: Int = 0,
    @SerializedName("right_vertical")
    val rightVertical: Int = 0
)

@Keep
enum class ControlCommandType {
    HEARTBEAT,
    ENABLE_CONTROL,
    DISABLE_CONTROL,
    STICK,
    SAFETY_RELEASE,
    @Deprecated("Use SAFETY_RELEASE")
    EMERGENCY_STOP,
    MISSION_PREPARE,
    MISSION_START,
    MISSION_PAUSE,
    MISSION_RESUME,
    MISSION_STOP
}

@Keep
data class ControlEvent(
    @SerializedName("version")
    val version: Int = 1,
    @SerializedName("type")
    val type: String,
    @SerializedName("request_id")
    val requestId: String? = null,
    @SerializedName("control_session_id")
    val controlSessionId: String? = null,
    @SerializedName("sequence")
    val sequence: Long? = null,
    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("status")
    val status: String,
    @SerializedName("message")
    val message: String,
    @SerializedName("dry_run")
    val dryRun: Boolean,
    @SerializedName("aircraft_connected")
    val aircraftConnected: Boolean? = null,
    @SerializedName("control_enabled")
    val controlEnabled: Boolean? = null,
    @SerializedName("task_id")
    val taskId: String? = null,
    @SerializedName("wayline_id")
    val waylineId: Int? = null,
    @SerializedName("waypoint_index")
    val waypointIndex: Int? = null,
    @SerializedName("latitude")
    val latitude: Double? = null,
    @SerializedName("longitude")
    val longitude: Double? = null,
    @SerializedName("altitude")
    val altitude: Double? = null,
    @SerializedName("velocity_x")
    val velocityX: Double? = null,
    @SerializedName("velocity_y")
    val velocityY: Double? = null,
    @SerializedName("velocity_z")
    val velocityZ: Double? = null,
    @SerializedName("roll")
    val roll: Double? = null,
    @SerializedName("pitch")
    val pitch: Double? = null,
    @SerializedName("yaw")
    val yaw: Double? = null,
    @SerializedName("battery_percent")
    val batteryPercent: Int? = null,
    @SerializedName("flight_mode")
    val flightMode: String? = null,
    @SerializedName("motors_on")
    val motorsOn: Boolean? = null,
    @SerializedName("gps_satellite_count")
    val gpsSatelliteCount: Int? = null
)

data class AircraftTelemetry(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val velocityX: Double? = null,
    val velocityY: Double? = null,
    val velocityZ: Double? = null,
    val roll: Double? = null,
    val pitch: Double? = null,
    val yaw: Double? = null,
    val batteryPercent: Int? = null,
    val flightMode: String? = null,
    val motorsOn: Boolean? = null,
    val gpsSatelliteCount: Int? = null
)

package dji.sampleV5.aircraft.control

object ControlCommandValidator {

    private const val PROTOCOL_VERSION = 1
    private const val MAX_COMMAND_AGE_MS = 5_000L
    private const val MAX_FUTURE_SKEW_MS = 2_000L
    // Keep the first field-test envelope at 50% of the DJI stick range.
    private const val STICK_MIN = -330
    private const val STICK_MAX = 330
    private val UUID_PATTERN = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
            "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    )

    fun validate(command: ControlCommand, now: Long = System.currentTimeMillis()): String? {
        if (command.version != PROTOCOL_VERSION) return "unsupported protocol version"
        if (command.requestId.isNullOrBlank() || !UUID_PATTERN.matches(command.requestId)) {
            return "requestId must be a UUID"
        }

        val type = runCatching { ControlCommandType.valueOf(command.type) }.getOrNull()
            ?: return "unsupported command type"
        val requiresControlSession = type in setOf(
            ControlCommandType.ENABLE_CONTROL,
            ControlCommandType.DISABLE_CONTROL,
            ControlCommandType.STICK,
            ControlCommandType.SAFETY_RELEASE,
            ControlCommandType.EMERGENCY_STOP
        )
        if (requiresControlSession &&
            (command.controlSessionId.isNullOrBlank() ||
                !UUID_PATTERN.matches(command.controlSessionId))) {
            return "controlSessionId must be a UUID"
        }
        if (requiresControlSession && (command.sequence ?: 0) <= 0) {
            return "positive sequence is required"
        }
        if (type.name.startsWith("MISSION_") &&
            (command.taskId.isNullOrBlank() || !UUID_PATTERN.matches(command.taskId))) {
            return "taskId must be a UUID"
        }
        if (type == ControlCommandType.MISSION_PREPARE) {
            val expectedPath = "/api/v1/msdk/missions/${command.taskId}/file"
            if (command.downloadUrl != expectedPath) {
                return "downloadUrl must be the trusted mission path"
            }
        }

        if (command.timestamp <= 0) return "timestamp is required"
        if (now - command.timestamp > MAX_COMMAND_AGE_MS) return "command is stale"
        if (command.timestamp - now > MAX_FUTURE_SKEW_MS) return "command timestamp is in the future"

        if (type == ControlCommandType.STICK) {
            val payload = command.payload ?: return "stick payload is required"
            val values = listOf(
                payload.leftHorizontal,
                payload.leftVertical,
                payload.rightHorizontal,
                payload.rightVertical
            )
            if (values.any { it !in STICK_MIN..STICK_MAX }) {
                return "stick value must be between $STICK_MIN and $STICK_MAX"
            }
        }

        return null
    }
}

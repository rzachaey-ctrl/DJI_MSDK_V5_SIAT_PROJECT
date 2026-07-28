package dji.sampleV5.aircraft.control

import dji.sampleV5.aircraft.BuildConfig
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import dji.v5.manager.aircraft.waypoint3.WaylineExecutingInfoListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionExecuteStateListener
import dji.v5.manager.aircraft.waypoint3.model.WaylineExecutingInfo
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
import dji.v5.utils.common.ContextUtil
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Executes the mission lifecycle requested by the Java backend.
 *
 * KMZ files are stored in the application's private directory. In dry-run
 * mode the file is still downloaded and validated, but it is never uploaded
 * to the aircraft and no flight operation is invoked.
 */
object MsdkMissionAdapter {

    private const val MAX_MISSION_BYTES = 100L * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 250L * 1024 * 1024
    private const val MAX_ZIP_ENTRY_BYTES = 100L * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 2_048
    private const val MAX_COMPRESSION_RATIO = 200L
    private val httpClient = OkHttpClient()
    private val missions = ConcurrentHashMap<String, PreparedMission>()
    @Volatile
    private var activeTaskId: String? = null
    @Volatile
    private var pauseRequested = false
    @Volatile
    private var stopRequested = false
    @Volatile
    private var preparingTaskId: String? = null
    private var prepareGeneration = 0L
    private var inFlightDownload: Call? = null
    private var telemetrySink: ((MissionTelemetry) -> Unit)? = null

    private val missionStateListener = WaypointMissionExecuteStateListener { state ->
        val taskId = activeTaskId ?: return@WaypointMissionExecuteStateListener
        val status = when (state) {
            WaypointMissionExecuteState.READY -> "READY"
            WaypointMissionExecuteState.UPLOADING,
            WaypointMissionExecuteState.PREPARING -> "UPLOADING_TO_AIRCRAFT"
            WaypointMissionExecuteState.ENTER_WAYLINE,
            WaypointMissionExecuteState.EXECUTING,
            WaypointMissionExecuteState.RECOVERING,
            WaypointMissionExecuteState.RETURN_TO_START_POINT -> "EXECUTING"
            WaypointMissionExecuteState.INTERRUPTED ->
                if (pauseRequested) "PAUSED" else "INTERRUPTED"
            WaypointMissionExecuteState.FINISHED ->
                if (stopRequested) "INTERRUPTED" else "FINISHED"
            else -> return@WaypointMissionExecuteStateListener
        }
        emit(taskId, status, "DJI mission state: ${state.name}")
        if (state == WaypointMissionExecuteState.FINISHED) {
            pauseRequested = false
            stopRequested = false
            activeTaskId = null
        }
    }

    private val waylineInfoListener = object : WaylineExecutingInfoListener {
        override fun onWaylineExecutingInfoUpdate(info: WaylineExecutingInfo) {
            val taskId = activeTaskId
                ?: recoverActiveTask(info.missionFileName)
                ?: return
            emit(
                taskId = taskId,
                status = if (pauseRequested) "PAUSED" else "EXECUTING",
                message = "Executing waypoint ${info.currentWaypointIndex}",
                waylineId = info.waylineID,
                waypointIndex = info.currentWaypointIndex
            )
        }

        override fun onWaylineExecutingInterruptReasonUpdate(error: IDJIError?) {
            val taskId = activeTaskId ?: return
            if (error != null) {
                emit(taskId, "FAILED", "DJI mission interrupted: $error")
                pauseRequested = false
                stopRequested = false
                activeTaskId = null
            }
        }
    }

    fun start(sink: (MissionTelemetry) -> Unit) {
        telemetrySink = sink
        val manager = WaypointMissionManager.getInstance()
        recoverPreparedMissions(manager)
        manager.addWaypointMissionExecuteStateListener(missionStateListener)
        manager.addWaylineExecutingInfoListener(waylineInfoListener)
    }

    fun stop() {
        cancelPendingPreparation()
        val manager = WaypointMissionManager.getInstance()
        manager.removeWaypointMissionExecuteStateListener(missionStateListener)
        manager.removeWaylineExecutingInfoListener(waylineInfoListener)
        telemetrySink = null
        activeTaskId = null
        pauseRequested = false
        stopRequested = false
    }

    fun cancelPendingPreparation() {
        synchronized(this) {
            prepareGeneration++
            preparingTaskId = null
            inFlightDownload?.cancel()
            inFlightDownload = null
        }
    }

    fun execute(command: ControlCommand, result: (String, String) -> Unit) {
        when (command.type) {
            ControlCommandType.MISSION_PREPARE.name -> prepare(command, result)
            ControlCommandType.MISSION_START.name -> withPrepared(command, result) { mission ->
                if (activeTaskId != null) {
                    result("REJECTED", "Another mission is already active on RC Pro")
                    return@withPrepared
                }
                activeTaskId = command.taskId
                pauseRequested = false
                stopRequested = false
                if (BuildConfig.CONTROL_DRY_RUN) {
                    result("EXECUTING", "Mission start simulated in dry-run mode")
                } else {
                    WaypointMissionManager.getInstance().startMission(
                        mission.missionId, mission.waylineIds, completion(
                            { result("EXECUTING", "Mission started") },
                            {
                                activeTaskId = null
                                result("REJECTED", "Mission start failed: $it")
                            }
                        )
                    )
                }
            }
            ControlCommandType.MISSION_PAUSE.name -> withActive(command, result) {
                pauseRequested = true
                lifecycle(result, "PAUSED", "paused", {
                    pauseRequested = false
                }) {
                    WaypointMissionManager.getInstance().pauseMission(it)
                }
            }
            ControlCommandType.MISSION_RESUME.name -> withActive(command, result) {
                pauseRequested = false
                lifecycle(result, "EXECUTING", "resumed", {
                    pauseRequested = true
                }) {
                    WaypointMissionManager.getInstance().resumeMission(it)
                }
            }
            ControlCommandType.MISSION_STOP.name -> withActive(command, result) {
                pauseRequested = false
                stopRequested = true
                if (BuildConfig.CONTROL_DRY_RUN) {
                    result("INTERRUPTED", "Mission stop simulated in dry-run mode")
                    activeTaskId = null
                    stopRequested = false
                } else {
                    val mission = missions[command.taskId] ?: run {
                        stopRequested = false
                        result("REJECTED", "Mission is not prepared on RC Pro")
                        return@withActive
                    }
                    WaypointMissionManager.getInstance().stopMission(
                        mission.missionId, completion(
                            {
                                result("INTERRUPTED", "Mission stopped")
                                activeTaskId = null
                                stopRequested = false
                            },
                            {
                                stopRequested = false
                                result("REJECTED", "Mission stop failed: $it")
                            }
                        )
                    )
                }
            }
            else -> result("FAILED", "Unsupported mission command")
        }
    }

    private fun prepare(command: ControlCommand, result: (String, String) -> Unit) {
        val taskId = command.taskId!!
        val generation = synchronized(this) {
            if (activeTaskId != null || preparingTaskId != null) {
                null
            } else {
                prepareGeneration++
                preparingTaskId = taskId
                prepareGeneration
            }
        }
        if (generation == null) {
            result("FAILED", "Another mission is active or being prepared on RC Pro")
            return
        }
        val downloadUrl = trustedDownloadUrl(command.downloadUrl!!) ?: run {
            finishPreparation(taskId, generation)
            result("FAILED", "Mission download path could not be resolved safely")
            return
        }
        val request = Request.Builder().url(downloadUrl)
            .header("Authorization", "Bearer ${BuildConfig.CONTROL_AUTH_TOKEN}")
            .build()
        result("DOWNLOADING", "Downloading KMZ to RC Pro")
        val downloadCall = httpClient.newCall(request)
        synchronized(this) {
            if (!isPreparationCurrent(taskId, generation)) {
                downloadCall.cancel()
                return
            }
            inFlightDownload = downloadCall
        }
        downloadCall.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (finishPreparation(taskId, generation)) {
                    result("FAILED", "KMZ download failed: ${error.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                synchronized(MsdkMissionAdapter) {
                    if (isPreparationCurrent(taskId, generation)) {
                        inFlightDownload = null
                    }
                }
                response.use {
                    if (!isPreparationCurrent(taskId, generation)) return
                    if (!it.isSuccessful) {
                        finishPreparation(taskId, generation)
                        result("FAILED", "KMZ download returned HTTP ${it.code}")
                        return
                    }
                    val body = it.body ?: run {
                        finishPreparation(taskId, generation)
                        result("FAILED", "KMZ response body is empty")
                        return
                    }
                    if (body.contentLength() > MAX_MISSION_BYTES) {
                        finishPreparation(taskId, generation)
                        result("FAILED", "KMZ exceeds the 100 MB safety limit")
                        return
                    }
                    val directory = File(ContextUtil.getContext().filesDir, "msdk-missions")
                    val temporary = File(directory, ".$taskId.$generation.kmz.part")
                    val file = File(directory, "$taskId.kmz")
                    try {
                        if (!directory.exists() && !directory.mkdirs()) {
                            throw IOException("Unable to create mission storage")
                        }
                        temporary.delete()
                        val copied = body.byteStream().use { input ->
                            temporary.outputStream().use { output ->
                                copyBounded(input, output)
                            }
                        }
                        if (copied == 0L) throw IOException("Downloaded KMZ is empty")
                        if (!isPreparationCurrent(taskId, generation)) {
                            temporary.delete()
                            return
                        }
                        if (file.exists() && !file.delete()) {
                            throw IOException("Unable to replace prior mission file")
                        }
                        if (!temporary.renameTo(file)) {
                            throw IOException("Unable to finalize mission file")
                        }

                        validateKmzArchive(file)
                        val manager = WaypointMissionManager.getInstance()
                        val waylineIds = manager.getAvailableWaylineIDs(file.absolutePath)
                        if (waylineIds.isEmpty()) {
                            file.delete()
                            if (finishPreparation(taskId, generation)) {
                                missions.remove(taskId)
                                result("FAILED", "KMZ contains no available wayline")
                            }
                            return
                        }
                        val mission = PreparedMission(
                            file = file,
                            missionId = file.nameWithoutExtension,
                            waylineIds = waylineIds
                        )
                        if (BuildConfig.CONTROL_DRY_RUN) {
                            missions[taskId] = mission
                            finishPreparation(taskId, generation)
                            result("READY", "KMZ validated; aircraft upload skipped in dry-run mode")
                            return
                        }
                        result("UPLOADING_TO_AIRCRAFT", "Uploading KMZ to aircraft")
                        manager.pushKMZFileToAircraft(file.absolutePath,
                            object : CommonCallbacks.CompletionCallbackWithProgress<Double> {
                                override fun onProgressUpdate(progress: Double) = Unit
                                override fun onSuccess() {
                                    if (finishPreparation(taskId, generation)) {
                                        missions[taskId] = mission
                                        result("READY", "KMZ uploaded to aircraft")
                                    }
                                }
                                override fun onFailure(error: IDJIError) {
                                    if (finishPreparation(taskId, generation)) {
                                        missions.remove(taskId)
                                        result("FAILED", "KMZ upload failed: $error")
                                    }
                                }
                            })
                    } catch (error: Exception) {
                        temporary.delete()
                        if (finishPreparation(taskId, generation)) {
                            file.delete()
                            missions.remove(taskId)
                            result("FAILED", "KMZ preparation failed: ${error.message}")
                        }
                    }
                }
            }
        })
    }

    private fun isPreparationCurrent(taskId: String, generation: Long): Boolean =
        synchronized(this) {
            preparingTaskId == taskId && prepareGeneration == generation
        }

    private fun finishPreparation(taskId: String, generation: Long): Boolean =
        synchronized(this) {
            if (preparingTaskId != taskId || prepareGeneration != generation) {
                false
            } else {
                preparingTaskId = null
                inFlightDownload = null
                true
            }
        }

    private fun lifecycle(
        result: (String, String) -> Unit,
        status: String,
        action: String,
        onFailure: () -> Unit = {},
        invoke: (CommonCallbacks.CompletionCallback) -> Unit
    ) {
        if (BuildConfig.CONTROL_DRY_RUN) {
            result(status, "Mission $action in dry-run mode")
            return
        }
        invoke(completion(
            { result(status, "Mission $action") },
            {
                onFailure()
                result("REJECTED", "Mission $action failed: $it")
            }
        ))
    }

    private fun withPrepared(
        command: ControlCommand,
        result: (String, String) -> Unit,
        block: (PreparedMission) -> Unit
    ) {
        val mission = missions[command.taskId]
        if (mission == null) {
            result("REJECTED", "Mission is not prepared on RC Pro")
            return
        }
        block(mission)
    }

    private fun withActive(
        command: ControlCommand,
        result: (String, String) -> Unit,
        block: () -> Unit
    ) {
        if (activeTaskId != command.taskId) {
            result("REJECTED", "Requested task is not the active mission")
            return
        }
        block()
    }

    private fun recoverPreparedMissions(manager: WaypointMissionManager) {
        val directory = File(ContextUtil.getContext().filesDir, "msdk-missions")
        directory.listFiles { file ->
            file.isFile && file.extension.equals("kmz", ignoreCase = true)
        }?.forEach { file ->
            val taskId = file.nameWithoutExtension
            if (runCatching { UUID.fromString(taskId) }.isFailure) return@forEach
            val waylineIds = runCatching {
                manager.getAvailableWaylineIDs(file.absolutePath)
            }.getOrDefault(emptyList())
            if (waylineIds.isNotEmpty()) {
                missions[taskId] = PreparedMission(file, taskId, waylineIds)
            }
        }
    }

    private fun recoverActiveTask(missionFileName: String?): String? {
        val taskId = missionFileName
            ?.let { File(it).nameWithoutExtension }
            ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ?: return null
        if (!missions.containsKey(taskId)) {
            val file = File(
                File(ContextUtil.getContext().filesDir, "msdk-missions"),
                "$taskId.kmz"
            )
            val waylineIds = runCatching {
                WaypointMissionManager.getInstance()
                    .getAvailableWaylineIDs(file.absolutePath)
            }.getOrDefault(emptyList())
            if (!file.isFile || waylineIds.isEmpty()) return null
            missions[taskId] = PreparedMission(file, taskId, waylineIds)
        }
        activeTaskId = taskId
        return taskId
    }

    private fun trustedDownloadUrl(path: String): String? {
        return runCatching {
            val bridge = URI(BuildConfig.CONTROL_WS_URL)
            val httpScheme = when (bridge.scheme.lowercase()) {
                "ws" -> "http"
                "wss" -> "https"
                else -> return null
            }
            URI(
                httpScheme,
                null,
                bridge.host,
                bridge.port,
                path,
                null,
                null
            ).toASCIIString()
        }.getOrNull()
    }

    private fun copyBounded(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            if (total > MAX_MISSION_BYTES) {
                throw IOException("KMZ exceeds the 100 MB safety limit")
            }
            output.write(buffer, 0, count)
        }
    }

    private fun validateKmzArchive(file: File) {
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var entryCount = 0
            var totalUncompressed = 0L
            var containsWayline = false
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val normalized = entry.name.replace('\\', '/').lowercase()
                entryCount++
                if (entryCount > MAX_ZIP_ENTRIES ||
                    normalized.startsWith("/") ||
                    normalized.split('/').any { it == ".." }) {
                    throw IOException("KMZ archive structure is unsafe")
                }
                if (entry.size > MAX_ZIP_ENTRY_BYTES ||
                    (entry.size > 0 && entry.compressedSize > 0 &&
                        entry.size / entry.compressedSize > MAX_COMPRESSION_RATIO)) {
                    throw IOException("KMZ contains an unsafe compressed entry")
                }
                if (!entry.isDirectory) {
                    var entryBytes = 0L
                    zip.getInputStream(entry).use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            entryBytes += count
                            totalUncompressed += count
                            if (entryBytes > MAX_ZIP_ENTRY_BYTES ||
                                totalUncompressed > MAX_UNCOMPRESSED_BYTES) {
                                throw IOException("KMZ expands beyond the safety limit")
                            }
                        }
                    }
                    if (normalized == "wpmz/waylines.wpml") {
                        containsWayline = true
                    }
                }
            }
            if (!containsWayline) {
                throw IOException("KMZ does not contain wpmz/waylines.wpml")
            }
        }
    }

    private fun completion(
        success: () -> Unit,
        failure: (IDJIError) -> Unit
    ) = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() = success()
        override fun onFailure(error: IDJIError) = failure(error)
    }

    private data class PreparedMission(
        val file: File,
        val missionId: String,
        val waylineIds: List<Int>
    )

    private fun emit(
        taskId: String,
        status: String,
        message: String,
        waylineId: Int? = null,
        waypointIndex: Int? = null
    ) {
        telemetrySink?.invoke(MissionTelemetry(
            taskId, status, message, waylineId, waypointIndex
        ))
    }
}

data class MissionTelemetry(
    val taskId: String,
    val status: String,
    val message: String,
    val waylineId: Int? = null,
    val waypointIndex: Int? = null
)

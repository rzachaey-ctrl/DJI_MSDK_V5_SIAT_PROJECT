package com.dji.sample.msdk.mission.service;

import com.dji.sample.msdk.mission.model.MsdkMission;
import com.dji.sample.msdk.mission.model.MsdkMissionStatus;
import com.dji.sample.msdk.control.model.MsdkControlEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class MsdkMissionService {

    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    private static final long MAX_UNCOMPRESSED_SIZE = 250L * 1024 * 1024;
    private static final long MAX_ENTRY_SIZE = 100L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 2_048;
    private static final long MAX_COMPRESSION_RATIO = 200L;
    private static final String WAYLINES_ENTRY = "wpmz/waylines.wpml";
    private static final Set<MsdkMissionStatus> AUTHORITATIVE_ASYNC_TERMINAL_STATES =
            Set.of(MsdkMissionStatus.FINISHED,
                    MsdkMissionStatus.INTERRUPTED,
                    MsdkMissionStatus.FAILED);
    private static final Map<MsdkMissionStatus, Set<MsdkMissionStatus>> ALLOWED_TRANSITIONS =
            Map.of(
                    MsdkMissionStatus.PENDING,
                    Set.of(MsdkMissionStatus.DOWNLOADING),
                    MsdkMissionStatus.DOWNLOADING,
                    Set.of(MsdkMissionStatus.UPLOADING_TO_AIRCRAFT,
                            MsdkMissionStatus.READY, MsdkMissionStatus.FAILED),
                    MsdkMissionStatus.UPLOADING_TO_AIRCRAFT,
                    Set.of(MsdkMissionStatus.READY, MsdkMissionStatus.FAILED),
                    MsdkMissionStatus.READY,
                    Set.of(MsdkMissionStatus.EXECUTING, MsdkMissionStatus.DOWNLOADING,
                            MsdkMissionStatus.FAILED),
                    MsdkMissionStatus.EXECUTING,
                    Set.of(MsdkMissionStatus.PAUSED, MsdkMissionStatus.FINISHED,
                            MsdkMissionStatus.INTERRUPTED, MsdkMissionStatus.FAILED),
                    MsdkMissionStatus.PAUSED,
                    Set.of(MsdkMissionStatus.EXECUTING, MsdkMissionStatus.INTERRUPTED,
                            MsdkMissionStatus.FAILED),
                    MsdkMissionStatus.INTERRUPTED,
                    Set.of(MsdkMissionStatus.DOWNLOADING),
                    MsdkMissionStatus.FAILED,
                    Set.of(MsdkMissionStatus.DOWNLOADING),
                    MsdkMissionStatus.FINISHED,
                    Set.of(MsdkMissionStatus.DOWNLOADING)
            );

    private final JdbcTemplate jdbcTemplate;
    private final Path storageRoot;
    private final ConcurrentMap<String, String> activeAttempts = new ConcurrentHashMap<>();

    public MsdkMissionService(
            JdbcTemplate jdbcTemplate,
            @Value("${msdk.mission.storage-path:storage/msdk-missions}") String storagePath) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void initialize() throws IOException {
        Files.createDirectories(storageRoot);
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS msdk_mission ("
                + "task_id VARCHAR(64) PRIMARY KEY,"
                + "original_file_name VARCHAR(255) NOT NULL,"
                + "stored_file_name VARCHAR(100) NOT NULL,"
                + "file_size BIGINT NOT NULL,"
                + "status VARCHAR(32) NOT NULL,"
                + "wayline_id INT,"
                + "waypoint_index INT,"
                + "message VARCHAR(512),"
                + "created_at BIGINT NOT NULL,"
                + "updated_at BIGINT NOT NULL)");
    }

    public MsdkMission create(MultipartFile file) throws IOException {
        validate(file);
        String taskId = UUID.randomUUID().toString();
        String originalName = safeOriginalName(file.getOriginalFilename());
        String storedName = taskId + ".kmz";
        Path target = resolveStoredFile(storedName);
        Path temporary = resolveStoredFile("." + taskId + ".kmz.part");
        try {
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(target);
            throw exception;
        }

        long now = System.currentTimeMillis();
        try {
            jdbcTemplate.update("INSERT INTO msdk_mission "
                            + "(task_id, original_file_name, stored_file_name, file_size, status, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    taskId, originalName, storedName, file.getSize(),
                    MsdkMissionStatus.PENDING.name(), now, now);
        } catch (RuntimeException exception) {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(target);
            throw exception;
        }
        return findRequired(taskId);
    }

    public Optional<MsdkMission> find(String taskId) {
        return jdbcTemplate.query("SELECT task_id, original_file_name, file_size, status, "
                        + "wayline_id, waypoint_index, message, created_at, updated_at "
                        + "FROM msdk_mission WHERE task_id=?",
                (resultSet, rowNum) -> MsdkMission.builder()
                        .taskId(resultSet.getString("task_id"))
                        .originalFileName(resultSet.getString("original_file_name"))
                        .fileSize(resultSet.getLong("file_size"))
                        .status(MsdkMissionStatus.valueOf(resultSet.getString("status")))
                        .waylineId((Integer) resultSet.getObject("wayline_id"))
                        .waypointIndex((Integer) resultSet.getObject("waypoint_index"))
                        .message(resultSet.getString("message"))
                        .createdAt(resultSet.getLong("created_at"))
                        .updatedAt(resultSet.getLong("updated_at"))
                        .downloadUrl("/api/v1/msdk/missions/" + taskId + "/file")
                        .build(),
                taskId).stream().findFirst();
    }

    public List<MsdkMission> listRecent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query("SELECT task_id, original_file_name, file_size, status, "
                        + "wayline_id, waypoint_index, message, created_at, updated_at "
                        + "FROM msdk_mission ORDER BY created_at DESC LIMIT ?",
                (resultSet, rowNum) -> mapMission(resultSet), safeLimit);
    }

    public Resource loadFile(String taskId) throws IOException {
        String storedName = jdbcTemplate.query("SELECT stored_file_name FROM msdk_mission WHERE task_id=?",
                resultSet -> resultSet.next() ? resultSet.getString(1) : null, taskId);
        if (!StringUtils.hasText(storedName)) {
            throw new IllegalArgumentException("Mission task does not exist.");
        }
        Path file = resolveStoredFile(storedName);
        Resource resource = new UrlResource(file.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalStateException("Mission file is missing.");
        }
        return resource;
    }

    public MsdkMission updateStatus(
            String taskId,
            MsdkMissionStatus status,
            String message,
            Integer waylineId,
            Integer waypointIndex) {
        MsdkMission current = findRequired(taskId);
        if (current.getStatus() != status && !canTransition(current.getStatus(), status)) {
            throw new IllegalStateException("Invalid mission state transition: "
                    + current.getStatus() + " -> " + status);
        }
        int updated = jdbcTemplate.update("UPDATE msdk_mission SET status=?, message=?, "
                        + "wayline_id=COALESCE(?, wayline_id), "
                        + "waypoint_index=COALESCE(?, waypoint_index), updated_at=? "
                        + "WHERE task_id=? AND status=?",
                status.name(), message, waylineId, waypointIndex,
                System.currentTimeMillis(), taskId, current.getStatus().name());
        if (updated == 0) {
            throw new IllegalStateException(
                    "Mission state changed concurrently; refresh before retrying.");
        }
        return findRequired(taskId);
    }

    public void applyClientEvent(MsdkControlEvent event) {
        if (event == null || !StringUtils.hasText(event.getTaskId())) {
            return;
        }
        MsdkMissionStatus status;
        try {
            status = MsdkMissionStatus.valueOf(event.getStatus().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            return;
        }
        String requestId = event.getRequestId();
        String activeRequestId = activeAttempts.get(event.getTaskId());
        boolean authoritativeAsyncTerminal = false;
        if (StringUtils.hasText(requestId)) {
            if (!requestId.equals(activeRequestId)) {
                return;
            }
        } else if (activeRequestId != null) {
            authoritativeAsyncTerminal =
                    isAuthoritativeAsyncTerminal(event.getTaskId(), status);
            if (!authoritativeAsyncTerminal) {
                // Non-terminal callbacks can belong to an older attempt. Keep
                // requiring their request id while a command is outstanding.
                return;
            }
        }
        try {
            updateStatus(event.getTaskId(), status, event.getMessage(),
                    event.getWaylineId(), event.getWaypointIndex());
            if (StringUtils.hasText(requestId) && isAttemptTerminal(status)) {
                activeAttempts.remove(event.getTaskId(), requestId);
            } else if (authoritativeAsyncTerminal) {
                // Natural completion/interruption is authoritative even if it
                // overtakes a pending STOP. Its later rejection must not move
                // the mission back or leave the attempt permanently claimed.
                activeAttempts.remove(event.getTaskId(), activeRequestId);
            }
        } catch (IllegalStateException exception) {
            // A delayed RC event must not move a task backwards.
        }
    }

    private boolean isAuthoritativeAsyncTerminal(
            String taskId, MsdkMissionStatus status) {
        if (!AUTHORITATIVE_ASYNC_TERMINAL_STATES.contains(status)) {
            return false;
        }
        return find(taskId)
                .map(MsdkMission::getStatus)
                .map(current -> current == MsdkMissionStatus.EXECUTING
                        || current == MsdkMissionStatus.PAUSED)
                .orElse(false);
    }

    public MsdkMission claimAction(
            String taskId,
            String requestId,
            MsdkMissionStatus targetStatus,
            MsdkMissionStatus... allowed) {
        if (!StringUtils.hasText(requestId)) {
            throw new IllegalArgumentException("Mission requestId is required.");
        }
        String existing = activeAttempts.putIfAbsent(taskId, requestId);
        if (existing != null) {
            throw new IllegalStateException("Another action is already pending for this mission.");
        }
        try {
            MsdkMission mission = findRequired(taskId);
            boolean permitted = false;
            for (MsdkMissionStatus status : allowed) {
                if (mission.getStatus() == status) {
                    permitted = true;
                    break;
                }
            }
            if (!permitted) {
                throw new IllegalStateException(
                        "Mission action is not allowed while status is "
                                + mission.getStatus() + ".");
            }
            if (targetStatus != null && mission.getStatus() != targetStatus) {
                return updateStatus(taskId, targetStatus,
                        "Waiting for RC Pro to handle the mission action.", null, null);
            }
            int updated = jdbcTemplate.update(
                    "UPDATE msdk_mission SET updated_at=? WHERE task_id=? AND status=?",
                    System.currentTimeMillis(), taskId, mission.getStatus().name());
            if (updated == 0) {
                throw new IllegalStateException(
                        "Mission state changed concurrently; refresh before retrying.");
            }
            return findRequired(taskId);
        } catch (RuntimeException exception) {
            activeAttempts.remove(taskId, requestId);
            throw exception;
        }
    }

    public void failAttempt(
            String taskId, String requestId, String message, boolean markFailed) {
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(requestId)
                || !activeAttempts.remove(taskId, requestId)) {
            return;
        }
        try {
            MsdkMission current = findRequired(taskId);
            if (markFailed && (current.getStatus() == MsdkMissionStatus.DOWNLOADING
                    || current.getStatus() == MsdkMissionStatus.UPLOADING_TO_AIRCRAFT)) {
                updateStatus(taskId, MsdkMissionStatus.FAILED, message, null, null);
            } else {
                jdbcTemplate.update(
                        "UPDATE msdk_mission SET message=?, updated_at=? WHERE task_id=?",
                        message, System.currentTimeMillis(), taskId);
            }
        } catch (RuntimeException exception) {
            // The command result remains authoritative even if status cleanup
            // races a later aircraft event.
        }
    }

    public void requireStatus(String taskId, MsdkMissionStatus... allowed) {
        MsdkMission mission = findRequired(taskId);
        for (MsdkMissionStatus status : allowed) {
            if (mission.getStatus() == status) {
                return;
            }
        }
        throw new IllegalStateException("Mission action is not allowed while status is "
                + mission.getStatus() + ".");
    }

    public static boolean canTransition(MsdkMissionStatus from, MsdkMissionStatus to) {
        return ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    private boolean isAttemptTerminal(MsdkMissionStatus status) {
        return status != MsdkMissionStatus.DOWNLOADING
                && status != MsdkMissionStatus.UPLOADING_TO_AIRCRAFT;
    }

    private MsdkMission findRequired(String taskId) {
        return find(taskId).orElseThrow(() ->
                new IllegalStateException("Mission was saved but could not be queried."));
    }

    private MsdkMission mapMission(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        String taskId = resultSet.getString("task_id");
        return MsdkMission.builder()
                .taskId(taskId)
                .originalFileName(resultSet.getString("original_file_name"))
                .fileSize(resultSet.getLong("file_size"))
                .status(MsdkMissionStatus.valueOf(resultSet.getString("status")))
                .waylineId((Integer) resultSet.getObject("wayline_id"))
                .waypointIndex((Integer) resultSet.getObject("waypoint_index"))
                .message(resultSet.getString("message"))
                .createdAt(resultSet.getLong("created_at"))
                .updatedAt(resultSet.getLong("updated_at"))
                .downloadUrl("/api/v1/msdk/missions/" + taskId + "/file")
                .build();
    }

    private void validate(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A KMZ mission file is required.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("KMZ mission file must not exceed 100 MB.");
        }
        String filename = safeOriginalName(file.getOriginalFilename());
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".kmz")) {
            throw new IllegalArgumentException("Only .kmz mission files are accepted.");
        }
        if (!containsWaylinesWpml(file.getInputStream())) {
            throw new IllegalArgumentException(
                    "Invalid KMZ: wpmz/waylines.wpml was not found.");
        }
    }

    private boolean containsWaylinesWpml(InputStream inputStream) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(inputStream))) {
            ZipEntry entry;
            byte[] buffer = new byte[16 * 1024];
            int entryCount = 0;
            long totalUncompressed = 0L;
            boolean containsWayline = false;
            while ((entry = zip.getNextEntry()) != null) {
                String normalized = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRIES
                        || normalized.startsWith("/")
                        || normalized.contains("../")) {
                    throw new IllegalArgumentException("KMZ archive structure is unsafe.");
                }
                if (entry.getSize() > MAX_ENTRY_SIZE
                        || (entry.getSize() > 0 && entry.getCompressedSize() > 0
                        && entry.getSize() / entry.getCompressedSize()
                        > MAX_COMPRESSION_RATIO)) {
                    throw new IllegalArgumentException(
                            "KMZ archive contains an unsafe compressed entry.");
                }
                if (!entry.isDirectory()) {
                    long entryBytes = 0L;
                    int count;
                    while ((count = zip.read(buffer)) >= 0) {
                        entryBytes += count;
                        totalUncompressed += count;
                        if (entryBytes > MAX_ENTRY_SIZE
                                || totalUncompressed > MAX_UNCOMPRESSED_SIZE) {
                            throw new IllegalArgumentException(
                                    "KMZ archive expands beyond the safety limit.");
                        }
                    }
                    if (WAYLINES_ENTRY.equals(normalized)) {
                        containsWayline = true;
                    }
                }
            }
            return containsWayline;
        }
    }

    private String safeOriginalName(String originalName) {
        if (!StringUtils.hasText(originalName)) {
            throw new IllegalArgumentException("Mission filename is required.");
        }
        return Paths.get(originalName).getFileName().toString();
    }

    private Path resolveStoredFile(String storedName) {
        Path resolved = storageRoot.resolve(storedName).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Invalid mission file path.");
        }
        return resolved;
    }
}

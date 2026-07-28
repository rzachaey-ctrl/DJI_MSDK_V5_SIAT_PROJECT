package com.dji.sample.msdk.mission.controller;

import com.dji.sample.common.model.CustomClaim;
import com.dji.sample.component.AuthInterceptor;
import com.dji.sample.msdk.mission.model.MsdkMission;
import com.dji.sample.msdk.mission.service.MsdkMissionService;
import com.dji.sample.msdk.mission.model.MsdkMissionStatus;
import com.dji.sample.msdk.control.model.MsdkControlCommand;
import com.dji.sample.msdk.control.service.MsdkControlBridgeService;
import com.dji.sdk.common.HttpResultResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/msdk/missions")
@RequiredArgsConstructor
public class MsdkMissionController {

    private final MsdkMissionService missionService;
    private final MsdkControlBridgeService controlBridgeService;

    @Value("${msdk.control.auth-token:}")
    private String controlAuthToken;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public HttpResultResponse<MsdkMission> upload(@RequestParam("file") MultipartFile file)
            throws IOException {
        return HttpResultResponse.success(missionService.create(file));
    }

    @GetMapping("/{taskId}")
    public HttpResultResponse<MsdkMission> get(@PathVariable String taskId) {
        return missionService.find(taskId)
                .map(HttpResultResponse::success)
                .orElseGet(() -> HttpResultResponse.error("Mission task does not exist."));
    }

    @GetMapping
    public HttpResultResponse<List<MsdkMission>> list(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return HttpResultResponse.success(missionService.listRecent(limit));
    }

    @GetMapping("/{taskId}/file")
    public ResponseEntity<Resource> download(
            @PathVariable String taskId,
            HttpServletRequest request) throws IOException {
        String expected = controlAuthToken == null ? "" : controlAuthToken.trim();
        String supplied = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (expected.length() < 32) {
            return ResponseEntity.status(503).build();
        }
        if (supplied == null || !MessageDigest.isEqual(
                ("Bearer " + expected).getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        MsdkMission mission = missionService.find(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Mission task does not exist."));
        Resource resource = missionService.loadFile(taskId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(mission.getOriginalFileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/vnd.google-earth.kmz"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(mission.getFileSize())
                .body(resource);
    }

    @PostMapping("/{taskId}/prepare")
    public HttpResultResponse<MsdkControlCommand> prepare(
            @PathVariable String taskId, HttpServletRequest request)
            throws IOException {
        String requestId = UUID.randomUUID().toString();
        MsdkMission mission = missionService.claimAction(
                taskId, requestId, MsdkMissionStatus.DOWNLOADING,
                MsdkMissionStatus.PENDING,
                MsdkMissionStatus.FAILED, MsdkMissionStatus.INTERRUPTED,
                MsdkMissionStatus.FINISHED, MsdkMissionStatus.READY);
        MsdkControlCommand command = missionCommand("MISSION_PREPARE", mission);
        command.setRequestId(requestId);
        // The RC resolves this path against its configured, trusted bridge origin.
        // Never derive an authenticated download target from the HTTP Host header.
        command.setDownloadUrl(mission.getDownloadUrl());
        missionService.updateStatus(taskId, MsdkMissionStatus.DOWNLOADING,
                "Waiting for RC Pro to download the KMZ.", null, null);
        try {
            return HttpResultResponse.success(
                    controlBridgeService.sendTrusted(command, operatorId(request)));
        } catch (IOException | RuntimeException exception) {
            missionService.failAttempt(
                    taskId, requestId, "Unable to dispatch mission to RC Pro.", true);
            throw exception;
        }
    }

    @PostMapping("/{taskId}/start")
    public HttpResultResponse<MsdkControlCommand> start(
            @PathVariable String taskId, HttpServletRequest request)
            throws IOException {
        return send("MISSION_START", taskId, operatorId(request), MsdkMissionStatus.READY);
    }

    @PostMapping("/{taskId}/pause")
    public HttpResultResponse<MsdkControlCommand> pause(
            @PathVariable String taskId, HttpServletRequest request)
            throws IOException {
        return send("MISSION_PAUSE", taskId, operatorId(request),
                MsdkMissionStatus.EXECUTING);
    }

    @PostMapping("/{taskId}/resume")
    public HttpResultResponse<MsdkControlCommand> resume(
            @PathVariable String taskId, HttpServletRequest request)
            throws IOException {
        return send("MISSION_RESUME", taskId, operatorId(request),
                MsdkMissionStatus.PAUSED);
    }

    @PostMapping("/{taskId}/stop")
    public HttpResultResponse<MsdkControlCommand> stop(
            @PathVariable String taskId, HttpServletRequest request)
            throws IOException {
        return send("MISSION_STOP", taskId, operatorId(request),
                MsdkMissionStatus.EXECUTING, MsdkMissionStatus.PAUSED);
    }

    private HttpResultResponse<MsdkControlCommand> send(
            String type,
            String taskId,
            String operatorId,
            MsdkMissionStatus... allowed) throws IOException {
        String requestId = UUID.randomUUID().toString();
        MsdkMission mission =
                missionService.claimAction(taskId, requestId, null, allowed);
        MsdkControlCommand command = missionCommand(type, mission);
        command.setRequestId(requestId);
        try {
            return HttpResultResponse.success(
                    controlBridgeService.sendTrusted(command, operatorId));
        } catch (IOException | RuntimeException exception) {
            missionService.failAttempt(
                    taskId, requestId, "Unable to dispatch mission action to RC Pro.", false);
            throw exception;
        }
    }

    private MsdkControlCommand missionCommand(String type, MsdkMission mission) {
        MsdkControlCommand command = new MsdkControlCommand();
        command.setType(type);
        command.setTaskId(mission.getTaskId());
        command.setMissionFileName(mission.getOriginalFileName());
        return command;
    }

    private String operatorId(HttpServletRequest request) {
        Object attribute = request.getAttribute(AuthInterceptor.TOKEN_CLAIM);
        if (!(attribute instanceof CustomClaim)) {
            throw new IllegalStateException("An authenticated operator is required.");
        }
        CustomClaim claim = (CustomClaim) attribute;
        if (!StringUtils.hasText(claim.getWorkspaceId())
                || !StringUtils.hasText(claim.getId())
                || claim.getUserType() == null) {
            throw new IllegalStateException("The authenticated operator claim is incomplete.");
        }
        return claim.getWorkspaceId() + "/"
                + claim.getUserType() + "/"
                + claim.getId();
    }
}

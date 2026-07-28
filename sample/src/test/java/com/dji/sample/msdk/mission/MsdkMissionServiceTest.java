package com.dji.sample.msdk.mission;

import com.dji.sample.msdk.control.model.MsdkControlEvent;
import com.dji.sample.msdk.mission.model.MsdkMission;
import com.dji.sample.msdk.mission.service.MsdkMissionService;
import com.dji.sample.msdk.mission.model.MsdkMissionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MsdkMissionServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void rejectsNonKmzFiles() {
        MsdkMissionService service =
                new MsdkMissionService(mock(JdbcTemplate.class), tempDirectory.toString());
        MockMultipartFile file =
                new MockMultipartFile("file", "route.txt", "text/plain", "not a mission".getBytes());

        assertThrows(IllegalArgumentException.class, () -> service.create(file));
    }

    @Test
    void rejectsKmzWithoutWaylinesWpml() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("wpmz/template.kml"));
            zip.write("<kml/>".getBytes());
            zip.closeEntry();
        }
        MsdkMissionService service =
                new MsdkMissionService(mock(JdbcTemplate.class), tempDirectory.toString());
        MockMultipartFile file =
                new MockMultipartFile("file", "route.kmz",
                        "application/vnd.google-earth.kmz", bytes.toByteArray());

        assertThrows(IllegalArgumentException.class, () -> service.create(file));
    }

    @Test
    void acceptsValidMissionStateTransitions() {
        assertTrue(MsdkMissionService.canTransition(
                MsdkMissionStatus.PENDING, MsdkMissionStatus.DOWNLOADING));
        assertTrue(MsdkMissionService.canTransition(
                MsdkMissionStatus.READY, MsdkMissionStatus.EXECUTING));
        assertTrue(MsdkMissionService.canTransition(
                MsdkMissionStatus.EXECUTING, MsdkMissionStatus.PAUSED));
    }

    @Test
    void rejectsBackwardMissionStateTransitions() {
        assertFalse(MsdkMissionService.canTransition(
                MsdkMissionStatus.EXECUTING, MsdkMissionStatus.READY));
        assertFalse(MsdkMissionService.canTransition(
                MsdkMissionStatus.FINISHED, MsdkMissionStatus.EXECUTING));
    }

    @Test
    void rejectedActionPreservesMissionStateAndAllowsRetry() {
        String taskId = "4ce02480-56f9-4d03-a434-0ccedfd01c48";
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MsdkMissionService service =
                serviceAtStatus(jdbcTemplate, taskId, MsdkMissionStatus.EXECUTING);
        activeAttempts(service).put(taskId, "first-stop");

        service.failAttempt(
                taskId, "first-stop", "Mission stop was rejected.", false);

        Map<?, ?> attempts = activeAttempts(service);
        assertFalse(attempts.containsKey(taskId));
        assertEquals(MsdkMissionStatus.EXECUTING,
                service.find(taskId).orElseThrow().getStatus());
        assertNull(activeAttempts(service).putIfAbsent(taskId, "retry-stop"));
    }

    @Test
    void requestlessTerminalStateOvertakesPendingStopAndDropsLateResult() {
        String taskId = "f1bc24c9-feb6-4a91-a2c7-8e70606acdf5";
        String requestId = "pending-stop";
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MsdkMissionService service =
                serviceAtStatus(jdbcTemplate, taskId, MsdkMissionStatus.EXECUTING);
        activeAttempts(service).put(taskId, requestId);
        doReturn(MsdkMission.builder()
                .taskId(taskId)
                .status(MsdkMissionStatus.FINISHED)
                .build())
                .when(service)
                .updateStatus(taskId, MsdkMissionStatus.FINISHED,
                        "finished", null, null);

        MsdkControlEvent finished = missionEvent(taskId, null, "FINISHED");
        service.applyClientEvent(finished);
        service.applyClientEvent(missionEvent(taskId, requestId, "FAILED"));

        assertFalse(activeAttempts(service).containsKey(taskId));
        verify(service, times(1)).updateStatus(
                taskId, MsdkMissionStatus.FINISHED, "finished", null, null);
        verify(service, never()).updateStatus(
                taskId, MsdkMissionStatus.FAILED, "failed", null, null);
    }

    @Test
    void requestlessNonTerminalStateRemainsBlockedByAttemptCorrelation() {
        String taskId = "bcf23aea-96fb-43c2-881f-9c36e9b35ab6";
        String requestId = "pending-pause";
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MsdkMissionService service =
                serviceAtStatus(jdbcTemplate, taskId, MsdkMissionStatus.EXECUTING);
        activeAttempts(service).put(taskId, requestId);

        service.applyClientEvent(missionEvent(taskId, null, "PAUSED"));

        assertEquals(requestId, activeAttempts(service).get(taskId));
        verify(service, never()).updateStatus(
                taskId, MsdkMissionStatus.PAUSED, "paused", null, null);
    }

    private MsdkMissionService serviceAtStatus(
            JdbcTemplate jdbcTemplate,
            String taskId,
            MsdkMissionStatus status) {
        MsdkMissionService service = spy(
                new MsdkMissionService(jdbcTemplate, tempDirectory.toString()));
        MsdkMission mission = MsdkMission.builder()
                .taskId(taskId)
                .status(status)
                .build();
        doReturn(Optional.of(mission)).when(service).find(taskId);
        return service;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> activeAttempts(MsdkMissionService service) {
        Map<String, String> attempts =
                (Map<String, String>) ReflectionTestUtils.getField(
                        service, "activeAttempts");
        assertNotNull(attempts);
        return attempts;
    }

    private MsdkControlEvent missionEvent(
            String taskId, String requestId, String status) {
        MsdkControlEvent event = new MsdkControlEvent();
        event.setType("MISSION_STATE");
        event.setTaskId(taskId);
        event.setRequestId(requestId);
        event.setStatus(status);
        event.setMessage(status.toLowerCase());
        return event;
    }
}

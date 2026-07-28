package com.dji.sample.msdk.control.service;

import com.dji.sample.msdk.control.model.MsdkControlCommand;
import com.dji.sample.msdk.control.model.MsdkControlEvent;
import com.dji.sample.msdk.control.model.MsdkControlAuditEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class MsdkControlAuditService {

    private static final long STICK_AUDIT_SAMPLE_INTERVAL_MS = 5_000L;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AtomicLong lastStickAuditAt = new AtomicLong();
    private final Set<String> auditedRequests = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor auditExecutor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1_000),
            runnable -> {
                Thread thread = new Thread(runnable, "msdk-control-audit");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    @PostConstruct
    public void initializeSchema() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS msdk_control_audit ("
                + "request_id VARCHAR(64) PRIMARY KEY,"
                + "control_session_id VARCHAR(64),"
                + "sequence_no BIGINT,"
                + "command_type VARCHAR(32) NOT NULL,"
                + "command_json TEXT NOT NULL,"
                + "result_status VARCHAR(32) NOT NULL,"
                + "result_message VARCHAR(512),"
                + "created_at BIGINT NOT NULL,"
                + "updated_at BIGINT NOT NULL)");
    }

    public void commandSent(MsdkControlCommand command) {
        long now = System.currentTimeMillis();
        if ("STICK".equals(command.getType()) && !claimStickAuditSlot(now)) {
            return;
        }
        auditedRequests.add(command.getRequestId());
        submit(command.getRequestId(), () -> {
            try {
            jdbcTemplate.update("INSERT INTO msdk_control_audit "
                            + "(request_id, control_session_id, sequence_no, command_type, command_json, "
                            + "result_status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE command_json=VALUES(command_json), "
                            + "result_status=VALUES(result_status), updated_at=VALUES(updated_at)",
                    command.getRequestId(), command.getControlSessionId(), command.getSequence(),
                    command.getType(), objectMapper.writeValueAsString(command),
                    "PENDING", now, now);
            } catch (RuntimeException | JsonProcessingException exception) {
                auditedRequests.remove(command.getRequestId());
                log.error("Unable to persist MSDK command audit {}",
                        command.getRequestId(), exception);
            }
        });
    }

    public void resultReceived(MsdkControlEvent event) {
        if (event == null || event.getRequestId() == null
                || !auditedRequests.contains(event.getRequestId())) {
            return;
        }
        submit(event.getRequestId(), () -> {
            try {
                jdbcTemplate.update("UPDATE msdk_control_audit SET result_status=?, "
                                + "result_message=?, updated_at=? WHERE request_id=?",
                        event.getStatus(), event.getMessage(),
                        System.currentTimeMillis(), event.getRequestId());
            } catch (RuntimeException exception) {
                log.error("Unable to update MSDK command audit {}",
                        event.getRequestId(), exception);
            } finally {
                if (isTerminal(event.getStatus())) {
                    auditedRequests.remove(event.getRequestId());
                }
            }
        });
    }

    public List<MsdkControlAuditEntry> listRecent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.query(
                "SELECT request_id, control_session_id, sequence_no, command_type, "
                        + "result_status, result_message, created_at, updated_at "
                        + "FROM msdk_control_audit ORDER BY created_at DESC LIMIT ?",
                (resultSet, rowNum) -> MsdkControlAuditEntry.builder()
                        .requestId(resultSet.getString("request_id"))
                        // A lease id is a capability and must never be exposed
                        // through a global historical endpoint.
                        .controlSessionId(null)
                        .sequence((Long) resultSet.getObject("sequence_no"))
                        .commandType(resultSet.getString("command_type"))
                        .resultStatus(resultSet.getString("result_status"))
                        .resultMessage(resultSet.getString("result_message"))
                        .createdAt(resultSet.getLong("created_at"))
                        .updatedAt(resultSet.getLong("updated_at"))
                        .build(),
                safeLimit);
    }

    private void submit(String requestId, Runnable operation) {
        try {
            auditExecutor.execute(operation);
        } catch (RejectedExecutionException exception) {
            auditedRequests.remove(requestId);
            log.error("MSDK audit queue is full; dropping audit for {}", requestId);
        }
    }

    private boolean isTerminal(String status) {
        if (status == null) {
            return true;
        }
        String normalized = status.toUpperCase(Locale.ROOT);
        return !"PENDING".equals(normalized)
                && !"DOWNLOADING".equals(normalized)
                && !"UPLOADING_TO_AIRCRAFT".equals(normalized);
    }

    private boolean claimStickAuditSlot(long now) {
        while (true) {
            long previous = lastStickAuditAt.get();
            if (now - previous < STICK_AUDIT_SAMPLE_INTERVAL_MS) {
                return false;
            }
            if (lastStickAuditAt.compareAndSet(previous, now)) {
                return true;
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        auditExecutor.shutdown();
    }
}

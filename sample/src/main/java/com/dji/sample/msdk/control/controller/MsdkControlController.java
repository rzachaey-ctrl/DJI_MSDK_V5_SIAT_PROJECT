package com.dji.sample.msdk.control.controller;

import com.dji.sample.common.model.CustomClaim;
import com.dji.sample.component.AuthInterceptor;
import com.dji.sample.msdk.control.model.MsdkControlCommand;
import com.dji.sample.msdk.control.model.MsdkControlEvent;
import com.dji.sample.msdk.control.model.MsdkControlStatus;
import com.dji.sample.msdk.control.model.MsdkControlSession;
import com.dji.sample.msdk.control.model.MsdkControlAuditEntry;
import com.dji.sample.msdk.control.service.MsdkControlAuditService;
import com.dji.sample.msdk.control.service.MsdkControlBridgeService;
import com.dji.sdk.common.HttpResultResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1/msdk/control")
@RequiredArgsConstructor
public class MsdkControlController {

    private static final Set<String> DIRECT_CONTROL_COMMANDS = Set.of(
            "HEARTBEAT",
            "ENABLE_CONTROL",
            "DISABLE_CONTROL",
            "STICK",
            "SAFETY_RELEASE");

    private final MsdkControlBridgeService bridgeService;
    private final MsdkControlAuditService auditService;

    @GetMapping("/status")
    public HttpResultResponse<MsdkControlStatus> status(HttpServletRequest request) {
        return HttpResultResponse.success(bridgeService.status(operatorId(request)));
    }

    @PostMapping("/sessions")
    public HttpResultResponse<MsdkControlSession> acquireSession(HttpServletRequest request) {
        return HttpResultResponse.success(bridgeService.acquireSession(operatorId(request)));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public HttpResultResponse<Void> releaseSession(
            @PathVariable String sessionId, HttpServletRequest request) throws IOException {
        bridgeService.releaseSession(sessionId, operatorId(request));
        return HttpResultResponse.success();
    }

    @PostMapping("/commands")
    public HttpResultResponse<MsdkControlCommand> send(
            @RequestBody MsdkControlCommand command, HttpServletRequest request)
            throws IOException {
        String type = command == null || command.getType() == null
                ? "" : command.getType().trim().toUpperCase(Locale.ROOT);
        if (!DIRECT_CONTROL_COMMANDS.contains(type)) {
            throw new IllegalArgumentException(
                    "Use the mission lifecycle endpoints for mission commands.");
        }
        return HttpResultResponse.success(bridgeService.send(command, operatorId(request)));
    }

    @GetMapping("/commands/{requestId}")
    public HttpResultResponse<MsdkControlEvent> commandResult(
            @PathVariable String requestId, HttpServletRequest request) {
        MsdkControlEvent result = bridgeService.commandResult(requestId, operatorId(request));
        return result == null
                ? HttpResultResponse.error("No result has been received for this requestId.")
                : HttpResultResponse.success(result);
    }

    @GetMapping("/audits")
    public HttpResultResponse<List<MsdkControlAuditEntry>> audits(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return HttpResultResponse.success(auditService.listRecent(limit));
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
        return String.valueOf(claim.getWorkspaceId()) + "/"
                + String.valueOf(claim.getUserType()) + "/"
                + String.valueOf(claim.getId());
    }
}

package com.dji.sample.msdk.control.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MsdkControlAuditEntry {

    private String requestId;
    private String controlSessionId;
    private Long sequence;
    private String commandType;
    private String resultStatus;
    private String resultMessage;
    private Long createdAt;
    private Long updatedAt;
}

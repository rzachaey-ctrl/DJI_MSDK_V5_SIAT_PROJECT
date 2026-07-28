package com.dji.sample.msdk.control.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MsdkControlSession {

    private String id;

    private Long acquiredAt;

    private Long lastHeartbeatAt;

    private Long nextSequence;

    private Boolean releasing;

    /**
     * Server-side lease owner. This is intentionally never exposed over JSON:
     * the session id is a capability and may only be returned to its owner.
     */
    @JsonIgnore
    private String ownerId;
}

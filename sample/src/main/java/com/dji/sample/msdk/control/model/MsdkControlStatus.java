package com.dji.sample.msdk.control.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MsdkControlStatus {

    private boolean connected;

    private String sessionId;

    private String remoteAddress;

    private Long connectedAt;

    private Long lastSeenAt;

    private MsdkControlEvent lastEvent;

    private MsdkControlEvent telemetry;

    private Boolean aircraftConnected;

    private Boolean controlEnabled;

    private MsdkControlSession controlSession;

    private Boolean controlSessionOwned;

    private Long telemetryAgeMs;

    private Boolean telemetryFresh;

    private Boolean dryRun;
}

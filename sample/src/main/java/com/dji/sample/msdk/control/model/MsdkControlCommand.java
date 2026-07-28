package com.dji.sample.msdk.control.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class MsdkControlCommand {

    private Integer version = 1;

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("control_session_id")
    private String controlSessionId;

    private Long sequence;

    private String type;

    private Long timestamp;

    private MsdkStickPayload payload;

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("download_url")
    private String downloadUrl;

    @JsonProperty("mission_file_name")
    private String missionFileName;
}

package com.dji.sample.msdk.mission.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MsdkMission {

    private String taskId;

    private String originalFileName;

    private Long fileSize;

    private MsdkMissionStatus status;

    private Integer waylineId;

    private Integer waypointIndex;

    private String message;

    private Long createdAt;

    private Long updatedAt;

    private String downloadUrl;
}

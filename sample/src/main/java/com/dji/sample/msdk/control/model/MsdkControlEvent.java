package com.dji.sample.msdk.control.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class MsdkControlEvent {

    private Integer version;

    private String type;

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("control_session_id")
    private String controlSessionId;

    private Long sequence;

    private Long timestamp;

    private String status;

    private String message;

    @JsonProperty("dry_run")
    private Boolean dryRun;

    @JsonProperty("aircraft_connected")
    private Boolean aircraftConnected;

    @JsonProperty("control_enabled")
    private Boolean controlEnabled;

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("wayline_id")
    private Integer waylineId;

    @JsonProperty("waypoint_index")
    private Integer waypointIndex;

    private Double latitude;
    private Double longitude;
    private Double altitude;

    @JsonProperty("velocity_x")
    private Double velocityX;

    @JsonProperty("velocity_y")
    private Double velocityY;

    @JsonProperty("velocity_z")
    private Double velocityZ;

    private Double roll;
    private Double pitch;
    private Double yaw;

    @JsonProperty("battery_percent")
    private Integer batteryPercent;

    @JsonProperty("flight_mode")
    private String flightMode;

    @JsonProperty("motors_on")
    private Boolean motorsOn;

    @JsonProperty("gps_satellite_count")
    private Integer gpsSatelliteCount;
}

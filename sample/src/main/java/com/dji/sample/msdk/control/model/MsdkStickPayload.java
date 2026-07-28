package com.dji.sample.msdk.control.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class MsdkStickPayload {

    @JsonProperty("left_horizontal")
    @JsonAlias("leftHorizontal")
    private Integer leftHorizontal = 0;

    @JsonProperty("left_vertical")
    @JsonAlias("leftVertical")
    private Integer leftVertical = 0;

    @JsonProperty("right_horizontal")
    @JsonAlias("rightHorizontal")
    private Integer rightHorizontal = 0;

    @JsonProperty("right_vertical")
    @JsonAlias("rightVertical")
    private Integer rightVertical = 0;
}

package com.dji.sample.manage.service;

import com.dji.sdk.cloudapi.device.OsdRcDrone;
import com.dji.sdk.common.Common;
import com.dji.sdk.mqtt.osd.TopicOsdRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OsdRcDroneParsingTest {

    @Test
    void parsesTelemetryFieldsFromRcDroneOsdMessage() throws Exception {
        String payload = "{"
                + "\"tid\":\"test-tid\","
                + "\"bid\":\"test-bid\","
                + "\"timestamp\":1650000000000,"
                + "\"gateway\":\"RC-SN\","
                + "\"from\":\"AIRCRAFT-SN\","
                + "\"data\":{"
                + "\"latitude\":31.2304,"
                + "\"longitude\":121.4737,"
                + "\"height\":36.5,"
                + "\"elevation\":42.0,"
                + "\"horizontal_speed\":5.4,"
                + "\"vertical_speed\":-0.8,"
                + "\"attitude_head\":128.0,"
                + "\"battery\":{\"capacity_percent\":76}"
                + "}}";

        TopicOsdRequest<OsdRcDrone> request = Common.getObjectMapper().readValue(
                payload, new TypeReference<TopicOsdRequest<OsdRcDrone>>() {});

        OsdRcDrone osd = request.getData();
        assertNotNull(osd);
        assertEquals("RC-SN", request.getGateway());
        assertEquals("AIRCRAFT-SN", request.getFrom());
        assertEquals(31.2304F, osd.getLatitude());
        assertEquals(121.4737F, osd.getLongitude());
        assertEquals(36.5F, osd.getHeight());
        assertEquals(42.0F, osd.getElevation());
        assertEquals(5.4F, osd.getHorizontalSpeed());
        assertEquals(-0.8F, osd.getVerticalSpeed());
        assertEquals(128.0F, osd.getAttitudeHead());
        assertNotNull(osd.getBattery());
        assertEquals(76, osd.getBattery().getCapacityPercent());
    }
}

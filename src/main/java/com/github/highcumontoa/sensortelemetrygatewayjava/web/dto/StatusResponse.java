package com.github.highcumontoa.sensortelemetrygatewayjava.web.dto;

import java.util.Map;

public record StatusResponse(
        String deviceId,
        String status,
        long lastValidEventTime,
        long lastValidIngestTime,
        long evaluatedAt,
        Map<String, Object> latest
) {
}

package com.github.highcumontoa.sensortelemetrygatewayjava.web.dto;

import java.util.List;

public record RegisterRequest(
        String deviceId,
        String protocolVersion,
        List<String> capabilities
) {
}

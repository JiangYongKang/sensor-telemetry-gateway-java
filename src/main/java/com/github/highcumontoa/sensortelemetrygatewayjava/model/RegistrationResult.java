package com.github.highcumontoa.sensortelemetrygatewayjava.model;

import java.util.Set;

/** 注册结论：是否首次创建（用于幂等判断）。 */
public record RegistrationResult(
        String deviceId,
        ProtocolVersion protocolVersion,
        Set<SensorType> capabilities,
        boolean created
) {
}

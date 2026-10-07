package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/**
 * 机群概览中单台设备的快照行。
 * 时间字段为 null 表示该设备自注册以来尚无任何有效上报。
 */
public record DeviceOverview(
        String deviceId,
        String status,
        Long lastValidIngestTime,
        Long lastValidEventTime
) {
}

package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/** 机群概览中单台设备的快照：在线状态与最近一次有效上报时间（接收时间与事件时间）。 */
public record DeviceOverview(
        String deviceId,
        String status,
        long lastValidIngestTime,
        long lastValidEventTime
) {
}

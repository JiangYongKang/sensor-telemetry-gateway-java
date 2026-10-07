package com.github.highcumontoa.sensortelemetrygatewayjava.model;

import java.util.List;

/**
 * 机群级在线概览：在 evaluatedAt 时刻的一次自洽快照。
 * onlineCount + offlineCount == devices.size()，且每台设备的状态
 * 与其 lastValidIngestTime 在同一判定时刻、同一线程安全快照下得出。
 */
public record FleetOverview(
        long evaluatedAt,
        long offlineThresholdMillis,
        int onlineCount,
        int offlineCount,
        List<DeviceOverview> devices
) {
}

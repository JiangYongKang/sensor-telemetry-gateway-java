package com.github.highcumontoa.sensortelemetrygatewayjava.model;

import java.util.List;

/** 机群级在线概览：同一判定时刻下所有已注册设备的整体在线情况。 */
public record FleetOverview(
        long evaluatedAt,
        int totalDevices,
        int onlineCount,
        int offlineCount,
        List<DeviceOverview> devices
) {
}

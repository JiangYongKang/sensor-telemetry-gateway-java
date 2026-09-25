package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/**
 * 设备状态。在线判定仅基于最后有效上报时间：now - lastValidReportTime &lt;= threshold 视为在线，
 * 规则确定且可复现。
 */
public record DeviceStatus(String deviceId, ProtocolVersion version, boolean registered,
                           long lastValidReportTimeMillis, long evaluatedAtMillis,
                           boolean online, long offlineThresholdMillis) {
}

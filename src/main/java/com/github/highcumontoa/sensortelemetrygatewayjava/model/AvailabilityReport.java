package com.github.highcumontoa.sensortelemetrygatewayjava.model;

import java.util.List;

/**
 * 设备可用性统计：区间 [from, to] 内累计在线时长、在线占比与掉线时间段。
 * 判定口径与单台设备在线状态完全一致（基于最后一次有效上报的接收时间）。
 */
public record AvailabilityReport(
        String deviceId,
        long from,
        long to,
        long onlineMillis,
        long totalMillis,
        double onlineRatio,
        List<OfflinePeriod> offlinePeriods
) {
}

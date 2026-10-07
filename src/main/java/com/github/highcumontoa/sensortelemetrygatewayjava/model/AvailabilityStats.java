package com.github.highcumontoa.sensortelemetrygatewayjava.model;

import java.util.List;

/**
 * 设备在查询区间 [from, to] 上的可用性结论。
 * onlineMillis 为累计在线时长；onlineRatio 为在线占比；
 * offlineIntervals 为区间内具体的掉线时间段（按开始时间升序、互不重叠）。
 */
public record AvailabilityStats(
        String deviceId,
        long from,
        long to,
        long onlineMillis,
        double onlineRatio,
        List<OfflineInterval> offlineIntervals
) {
}

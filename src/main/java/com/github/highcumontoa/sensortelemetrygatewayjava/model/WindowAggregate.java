package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/**
 * 滑动窗口聚合结果。窗口与水位语义由 TelemetryStore 固定定义。
 *
 * @param count 窗口内读数条数
 * @param latestValue 最新值（取窗口内事件时间最大的读数）
 * @param latestEventTimeMillis 最新值的事件时间
 * @param minValue / maxValue / avgValue 窗口内最小/最大/平均值
 * @param windowStartMillis / windowEndMillis 实际使用的窗口区间
 * @param watermarkMillis 当前水位（= 该设备最后有效上报时间）
 */
public record WindowAggregate(String deviceId, SensorType sensorType,
                              int count, Double latestValue, long latestEventTimeMillis,
                              Double minValue, Double maxValue, Double avgValue,
                              long windowStartMillis, long windowEndMillis,
                              long watermarkMillis) {
}

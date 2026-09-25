package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/** 单传感器在滑动窗口上的聚合结果。 */
public record WindowStats(
        SensorType type,
        long count,
        Double min,
        Double max,
        Double avg
) {
}

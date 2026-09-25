package com.github.highcumontoa.sensortelemetrygatewayjava.model;

import java.util.Set;

/** 某种传感器类型的规范化规格：标准单位、可接受单位、量程上下限（闭区间）。 */
public record SensorSpec(
        SensorType type,
        String canonicalUnit,
        Set<String> acceptedUnits,
        double min,
        double max
) {
}

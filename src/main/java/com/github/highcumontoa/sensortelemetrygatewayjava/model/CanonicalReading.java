package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/** 规范化后的单条读数（统一单位、通过量程校验）。 */
public record CanonicalReading(
        SensorType type,
        double value,
        String unit
) {
}

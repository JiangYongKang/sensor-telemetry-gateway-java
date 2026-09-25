package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/**
 * 归一化后的有效读数（规范单位、规范量程内）。
 */
public record NormalizedReading(String deviceId, SensorType sensorType,
                                double value, String unit,
                                long eventTimeMillis, long acceptedAtMillis) {
}

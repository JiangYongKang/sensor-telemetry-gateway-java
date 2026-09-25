package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/** 解析前原始上报中的单条读数，保留原始值与单位用于追溯。 */
public record RawReading(
        String type,
        Double value,
        String unit
) {
}

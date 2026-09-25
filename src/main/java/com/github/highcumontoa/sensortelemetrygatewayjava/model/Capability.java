package com.github.highcumontoa.sensortelemetrygatewayjava.model;

import java.util.Set;

/**
 * 设备能力范围：允许上报的传感器类型集合。
 */
public record Capability(Set<SensorType> sensorTypes) {
}

package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/**
 * 按协议版本完成兼容映射后的字段（单位与量程归一化之前）。
 *
 * @param eventTimeMillis 事件时间；旧版本缺失时由网关接收时间补默认值
 * @param eventTimeDefaulted 是否因缺失而使用了默认时间
 * @param sensorCode      版本对应的传感器类型编码
 * @param rawValue        原始数值
 * @param rawUnit         原始单位；旧版本缺失时补规范单位默认值
 * @param unitDefaulted   是否因缺失而使用了默认单位
 */
public record MappedFields(long eventTimeMillis, boolean eventTimeDefaulted,
                           String sensorCode, double rawValue,
                           String rawUnit, boolean unitDefaulted) {
}

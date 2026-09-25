package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/**
 * 设备注册请求。
 */
public record DeviceRegistration(String deviceId, String protocolVersion, Capability capability) {
}

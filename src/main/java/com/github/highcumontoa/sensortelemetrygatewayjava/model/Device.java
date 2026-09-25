package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/**
 * 已注册设备。
 *
 * @param deviceId    设备标识
 * @param version     注册时声明的协议版本；上报版本必须与之相同
 * @param capability  能力范围
 */
public record Device(String deviceId, ProtocolVersion version, Capability capability) {
}

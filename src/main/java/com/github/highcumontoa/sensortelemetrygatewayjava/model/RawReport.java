package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/**
 * 原始上报留档：无论接受或拒绝均保留原始报文以便追溯。
 *
 * @param rawJson         原始 JSON 报文（解析失败时也保留）
 * @param deviceId        尽力解析出的设备标识，无法解析时为 null
 * @param declaredVersion 尽力解析出的版本字符串，无法解析时为 null
 * @param receivedAtMillis 网关接收时刻（注入时钟）
 * @param entryPoint      接收入口标识（HTTP 单条 / HTTP 批量 / 服务直调）
 */
public record RawReport(String rawJson, String deviceId, String declaredVersion,
                        long receivedAtMillis, String entryPoint) {
}

package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/**
 * 注册结论：accepted=false 时 rejectReason 给出可区分原因。
 * idempotent=true 表示设备已存在且声明完全一致，按幂等成功处理。
 */
public record RegistrationResult(boolean accepted, boolean idempotent, String deviceId,
                                 RejectReason rejectReason, String detail) {
}

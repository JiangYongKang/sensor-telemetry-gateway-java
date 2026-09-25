package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/**
 * 单个上报在任意入口下的统一结论。
 * accepted=false 时 rejectReason 必须可区分；raw 始终保留。
 */
public record IngestResult(boolean accepted, String deviceId, SensorType sensorType,
                           RejectReason rejectReason, String detail,
                           NormalizedReading reading, RawReport raw) {

    public static IngestResult ok(NormalizedReading reading, RawReport raw) {
        return new IngestResult(true, reading.deviceId(), reading.sensorType(),
                null, "accepted", reading, raw);
    }

    public static IngestResult reject(RawReport raw, RejectReason reason, String detail) {
        return new IngestResult(false, raw == null ? null : raw.deviceId(), null,
                reason, detail, null, raw);
    }
}

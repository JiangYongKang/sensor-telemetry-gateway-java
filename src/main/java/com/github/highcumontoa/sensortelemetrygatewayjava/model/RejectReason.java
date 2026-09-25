package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/**
 * 上报 / 注册被拒绝的可区分原因码。
 */
public enum RejectReason {
    MALFORMED_JSON,
    UNSUPPORTED_PROTOCOL_VERSION,
    UNKNOWN_DEVICE,
    DEVICE_VERSION_MISMATCH,
    UNKNOWN_SENSOR_TYPE,
    SENSOR_NOT_IN_CAPABILITY,
    MISSING_REQUIRED_FIELD,
    UNSUPPORTED_UNIT,
    VALUE_OUT_OF_RANGE,
    LATE_REPORT,
    DUPLICATE_REGISTRATION_CONFLICT,
    INVALID_PAYLOAD
}

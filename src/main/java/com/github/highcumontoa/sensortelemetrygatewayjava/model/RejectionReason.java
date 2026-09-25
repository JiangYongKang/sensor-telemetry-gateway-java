package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/** 相互可区分的拒绝原因，错误结论必须稳定映射到其中之一。 */
public enum RejectionReason {
    PAYLOAD_INVALID,
    DEVICE_NOT_REGISTERED,
    UNSUPPORTED_PROTOCOL_VERSION,
    PROTOCOL_VERSION_MISMATCH,
    REGISTRATION_CONFLICT,
    MISSING_FIELD,
    SENSOR_TYPE_UNKNOWN,
    CAPABILITY_NOT_DECLARED,
    INVALID_UNIT,
    VALUE_OUT_OF_RANGE,
    FUTURE_TIMESTAMP,
    DUPLICATE_SEQUENCE
}

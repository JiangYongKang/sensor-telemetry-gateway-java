package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/**
 * 上报协议版本。不支持的版本必须以 {@code UNSUPPORTED_PROTOCOL_VERSION} 明确拒绝，
 * 绝不允许按其它版本解析。
 */
public enum ProtocolVersion {
    V1,
    V2;

    /**
     * 严格解析协议版本（大小写不敏感、去除首尾空白）。
     *
     * @return 解析出的版本；null、空白或无法识别均返回 empty，
     *         由上层以 UNSUPPORTED_PROTOCOL_VERSION 明确拒绝，绝不按其它版本解析
     */
    public static java.util.Optional<ProtocolVersion> fromString(String raw) {
        if (raw == null) {
            return java.util.Optional.empty();
        }
        String normalized = raw.trim();
        if (normalized.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(ProtocolVersion.valueOf(normalized.toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}

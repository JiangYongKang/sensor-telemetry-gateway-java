package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/** 原始上报留档：原始报文、解析出的原始读数与拒绝原因。 */
public record RawReport(
        String deviceId,
        String protocolVersion,
        Long sequence,
        java.util.List<RawReading> readings,
        String rawJson,
        RejectionReason rejectionReason,
        long receivedAt
) {
}

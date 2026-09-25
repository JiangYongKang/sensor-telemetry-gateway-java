package com.github.highcumontoa.sensortelemetrygatewayjava.model;

import java.util.List;

/** 单条上报的接入结论：接受或被拒绝（含可区分原因）。 */
public record IngestResult(
        String deviceId,
        boolean accepted,
        RejectionReason rejectionReason,
        Long eventTime,
        List<CanonicalReading> readings
) {
}

package com.github.highcumontoa.sensortelemetrygatewayjava.model;

import java.util.List;

/** 被接受并规范化、已写入设备状态的一次上报。 */
public record AcceptedSample(
        String deviceId,
        long eventTime,
        long ingestTime,
        long sequence,
        List<CanonicalReading> readings
) {
}

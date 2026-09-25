package com.github.highcumontoa.sensortelemetrygatewayjava.web.dto;

import java.util.List;

/** 上报请求：V1 无 sequence 字段，V2 建议携带；timestamp 缺省由网关补接收时间。 */
public record ReportRequest(
        String deviceId,
        String protocolVersion,
        Long timestamp,
        Long sequence,
        List<ReadingDto> readings
) {
}

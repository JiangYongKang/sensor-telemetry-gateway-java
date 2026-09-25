package com.github.highcumontoa.sensortelemetrygatewayjava.ingest;

import com.github.highcumontoa.sensortelemetrygatewayjava.model.ProtocolVersion;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RawReading;

import java.util.List;

/**
 * 按声明版本完成字段映射后的上报。
 * timestamp==null 表示该版本未提供时间戳，由网关以接收时间补位（语义确定）。
 * sequence==-1 表示不参与序列号去重（V1 恒为 -1；V2 缺省亦为 -1）。
 */
public record MappedReport(
        String deviceId,
        ProtocolVersion version,
        Long timestamp,
        long sequence,
        List<RawReading> readings
) {
}

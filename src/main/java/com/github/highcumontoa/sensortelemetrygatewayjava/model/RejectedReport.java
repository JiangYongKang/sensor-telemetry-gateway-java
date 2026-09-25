package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/**
 * 被拒绝上报的留档记录，保留原始报文与判定依据。
 */
public record RejectedReport(RawReport raw, RejectReason reason, String detail, long rejectedAtMillis) {
}

package com.github.highcumontoa.sensortelemetrygatewayjava.protocol;

import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectReason;

/**
 * 版本内字段映射失败，携带可区分的拒绝原因。
 */
public class ReportMappingException extends Exception {

    private final RejectReason rejectReason;

    public ReportMappingException(RejectReason rejectReason, String message) {
        super(message);
        this.rejectReason = rejectReason;
    }

    public RejectReason rejectReason() {
        return rejectReason;
    }
}

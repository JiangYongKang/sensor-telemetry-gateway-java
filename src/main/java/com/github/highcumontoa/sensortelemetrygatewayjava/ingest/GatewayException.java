package com.github.highcumontoa.sensortelemetrygatewayjava.ingest;

import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectionReason;

/** 携带可区分拒绝原因的领域异常。 */
public class GatewayException extends RuntimeException {

    private final RejectionReason reason;

    public GatewayException(RejectionReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectionReason getReason() {
        return reason;
    }
}

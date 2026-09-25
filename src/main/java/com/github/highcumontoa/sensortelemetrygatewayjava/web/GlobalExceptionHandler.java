package com.github.highcumontoa.sensortelemetrygatewayjava.web;

import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.GatewayException;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectionReason;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 将领域拒绝原因映射为稳定、可解释的 HTTP 响应。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Map<String, Object>> handleGateway(GatewayException ex) {
        return ResponseEntity.status(statusFor(ex.getReason())).body(Map.of(
                "accepted", false,
                "rejectionReason", ex.getReason().name(),
                "message", ex.getMessage() == null ? "" : ex.getMessage()
        ));
    }

    /** 拒绝原因到 HTTP 状态码的唯一映射，控制器与异常处理共用，保证各入口语义一致。 */
    public static HttpStatus statusFor(RejectionReason reason) {
        return switch (reason) {
            case DEVICE_NOT_REGISTERED -> HttpStatus.NOT_FOUND;
            case REGISTRATION_CONFLICT, PROTOCOL_VERSION_MISMATCH,
                 UNSUPPORTED_PROTOCOL_VERSION, DUPLICATE_SEQUENCE -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}

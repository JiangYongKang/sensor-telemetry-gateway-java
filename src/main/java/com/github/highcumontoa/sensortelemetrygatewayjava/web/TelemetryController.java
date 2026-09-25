package com.github.highcumontoa.sensortelemetrygatewayjava.web;

import com.github.highcumontoa.sensortelemetrygatewayjava.model.IngestResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.TelemetryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 单条与批量两个上报入口共用 TelemetryService 管线，结论一致。 */
@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TelemetryService telemetryService;

    public TelemetryController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @PostMapping
    public ResponseEntity<IngestResult> report(@RequestBody String rawJson) {
        IngestResult result = telemetryService.ingest(rawJson);
        HttpStatus status = result.accepted()
                ? HttpStatus.ACCEPTED
                : GlobalExceptionHandler.statusFor(result.rejectionReason());
        return ResponseEntity.status(status).body(result);
    }

    @PostMapping("/batch")
    public ResponseEntity<List<IngestResult>> reportBatch(@RequestBody String rawJson) {
        List<IngestResult> results = telemetryService.ingestBatch(rawJson);
        return ResponseEntity.accepted().body(results);
    }
}

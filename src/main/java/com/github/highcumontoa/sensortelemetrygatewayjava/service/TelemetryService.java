package com.github.highcumontoa.sensortelemetrygatewayjava.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensortelemetrygatewayjava.clock.Clock;
import com.github.highcumontoa.sensortelemetrygatewayjava.config.GatewayProperties;
import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.GatewayException;
import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.MappedReport;
import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.ProtocolSupport;
import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.ReportEvaluator;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.AcceptedSample;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceState;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.IngestResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.ProtocolVersion;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RawReading;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RawReport;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectionReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.registry.DeviceRegistry;
import com.github.highcumontoa.sensortelemetrygatewayjava.store.RawReportStore;
import com.github.highcumontoa.sensortelemetrygatewayjava.web.dto.ReadingDto;
import com.github.highcumontoa.sensortelemetrygatewayjava.web.dto.ReportRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 上报接入编排。单条与批量入口共用同一条管线，
 * 因此同一输入在任何入口下的结论完全一致。
 */
@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final DeviceRegistry registry;
    private final RawReportStore rawReportStore;
    private final ReportEvaluator evaluator;
    private final ProtocolSupport protocolSupport;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public TelemetryService(DeviceRegistry registry, RawReportStore rawReportStore,
                            ReportEvaluator evaluator, ProtocolSupport protocolSupport,
                            Clock clock, GatewayProperties properties,
                            ObjectMapper objectMapper) {
        this.registry = registry;
        this.rawReportStore = rawReportStore;
        this.evaluator = evaluator;
        this.protocolSupport = protocolSupport;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public IngestResult ingest(String rawJson) {
        long receivedAt = clock.nowMillis();
        log.info("收到上报 raw={}", rawJson);

        ReportRequest request;
        try {
            request = objectMapper.readValue(rawJson, ReportRequest.class);
        } catch (JsonProcessingException ex) {
            return archiveAndReject(rawJson, null, null, receivedAt,
                    RejectionReason.PAYLOAD_INVALID, "JSON 无法解析: " + ex.getOriginalMessage());
        }
        return ingestParsed(request, rawJson, receivedAt);
    }

    /**
     * 批量入口：接受 JSON 数组（每个元素为一个上报对象）。
     * 数组整体非法解析时整批拒绝；数组内每个元素各自独立判定、独立留档，
     * 与单条入口共用 ingest 管线，结论完全一致。
     */
    public List<IngestResult> ingestBatch(String rawJsonArray) {
        long receivedAt = clock.nowMillis();
        List<JsonNode> items;
        try {
            JsonNode root = objectMapper.readTree(rawJsonArray);
            if (root == null || !root.isArray()) {
                return List.of(archiveAndReject(rawJsonArray, null, null, receivedAt,
                        RejectionReason.PAYLOAD_INVALID, "批量上报必须是 JSON 数组"));
            }
            items = new ArrayList<>();
            root.forEach(items::add);
        } catch (JsonProcessingException ex) {
            return List.of(archiveAndReject(rawJsonArray, null, null, receivedAt,
                    RejectionReason.PAYLOAD_INVALID, "批量上报 JSON 无法解析: " + ex.getOriginalMessage()));
        }
        List<IngestResult> results = new ArrayList<>();
        for (JsonNode item : items) {
            // 规范化为紧凑 JSON 作为该条原始报文留档，确定且可复现。
            results.add(ingest(item.toString()));
        }
        return results;
    }

    private IngestResult ingestParsed(ReportRequest request, String rawJson, long receivedAt) {
        if (request.deviceId() == null || request.deviceId().isBlank()) {
            return archiveAndReject(rawJson, request, null, receivedAt,
                    RejectionReason.MISSING_FIELD, "上报缺少必填字段 deviceId");
        }
        if (request.readings() == null || request.readings().isEmpty()) {
            return archiveAndReject(rawJson, request, null, receivedAt,
                    RejectionReason.MISSING_FIELD, "上报缺少必填字段 readings（至少一条读数）");
        }

        ProtocolVersion version;
        try {
            version = protocolSupport.parse(request.protocolVersion());
        } catch (GatewayException ex) {
            return archiveAndReject(rawJson, request, null, receivedAt,
                    ex.getReason(), ex.getMessage());
        }

        List<RawReading> rawReadings = request.readings().stream()
                .map(dto -> new RawReading(dto.type(), dto.value(), dto.unit()))
                .toList();
        MappedReport mapped = new MappedReport(request.deviceId(), version,
                request.timestamp(), protocolSupport.mapSequence(version, request.sequence()),
                rawReadings);

        DeviceState device = registry.find(request.deviceId());
        RawReport raw = new RawReport(request.deviceId(), version.name(),
                request.sequence(), rawReadings, rawJson, null, receivedAt);

        AcceptedSample sample;
        try {
            sample = evaluator.evaluate(raw, mapped);
            boolean applied = registry.applySample(device, sample);
            if (!applied) {
                return archiveAndReject(rawJson, request, version, receivedAt,
                        RejectionReason.DUPLICATE_SEQUENCE,
                        "设备 '" + request.deviceId() + "' 已接收过序列号 " + mapped.sequence()
                                + " 的上报，V2 序列号在窗口内必须唯一");
            }
        } catch (GatewayException ex) {
            return archiveAndReject(rawJson, request, version, receivedAt,
                    ex.getReason(), ex.getMessage());
        }

        rawReportStore.save(raw);
        IngestResult result = new IngestResult(request.deviceId(), true, null,
                sample.eventTime(), sample.readings());
        log.info("接入结论 accepted device={} eventTime={} readings={}",
                request.deviceId(), sample.eventTime(), sample.readings());
        return result;
    }

    private IngestResult archiveAndReject(String rawJson, ReportRequest request,
                                          ProtocolVersion parsedVersion, long receivedAt,
                                          RejectionReason reason, String detail) {
        String deviceId = request == null ? null : request.deviceId();
        String version = parsedVersion == null
                ? (request == null ? null : request.protocolVersion())
                : parsedVersion.name();
        List<RawReading> rawReadings;
        Long sequence = null;
        if (request != null && request.readings() != null) {
            rawReadings = request.readings().stream()
                    .map(dto -> new RawReading(dto.type(), dto.value(), dto.unit()))
                    .toList();
            sequence = request.sequence();
        } else {
            rawReadings = List.of();
        }
        rawReportStore.save(new RawReport(deviceId, version, sequence, rawReadings,
                rawJson, reason, receivedAt));
        log.warn("接入结论 rejected device={} reason={} 判定依据: {}", deviceId, reason, detail);
        return new IngestResult(deviceId, false, reason, null, List.of());
    }
}

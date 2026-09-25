package com.github.highcumontoa.sensortelemetrygatewayjava.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.Device;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceRegistration;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.IngestResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.MappedFields;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.NormalizedReading;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.ProtocolVersion;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RawReport;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RegistrationResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectedReport;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorCatalog;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.WindowAggregate;
import com.github.highcumontoa.sensortelemetrygatewayjava.normalization.ReadingNormalizer;
import com.github.highcumontoa.sensortelemetrygatewayjava.protocol.ProtocolMapper;
import com.github.highcumontoa.sensortelemetrygatewayjava.protocol.ProtocolMappers;
import com.github.highcumontoa.sensortelemetrygatewayjava.protocol.ReportMappingException;
import com.github.highcumontoa.sensortelemetrygatewayjava.state.Clock;
import com.github.highcumontoa.sensortelemetrygatewayjava.state.DeviceRegistry;
import com.github.highcumontoa.sensortelemetrygatewayjava.state.TelemetryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 所有入口（HTTP 单条、HTTP 批量、服务直调）共用的唯一业务入口。
 *
 * <p>上报校验流水线顺序固定（保证结论一致、原因可区分）：
 * <pre>
 *  1. JSON 语法            → MALFORMED_JSON
 *  2. 协议版本存在且受支持  → MISSING_REQUIRED_FIELD / UNSUPPORTED_PROTOCOL_VERSION
 *  3. 设备已注册           → UNKNOWN_DEVICE
 *  4. 上报版本==注册版本    → DEVICE_VERSION_MISMATCH
 *  5. 版本内字段映射        → MISSING_REQUIRED_FIELD / INVALID_PAYLOAD
 *  6. 类型编码受支持        → UNKNOWN_SENSOR_TYPE
 *  7. 类型在能力范围内      → SENSOR_NOT_IN_CAPABILITY
 *  8. 单位/量程归一化       → UNSUPPORTED_UNIT / VALUE_OUT_OF_RANGE
 *  9. 乱序/迟到判定         → LATE_REPORT
 * </pre>
 * 任何一步拒绝都保留原始报文。
 */
@Service
public class TelemetryService {

    public static final String ENTRY_SINGLE_HTTP = "HTTP_SINGLE";
    public static final String ENTRY_BATCH_HTTP = "HTTP_BATCH";
    public static final String ENTRY_SERVICE = "SERVICE";

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final ObjectMapper objectMapper;
    private final ProtocolMappers protocolMappers;
    private final ReadingNormalizer normalizer;
    private final DeviceRegistry registry;
    private final TelemetryStore store;
    private final Clock clock;

    public TelemetryService(ObjectMapper objectMapper, ProtocolMappers protocolMappers,
                            ReadingNormalizer normalizer, DeviceRegistry registry,
                            TelemetryStore store, Clock clock) {
        this.objectMapper = objectMapper;
        this.protocolMappers = protocolMappers;
        this.normalizer = normalizer;
        this.registry = registry;
        this.store = store;
        this.clock = clock;
    }

    public RegistrationResult register(DeviceRegistration registration) {
        return registry.register(registration);
    }

    public IngestResult ingest(String rawJson, String entryPoint) {
        long receivedAt = clock.nowMillis();

        JsonNode root;
        try {
            root = objectMapper.readTree(rawJson);
        } catch (Exception e) {
            return reject(new RawReport(rawJson, null, null, receivedAt, entryPoint),
                    RejectReason.MALFORMED_JSON, "payload is not valid JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            return reject(new RawReport(rawJson, null, null, receivedAt, entryPoint),
                    RejectReason.INVALID_PAYLOAD, "payload must be a JSON object");
        }

        String deviceId = textOrNull(root, "deviceId");
        String declaredVersion = extractVersionToken(root);
        RawReport raw = new RawReport(rawJson, deviceId, declaredVersion, receivedAt, entryPoint);

        // 步骤 2：版本存在性与受支持性（绝不按其它版本解析）
        if (declaredVersion == null) {
            return reject(raw, RejectReason.MISSING_REQUIRED_FIELD,
                    "missing protocol version (expected 'protocolVersion' or legacy 'ver')");
        }
        Optional<ProtocolVersion> versionOpt = ProtocolVersion.fromString(declaredVersion);
        if (versionOpt.isEmpty()) {
            return reject(raw, RejectReason.UNSUPPORTED_PROTOCOL_VERSION,
                    "unsupported protocol version: " + declaredVersion);
        }
        ProtocolVersion version = versionOpt.get();

        // 步骤 3：设备必须已注册
        if (deviceId == null) {
            return reject(raw, RejectReason.UNKNOWN_DEVICE, "deviceId is missing");
        }
        Optional<Device> deviceOpt = registry.find(deviceId);
        if (deviceOpt.isEmpty()) {
            return reject(raw, RejectReason.UNKNOWN_DEVICE,
                    "device is not registered: " + deviceId);
        }
        Device device = deviceOpt.get();

        // 步骤 4：上报版本必须与注册版本一致
        if (device.version() != version) {
            return reject(raw, RejectReason.DEVICE_VERSION_MISMATCH,
                    "report version " + version + " does not match registered version "
                            + device.version());
        }

        // 步骤 5：严格按该版本映射字段（含旧版本缺省语义）
        MappedFields mapped;
        try {
            ProtocolMapper mapper = protocolMappers.get(version);
            mapped = mapper.map(root, receivedAt);
        } catch (ReportMappingException e) {
            return reject(raw, e.rejectReason(), e.getMessage());
        }

        // 步骤 6：类型编码在该版本下必须可解析
        Optional<SensorType> typeOpt = SensorCatalog.resolve(mapped.sensorCode(), version);
        if (typeOpt.isEmpty()) {
            return reject(raw, RejectReason.UNKNOWN_SENSOR_TYPE,
                    "unknown sensor code for " + version + ": " + mapped.sensorCode());
        }
        SensorType sensorType = typeOpt.get();

        // 步骤 7：类型必须在设备声明的能力范围内
        if (!device.capability().sensorTypes().contains(sensorType)) {
            return reject(raw, RejectReason.SENSOR_NOT_IN_CAPABILITY,
                    "device " + deviceId + " capability " + device.capability().sensorTypes()
                            + " does not include " + sensorType);
        }

        // 步骤 8：单位换算与量程校验
        NormalizedReading reading;
        try {
            reading = normalizer.normalize(deviceId, version, mapped, receivedAt);
        } catch (ReportMappingException e) {
            return reject(raw, e.rejectReason(), e.getMessage());
        }

        // 步骤 9：乱序接纳 / 迟到拒绝（水位与锁在 store 内确定）
        Optional<RejectedReport> lateReject = store.accept(reading);
        if (lateReject.isPresent()) {
            RejectedReport r = lateReject.get();
            return reject(raw, r.reason(), r.detail());
        }

        IngestResult result = IngestResult.ok(reading, raw);
        log.info("[{}] ACCEPT input={} -> device={}, sensor={}, canonical={} {}, eventTime={}, basis={}",
                entryPoint, rawJson, deviceId, sensorType, reading.value(), reading.unit(),
                reading.eventTimeMillis(),
                "version=" + version
                        + (mapped.unitDefaulted() ? ", unit defaulted to " + reading.unit() : "")
                        + (mapped.eventTimeDefaulted() ? ", eventTime defaulted to receivedAt" : ""));
        return result;
    }

    public List<IngestResult> ingestBatch(String rawJsonArray, String entryPoint) {
        long receivedAt = clock.nowMillis();
        JsonNode array;
        try {
            array = objectMapper.readTree(rawJsonArray);
        } catch (Exception e) {
            RawReport raw = new RawReport(rawJsonArray, null, null, receivedAt, entryPoint);
            return List.of(reject(raw, RejectReason.MALFORMED_JSON,
                    "batch payload is not valid JSON: " + e.getMessage()));
        }
        if (array == null || !array.isArray()) {
            RawReport raw = new RawReport(rawJsonArray, null, null, receivedAt, entryPoint);
            return List.of(reject(raw, RejectReason.INVALID_PAYLOAD,
                    "batch payload must be a JSON array of report objects"));
        }
        List<IngestResult> results = new ArrayList<>(array.size());
        for (JsonNode element : array) {
            // 逐条重走单条流水线，仅重序列化形式不同，判定规则与结论完全一致。
            String elementJson = element.isObject() ? element.toString() : String.valueOf(element);
            results.add(ingest(elementJson, entryPoint));
        }
        return results;
    }

    public WindowAggregate aggregate(String deviceId, SensorType sensorType) {
        return store.aggregate(deviceId, sensorType);
    }

    private IngestResult reject(RawReport raw, RejectReason reason, String detail) {
        log.info("[{}] REJECT input={} device={} version={} -> reason={}, basis={}",
                raw.entryPoint(), raw.rawJson(), raw.deviceId(), raw.declaredVersion(),
                reason, detail);
        // 所有拒绝原因共用同一条留档路径：原始报文、原因与判定依据一并保留，便于追溯。
        store.recordRejected(raw, reason, detail);
        return IngestResult.reject(raw, reason, detail);
    }

    /**
     * 版本字段兼容读取：优先 V2 的 protocolVersion（文本），其次 V1 的 ver
     *（数字 1/2 规范为 "V1"/"V2"，文本则原样）。两者皆无返回 null。
     */
    private static String extractVersionToken(JsonNode root) {
        JsonNode v2 = root.get("protocolVersion");
        if (v2 != null && !v2.isNull()) {
            return v2.asText();
        }
        JsonNode v1 = root.get("ver");
        if (v1 != null && !v1.isNull()) {
            if (v1.isNumber()) {
                return "V" + v1.asLong();
            }
            return v1.asText();
        }
        return null;
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText().trim();
    }
}

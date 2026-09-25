package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import com.github.highcumontoa.sensortelemetrygatewayjava.model.IngestResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RawReport;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectionReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.web.dto.ReadingDto;
import com.github.highcumontoa.sensortelemetrygatewayjava.web.dto.ReportRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 字段缺失/类型未知/单位非法/量程越界/能力未声明等拒绝原因必须两两可区分。 */
class ReportValidationTest {

    private static final Logger log = LoggerFactory.getLogger(ReportValidationTest.class);

    private final Harness harness = Harness.create();

    @BeforeEach
    void setUp() {
        harness.devices().register("d1", "V2", List.of("TEMPERATURE", "HUMIDITY", "PRESSURE"));
        harness.devices().register("legacy", "V1", List.of("TEMPERATURE"));
    }

    @Test
    void outOfRangeRejected() {
        String json = json("d1", "V2", 1000L, 1L, List.of(r("TEMPERATURE", 999.0, "C")));
        log.info("输入: {}", json);
        IngestResult result = harness.telemetry().ingest(json);
        log.info("结论: accepted={} reason={} 判定依据: 999C 超出温度量程 [-50,150]",
                result.accepted(), result.rejectionReason());
        assertFalse(result.accepted());
        assertEquals(RejectionReason.VALUE_OUT_OF_RANGE, result.rejectionReason());
    }

    @Test
    void valueConvertedFromFahrenheitIsRangeCheckedInCanonicalUnit() {
        // 200F = 93.33C 在量程内，接受；400F = 204.4C 越界，拒绝。
        String ok = json("d1", "V2", 1000L, 1L, List.of(r("TEMPERATURE", 200.0, "F")));
        String bad = json("d1", "V2", 1001L, 2L, List.of(r("TEMPERATURE", 400.0, "F")));
        log.info("输入(接受): {}", ok);
        log.info("输入(拒绝): {}", bad);
        assertTrue(harness.telemetry().ingest(ok).accepted());
        IngestResult result = harness.telemetry().ingest(bad);
        assertEquals(RejectionReason.VALUE_OUT_OF_RANGE, result.rejectionReason());
    }

    @Test
    void inconsistentUnitRejected() {
        String json = json("d1", "V2", 1000L, 1L, List.of(r("TEMPERATURE", 25.0, "Pa")));
        log.info("输入: {}；判定依据: Pa 不是 TEMPERATURE 支持单位", json);
        IngestResult result = harness.telemetry().ingest(json);
        assertFalse(result.accepted());
        assertEquals(RejectionReason.INVALID_UNIT, result.rejectionReason());
    }

    @Test
    void missingValueRejected() {
        String json = json("d1", "V2", 1000L, 1L, List.of(r("TEMPERATURE", null, "C")));
        log.info("输入: {}；判定依据: value 为必填", json);
        assertEquals(RejectionReason.MISSING_FIELD, harness.telemetry().ingest(json).rejectionReason());
    }

    @Test
    void v2RequiresExplicitUnitAndV1DefaultsToCanonical() {
        String v2noUnit = json("d1", "V2", 1000L, 1L, List.of(r("TEMPERATURE", 25.0, null)));
        log.info("输入: {}；判定依据: V2 缺 unit => MISSING_FIELD（旧版 V1 才允许缺省为标准单位）", v2noUnit);
        assertEquals(RejectionReason.MISSING_FIELD, harness.telemetry().ingest(v2noUnit).rejectionReason());

        String v1noUnit = json("legacy", "V1", 1000L, null, List.of(r("TEMPERATURE", 25.0, null)));
        log.info("输入: {}；判定依据: V1 缺 unit 稳定缺省为标准单位 C，接受", v1noUnit);
        IngestResult result = harness.telemetry().ingest(v1noUnit);
        assertTrue(result.accepted());
        assertEquals("C", result.readings().get(0).unit());
        assertEquals(25.0, result.readings().get(0).value(), 1e-9);
    }

    @Test
    void unknownSensorTypeRejected() {
        String json = json("d1", "V2", 1000L, 1L, List.of(r("WIND", 5.0, "m/s")));
        log.info("输入: {}；判定依据: 无 WIND 类型 => SENSOR_TYPE_UNKNOWN", json);
        assertEquals(RejectionReason.SENSOR_TYPE_UNKNOWN, harness.telemetry().ingest(json).rejectionReason());
    }

    @Test
    void undeclaredCapabilityRejected() {
        // d1 未声明 ILLUMINANCE
        String json = json("d1", "V2", 1000L, 1L, List.of(r("ILLUMINANCE", 300.0, "lx")));
        log.info("输入: {}；判定依据: 设备能力集不含 ILLUMINANCE => CAPABILITY_NOT_DECLARED", json);
        assertEquals(RejectionReason.CAPABILITY_NOT_DECLARED, harness.telemetry().ingest(json).rejectionReason());
    }

    @Test
    void versionMismatchAndUnsupportedVersionAreDistinct() {
        // d1 注册为 V2，按 V1 上报 => 版本不匹配；按 V3 上报 => 版本不受支持
        String mismatch = json("d1", "V1", 1000L, null, List.of(r("TEMPERATURE", 25.0, "C")));
        String unsupported = json("d1", "V3", 1000L, null, List.of(r("TEMPERATURE", 25.0, "C")));
        log.info("输入: {}；判定依据: 设备注册 V2，拒绝按 V1 解析", mismatch);
        log.info("输入: {}；判定依据: 网关不认识 V3，不回退解析", unsupported);
        assertEquals(RejectionReason.PROTOCOL_VERSION_MISMATCH, harness.telemetry().ingest(mismatch).rejectionReason());
        assertEquals(RejectionReason.UNSUPPORTED_PROTOCOL_VERSION, harness.telemetry().ingest(unsupported).rejectionReason());
    }

    @Test
    void unregisteredDeviceRejected() {
        String json = json("ghost", "V2", 1000L, 1L, List.of(r("TEMPERATURE", 25.0, "C")));
        log.info("输入: {}；判定依据: 设备未注册 => DEVICE_NOT_REGISTERED", json);
        assertEquals(RejectionReason.DEVICE_NOT_REGISTERED, harness.telemetry().ingest(json).rejectionReason());
    }

    @Test
    void futureTimestampBeyondToleranceRejected() {
        // 接收时间为时钟当前值；事件时间超出未来容忍 5s => FUTURE_TIMESTAMP。
        String json = json("d1", "V2", 1_000_000L + 10_000L, 9L, List.of(r("TEMPERATURE", 25.0, "C")));
        log.info("输入: {} 接收时间=1000000 容忍=5000ms；判定依据: 事件时间超前过多 => FUTURE_TIMESTAMP", json);
        assertEquals(RejectionReason.FUTURE_TIMESTAMP, harness.telemetry().ingest(json).rejectionReason());
    }

    @Test
    void malformedJsonRejected() {
        String raw = "{not-json";
        log.info("输入: {}；判定依据: 解析失败 => PAYLOAD_INVALID", raw);
        assertEquals(RejectionReason.PAYLOAD_INVALID, harness.telemetry().ingest(raw).rejectionReason());
    }

    @Test
    void rejectedReportsAreArchivedWithRawPayload() {
        String json = json("d1", "V2", 1000L, 1L, List.of(r("TEMPERATURE", 999.0, "C")));
        harness.telemetry().ingest(json);
        List<RawReport> archived = harness.rawStore().findByDevice("d1");
        log.info("输入: 越界上报 1 条；判定依据: 留档数量=1 且原始报文逐字保留");
        assertEquals(1, archived.size());
        assertEquals(RejectionReason.VALUE_OUT_OF_RANGE, archived.get(0).rejectionReason());
        assertEquals(json, archived.get(0).rawJson());
    }

    private static ReadingDto r(String type, Double value, String unit) {
        return new ReadingDto(type, value, unit);
    }

    private String json(String deviceId, String version, Long ts, Long seq, List<ReadingDto> readings) {
        ReportRequest request = new ReportRequest(deviceId, version, ts, seq, readings);
        try {
            return harness.mapper().writeValueAsString(request);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}

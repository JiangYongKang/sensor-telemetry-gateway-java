package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.IngestResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.TelemetryService;

/**
 * 上报校验：单位换算、量程越界、字段缺失、未知类型/单位、能力越界、版本问题——
 * 结论必须一致且失败原因两两可区分；被拒原始报文必须留档。
 */
class ValidationRejectionTest {

    private static final Logger log = LoggerFactory.getLogger(ValidationRejectionTest.class);

    private TelemetryTestFixtures.TestStack stack;
    private TelemetryService service;

    @BeforeEach
    void setUp() {
        stack = TelemetryTestFixtures.newStack();
        service = stack.service();
        service.register(TelemetryTestFixtures.registration("v2dev", "V2",
                SensorType.TEMPERATURE, SensorType.HUMIDITY, SensorType.PRESSURE));
        service.register(TelemetryTestFixtures.registration("v1dev", "V1",
                SensorType.TEMPERATURE, SensorType.HUMIDITY, SensorType.PRESSURE));
        service.register(TelemetryTestFixtures.registration("tempOnly", "V2", SensorType.TEMPERATURE));
    }

    private IngestResult ingest(String json) {
        log.info("INPUT {}", json);
        IngestResult result = service.ingest(json, TelemetryService.ENTRY_SERVICE);
        log.info("DECISION accepted={} reason={} sensor={} reading={} basis={}",
                result.accepted(), result.rejectReason(), result.sensorType(),
                result.reading(), result.detail());
        // 任何结论都必须保留原始报文
        assertNotNull(result.raw(), "原始上报必须保留");
        assertEquals(json, result.raw().rawJson());
        return result;
    }

    @Test
    void fahrenheitIsConvertedToCelsiusAndAccepted() {
        // 77F = 25C，在 [-40,85] 内
        var result = ingest("""
                {"deviceId":"v2dev","protocolVersion":"V2","sensor":"TEMPERATURE",
                 "value":77.0,"unit":"F","eventTime":1700000000000}""");
        assertTrue(result.accepted());
        assertEquals("C", result.reading().unit());
        assertEquals(25.0, result.reading().value(), 1e-9);
    }

    @Test
    void humidityRatioIsScaledToPercentAndAccepted() {
        var result = ingest("""
                {"deviceId":"v2dev","protocolVersion":"V2","sensor":"HUMIDITY",
                 "value":0.42,"unit":"ratio","eventTime":1700000000000}""");
        assertTrue(result.accepted());
        assertEquals("%", result.reading().unit());
        assertEquals(42.0, result.reading().value(), 1e-9);
    }

    @Test
    void pressureKpaIsScaledToHpaAndAccepted() {
        // 100 kPa = 1000 hPa
        var result = ingest("""
                {"deviceId":"v2dev","protocolVersion":"V2","sensor":"PRESSURE",
                 "value":100.0,"unit":"kPa","eventTime":1700000000000}""");
        assertTrue(result.accepted());
        assertEquals(1000.0, result.reading().value(), 1e-9);
    }

    @Test
    void outOfRangeAfterConversionIsRejected() {
        // 200F = 93.33C > 85，换算后越界，必须拒绝（不能只看原始数值）
        var result = ingest("""
                {"deviceId":"v2dev","protocolVersion":"V2","sensor":"TEMPERATURE",
                 "value":200.0,"unit":"F","eventTime":1700000000000}""");
        assertFalse(result.accepted());
        assertEquals(RejectReason.VALUE_OUT_OF_RANGE, result.rejectReason());
    }

    @Test
    void boundaryValuesAreInRange() {
        var low = ingest("""
                {"deviceId":"v2dev","protocolVersion":"V2","sensor":"TEMPERATURE",
                 "value":-40.0,"unit":"C","eventTime":1700000000000}""");
        var high = ingest("""
                {"deviceId":"v2dev","protocolVersion":"V2","sensor":"TEMPERATURE",
                 "value":85.0,"unit":"C","eventTime":1700000000001}""");
        assertTrue(low.accepted() && high.accepted(), "量程为闭区间，边界值合法");
    }

    @Test
    void unsupportedUnitIsRejected() {
        var result = ingest("""
                {"deviceId":"v2dev","protocolVersion":"V2","sensor":"TEMPERATURE",
                 "value":25.0,"unit":"K","eventTime":1700000000000}""");
        assertFalse(result.accepted());
        assertEquals(RejectReason.UNSUPPORTED_UNIT, result.rejectReason());
    }

    @Test
    void unknownSensorTypeIsRejected() {
        var result = ingest("""
                {"deviceId":"v2dev","protocolVersion":"V2","sensor":"WIND",
                 "value":1.0,"unit":"m/s","eventTime":1700000000000}""");
        assertFalse(result.accepted());
        assertEquals(RejectReason.UNKNOWN_SENSOR_TYPE, result.rejectReason());
    }

    @Test
    void sensorOutsideCapabilityIsRejected() {
        var result = ingest("""
                {"deviceId":"tempOnly","protocolVersion":"V2","sensor":"PRESSURE",
                 "value":1000.0,"unit":"hPa","eventTime":1700000000000}""");
        assertFalse(result.accepted());
        assertEquals(RejectReason.SENSOR_NOT_IN_CAPABILITY, result.rejectReason());
    }

    @Test
    void missingRequiredFieldV2IsRejected() {
        var result = ingest("""
                {"deviceId":"v2dev","protocolVersion":"V2","sensor":"TEMPERATURE",
                 "value":25.0,"unit":"C"}""");
        assertFalse(result.accepted());
        assertEquals(RejectReason.MISSING_REQUIRED_FIELD, result.rejectReason());
    }

    @Test
    void malformedJsonIsRejectedAndKept() {
        String raw = "{not-json";
        log.info("INPUT {}", raw);
        var result = service.ingest(raw, TelemetryService.ENTRY_SERVICE);
        log.info("DECISION accepted={} reason={} basis={}", result.accepted(),
                result.rejectReason(), result.detail());
        assertFalse(result.accepted());
        assertEquals(RejectReason.MALFORMED_JSON, result.rejectReason());
        assertEquals(raw, result.raw().rawJson());
    }

    @Test
    void unknownDeviceIsRejected() {
        var result = ingest("""
                {"deviceId":"ghost","protocolVersion":"V2","sensor":"TEMPERATURE",
                 "value":25.0,"unit":"C","eventTime":1700000000000}""");
        assertFalse(result.accepted());
        assertEquals(RejectReason.UNKNOWN_DEVICE, result.rejectReason());
    }

    @Test
    void versionMismatchWithRegistrationIsRejected() {
        // v1dev 注册为 V1，却以 V2 上报
        var result = ingest("""
                {"deviceId":"v1dev","protocolVersion":"V2","sensor":"TEMPERATURE",
                 "value":25.0,"unit":"C","eventTime":1700000000000}""");
        assertFalse(result.accepted());
        assertEquals(RejectReason.DEVICE_VERSION_MISMATCH, result.rejectReason());
    }

    @Test
    void unsupportedReportVersionIsRejectedNotGuessed() {
        var result = ingest("""
                {"deviceId":"v2dev","protocolVersion":"V3","sensor":"TEMPERATURE",
                 "value":25.0,"unit":"C","eventTime":1700000000000}""");
        assertFalse(result.accepted());
        assertEquals(RejectReason.UNSUPPORTED_PROTOCOL_VERSION, result.rejectReason(),
                "不支持的版本必须明确拒绝，而不是按 V1/V2 解析");
    }

    @Test
    void v1MissingUnitDefaultsToCanonicalAndMissingTimeDefaultsToReceivedAt() {
        var result = ingest("""
                {"deviceId":"v1dev","ver":1,"type":1,"value":23.5}""");
        assertTrue(result.accepted(), "V1 缺单位/缺时间使用确定缺省语义，应当接受: "
                + result.detail());
        assertEquals("C", result.reading().unit());
        assertEquals(TelemetryTestFixtures.T0, result.reading().eventTimeMillis(),
                "V1 缺事件时间时缺省为网关接收时间");
    }
}

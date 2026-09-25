package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.Capability;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;

/**
 * 设备注册：重复注册幂等、声明冲突可区分、未知版本明确拒绝。
 */
class DeviceRegistrationTest {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistrationTest.class);

    private final TelemetryTestFixtures.TestStack stack = TelemetryTestFixtures.newStack();

    @Test
    void firstRegistrationSucceedsAndIsNotIdempotent() {
        var reg = TelemetryTestFixtures.registration("dev-1", "V2", SensorType.TEMPERATURE);
        log.info("INPUT register {}", reg);
        var result = stack.registry().register(reg);
        log.info("DECISION accepted={} idempotent={} reason={} basis={}",
                result.accepted(), result.idempotent(), result.rejectReason(), result.detail());

        assertTrue(result.accepted());
        assertFalse(result.idempotent());
        assertEquals(1, stack.registry().size());
    }

    @Test
    void identicalRepeatRegistrationIsIdempotent() {
        var first = TelemetryTestFixtures.registration("dev-2", "V1",
                SensorType.TEMPERATURE, SensorType.HUMIDITY);
        var repeat = TelemetryTestFixtures.registration("dev-2", "V1",
                SensorType.HUMIDITY, SensorType.TEMPERATURE);
        log.info("INPUT first={} repeat={} (能力集合顺序不同，语义一致)", first, repeat);

        var r1 = stack.registry().register(first);
        var r2 = stack.registry().register(repeat);
        log.info("DECISION r1(accepted={},idempotent={}) r2(accepted={},idempotent={},reason={}) basis={}",
                r1.accepted(), r1.idempotent(), r2.accepted(), r2.idempotent(), r2.rejectReason(),
                r2.detail());

        assertTrue(r1.accepted());
        assertTrue(r2.accepted());
        assertTrue(r2.idempotent(), "声明完全一致的重复注册必须幂等成功");
        assertEquals(1, stack.registry().size());
    }

    @Test
    void conflictingVersionRepeatRegistrationIsRejected() {
        var v1 = TelemetryTestFixtures.registration("dev-3", "V1", SensorType.TEMPERATURE);
        var v2 = TelemetryTestFixtures.registration("dev-3", "V2", SensorType.TEMPERATURE);
        log.info("INPUT first={} conflicting={}", v1, v2);

        stack.registry().register(v1);
        var result = stack.registry().register(v2);
        log.info("DECISION accepted={} reason={} basis={}", result.accepted(),
                result.rejectReason(), result.detail());

        assertFalse(result.accepted());
        assertEquals(RejectReason.DUPLICATE_REGISTRATION_CONFLICT, result.rejectReason());
    }

    @Test
    void conflictingCapabilityRepeatRegistrationIsRejected() {
        var r1 = TelemetryTestFixtures.registration("dev-4", "V2", SensorType.TEMPERATURE);
        var r2 = new com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceRegistration(
                "dev-4", "V2", new Capability(Set.of(SensorType.PRESSURE)));
        log.info("INPUT first={} conflicting={}", r1, r2);

        stack.registry().register(r1);
        var result = stack.registry().register(r2);
        log.info("DECISION accepted={} reason={} basis={}", result.accepted(),
                result.rejectReason(), result.detail());

        assertFalse(result.accepted());
        assertEquals(RejectReason.DUPLICATE_REGISTRATION_CONFLICT, result.rejectReason());
    }

    @Test
    void unsupportedVersionAtRegistrationIsRejectedClearly() {
        var reg = TelemetryTestFixtures.registration("dev-5", "V9", SensorType.TEMPERATURE);
        log.info("INPUT register with unsupported version: {}", reg);
        var result = stack.registry().register(reg);
        log.info("DECISION accepted={} reason={} basis={}", result.accepted(),
                result.rejectReason(), result.detail());

        assertFalse(result.accepted());
        assertEquals(RejectReason.UNSUPPORTED_PROTOCOL_VERSION, result.rejectReason());
        assertEquals(0, stack.registry().size());
    }
}

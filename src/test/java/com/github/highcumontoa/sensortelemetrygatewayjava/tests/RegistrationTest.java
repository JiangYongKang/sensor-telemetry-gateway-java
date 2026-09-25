package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.GatewayException;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RegistrationResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectionReason;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 设备注册：幂等重复注册、声明冲突、不支持版本的拒绝原因必须可区分。 */
class RegistrationTest {

    private static final Logger log = LoggerFactory.getLogger(RegistrationTest.class);

    private final Harness harness = Harness.create();

    @Test
    void duplicateRegistrationIsIdempotent() {
        log.info("输入: 重复注册 dev-1 V2 [TEMPERATURE, PRESSURE]，两次声明顺序不同；"
                + "判定依据: 版本相同且能力集合相等（与顺序无关）即幂等");
        RegistrationResult first = harness.devices().register("dev-1", "V2",
                List.of("TEMPERATURE", "PRESSURE"));
        RegistrationResult second = harness.devices().register("dev-1", "v2",
                List.of("PRESSURE", "TEMPERATURE"));
        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.capabilities(), second.capabilities());
    }

    @Test
    void conflictingVersionRejectedWithDistinctReason() {
        harness.devices().register("dev-2", "V1", List.of("TEMPERATURE"));
        log.info("输入: dev-2 已注册 V1，再次以 V2 注册；判定依据: 版本不同 => REGISTRATION_CONFLICT");
        GatewayException ex = assertThrows(GatewayException.class,
                () -> harness.devices().register("dev-2", "V2", List.of("TEMPERATURE")));
        assertEquals(RejectionReason.REGISTRATION_CONFLICT, ex.getReason());
    }

    @Test
    void conflictingCapabilitiesRejectedWithDistinctReason() {
        harness.devices().register("dev-3", "V2", List.of("TEMPERATURE"));
        log.info("输入: dev-3 已注册能力 TEMPERATURE，再次声明增加 HUMIDITY；"
                + "判定依据: 能力集合不等 => REGISTRATION_CONFLICT");
        GatewayException ex = assertThrows(GatewayException.class,
                () -> harness.devices().register("dev-3", "V2",
                        List.of("TEMPERATURE", "HUMIDITY")));
        assertEquals(RejectionReason.REGISTRATION_CONFLICT, ex.getReason());
    }

    @Test
    void unsupportedVersionExplicitlyRejected() {
        log.info("输入: 注册版本 V3；判定依据: 仅 V1/V2 可解析 => UNSUPPORTED_PROTOCOL_VERSION，"
                + "绝不按其他版本解析");
        GatewayException ex = assertThrows(GatewayException.class,
                () -> harness.devices().register("dev-4", "V3", List.of("TEMPERATURE")));
        assertEquals(RejectionReason.UNSUPPORTED_PROTOCOL_VERSION, ex.getReason());
    }

    @Test
    void unknownCapabilityTypeRejected() {
        log.info("输入: 注册能力 RADIATION；判定依据: 无此传感器类型 => SENSOR_TYPE_UNKNOWN");
        GatewayException ex = assertThrows(GatewayException.class,
                () -> harness.devices().register("dev-5", "V2", List.of("RADIATION")));
        assertEquals(RejectionReason.SENSOR_TYPE_UNKNOWN, ex.getReason());
    }

    @Test
    void emptyCapabilitiesRejected() {
        log.info("输入: 注册 capabilities=[]；判定依据: 必须声明至少一项能力 => MISSING_FIELD");
        GatewayException ex = assertThrows(GatewayException.class,
                () -> harness.devices().register("dev-6", "V2", List.of()));
        assertEquals(RejectionReason.MISSING_FIELD, ex.getReason());
    }
}

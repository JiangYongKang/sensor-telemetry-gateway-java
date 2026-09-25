package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceStatus;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.WindowAggregate;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.TelemetryService;
import com.github.highcumontoa.sensortelemetrygatewayjava.state.TelemetryStore;

/**
 * 乱序 / 迟到 / 窗口聚合 / 离线判定：规则确定、可复现。
 * 默认窗口 60s、最大迟到 10s、离线阈值 30s（见 TelemetryProperties 默认值）。
 */
class OutOfOrderAndLateReportTest {

    private static final Logger log = LoggerFactory.getLogger(OutOfOrderAndLateReportTest.class);

    private static final long T = TelemetryTestFixtures.T0;

    private TelemetryTestFixtures.TestStack stack;
    private TelemetryService service;
    private TelemetryStore store;

    @BeforeEach
    void setUp() {
        stack = TelemetryTestFixtures.newStack();
        service = stack.service();
        store = stack.store();
        service.register(TelemetryTestFixtures.registration("dev", "V2", SensorType.TEMPERATURE));
    }

    private void reportAt(long eventTime, double value) {
        String json = "{\"deviceId\":\"dev\",\"protocolVersion\":\"V2\",\"sensor\":\"TEMPERATURE\","
                + "\"value\":" + value + ",\"unit\":\"C\",\"eventTime\":" + eventTime + "}";
        log.info("INPUT receivedAt={} {}", stack.nowMillis(), json);
        var result = service.ingest(json, TelemetryService.ENTRY_SERVICE);
        log.info("DECISION accepted={} reason={} basis={}", result.accepted(),
                result.rejectReason(), result.detail());
    }

    private WindowAggregate aggregate() {
        WindowAggregate a = store.aggregate("dev", SensorType.TEMPERATURE);
        log.info("QUERY aggregate -> watermark={}, window=[{},{}], count={}, latest={}, min={}, max={}, avg={}",
                a.watermarkMillis(), a.windowStartMillis(), a.windowEndMillis(),
                a.count(), a.latestValue(), a.minValue(), a.maxValue(), a.avgValue());
        return a;
    }

    @Test
    void outOfOrderWithinLatenessIsAcceptedAndCountedExactlyOnce() {
        reportAt(T + 60_000, 30.0); // 水位 T+60s
        reportAt(T + 55_000, 20.0); // 乱序但仅落后 5s <= 10s，接受
        WindowAggregate a = aggregate();
        assertEquals(2, a.count(), "乱序读数必须计入且仅计一次");
        assertEquals(20.0, a.minValue(), 1e-9);
        assertEquals(30.0, a.maxValue(), 1e-9);
        assertEquals(25.0, a.avgValue(), 1e-9);
        // 最新值按事件时间取 30.0，不被随后到达的旧读数 20.0 回退
        assertEquals(30.0, a.latestValue(), 1e-9);
        assertEquals(T + 60_000, a.latestEventTimeMillis());
        assertEquals(T + 60_000, a.watermarkMillis(), "水位单调不减");
    }

    @Test
    void lateBeyondHorizonIsRejectedWithDistinctReason() {
        reportAt(T + 60_000, 30.0); // 水位 T+60s
        reportAt(T + 45_000, 5.0);  // 落后 15s > 10s，拒绝
        WindowAggregate a = aggregate();
        assertEquals(1, a.count(), "迟到读数不得入窗");
        assertEquals(30.0, a.latestValue(), 1e-9);

        var rejected = store.rejected("dev");
        assertEquals(1, rejected.size());
        assertEquals(RejectReason.LATE_REPORT, rejected.get(0).reason());
        log.info("RETAINED rejected raw-basis={}", rejected.get(0).detail());
    }

    @Test
    void slidingWindowPrunesReadingsOutOfWindow() {
        reportAt(T + 10_000, 10.0);
        reportAt(T + 70_000, 70.0); // 水位推进到 T+70s，窗口起点 T+10s（闭区间，10s 保留）
        WindowAggregate a = aggregate();
        assertEquals(2, a.count());

        reportAt(T + 71_000, 71.0); // 窗口起点 T+11s，10s 读数出窗裁剪
        a = aggregate();
        assertEquals(2, a.count(), "出窗读数必须被裁剪");
        assertEquals(70.0, a.minValue(), 1e-9);
        assertEquals(71.0, a.maxValue(), 1e-9);
    }

    @Test
    void latestValueAdvancesWithNewerEventTime() {
        reportAt(T + 1_000, 1.0);
        reportAt(T + 2_000, 2.0);
        WindowAggregate a = aggregate();
        assertEquals(2.0, a.latestValue(), 1e-9);
        reportAt(T + 1_500, 1.5); // 乱序接受，但不回退最新值
        a = aggregate();
        assertEquals(2.0, a.latestValue(), 1e-9, "乱序旧值不得令最新值回退");
    }

    @Test
    void offlineIsDeterminedOnlyByLastValidReportTime() {
        stack.clock().setTime(T);
        reportAt(T, 20.0); // 接收时间 T

        stack.clock().setTime(T + 30_000);
        DeviceStatus online = statusWithLookup(T + 30_000);
        DeviceStatus offline = statusWithLookup(T + 30_001);

        log.info("QUERY status at +30000ms -> online={} (阈值闭区间)", online.online());
        log.info("QUERY status at +30001ms -> online={} basis=now-lastValid={}ms > {}ms",
                offline.online(), 30_001L, offline.offlineThresholdMillis());
        assertTrue(online.online(), "恰好等于阈值仍在线");
        assertFalse(offline.online(), "超过阈值 1ms 即离线");
        assertEquals(T, online.lastValidReportTimeMillis());
    }

    private DeviceStatus statusWithLookup(long now) {
        stack.clock().setTime(now);
        var registry = stack.registry();
        return store.status("dev", new TelemetryStore.DeviceLookup() {
            @Override
            public boolean registered(String deviceId) {
                return registry.find(deviceId).isPresent();
            }

            @Override
            public com.github.highcumontoa.sensortelemetrygatewayjava.model.ProtocolVersion
                    version(String deviceId) {
                return registry.find(deviceId)
                        .map(com.github.highcumontoa.sensortelemetrygatewayjava.model.Device::version)
                        .orElse(null);
            }
        });
    }
}

package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceState;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.IngestResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.WindowStats;
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

/**
 * 乱序与迟到处理：聚合按事件时间过滤，与到达顺序无关；
 * 迟到样本计入窗口但不使最新值回退；离线判定基于最后有效事件时间。
 */
class OutOfOrderWindowTest {

    private static final Logger log = LoggerFactory.getLogger(OutOfOrderWindowTest.class);

    private final Harness harness = Harness.create();
    private static final long T0 = 1_000_000L;

    @BeforeEach
    void setUp() {
        harness.devices().register("dev", "V1", List.of("TEMPERATURE"));
    }

    @Test
    void outOfOrderArrivalProducesSameWindowAsInOrder() {
        // 事件时间 0/10/20s 的三个样本，故意按 10s -> 0s -> 20s 的顺序乱序到达
        // （接收时钟严格递增，后两条均为迟到上报）。
        sendV1(T0 + 10_000, T0 + 10_000, 10.0);
        sendV1(T0, T0 + 20_000, 0.0);
        sendV1(T0 + 20_000, T0 + 30_000, 20.0);
        log.info("输入: 乱序到达 (eventTime=10010000@到达10s, eventTime=10000000@到达20s, "
                + "eventTime=10020000@到达30s)；"
                + "判定依据: 窗口 (at-60s, at] 按事件时间过滤并按 (eventTime,seq) 稳定聚合");

        WindowStats stats = harness.aggregation().stats("dev",
                com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType.TEMPERATURE,
                T0 + 30_000);
        assertEquals(3, stats.count());
        assertEquals(0.0, stats.min(), 1e-9);
        assertEquals(20.0, stats.max(), 1e-9);
        assertEquals(10.0, stats.avg(), 1e-9);
    }

    @Test
    void samplesOutsideWindowAreExcludedByEventTime() {
        sendV1(T0, T0, 0.0);
        sendV1(T0 + 59_999, T0 + 60_000, 59.0);
        harness.clock().set(T0 + 120_000);
        sendV1(T0 + 120_000, T0 + 120_000, 120.0);
        log.info("输入: eventTime=0@到达0, eventTime=59999@到达60000，at=120000 再发 120000；"
                + "判定依据: 左开区间 (at-60s, at] 只含 120000");
        WindowStats stats = harness.aggregation().stats("dev",
                com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType.TEMPERATURE,
                T0 + 120_000);
        assertEquals(1, stats.count());
        assertEquals(120.0, stats.max(), 1e-9);
    }

    @Test
    void lateSampleIsCountedButDoesNotRegressLatest() {
        sendV1(T0 + 20_000, T0 + 20_000, 20.0);
        // 迟到 15s 的旧样本，仍落在 60s 窗口内：计入聚合，但不得覆盖最新值。
        sendV1(T0 + 5_000, T0 + 20_000, 5.0);
        log.info("输入: 先到 eventTime=20000 value=20，迟到 eventTime=5000 value=5；"
                + "判定依据: 5000 在窗口内计入聚合；最新值只随不旧的事件时间更新");

        DeviceState state = harness.registry().find("dev");
        synchronized (state) {
            assertEquals(20.0, state.latest()
                    .get(com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType.TEMPERATURE)
                    .value(), 1e-9);
        }
        WindowStats stats = harness.aggregation().stats("dev",
                com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType.TEMPERATURE,
                T0 + 20_000);
        assertEquals(2, stats.count());
        assertEquals(5.0, stats.min(), 1e-9);
        assertEquals(20.0, stats.max(), 1e-9);
    }

    @Test
    void offlineIsDeterminedByLastValidEventTime() {
        sendV1(T0, T0, 0.0);
        harness.clock().set(T0 + 30_000);
        boolean atBoundary = harness.aggregation().isOnline(harness.registry().find("dev"), T0 + 30_000);
        harness.clock().set(T0 + 30_001);
        boolean pastThreshold = harness.aggregation().isOnline(harness.registry().find("dev"), T0 + 30_001);
        log.info("输入: 最后有效事件时间=10000000，离线阈值 30000ms；"
                + "判定依据: at-last<=30000 在线（闭区间），之后离线");
        assertTrue(atBoundary, "恰好 30s 边界仍在线");
        assertFalse(pastThreshold, "超过 30s 即离线");

        // 一次有效上报后立刻恢复在线。
        sendV1(T0 + 40_000, T0 + 40_000, 40.0);
        assertTrue(harness.aggregation().isOnline(harness.registry().find("dev"), T0 + 40_000));
    }

    @Test
    void rejectedReportDoesNotUpdateLastValidTime() {
        sendV1(T0, T0, 0.0);
        String bad = json("dev", "V1", T0 + 50_000, null,
                List.of(new ReadingDto("TEMPERATURE", 999.0, "C")));
        IngestResult rejected = harness.telemetry().ingest(bad);
        assertFalse(rejected.accepted());
        long last = harness.registry().find("dev").lastValidEventTime();
        log.info("输入: {}；判定依据: 越界上报不改变最后有效事件时间，仍为 {}", bad, T0);
        assertEquals(T0, last);
    }

    private void sendV1(long eventTime, long arriveAt, double value) {
        harness.clock().set(arriveAt);
        String json = json("dev", "V1", eventTime, null,
                List.of(new ReadingDto("TEMPERATURE", value, "C")));
        IngestResult result = harness.telemetry().ingest(json);
        assertTrue(result.accepted(), "上报应被接受: " + result.rejectionReason());
    }

    private String json(String deviceId, String version, Long ts, Long seq, List<ReadingDto> readings) {
        try {
            return harness.mapper().writeValueAsString(new ReportRequest(deviceId, version, ts, seq, readings));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}

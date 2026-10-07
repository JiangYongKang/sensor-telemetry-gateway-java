package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.GatewayException;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.AvailabilityStats;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.IngestResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.OfflineInterval;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备可用性统计：在线口径以“被接受上报的接收时间”为准，
 * 与事件时间无关；同一区间反复查询结果完全一致；
 * 超出保留范围明确拒绝而非静默算错。
 */
class AvailabilityStatsTest {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityStatsTest.class);

    private final Harness harness = Harness.create();
    private static final long T0 = 1_000_000L;
    private static final long OFF = 30_000L; // 默认离线阈值

    @BeforeEach
    void setUp() {
        harness.devices().register("dev", "V1", List.of("TEMPERATURE"));
    }

    @Test
    void continuousReportingYieldsFullyOnline() {
        sendV1(T0 + 10_000, T0 + 10_000, 10.0);
        sendV1(T0 + 20_000, T0 + 20_000, 20.0);
        log.info("输入: 接收时间 {} 与 {} 各一条有效上报；查询区间 [{}, {}]；"
                        + "判定依据: 在线区间 [s, s+{}] 合并后覆盖整个查询区间",
                T0 + 10_000, T0 + 20_000, T0 + 10_000, T0 + 50_000, OFF);

        AvailabilityStats stats = harness.availability()
                .availability("dev", T0 + 10_000, T0 + 50_000);
        assertEquals(40_000, stats.onlineMillis());
        assertEquals(1.0, stats.onlineRatio(), 1e-9);
        assertTrue(stats.offlineIntervals().isEmpty(), "持续在线不应有掉线段");
    }

    @Test
    void gapsBetweenReportsBecomeOfflineIntervals() {
        sendV1(T0 + 10_000, T0 + 10_000, 10.0);
        sendV1(T0 + 50_000, T0 + 50_000, 50.0);
        log.info("输入: 接收时间 {} 与 {}；查询 [{}, {}]；判定依据: 在线区间 [{},{}] 与 [{},{}]，"
                        + "其余为掉线段", T0 + 10_000, T0 + 50_000, T0, T0 + 100_000,
                T0 + 10_000, T0 + 40_000, T0 + 50_000, T0 + 80_000);

        AvailabilityStats stats = harness.availability().availability("dev", T0, T0 + 100_000);
        assertEquals(60_000, stats.onlineMillis());
        assertEquals(0.6, stats.onlineRatio(), 1e-9);
        assertEquals(List.of(
                new OfflineInterval(T0, T0 + 10_000),
                new OfflineInterval(T0 + 40_000, T0 + 50_000),
                new OfflineInterval(T0 + 80_000, T0 + 100_000)), stats.offlineIntervals());
    }

    @Test
    void lateOrOutOfOrderReportStillMarksDeviceOnline() {
        // 设备长期沉默后，一条事件时间极旧（乱序/滞后）的有效上报在当前时刻到达。
        harness.clock().set(T0 + 500_000);
        sendV1(T0, T0 + 500_000, 1.0);
        log.info("输入: 事件时间 {}（极旧）于接收时间 {} 到达；判定依据: 在线看接收时间，"
                + "事件时间落后不影响在线结论", T0, T0 + 500_000);

        assertTrue(harness.aggregation().isOnline(harness.registry().find("dev"), T0 + 500_000),
                "刚成功接入有效上报（哪怕事件时间很旧）必须在线");
        assertTrue(harness.aggregation().isOnline(harness.registry().find("dev"), T0 + 529_999),
                "接收时间后 30s 内仍在线");

        AvailabilityStats stats = harness.availability()
                .availability("dev", T0 + 500_000, T0 + 505_000);
        assertEquals(5_000, stats.onlineMillis());
        assertEquals(1.0, stats.onlineRatio(), 1e-9);

        // 对照：该条上报到达之前，设备确实处于离线。
        AvailabilityStats before = harness.availability()
                .availability("dev", T0 + 400_000, T0 + 500_000);
        assertEquals(0, before.onlineMillis());
        assertEquals(List.of(new OfflineInterval(T0 + 400_000, T0 + 500_000)),
                before.offlineIntervals());
    }

    @Test
    void rejectedReportDoesNotChangeAvailability() {
        sendV1(T0 + 10_000, T0 + 10_000, 10.0);
        AvailabilityStats before = harness.availability().availability("dev", T0, T0 + 100_000);

        harness.clock().set(T0 + 20_000);
        String bad = json("dev", "V1", T0 + 20_000, null,
                List.of(new ReadingDto("TEMPERATURE", 999.0, "C")));
        IngestResult rejected = harness.telemetry().ingest(bad);
        assertFalse(rejected.accepted());
        log.info("输入: 越界上报 {} 于 {} 被拒；判定依据: 被拒绝的上报不改变可用性结论", bad, T0 + 20_000);

        AvailabilityStats after = harness.availability().availability("dev", T0, T0 + 100_000);
        assertEquals(before, after, "拒绝前后同一区间的可用性结论必须完全一致");
        assertEquals(T0 + 10_000, harness.registry().find("dev").lastValidIngestTime());
    }

    @Test
    void repeatedQueryOfSameRangeIsDeterministic() {
        sendV1(T0 + 10_000, T0 + 10_000, 10.0);
        sendV1(T0 + 50_000, T0 + 50_000, 50.0);
        AvailabilityStats first = harness.availability().availability("dev", T0, T0 + 100_000);
        AvailabilityStats second = harness.availability().availability("dev", T0, T0 + 100_000);
        log.info("输入: 同一区间 [{}, {}] 连续查询两次；判定依据: 结果逐值相等", T0, T0 + 100_000);
        assertEquals(first, second);
    }

    @Test
    void queryBeyondRetentionIsExplicitlyRejected() {
        harness.properties().setAvailabilityRetentionMillis(60_000);
        sendV1(T0 + 10_000, T0 + 10_000, 10.0);
        // 推进时钟并再发一条，触发历史淘汰：cutoff = T0+140_000，floor = cutoff + 30_000。
        sendV1(T0 + 200_000, T0 + 200_000, 20.0);
        long floor = harness.registry().find("dev").historyFloor();
        log.info("输入: 保留时长 60000ms，淘汰后 floor={}；查询 from={}（早于 floor）；"
                + "判定依据: 必须明确拒绝 RETENTION_EXCEEDED 而非静默算错", floor, T0);
        assertEquals(T0 + 170_000, floor);

        GatewayException ex = assertThrows(GatewayException.class,
                () -> harness.availability().availability("dev", T0, T0 + 200_000));
        assertEquals(RejectionReason.RETENTION_EXCEEDED, ex.getReason());

        // 覆盖范围内的查询仍然可用且准确：保留记录只有 T0+200_000 一条。
        AvailabilityStats stats = harness.availability()
                .availability("dev", T0 + 170_000, T0 + 220_000);
        assertEquals(20_000, stats.onlineMillis());
        assertEquals(List.of(new OfflineInterval(T0 + 170_000, T0 + 200_000)),
                stats.offlineIntervals());
    }

    @Test
    void invalidRangeAndUnknownDeviceAreRejected() {
        log.info("输入: from>=to 的区间与未注册设备；判定依据: INVALID_TIME_RANGE / DEVICE_NOT_REGISTERED");
        GatewayException rangeEx = assertThrows(GatewayException.class,
                () -> harness.availability().availability("dev", T0 + 1000, T0 + 1000));
        assertEquals(RejectionReason.INVALID_TIME_RANGE, rangeEx.getReason());

        GatewayException deviceEx = assertThrows(GatewayException.class,
                () -> harness.availability().availability("nobody", T0, T0 + 1000));
        assertEquals(RejectionReason.DEVICE_NOT_REGISTERED, deviceEx.getReason());
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
            return harness.mapper().writeValueAsString(
                    new ReportRequest(deviceId, version, ts, seq, readings));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}

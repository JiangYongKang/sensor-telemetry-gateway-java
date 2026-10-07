package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.GatewayException;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.AvailabilityReport;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.IngestResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.OfflinePeriod;
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
 * 设备可用性统计：在线口径与单台设备状态一致（基于最近一次有效上报的接收时间）；
 * 乱序/滞后上报不影响在线结论；被拒绝的上报不改变可用性；
 * 同一区间反复查询结果完全一致；超出保留范围明确报 OUT_OF_RETENTION。
 */
class AvailabilityTest {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityTest.class);

    private final Harness harness = Harness.create();
    private static final long T0 = 1_000_000L;
    private static final long OFFLINE = 30_000L;

    @BeforeEach
    void setUp() {
        harness.devices().register("dev", "V1", List.of("TEMPERATURE"));
    }

    @Test
    void onlineDurationRatioAndOfflinePeriodsAreComputed() {
        // T0+10s 上报一次 => 在线区间 [T0+10s, T0+40s]。
        sendV1(T0 + 10_000, T0 + 10_000, 20.0);
        harness.clock().set(T0 + 50_000);
        log.info("输入: 注册于 {}，T0+10s 一次有效上报，查询 [{}, {}]；判定依据: 在线区间=[ingest, ingest+{}ms]",
                T0, T0, T0 + 50_000, OFFLINE);

        AvailabilityReport report = harness.availability().availability("dev", T0, T0 + 50_000);
        log.info("结果: {}", report);
        assertEquals(50_000L, report.totalMillis());
        assertEquals(30_000L, report.onlineMillis());
        assertEquals(0.6d, report.onlineRatio(), 1e-9);
        assertEquals(List.of(new OfflinePeriod(T0, T0 + 10_000),
                new OfflinePeriod(T0 + 40_000, T0 + 50_000)), report.offlinePeriods());

        // 同一区间反复查询，结果必须逐值完全一样。
        AvailabilityReport again = harness.availability().availability("dev", T0, T0 + 50_000);
        assertEquals(report, again, "同一区间反复查询结果必须完全一致");
    }

    @Test
    void lateArrivingReportWithOldEventTimeKeepsDeviceOnline() {
        // 接收于 T0+100s，但事件时间早到 T0：按事件时间口径会误判离线，按接收时间口径必须在线。
        sendV1(T0, T0 + 100_000, 21.0);
        boolean online = harness.aggregation().isOnline(harness.registry().find("dev"), T0 + 100_000);
        log.info("输入: eventTime={} 但接收于 {}（滞后 100s）；判定依据: 在线看接收时间，"
                + "at-lastIngest=0<=30000 => 在线", T0, T0 + 100_000);
        assertTrue(online, "滞后上报只要被接受，设备就必须在线");

        harness.clock().set(T0 + 120_000);
        AvailabilityReport report = harness.availability()
                .availability("dev", T0 + 90_000, T0 + 120_000);
        log.info("结果: {}", report);
        assertEquals(20_000L, report.onlineMillis());
        assertEquals(List.of(new OfflinePeriod(T0 + 90_000, T0 + 100_000)), report.offlinePeriods());
    }

    @Test
    void rejectedReportDoesNotChangeAvailability() {
        sendV1(T0 + 10_000, T0 + 10_000, 20.0);
        harness.clock().set(T0 + 50_000);
        // 已超出离线阈值，此时一条越界上报被拒绝，不得把设备“救活”。
        String bad = json("dev", "V1", T0 + 50_000, null,
                List.of(new ReadingDto("TEMPERATURE", 999.0, "C")));
        IngestResult rejected = harness.telemetry().ingest(bad);
        assertFalse(rejected.accepted());
        log.info("输入: 越界上报 {} 于 {} 被拒绝；判定依据: 拒绝不更新接收时间，设备保持离线", bad, T0 + 50_000);
        assertFalse(harness.aggregation().isOnline(harness.registry().find("dev"), T0 + 50_000));

        AvailabilityReport report = harness.availability().availability("dev", T0, T0 + 50_000);
        assertEquals(30_000L, report.onlineMillis(), "被拒绝的上报不得改变累计在线时长");
    }

    @Test
    void multipleOfflinePeriodsAreReportedInOrder() {
        sendV1(T0 + 10_000, T0 + 10_000, 20.0);   // 在线 [+10s, +40s]
        sendV1(T0 + 100_000, T0 + 100_000, 22.0); // 在线 [+100s, +130s]
        harness.clock().set(T0 + 120_000);
        log.info("输入: 两次上报接收于 T0+10s 与 T0+100s，查询 [T0, T0+120s]；"
                + "判定依据: 掉线时段为在线区间的补集");

        AvailabilityReport report = harness.availability().availability("dev", T0, T0 + 120_000);
        log.info("结果: {}", report);
        assertEquals(List.of(new OfflinePeriod(T0, T0 + 10_000),
                new OfflinePeriod(T0 + 40_000, T0 + 100_000)), report.offlinePeriods());
        assertEquals(50_000L, report.onlineMillis());
        assertEquals(120_000L, report.totalMillis());
    }

    @Test
    void queryBeforeRetentionHorizonFailsExplicitly() {
        harness.properties().setAvailabilityRetentionMillis(60_000L);
        sendV1(T0 + 10_000, T0 + 10_000, 20.0);    // 在线区间 [T0+10s, T0+40s]
        sendV1(T0 + 200_000, T0 + 200_000, 23.0);  // 触发淘汰：保留边界 = T0+140s
        harness.clock().set(T0 + 200_000);
        long coveredFrom = harness.registry().find("dev").availabilityCoveredFrom();
        log.info("输入: 保留时长 60s，第二笔上报于 T0+200s 触发淘汰；判定依据: 可查下界={}，"
                + "更早的查询必须报 OUT_OF_RETENTION 而不是静默给错数", coveredFrom);
        assertEquals(T0 + 40_000, coveredFrom);

        GatewayException ex = assertThrows(GatewayException.class,
                () -> harness.availability().availability("dev", T0, T0 + 200_000));
        assertEquals(RejectionReason.OUT_OF_RETENTION, ex.getReason());
        log.info("超出保留范围反馈: {}", ex.getMessage());

        // 保留范围内的查询不受影响，且结果确定。
        AvailabilityReport report = harness.availability()
                .availability("dev", T0 + 40_000, T0 + 200_000);
        assertEquals(0L, report.onlineMillis(), "T0+40s 到 T0+200s 之间无在线区间");
        assertEquals(report, harness.availability().availability("dev", T0 + 40_000, T0 + 200_000));
    }

    @Test
    void invalidRangeAndUnknownDeviceAreRejected() {
        GatewayException unregistered = assertThrows(GatewayException.class,
                () -> harness.availability().availability("nobody", T0, T0 + 1_000));
        assertEquals(RejectionReason.DEVICE_NOT_REGISTERED, unregistered.getReason());

        GatewayException backwards = assertThrows(GatewayException.class,
                () -> harness.availability().availability("dev", T0 + 1_000, T0));
        assertEquals(RejectionReason.INVALID_TIME_RANGE, backwards.getReason());

        GatewayException missing = assertThrows(GatewayException.class,
                () -> harness.availability().availability("dev", null, T0));
        assertEquals(RejectionReason.MISSING_FIELD, missing.getReason());
        log.info("输入: 未注册设备/from>=to/缺参数；判定依据: 分别映射 "
                + "DEVICE_NOT_REGISTERED/INVALID_TIME_RANGE/MISSING_FIELD");
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

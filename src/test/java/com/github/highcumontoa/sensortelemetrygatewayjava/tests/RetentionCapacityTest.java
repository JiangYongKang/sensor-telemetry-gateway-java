package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.GatewayException;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceState;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.IngestResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectionReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.WindowStats;
import com.github.highcumontoa.sensortelemetrygatewayjava.web.dto.ReadingDto;
import com.github.highcumontoa.sensortelemetrygatewayjava.web.dto.ReportRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容量与保留行为：窗口样本、序列号去重、原始留档均有可配置上限；
 * 淘汰只移除旧数据，不造成计数重复、丢数或在线状态抖动；
 * 涉及已淘汰范围的窗口查询明确报 OUT_OF_RETENTION，绝不静默给出偏小的假结果。
 */
class RetentionCapacityTest {

    private static final Logger log = LoggerFactory.getLogger(RetentionCapacityTest.class);

    private static final long T0 = 1_000_000L;

    @Test
    void windowSampleCapEvictsOldestAndAffectedQueryFailsExplicitly() {
        Harness harness = Harness.create();
        harness.properties().setMaxWindowSamples(10);
        harness.devices().register("dev", "V1", List.of("TEMPERATURE"));

        // 同一接收时刻连发 20 条（事件时间都在 60s 查询窗口内），窗口上限 10 => 只留最新 10 条。
        harness.clock().set(T0);
        for (int i = 0; i < 20; i++) {
            IngestResult result = harness.telemetry().ingest(json(harness, "dev", "V1",
                    T0 - 100 + i, null, 20.0 + i % 5));
            assertTrue(result.accepted());
        }
        DeviceState state = harness.registry().find("dev");
        int windowSize;
        long evictedUpTo;
        synchronized (state) {
            windowSize = state.window().size();
            evictedUpTo = state.windowEvictedUpTo();
        }
        log.info("输入: maxWindowSamples=10，窗口内连发 20 条；判定依据: 只留最新 10 条，"
                + "被淘汰样本仍在查询窗口内 => 查询必须报 OUT_OF_RETENTION 而非静默返回 count=10");
        assertEquals(10, windowSize);
        assertTrue(evictedUpTo >= 0);

        GatewayException ex = assertThrows(GatewayException.class,
                () -> harness.aggregation().stats("dev", SensorType.TEMPERATURE, T0));
        assertEquals(RejectionReason.OUT_OF_RETENTION, ex.getReason());
        log.info("容量淘汰后的窗口查询反馈: {}", ex.getMessage());
    }

    @Test
    void timeBasedWindowEvictionMarksRetentionBoundary() {
        Harness harness = Harness.create();
        harness.devices().register("dev", "V1", List.of("TEMPERATURE"));
        send(harness, T0, T0, 20.0);
        // 时钟前进 120s 后再上报，触发按时间淘汰（窗口 60s），T0 的样本被移除。
        send(harness, T0 + 120_000, T0 + 120_000, 25.0);
        log.info("输入: T0 上报一次，T0+120s 再上报触发时间淘汰；判定依据: 当前窗口查询正常，"
                + "涉及被淘汰样本的历史窗口必须报 OUT_OF_RETENTION");

        WindowStats current = harness.aggregation().stats("dev", SensorType.TEMPERATURE, T0 + 120_000);
        assertEquals(1, current.count());
        GatewayException ex = assertThrows(GatewayException.class,
                () -> harness.aggregation().stats("dev", SensorType.TEMPERATURE, T0 + 30_000));
        assertEquals(RejectionReason.OUT_OF_RETENTION, ex.getReason());
        log.info("超出保留范围反馈: {}", ex.getMessage());
    }

    @Test
    void seenSequencesAreBoundedAndEvictionDoesNotFlapOnlineState() {
        Harness harness = Harness.create();
        harness.properties().setMaxSeenSequences(5);
        harness.devices().register("dev", "V2", List.of("TEMPERATURE"));

        harness.clock().set(T0);
        for (int seq = 0; seq < 7; seq++) {
            IngestResult result = harness.telemetry().ingest(json(harness, "dev", "V2",
                    T0 - 10 + seq, (long) seq, 20.0));
            assertTrue(result.accepted(), "seq=" + seq + " 应被接受: " + result.rejectionReason());
        }
        DeviceState state = harness.registry().find("dev");
        int seenSize;
        synchronized (state) {
            seenSize = state.seenSequences().size();
        }
        log.info("输入: maxSeenSequences=5，连发序列号 0..6；判定依据: 去重状态淘汰最旧后 size<=5，"
                + "且在线状态不随淘汰抖动");
        assertEquals(5, seenSize);
        // 淘汰发生在写入锁内，刚上报成功的设备必须保持在线。
        assertTrue(harness.aggregation().isOnline(state, T0));
        // 仍在去重状态内的序列号仍然会被拒绝（幂等语义不因容量淘汰而立即失效）。
        IngestResult dup = harness.telemetry().ingest(json(harness, "dev", "V2", T0 - 4, 6L, 21.0));
        assertEquals(RejectionReason.DUPLICATE_SEQUENCE, dup.rejectionReason());
    }

    @Test
    void rawReportStoreIsBounded() {
        Harness harness = Harness.create();
        harness.properties().setMaxRawReports(10);
        harness.devices().register("dev", "V1", List.of("TEMPERATURE"));

        harness.clock().set(T0);
        for (int i = 0; i < 25; i++) {
            harness.telemetry().ingest(json(harness, "dev", "V1", T0 - 100 + i, null, 20.0));
        }
        log.info("输入: maxRawReports=10，连发 25 条；判定依据: 留档只保留最新 10 条，"
                + "evictedCount 记录淘汰总数");
        assertEquals(10, harness.rawStore().findAll().size());
        assertEquals(15, harness.rawStore().evictedCount());
    }

    private void send(Harness harness, long eventTime, long arriveAt, double value) {
        harness.clock().set(arriveAt);
        IngestResult result = harness.telemetry().ingest(
                json(harness, "dev", "V1", eventTime, null, value));
        assertTrue(result.accepted(), "上报应被接受: " + result.rejectionReason());
    }

    private String json(Harness harness, String deviceId, String version, Long ts, Long seq,
                        double value) {
        try {
            return harness.mapper().writeValueAsString(new ReportRequest(deviceId, version, ts, seq,
                    List.of(new ReadingDto("TEMPERATURE", value, "C"))));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}

package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.GatewayException;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.AvailabilityStats;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceState;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.FleetOverview;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.IngestResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectionReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.web.dto.ReadingDto;
import com.github.highcumontoa.sensortelemetrygatewayjava.web.dto.ReportRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容量与保留行为：窗口样本、去重状态、可用性历史与原始留档均有可配置上限；
 * 淘汰不造成重复计数、不丢未到期数据、不引起在线状态抖动；
 * 超出保留范围的历史查询得到明确拒绝。
 */
class CapacityRetentionTest {

    private static final Logger log = LoggerFactory.getLogger(CapacityRetentionTest.class);

    private static final long T0 = 1_000_000L;

    @Test
    void windowSamplesAndDedupStateAreCapped() {
        Harness harness = Harness.create();
        harness.properties().setMaxWindowSamples(10);
        harness.devices().register("dev", "V2", List.of("TEMPERATURE"));

        for (int i = 0; i < 50; i++) {
            IngestResult result = ingest(harness, "dev", T0 + i, (long) i);
            assertTrue(result.accepted(), "第 " + i + " 条应被接受");
        }
        DeviceState state = harness.registry().find("dev");
        synchronized (state) {
            log.info("输入: 窗口上限 10，连续接受 50 条；判定依据: 窗口与去重集合均不超过 10");
            assertEquals(10, state.window().size(), "窗口样本数必须有界");
            assertEquals(10, state.seenSequences().size(), "去重状态必须随窗口一起有界");
        }
        assertEquals(10, harness.aggregation()
                .stats("dev", SensorType.TEMPERATURE, T0 + 49).count());

        // 窗口内的序列号仍然去重（拒绝）；且拒绝不污染环境。
        IngestResult dup = ingest(harness, "dev", T0 + 49, 49L);
        assertFalse(dup.accepted());
        assertEquals(RejectionReason.DUPLICATE_SEQUENCE, dup.rejectionReason());

        // 已被淘汰的序列号可再接收（去重语义本就限定在窗口内），
        // 但其旧事件时间立即被容量淘汰，绝不允许造成重复计数。
        IngestResult reAccepted = ingest(harness, "dev", T0 + 5, 5L);
        assertTrue(reAccepted.accepted());
        synchronized (state) {
            assertEquals(10, state.window().size(), "淘汰后窗口仍必须有界");
        }
        assertEquals(10, harness.aggregation()
                .stats("dev", SensorType.TEMPERATURE, T0 + 49).count(), "不得重复计数");
        log.info("判定依据: 窗口内序列号去重生效；已淘汰序列号再接收不造成重复计数");
    }

    @Test
    void ingestHistoryIsBoundedByCountAndRaisesFloor() {
        Harness harness = Harness.create();
        harness.properties().setMaxIngestHistory(20);
        harness.devices().register("dev", "V1", List.of("TEMPERATURE"));

        for (int i = 0; i < 50; i++) {
            harness.clock().set(T0 + i * 1_000L);
            IngestResult result = ingest(harness, "dev", T0 + i * 1_000L, null);
            assertTrue(result.accepted());
        }
        DeviceState state = harness.registry().find("dev");
        long floor;
        synchronized (state) {
            log.info("输入: 历史上限 20 条，连续接受 50 条；判定依据: 历史条数有界且 floor 提升");
            assertEquals(20, state.ingestHistory().size(), "接入历史条数必须有界");
            floor = state.historyFloor();
        }
        // 保留最旧一条为 T0+30_000，floor = 30_000 + 30_000(离线阈值)。
        assertEquals(T0 + 60_000, floor);

        GatewayException ex = assertThrows(GatewayException.class,
                () -> harness.availability().availability("dev", T0, T0 + 49_000));
        assertEquals(RejectionReason.RETENTION_EXCEEDED, ex.getReason(),
                "超出保留范围的查询必须明确拒绝");

        // 覆盖范围内仍可准确回答：保留记录 T0+30_000..T0+49_000，
        // 在线区间并集覆盖 [T0+30_000, T0+79_000]。
        AvailabilityStats stats = harness.availability()
                .availability("dev", T0 + 60_000, T0 + 80_000);
        assertEquals(19_000, stats.onlineMillis());
    }

    @Test
    void ingestHistoryIsBoundedByRetentionTime() {
        Harness harness = Harness.create();
        harness.properties().setAvailabilityRetentionMillis(60_000);
        harness.devices().register("dev", "V1", List.of("TEMPERATURE"));

        harness.clock().set(T0 + 10_000);
        ingest(harness, "dev", T0 + 10_000, null);
        harness.clock().set(T0 + 200_000);
        ingest(harness, "dev", T0 + 200_000, null);

        DeviceState state = harness.registry().find("dev");
        synchronized (state) {
            log.info("输入: 保留时长 60000ms，时钟推进到 {} 后再接入一条；"
                    + "判定依据: 过旧记录被淘汰，floor 提升到 cutoff+离线阈值", T0 + 200_000);
            assertEquals(1, state.ingestHistory().size());
            assertEquals(T0 + 170_000, state.historyFloor());
        }
        GatewayException ex = assertThrows(GatewayException.class,
                () -> harness.availability().availability("dev", T0, T0 + 200_000));
        assertEquals(RejectionReason.RETENTION_EXCEEDED, ex.getReason());
    }

    @Test
    void rawReportArchiveIsBounded() {
        Harness harness = Harness.create();
        harness.properties().setRawReportCapacity(10);
        harness.devices().register("dev", "V1", List.of("TEMPERATURE"));

        for (int i = 0; i < 25; i++) {
            harness.clock().set(T0 + i * 1_000L);
            ingest(harness, "dev", T0 + i * 1_000L, null);
        }
        log.info("输入: 留档上限 10 条，连续上报 25 条；判定依据: 留档总数不超过 10");
        assertEquals(10, harness.rawStore().findAll().size());
        assertTrue(harness.rawStore().findByDevice("dev").size() <= 10);
    }

    @Test
    void evictionDoesNotFlapOnlineStatus() {
        Harness harness = Harness.create();
        harness.properties().setMaxWindowSamples(5);
        harness.properties().setMaxIngestHistory(5);
        harness.devices().register("dev", "V1", List.of("TEMPERATURE"));

        for (int i = 0; i < 30; i++) {
            harness.clock().set(T0 + i * 1_000L);
            ingest(harness, "dev", T0 + i * 1_000L, null);
        }
        // 淘汰已大量发生，但在线状态只取决于最后有效接收时间，必须稳定在线。
        DeviceState state = harness.registry().find("dev");
        for (int k = 0; k < 5; k++) {
            assertTrue(harness.aggregation().isOnline(state, T0 + 29_000),
                    "淘汰过程中在线状态不得抖动（第 " + k + " 次复核）");
        }
        FleetOverview first = harness.availability().fleetOverview();
        FleetOverview second = harness.availability().fleetOverview();
        log.info("输入: 窗口/历史上限均为 5，接受 30 条后连续两次概览；"
                + "判定依据: 在线状态稳定且两次概览逐值相等");
        assertEquals(1, first.onlineCount());
        assertEquals(first, second, "无新数据时概览必须稳定");
    }

    private IngestResult ingest(Harness harness, String deviceId, long eventTime, Long seq) {
        try {
            String json = harness.mapper().writeValueAsString(new ReportRequest(deviceId,
                    seq == null ? "V1" : "V2", eventTime, seq,
                    List.of(new ReadingDto("TEMPERATURE", 21.0, "C"))));
            return harness.telemetry().ingest(json);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}

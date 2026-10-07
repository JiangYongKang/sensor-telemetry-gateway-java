package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceOverview;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.FleetOverview;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.IngestResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.web.dto.ReadingDto;
import com.github.highcumontoa.sensortelemetrygatewayjava.web.dto.ReportRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机群级在线概览：计数与每台设备的最近有效上报时间来自同一判定时刻的
 * 自洽快照；与上报并发时不得出现“判离线却展示很新的上报时间”这类矛盾。
 */
class FleetOverviewTest {

    private static final Logger log = LoggerFactory.getLogger(FleetOverviewTest.class);

    private static final long T0 = 1_000_000L;
    private static final long OFF = 30_000L;

    @Test
    void overviewCountsAndLastReportTimes() {
        Harness harness = Harness.create();
        harness.devices().register("d1", "V1", List.of("TEMPERATURE"));
        harness.devices().register("d2", "V1", List.of("TEMPERATURE"));
        harness.devices().register("d3", "V1", List.of("TEMPERATURE"));
        send(harness, "d1", T0 + 5_000);
        send(harness, "d2", T0 + 8_000);

        harness.clock().set(T0 + 20_000);
        FleetOverview overview = harness.availability().fleetOverview();
        log.info("输入: 3 台设备，d1@{}、d2@{} 有有效上报，d3 从未上报；判定时刻 {}；"
                        + "判定依据: 距判定时刻 {}ms 内有有效接收即在线",
                T0 + 5_000, T0 + 8_000, T0 + 20_000, OFF);
        assertEquals(T0 + 20_000, overview.evaluatedAt());
        assertEquals(3, overview.devices().size());
        assertEquals(2, overview.onlineCount());
        assertEquals(1, overview.offlineCount());
        assertEquals(overview.devices().size(),
                overview.onlineCount() + overview.offlineCount());

        DeviceOverview d1 = overview.devices().get(0);
        DeviceOverview d2 = overview.devices().get(1);
        DeviceOverview d3 = overview.devices().get(2);
        assertEquals("d1", d1.deviceId());
        assertEquals("ONLINE", d1.status());
        assertEquals(T0 + 5_000, d1.lastValidIngestTime());
        assertEquals("ONLINE", d2.status());
        assertEquals(T0 + 8_000, d2.lastValidIngestTime());
        assertEquals("OFFLINE", d3.status());
        assertNull(d3.lastValidIngestTime(), "从未上报的设备最近上报时间应为 null");

        // 全部沉默超过离线阈值后：全部离线。
        harness.clock().set(T0 + 100_000);
        FleetOverview later = harness.availability().fleetOverview();
        log.info("输入: 时钟推进到 {}（所有设备沉默超阈值）；判定依据: 全部离线", T0 + 100_000);
        assertEquals(0, later.onlineCount());
        assertEquals(3, later.offlineCount());
    }

    @Test
    void overviewIsSelfConsistentUnderConcurrentReports() throws InterruptedException {
        Harness harness = Harness.create();
        int deviceCount = 8;
        for (int i = 0; i < deviceCount; i++) {
            harness.devices().register("dev-" + i, "V1", List.of("TEMPERATURE"));
        }
        // 固定时钟：所有接受的上报接收时间都等于判定时刻，行级自洽性必须恒成立。
        harness.clock().set(T0 + 1_000_000);

        int reporters = 4;
        int readers = 4;
        int rounds = 200;
        ExecutorService pool = Executors.newFixedThreadPool(reporters + readers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(reporters + readers);
        ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();

        for (int r = 0; r < reporters; r++) {
            int reporterId = r;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < rounds; k++) {
                        send(harness, "dev-" + ((reporterId + k) % deviceCount),
                                T0 + 1_000_000);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < rounds; k++) {
                        FleetOverview overview = harness.availability().fleetOverview();
                        verifySelfConsistent(overview, deviceCount, violations);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        log.info("输入: {} 个上报线程 × {} 条 + {} 个概览线程 × {} 次并发；"
                        + "判定依据: 每次概览行级自洽（状态与最近上报时间、计数互相一致）",
                reporters, rounds, readers, rounds);
        assertTrue(violations.isEmpty(), "并发概览出现自相矛盾: " + violations);
    }

    private void verifySelfConsistent(FleetOverview overview, int deviceCount,
                                      ConcurrentLinkedQueue<String> violations) {
        if (overview.devices().size() != deviceCount) {
            violations.add("设备数不完整: " + overview.devices().size());
            return;
        }
        long online = 0;
        for (DeviceOverview row : overview.devices()) {
            boolean expectOnline = row.lastValidIngestTime() != null
                    && overview.evaluatedAt() - row.lastValidIngestTime()
                    <= overview.offlineThresholdMillis();
            boolean rowOnline = "ONLINE".equals(row.status());
            if (rowOnline != expectOnline) {
                violations.add("行级矛盾: " + row + " evaluatedAt=" + overview.evaluatedAt());
            }
            if (rowOnline) {
                online++;
            }
        }
        if (online != overview.onlineCount()
                || overview.offlineCount() != deviceCount - overview.onlineCount()) {
            violations.add("计数矛盾: online=" + overview.onlineCount()
                    + " offline=" + overview.offlineCount() + " 实际在线行=" + online);
        }
    }

    private void send(Harness harness, String deviceId, long arriveAt) {
        harness.clock().set(arriveAt);
        try {
            String json = harness.mapper().writeValueAsString(new ReportRequest(deviceId, "V1",
                    arriveAt, null, List.of(new ReadingDto("TEMPERATURE", 21.0, "C"))));
            IngestResult result = harness.telemetry().ingest(json);
            assertTrue(result.accepted(), "上报应被接受: " + result.rejectionReason());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}

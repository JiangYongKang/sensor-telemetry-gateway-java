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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机群级在线概览：同一判定时刻下计数与每台设备状态自洽；
 * 与上报并发时任何一次查询结果内部都不矛盾；
 * 判定口径与单台设备状态查询一致（基于最近一次有效上报的接收时间）。
 */
class FleetOverviewTest {

    private static final Logger log = LoggerFactory.getLogger(FleetOverviewTest.class);

    private static final long T0 = 1_000_000L;
    private static final long OFFLINE = 30_000L;

    @Test
    void overviewReflectsEachDeviceAndCountsConsistently() {
        Harness harness = Harness.create();
        harness.devices().register("a", "V1", List.of("TEMPERATURE"));
        harness.devices().register("b", "V1", List.of("TEMPERATURE"));
        harness.devices().register("c", "V1", List.of("TEMPERATURE"));

        sendV1(harness, "a", T0 + 10_000, T0 + 10_000, 20.0);
        sendV1(harness, "b", T0 + 10_000, T0 + 10_000, 21.0);
        sendV1(harness, "b", T0 + 30_000, T0 + 30_000, 22.0);
        // c 从未上报。
        harness.clock().set(T0 + 50_000);
        log.info("输入: a 最后接收于 T0+10s，b 最后接收于 T0+30s，c 从未上报，判定时刻 T0+50s；"
                + "判定依据: at-lastIngest<={}ms 在线 => a 离线、b 在线、c 离线", OFFLINE);

        FleetOverview overview = harness.fleet().overview();
        log.info("结果: {}", overview);
        assertEquals(T0 + 50_000, overview.evaluatedAt());
        assertEquals(3, overview.totalDevices());
        assertEquals(1, overview.onlineCount());
        assertEquals(2, overview.offlineCount());
        assertEquals(overview.totalDevices(), overview.onlineCount() + overview.offlineCount());

        Map<String, DeviceOverview> byId = overview.devices().stream()
                .collect(Collectors.toMap(DeviceOverview::deviceId, Function.identity()));
        assertEquals("OFFLINE", byId.get("a").status());
        assertEquals(T0 + 10_000, byId.get("a").lastValidIngestTime());
        assertEquals("ONLINE", byId.get("b").status());
        assertEquals(T0 + 30_000, byId.get("b").lastValidIngestTime());
        assertEquals("OFFLINE", byId.get("c").status());
        assertEquals(-1L, byId.get("c").lastValidIngestTime());

        // 与单台设备状态查询口径一致。
        assertEquals(harness.aggregation().isOnline(harness.registry().find("b"), T0 + 50_000),
                "ONLINE".equals(byId.get("b").status()));
    }

    @Test
    void concurrentOverviewQueriesAreAlwaysSelfConsistent() throws InterruptedException {
        Harness harness = Harness.create();
        int deviceCount = 32;
        for (int i = 0; i < deviceCount; i++) {
            harness.devices().register("dev-" + i, "V2", List.of("TEMPERATURE"));
        }
        harness.clock().set(T0);

        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        CopyOnWriteArrayList<String> violations = new CopyOnWriteArrayList<>();

        // 8 个写入线程持续上报（固定时钟下接收时间相同，专注验证快照自洽）。
        for (int t = 0; t < 8; t++) {
            int threadIndex = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < 200 && !stop.get(); k++) {
                        String deviceId = "dev-" + ((threadIndex + k) % deviceCount);
                        IngestResult result = harness.telemetry().ingest(json(harness, deviceId,
                                "V2", T0, (long) k * 1000 + threadIndex, 20.0 + k % 10));
                        if (!result.accepted()
                                && result.rejectionReason()
                                != com.github.highcumontoa.sensortelemetrygatewayjava
                                .model.RejectionReason.DUPLICATE_SEQUENCE) {
                            violations.add("意外拒绝: " + result.rejectionReason());
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // 4 个查询线程并发取概览，每次结果都必须内部自洽。
        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < 500 && !stop.get(); k++) {
                        FleetOverview overview = harness.fleet().overview();
                        long online = overview.devices().stream()
                                .filter(d -> "ONLINE".equals(d.status())).count();
                        if (online != overview.onlineCount()
                                || overview.onlineCount() + overview.offlineCount()
                                != overview.totalDevices()) {
                            violations.add("计数不自洽: " + overview);
                        }
                        for (DeviceOverview device : overview.devices()) {
                            boolean expectOnline = device.lastValidIngestTime() >= 0
                                    && (overview.evaluatedAt() - device.lastValidIngestTime())
                                    <= OFFLINE;
                            boolean claimedOnline = "ONLINE".equals(device.status());
                            if (expectOnline != claimedOnline) {
                                violations.add("状态与最近上报时间矛盾: " + device
                                        + " evaluatedAt=" + overview.evaluatedAt());
                            }
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        stop.set(true);
        log.info("输入: 8 线程并发上报 + 4 线程并发查询概览；判定依据: 每次查询 onlineCount 与逐台状态、"
                + "最近上报时间完全自洽；违规={}", violations);
        assertTrue(violations.isEmpty(), "并发查询不得出现自相矛盾: " + violations);
    }

    private void sendV1(Harness harness, String deviceId, long eventTime, long arriveAt, double value) {
        harness.clock().set(arriveAt);
        IngestResult result = harness.telemetry().ingest(json(harness, deviceId, "V1",
                eventTime, null, value));
        assertTrue(result.accepted(), "上报应被接受: " + result.rejectionReason());
    }

    private String json(Harness harness, String deviceId, String version, Long ts, Long seq,
                        double value) {
        try {
            return harness.mapper().writeValueAsString(new ReportRequest(deviceId, version, ts,
                    seq, List.of(new ReadingDto("TEMPERATURE", value, "C"))));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}

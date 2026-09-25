package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import com.github.highcumontoa.sensortelemetrygatewayjava.model.IngestResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectionReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.WindowStats;
import com.github.highcumontoa.sensortelemetrygatewayjava.web.dto.ReadingDto;
import com.github.highcumontoa.sensortelemetrygatewayjava.web.dto.ReportRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一设备并发写入：窗口结果不得回退、重复计数或丢数；
 * 并发执行的聚合必须与同样输入串行执行逐值相等。
 */
class ConcurrencyConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyConsistencyTest.class);

    private static final long T0 = 1_000_000L;
    private static final int THREADS = 8;
    private static final int PER_THREAD = 25;
    private static final int TOTAL = THREADS * PER_THREAD;

    @Test
    void concurrentWritesMatchSerialExecution() throws InterruptedException {
        Harness concurrent = Harness.create();
        Harness serial = Harness.create();
        concurrent.devices().register("dev", "V2", List.of("TEMPERATURE"));
        serial.devices().register("dev", "V2", List.of("TEMPERATURE"));

        // 同一输入集合：序列号 0..199 唯一；事件时间故意乱序分布在窗口内。
        List<Report> inputs = new ArrayList<>();
        for (int i = 0; i < TOTAL; i++) {
            long eventTime = T0 + 1L + ((i * 37L) % 50_000L);
            double value = (i * 7) % 100 + 0.5;
            inputs.add(new Report(eventTime, i, value));
        }

        concurrent.clock().set(T0 + 55_000);
        serial.clock().set(T0 + 55_000);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger accepted = new AtomicInteger();
        for (int t = 0; t < THREADS; t++) {
            int threadIndex = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < PER_THREAD; k++) {
                        Report r = inputs.get(threadIndex * PER_THREAD + k);
                        IngestResult result = concurrent.telemetry().ingest(json(concurrent, "dev", r));
                        if (result.accepted()) {
                            accepted.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        // 串行参照：同集合、同顺序无关地逐条写入。
        for (Report r : inputs) {
            serial.telemetry().ingest(json(serial, "dev", r));
        }

        WindowStats concurrentStats = concurrent.aggregation().stats("dev", SensorType.TEMPERATURE, T0 + 55_000);
        WindowStats serialStats = serial.aggregation().stats("dev", SensorType.TEMPERATURE, T0 + 55_000);
        log.info("输入: {} 条唯一序列号并发上报 vs 同样输入串行；判定依据: count/min/max/avg 必须逐值相等", TOTAL);
        log.info("并发结果: {}; 串行结果: {}", concurrentStats, serialStats);
        assertEquals(TOTAL, accepted.get(), "不得丢数或重复计数");
        assertEquals(serialStats, concurrentStats, "并发聚合必须与串行一致");
        assertEquals(TOTAL, concurrentStats.count());
    }

    @Test
    void duplicateSequenceUnderContentionAcceptedExactlyOnce() throws InterruptedException {
        Harness harness = Harness.create();
        harness.devices().register("dup", "V2", List.of("TEMPERATURE"));
        harness.clock().set(T0 + 1_000);

        int contenders = 16;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicateRejected = new AtomicInteger();
        for (int i = 0; i < contenders; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 16 个线程竞争同一个序列号 42。
                    IngestResult result = harness.telemetry().ingest(json(harness, "dup",
                            new Report(T0 + 500, 42, 21.0)));
                    if (result.accepted()) {
                        accepted.incrementAndGet();
                    } else if (result.rejectionReason() == RejectionReason.DUPLICATE_SEQUENCE) {
                        duplicateRejected.incrementAndGet();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        log.info("输入: {} 个线程竞争序列号 42；判定依据: 恰好 1 接受、其余 DUPLICATE_SEQUENCE",
                contenders);
        assertEquals(1, accepted.get());
        assertEquals(contenders - 1, duplicateRejected.get());
        assertEquals(1, harness.aggregation().stats("dup", SensorType.TEMPERATURE, T0 + 1_000).count());
    }

    private static String json(Harness harness, String deviceId, Report r) {
        try {
            ReportRequest request = new ReportRequest(deviceId, "V2", r.eventTime,
                    (long) r.sequence, List.of(new ReadingDto("TEMPERATURE", r.value, "C")));
            return harness.mapper().writeValueAsString(request);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private record Report(long eventTime, int sequence, double value) {
    }
}

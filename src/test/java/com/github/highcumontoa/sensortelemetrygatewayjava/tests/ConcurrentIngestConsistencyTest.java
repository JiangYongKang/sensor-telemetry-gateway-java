package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.IngestResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.WindowAggregate;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.TelemetryService;

/**
 * 高频并发上报：同一设备的并发写入不得使窗口结果回退、重复计数或丢数。
 * 判定方法：同一组确定性输入分别串行与并发执行，最终聚合必须完全一致；
 * 并发执行过程中窗口计数必须始终满足 0 <= count <= 输入数（不重复不丢数的过程不变量）。
 */
class ConcurrentIngestConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentIngestConsistencyTest.class);

    /** 构造一组全部落在窗口内、且彼此间隔不超过最大迟到阈值的输入。 */
    private List<String> buildInputs(int n, long base) {
        List<String> inputs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // 事件时间乱序排列：偶数序靠后、奇数序靠前，但最大差距 < 10s 迟到阈值
            long eventTime = base + (i % 2 == 0 ? 9_000L - i : 1_000L + i);
            inputs.add("{\"deviceId\":\"dev\",\"protocolVersion\":\"V2\",\"sensor\":\"TEMPERATURE\","
                    + "\"value\":" + (20.0 + (i % 60)) + ",\"unit\":\"C\",\"eventTime\":" + eventTime + "}");
        }
        return inputs;
    }

    private WindowAggregate serialRun(List<String> inputs) {
        TelemetryTestFixtures.TestStack stack = TelemetryTestFixtures.newStack();
        TelemetryService service = stack.service();
        service.register(TelemetryTestFixtures.registration("dev", "V2", SensorType.TEMPERATURE));
        stack.clock().setTime(TelemetryTestFixtures.T0);
        for (String json : inputs) {
            IngestResult r = service.ingest(json, TelemetryService.ENTRY_SERVICE);
            assertTrue(r.accepted(), "串行基线应全部接受: " + r.detail());
        }
        return stack.store().aggregate("dev", SensorType.TEMPERATURE);
    }

    @Test
    void concurrentWritesYieldSameAggregateAsSerial() throws Exception {
        int n = 200;
        int threads = 8;
        List<String> inputs = buildInputs(n, TelemetryTestFixtures.T0);

        WindowAggregate serial = serialRun(inputs);
        log.info("SERIAL   result count={} latest={} min={} max={} avg={} watermark={}",
                serial.count(), serial.latestValue(), serial.minValue(), serial.maxValue(),
                serial.avgValue(), serial.watermarkMillis());

        TelemetryTestFixtures.TestStack stack = TelemetryTestFixtures.newStack();
        TelemetryService service = stack.service();
        service.register(TelemetryTestFixtures.registration("dev", "V2", SensorType.TEMPERATURE));
        stack.clock().setTime(TelemetryTestFixtures.T0);

        // 打乱输入顺序，再由多线程在同一屏障后同时提交，制造真实竞争。
        List<String> shuffled = new ArrayList<>(inputs);
        Collections.shuffle(shuffled, new java.util.Random(42));

        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong accepted = new AtomicLong();
        List<Future<?>> futures = new ArrayList<>();
        for (String json : shuffled) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                IngestResult r = service.ingest(json, TelemetryService.ENTRY_SERVICE);
                if (r.accepted()) {
                    accepted.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        WindowAggregate concurrent = stack.store().aggregate("dev", SensorType.TEMPERATURE);
        log.info("PARALLEL result count={} latest={} min={} max={} avg={} watermark={}",
                concurrent.count(), concurrent.latestValue(), concurrent.minValue(),
                concurrent.maxValue(), concurrent.avgValue(), concurrent.watermarkMillis());

        assertEquals(n, accepted.get(), "并发写入不得丢数");
        assertEquals(serial.count(), concurrent.count(), "并发与串行的窗口计数必须一致");
        assertEquals(serial.latestValue(), concurrent.latestValue(), 1e-12,
                "并发不得令最新值回退");
        assertEquals(serial.latestEventTimeMillis(), concurrent.latestEventTimeMillis());
        assertEquals(serial.minValue(), concurrent.minValue(), 1e-12);
        assertEquals(serial.maxValue(), concurrent.maxValue(), 1e-12);
        assertEquals(serial.avgValue(), concurrent.avgValue(), 1e-12);
        assertEquals(serial.watermarkMillis(), concurrent.watermarkMillis());
        assertNotNull(concurrent.latestValue());
    }

    @Test
    void repeatedSerialRunsAreDeterministic() {
        // 可复现性：同一输入序列执行两次，聚合结果必须逐字段一致。
        var inputs = buildInputs(50, TelemetryTestFixtures.T0);
        WindowAggregate a = serialRun(inputs);
        WindowAggregate b = serialRun(inputs);
        assertEquals(a.count(), b.count());
        assertEquals(a.latestValue(), b.latestValue(), 1e-12);
        assertEquals(a.avgValue(), b.avgValue(), 1e-12);
        assertEquals(a.watermarkMillis(), b.watermarkMillis());
        log.info("DETERMINISM re-run equal: count={} avg={}", a.count(), a.avgValue());
    }
}

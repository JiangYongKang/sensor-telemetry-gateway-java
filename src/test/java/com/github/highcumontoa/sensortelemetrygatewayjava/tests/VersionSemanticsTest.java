package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import com.github.highcumontoa.sensortelemetrygatewayjava.model.IngestResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectionReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.web.dto.ReadingDto;
import com.github.highcumontoa.sensortelemetrygatewayjava.web.dto.ReportRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V1/V2 字段兼容：缺省语义稳定、序列号仅 V2 生效、时间戳缺省补接收时间。 */
class VersionSemanticsTest {

    private static final Logger log = LoggerFactory.getLogger(VersionSemanticsTest.class);

    private final Harness harness = Harness.create();
    private static final long T0 = 2_000_000L;

    @Test
    void v1HasNoSequenceDeduplication() {
        harness.devices().register("v1dev", "V1", List.of("TEMPERATURE"));
        harness.clock().set(T0 + 1_000);
        String first = json("v1dev", "V1", T0, null, 21.0);
        String second = json("v1dev", "V1", T0 + 1_000, null, 22.0);
        log.info("输入: 两条 V1 上报（协议无序列号概念）；判定依据: 不做序列号去重，均接受");
        assertTrue(harness.telemetry().ingest(first).accepted());
        assertTrue(harness.telemetry().ingest(second).accepted());
    }

    @Test
    void v2ExplicitSequenceZeroIsDeduplicated() {
        harness.devices().register("v2dev", "V2", List.of("TEMPERATURE"));
        harness.clock().set(T0 + 1_000);
        String first = json("v2dev", "V2", T0, 0L, 21.0);
        String second = json("v2dev", "V2", T0 + 1_000, 0L, 22.0);
        log.info("输入: 两条 V2 上报均显式携带 sequence=0；判定依据: 0 是有效序列号，第二次 => DUPLICATE_SEQUENCE");
        assertTrue(harness.telemetry().ingest(first).accepted());
        IngestResult result = harness.telemetry().ingest(second);
        assertFalse(result.accepted());
        assertEquals(RejectionReason.DUPLICATE_SEQUENCE, result.rejectionReason());
    }

    @Test
    void v2MissingSequenceMeansNoDeduplication() {
        harness.devices().register("v2dev2", "V2", List.of("TEMPERATURE"));
        harness.clock().set(T0 + 1_000);
        String first = json("v2dev2", "V2", T0, null, 21.0);
        String second = json("v2dev2", "V2", T0 + 1_000, null, 22.0);
        log.info("输入: 两条 V2 上报均缺省 sequence；判定依据: 缺省=-1 不参与去重，均接受（语义确定）");
        assertTrue(harness.telemetry().ingest(first).accepted());
        assertTrue(harness.telemetry().ingest(second).accepted());
    }

    @Test
    void missingTimestampDefaultsToReceiveTime() {
        harness.devices().register("v2dev3", "V2", List.of("TEMPERATURE"));
        harness.clock().set(T0 + 7_777L);
        String noTs = json("v2dev3", "V2", null, 1L, 21.0);
        log.info("输入: {} 接收时间=2007777；判定依据: timestamp 缺省补接收时间（两个版本语义一致）", noTs);
        IngestResult result = harness.telemetry().ingest(noTs);
        assertTrue(result.accepted());
        assertEquals(T0 + 7_777L, result.eventTime());
    }

    private String json(String deviceId, String version, Long ts, Long seq, double value) {
        try {
            return harness.mapper().writeValueAsString(new ReportRequest(deviceId, version, ts, seq,
                    List.of(new ReadingDto("TEMPERATURE", value, "C"))));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}

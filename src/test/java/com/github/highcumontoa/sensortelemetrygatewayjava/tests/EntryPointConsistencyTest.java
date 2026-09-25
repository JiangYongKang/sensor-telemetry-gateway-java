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

/** 单条入口与批量入口共用同一管线：同一输入的结论与拒绝原因必须一致。 */
class EntryPointConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(EntryPointConsistencyTest.class);

    private final Harness single = Harness.create();
    private final Harness batch = Harness.create();

    @Test
    void sameInputsProduceSameResultsAcrossEntries() {
        single.devices().register("d", "V2", List.of("TEMPERATURE"));
        batch.devices().register("d", "V2", List.of("TEMPERATURE"));

        String good = json("d", "V2", 1_000L, 1L, 25.0, "C");
        String badUnit = json("d", "V2", 1_001L, 2L, 25.0, "Pa");
        String badRange = json("d", "V2", 1_002L, 3L, 999.0, "C");

        IngestResult r1 = single.telemetry().ingest(good);
        IngestResult r2 = single.telemetry().ingest(badUnit);
        IngestResult r3 = single.telemetry().ingest(badRange);

        String batchJson;
        try {
            // 批量入口接受上报对象数组（与 HTTP 一致）。
            batchJson = "[" + String.join(",", good, badUnit, badRange) + "]";
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        log.info("输入(批量): {}；判定依据: 与逐条单入口共用同一校验管线", batchJson);
        List<IngestResult> results = batch.telemetry().ingestBatch(batchJson);

        assertEquals(List.of(true, false, false), results.stream().map(IngestResult::accepted).toList());
        assertEquals(r1.accepted(), results.get(0).accepted());
        assertEquals(r2.rejectionReason(), results.get(1).rejectionReason());
        assertEquals(r3.rejectionReason(), results.get(2).rejectionReason());
        assertEquals(RejectionReason.INVALID_UNIT, results.get(1).rejectionReason());
        assertEquals(RejectionReason.VALUE_OUT_OF_RANGE, results.get(2).rejectionReason());

        // 拒绝报文在两种入口下都必须留档。
        assertEquals(3, single.rawStore().findAll().size());
        assertEquals(3, batch.rawStore().findAll().size());
    }

    @Test
    void nonArrayBatchPayloadRejectedWholesale() {
        log.info("输入: 批量报文不是 JSON 数组；判定依据: 整批 PAYLOAD_INVALID");
        List<IngestResult> results = single.telemetry().ingestBatch("{\"deviceId\":\"d\"}");
        assertEquals(1, results.size());
        assertFalse(results.get(0).accepted());
        assertEquals(RejectionReason.PAYLOAD_INVALID, results.get(0).rejectionReason());
    }

    private String json(String deviceId, String version, Long ts, Long seq, double value, String unit) {
        try {
            return single.mapper().writeValueAsString(new ReportRequest(deviceId, version, ts, seq,
                    List.of(new ReadingDto("TEMPERATURE", value, unit))));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}

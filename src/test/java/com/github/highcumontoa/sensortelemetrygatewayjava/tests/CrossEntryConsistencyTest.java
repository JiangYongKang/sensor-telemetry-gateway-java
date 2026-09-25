package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.Capability;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceRegistration;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.IngestResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.TelemetryService;
import com.github.highcumontoa.sensortelemetrygatewayjava.state.Clock;
import com.github.highcumontoa.sensortelemetrygatewayjava.state.FixedClock;
import java.util.Set;

/**
 * 跨入口一致性：同一条上报经 HTTP 单条、HTTP 批量、服务直调三个入口，
 * 接受/拒绝结论、拒绝原因、归一化值必须一致；拒绝报文可经查询接口追溯原文。
 */
@SpringBootTest
class CrossEntryConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(CrossEntryConsistencyTest.class);

    /** 用固定时钟覆盖生产墙钟，使 HTTP 路径同样可复现。 */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return new FixedClock(TelemetryTestFixtures.T0);
        }
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private TelemetryService service;
    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private JsonNode httpSingle(String rawJson) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/telemetry")
                        .contentType(MediaType.APPLICATION_JSON).content(rawJson))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode httpBatch(String rawJsonArray) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/telemetry/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(rawJsonArray))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        return array.get(0);
    }

    @Test
    void sameReportYieldsSameDecisionAcrossAllEntryPoints() throws Exception {
        // 经 HTTP 注册
        DeviceRegistration reg = new DeviceRegistration("cross-dev", "V2",
                new Capability(Set.of(SensorType.TEMPERATURE, SensorType.HUMIDITY)));
        mockMvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isOk());
        // 重复注册（HTTP）幂等
        MvcResult repeat = mockMvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isOk()).andReturn();
        JsonNode repeatNode = objectMapper.readTree(repeat.getResponse().getContentAsString());
        assertTrue(repeatNode.path("accepted").asBoolean());
        assertTrue(repeatNode.path("idempotent").asBoolean());

        String good = "{\"deviceId\":\"cross-dev\",\"protocolVersion\":\"V2\",\"sensor\":\"TEMPERATURE\","
                + "\"value\":77.0,\"unit\":\"F\",\"eventTime\":1700000000000}";
        log.info("INPUT across entries: {}", good);

        JsonNode http = httpSingle(good);
        JsonNode batch = httpBatch("[" + good + "]");
        IngestResult direct = service.ingest(good, TelemetryService.ENTRY_SERVICE);
        log.info("DECISION http.accepted={} batch.accepted={} direct.accepted={}; reasons http={} batch={} direct={}",
                http.path("accepted").asBoolean(), batch.path("accepted").asBoolean(), direct.accepted(),
                http.path("rejectReason").asText(null), batch.path("rejectReason").asText(null),
                direct.rejectReason());

        assertTrue(http.path("accepted").asBoolean());
        assertTrue(batch.path("accepted").asBoolean());
        assertTrue(direct.accepted());
        assertEquals(http.path("reading").path("value").asDouble(),
                direct.reading().value(), 1e-9, "HTTP 与服务直调归一化值必须一致");
        assertEquals(http.path("reading").path("value").asDouble(),
                batch.path("reading").path("value").asDouble(), 1e-9);
        assertEquals("C", http.path("reading").path("unit").asText());
    }

    @Test
    void sameInvalidReportYieldsSameDistinctReasonAcrossEntries() throws Exception {
        DeviceRegistration reg = new DeviceRegistration("cross-dev2", "V2",
                new Capability(Set.of(SensorType.TEMPERATURE)));
        service.register(reg);

        // 越界（200F=93.3C）+ 未知单位，两批输入分别只触发一种原因，验证原因可区分
        String outOfRange = "{\"deviceId\":\"cross-dev2\",\"protocolVersion\":\"V2\","
                + "\"sensor\":\"TEMPERATURE\",\"value\":200.0,\"unit\":\"F\",\"eventTime\":1700000000000}";
        String badUnit = "{\"deviceId\":\"cross-dev2\",\"protocolVersion\":\"V2\","
                + "\"sensor\":\"TEMPERATURE\",\"value\":25.0,\"unit\":\"K\",\"eventTime\":1700000000000}";
        String badVersion = "{\"deviceId\":\"cross-dev2\",\"protocolVersion\":\"V3\","
                + "\"sensor\":\"TEMPERATURE\",\"value\":25.0,\"unit\":\"C\",\"eventTime\":1700000000000}";

        for (String raw : new String[]{outOfRange, badUnit, badVersion}) {
            log.info("INPUT across entries: {}", raw);
            JsonNode http = httpSingle(raw);
            JsonNode batch = httpBatch("[" + raw + "]");
            IngestResult direct = service.ingest(raw, TelemetryService.ENTRY_SERVICE);

            assertFalse(http.path("accepted").asBoolean());
            assertEquals(http.path("rejectReason").asText(),
                    batch.path("rejectReason").asText(), "HTTP 单条与批量原因必须一致");
            assertEquals(http.path("rejectReason").asText(), direct.rejectReason().name(),
                    "HTTP 与服务直调原因必须一致");
            assertEquals(raw, http.path("raw").path("rawJson").asText(),
                    "HTTP 结论必须保留原始报文");
            log.info("DECISION aligned reason={} basis={}", direct.rejectReason(), direct.detail());
        }

        // 拒绝留档可按设备追溯，且三种 LATE 之外的原因均可区分
        MvcResult rejected = mockMvc.perform(get("/api/devices/cross-dev2/rejected"))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertTrue(list.size() >= 3);
        log.info("RETAINED rejected records={} (原始报文均保留)", list.size());
        assertEquals(RejectReason.VALUE_OUT_OF_RANGE.name(), list.get(0).path("reason").asText());
    }
}

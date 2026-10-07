package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import com.github.highcumontoa.sensortelemetrygatewayjava.clock.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 经真实 HTTP 栈验证可用性统计与机群概览端点，以及保留范围错误的可解释映射。 */
@SpringBootTest(properties = {
        "gateway.window-millis=60000",
        "gateway.offline-millis=30000",
        "gateway.max-future-millis=5000"
})
@AutoConfigureMockMvc
class FleetHttpTest {

    @Autowired
    private MockMvc mvc;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return () -> 10_000_000L;
        }
    }

    @Test
    void availabilityAndOverviewEndpoints() throws Exception {
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"th-1","protocolVersion":"V2",
                                 "capabilities":["TEMPERATURE"]}
                                """))
                .andExpect(status().isCreated());
        // 事件时间很旧（乱序/滞后），但接收时间为固定时钟 10000000：必须在线。
        mvc.perform(post("/api/telemetry").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"th-1","protocolVersion":"V2","timestamp":9000000,
                                 "sequence":1,"readings":[{"type":"temperature","value":30,"unit":"C"}]}
                                """))
                .andExpect(status().isAccepted());

        // 机群概览：1 台在线，最近有效上报时间 = 接收时间 10000000。
        mvc.perform(get("/api/fleet/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluatedAt").value(10_000_000))
                .andExpect(jsonPath("$.onlineCount").value(1))
                .andExpect(jsonPath("$.offlineCount").value(0))
                .andExpect(jsonPath("$.devices[0].deviceId").value("th-1"))
                .andExpect(jsonPath("$.devices[0].status").value("ONLINE"))
                .andExpect(jsonPath("$.devices[0].lastValidIngestTime").value(10_000_000))
                .andExpect(jsonPath("$.devices[0].lastValidEventTime").value(9_000_000));

        // 可用性：接收时间之后的 10s 全部在线（覆盖自 [10000000, 10030000]）。
        mvc.perform(get("/api/devices/th-1/availability")
                        .param("from", "10000000").param("to", "10010000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onlineMillis").value(10_000))
                .andExpect(jsonPath("$.onlineRatio").value(1.0))
                .andExpect(jsonPath("$.offlineIntervals.length()").value(0));

        // 查询起点早于注册时刻（历史覆盖下界）：明确 422 + RETENTION_EXCEEDED。
        mvc.perform(get("/api/devices/th-1/availability")
                        .param("from", "0").param("to", "10010000"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejectionReason").value("RETENTION_EXCEEDED"));

        // 非法区间：from >= to。
        mvc.perform(get("/api/devices/th-1/availability")
                        .param("from", "10010000").param("to", "10000000"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejectionReason").value("INVALID_TIME_RANGE"));

        // 未注册设备：404。
        mvc.perform(get("/api/devices/nobody/availability")
                        .param("from", "10000000").param("to", "10010000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.rejectionReason").value("DEVICE_NOT_REGISTERED"));
    }
}

package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.github.highcumontoa.sensortelemetrygatewayjava.clock.Clock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 经真实 HTTP 栈验证：各入口结论一致、拒绝原因可解释、状态与聚合可查询。 */
@SpringBootTest(properties = {
        "gateway.window-millis=60000",
        "gateway.offline-millis=30000",
        "gateway.max-future-millis=5000"
})
@AutoConfigureMockMvc
class HttpEndToEndTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Clock clock;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return new MutableClock(10_000_000L);
        }
    }

    /** 可推进的测试时钟：可用性/概览查询需要注册之后的一段时间区间。 */
    static final class MutableClock implements Clock {
        private long now;

        MutableClock(long start) {
            this.now = start;
        }

        @Override
        public long nowMillis() {
            return now;
        }

        void set(long value) {
            this.now = value;
        }
    }

    @Test
    void fullFlow() throws Exception {
        // 注册：201；幂等重复注册：200。
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"th-1","protocolVersion":"V2",
                                 "capabilities":["TEMPERATURE","HUMIDITY"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true));
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"th-1","protocolVersion":"v2",
                                 "capabilities":["HUMIDITY","TEMPERATURE"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false));

        // 正常上报（华氏度换算为摄氏度）。
        mvc.perform(post("/api/telemetry").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"th-1","protocolVersion":"V2","timestamp":9990000,
                                 "sequence":1,"readings":[{"type":"temperature","value":86,"unit":"F"}]}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.readings[0].value").value(30.0));

        // 越界拒绝：422 且原因可区分。
        mvc.perform(post("/api/telemetry").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"th-1","protocolVersion":"V2","timestamp":9991000,
                                 "sequence":2,"readings":[{"type":"TEMPERATURE","value":999,"unit":"C"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rejectionReason").value("VALUE_OUT_OF_RANGE"));

        // 版本不匹配：409 CONFLICT + 明确原因。
        mvc.perform(post("/api/telemetry").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"th-1","protocolVersion":"V1",
                                 "readings":[{"type":"TEMPERATURE","value":25,"unit":"C"}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.rejectionReason").value("PROTOCOL_VERSION_MISMATCH"));

        // 批量入口同样可区分拒绝原因。
        mvc.perform(post("/api/telemetry/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {"deviceId":"th-1","protocolVersion":"V2","timestamp":9992000,
                                   "sequence":3,"readings":[{"type":"HUMIDITY","value":55,"unit":"%"}]},
                                  {"deviceId":"th-1","protocolVersion":"V2","timestamp":9993000,
                                   "sequence":4,"readings":[{"type":"HUMIDITY","value":150,"unit":"%"}]}
                                ]
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$[0].accepted").value(true))
                .andExpect(jsonPath("$[1].rejectionReason").value("VALUE_OUT_OF_RANGE"));

        // 状态：最后有效事件时间为 9992000，接收时间为固定时钟 10000000 => ONLINE。
        mvc.perform(get("/api/devices/th-1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ONLINE"))
                .andExpect(jsonPath("$.lastValidEventTime").value(9992000))
                .andExpect(jsonPath("$.lastValidIngestTime").value(10000000))
                .andExpect(jsonPath("$.latest.TEMPERATURE.value").value(30.0));

        // 聚合：温度窗口内仅 1 个有效值（30C）。
        mvc.perform(get("/api/devices/th-1/stats").param("type", "TEMPERATURE").param("at", "10000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.avg").value(30.0));

        // 可用性：注册于 10000000 且此后有有效上报；时钟推进 10s 后查询该区间 => 全程在线。
        ((MutableClock) clock).set(10_010_000L);
        mvc.perform(get("/api/devices/th-1/availability")
                        .param("from", "10000000").param("to", "10010000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onlineMillis").value(10000))
                .andExpect(jsonPath("$.onlineRatio").value(1.0))
                .andExpect(jsonPath("$.offlinePeriods.length()").value(0));

        // 可用性：查询起点早于注册时刻（保留历史下界）=> 明确报 OUT_OF_RETENTION。
        mvc.perform(get("/api/devices/th-1/availability")
                        .param("from", "1000").param("to", "10010000"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejectionReason").value("OUT_OF_RETENTION"));

        // 机群概览：1 台设备在线，计数与逐台状态自洽。
        mvc.perform(get("/api/fleet/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluatedAt").value(10010000))
                .andExpect(jsonPath("$.totalDevices").value(1))
                .andExpect(jsonPath("$.onlineCount").value(1))
                .andExpect(jsonPath("$.offlineCount").value(0))
                .andExpect(jsonPath("$.devices[0].deviceId").value("th-1"))
                .andExpect(jsonPath("$.devices[0].status").value("ONLINE"))
                .andExpect(jsonPath("$.devices[0].lastValidIngestTime").value(10000000));

        // 未注册设备查询：404。
        mvc.perform(get("/api/devices/nobody/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.rejectionReason").value("DEVICE_NOT_REGISTERED"));
    }
}

package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensortelemetrygatewayjava.config.TelemetryProperties;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.Capability;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceRegistration;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.normalization.ReadingNormalizer;
import com.github.highcumontoa.sensortelemetrygatewayjava.protocol.ProtocolMappers;
import com.github.highcumontoa.sensortelemetrygatewayjava.state.Clock;
import com.github.highcumontoa.sensortelemetrygatewayjava.state.DeviceRegistry;
import com.github.highcumontoa.sensortelemetrygatewayjava.state.FixedClock;
import com.github.highcumontoa.sensortelemetrygatewayjava.state.TelemetryStore;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.TelemetryService;

/**
 * 测试辅助：以注入式固定时钟装配与生产一致的组件栈（不依赖 Spring 墙钟，结论可复现）。
 */
final class TelemetryTestFixtures {

    static final long T0 = 1_700_000_000_000L;

    private TelemetryTestFixtures() {
    }

    static TelemetryProperties defaultProperties() {
        return new TelemetryProperties();
    }

    static DeviceRegistration registration(String deviceId, String version, SensorType... types) {
        return new DeviceRegistration(deviceId, version, new Capability(Set.of(types)));
    }

    static FixedClock clock() {
        return new FixedClock(T0);
    }

    static TestStack newStack() {
        FixedClock clock = clock();
        ObjectMapper objectMapper = new ObjectMapper();
        ProtocolMappers protocolMappers = new ProtocolMappers();
        ReadingNormalizer normalizer = new ReadingNormalizer();
        DeviceRegistry registry = new DeviceRegistry();
        TelemetryStore store = new TelemetryStore(clock, defaultProperties());
        TelemetryService service = new TelemetryService(objectMapper, protocolMappers, normalizer,
                registry, store, clock);
        return new TestStack(clock, objectMapper, registry, store, service);
    }

    record TestStack(FixedClock clock, ObjectMapper objectMapper, DeviceRegistry registry,
                     TelemetryStore store, TelemetryService service) implements Clock {
        @Override
        public long nowMillis() {
            return clock.nowMillis();
        }
    }
}

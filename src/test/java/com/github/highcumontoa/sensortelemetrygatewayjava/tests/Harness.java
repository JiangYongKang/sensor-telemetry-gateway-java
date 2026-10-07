package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensortelemetrygatewayjava.config.GatewayProperties;
import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.ProtocolSupport;
import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.ReportEvaluator;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorCatalog;
import com.github.highcumontoa.sensortelemetrygatewayjava.registry.DeviceRegistry;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.AggregationService;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.AvailabilityService;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.DeviceService;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.TelemetryService;
import com.github.highcumontoa.sensortelemetrygatewayjava.store.RawReportStore;

/** 测试装配：与生产相同的依赖图，仅把时钟换成 ManualClock。 */
public record Harness(
        ManualClock clock,
        GatewayProperties properties,
        SensorCatalog catalog,
        DeviceRegistry registry,
        RawReportStore rawStore,
        DeviceService devices,
        TelemetryService telemetry,
        AggregationService aggregation,
        AvailabilityService availability,
        ObjectMapper mapper
) {
    public static Harness create() {
        ManualClock clock = new ManualClock(1_000_000L);
        GatewayProperties props = new GatewayProperties();
        SensorCatalog catalog = new SensorCatalog();
        ProtocolSupport protocolSupport = new ProtocolSupport();
        DeviceRegistry registry = new DeviceRegistry(clock, props);
        RawReportStore rawStore = new RawReportStore(props);
        ReportEvaluator evaluator = new ReportEvaluator(registry, catalog, protocolSupport, props);
        ObjectMapper mapper = new ObjectMapper();
        TelemetryService telemetry = new TelemetryService(registry, rawStore, evaluator,
                protocolSupport, clock, props, mapper);
        DeviceService devices = new DeviceService(registry, protocolSupport);
        AggregationService aggregation = new AggregationService(registry, clock, props);
        AvailabilityService availability = new AvailabilityService(registry, clock, props,
                aggregation);
        return new Harness(clock, props, catalog, registry, rawStore, devices,
                telemetry, aggregation, availability, mapper);
    }
}

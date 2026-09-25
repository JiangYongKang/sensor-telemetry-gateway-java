package com.github.highcumontoa.sensortelemetrygatewayjava.web;

import com.github.highcumontoa.sensortelemetrygatewayjava.clock.Clock;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.CanonicalReading;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceState;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.WindowStats;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.AggregationService;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.DeviceService;
import com.github.highcumontoa.sensortelemetrygatewayjava.web.dto.StatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/devices")
public class StatusController {

    private final AggregationService aggregationService;
    private final DeviceService deviceService;
    private final Clock clock;

    public StatusController(AggregationService aggregationService, DeviceService deviceService, Clock clock) {
        this.aggregationService = aggregationService;
        this.deviceService = deviceService;
        this.clock = clock;
    }

    @GetMapping("/{deviceId}/status")
    public StatusResponse status(@PathVariable String deviceId) {
        DeviceState state = deviceService.getDevice(deviceId);
        long at = clock.nowMillis();
        boolean online = aggregationService.isOnline(state, at);
        Map<String, Object> latest = new LinkedHashMap<>();
        synchronized (state) {
            for (Map.Entry<SensorType, CanonicalReading> entry : state.latest().entrySet()) {
                CanonicalReading reading = entry.getValue();
                latest.put(entry.getKey().name(), Map.of(
                        "value", reading.value(),
                        "unit", reading.unit(),
                        "eventTime", state.latestEventTime().get(entry.getKey())));
            }
            return new StatusResponse(deviceId, online ? "ONLINE" : "OFFLINE",
                    state.lastValidEventTime(), at, latest);
        }
    }

    @GetMapping("/{deviceId}/stats")
    public WindowStats stats(@PathVariable String deviceId,
                             @RequestParam String type,
                             @RequestParam(required = false) Long at) {
        SensorType sensorType;
        try {
            sensorType = SensorType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return new WindowStats(null, 0, null, null, null);
        }
        return aggregationService.stats(deviceId, sensorType, at);
    }
}

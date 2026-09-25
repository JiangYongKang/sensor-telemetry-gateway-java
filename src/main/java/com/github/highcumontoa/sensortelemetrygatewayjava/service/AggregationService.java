package com.github.highcumontoa.sensortelemetrygatewayjava.service;

import com.github.highcumontoa.sensortelemetrygatewayjava.clock.Clock;
import com.github.highcumontoa.sensortelemetrygatewayjava.config.GatewayProperties;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.AcceptedSample;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.CanonicalReading;
import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.GatewayException;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceState;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectionReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.WindowStats;
import com.github.highcumontoa.sensortelemetrygatewayjava.registry.DeviceRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 基于事件时间的滑动窗口聚合。
 * 窗口为左开右闭区间 (at-window, at]，按样本事件时间过滤，
 * 因此结果只取决于已接收的样本集合，与到达先后完全无关；
 * 同事件时间按 (eventTime, sequence) 稳定排列，结论可复现。
 */
@Service
public class AggregationService {

    private final DeviceRegistry registry;
    private final Clock clock;
    private final GatewayProperties properties;

    public AggregationService(DeviceRegistry registry, Clock clock, GatewayProperties properties) {
        this.registry = registry;
        this.clock = clock;
        this.properties = properties;
    }

    public WindowStats stats(String deviceId, SensorType type, Long atMillis) {
        DeviceState state = registry.find(deviceId);
        if (state == null) {
            throw new GatewayException(RejectionReason.DEVICE_NOT_REGISTERED,
                    "设备 '" + deviceId + "' 未注册");
        }
        long at = atMillis == null ? clock.nowMillis() : atMillis;
        long lower = at - properties.getWindowMillis();
        List<Double> values = new ArrayList<>();
        synchronized (state) {
            for (AcceptedSample sample : state.window()) {
                if (sample.eventTime() > lower && sample.eventTime() <= at) {
                    for (CanonicalReading reading : sample.readings()) {
                        if (reading.type() == type) {
                            values.add(reading.value());
                        }
                    }
                }
            }
        }
        if (values.isEmpty()) {
            return new WindowStats(type, 0, null, null, null);
        }
        values.sort(Comparator.naturalOrder());
        double min = values.get(0);
        double max = values.get(values.size() - 1);
        double sum = 0.0d;
        for (double v : values) {
            sum += v;
        }
        return new WindowStats(type, values.size(), min, max, sum / values.size());
    }

    /** 基于最后一次有效上报的事件时间判定离线，规则固定且可复现。 */
    public boolean isOnline(DeviceState state, long atMillis) {
        long last;
        synchronized (state) {
            last = state.lastValidEventTime();
        }
        return last >= 0 && (atMillis - last) <= properties.getOfflineMillis();
    }
}

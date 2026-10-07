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

    /**
     * 在线判定口径（与可用性统计、机群概览共用同一规则，结论可复现）：
     * 以最后一次**被接受**上报的接收时间为准——只要最近确实成功接入过有效上报
     * 即在线，哪怕其事件时间远早于接收时间（乱序/滞后到达）也不判离线；
     * 超过 offlineMillis 没有任何有效上报才离线。被拒绝的上报不影响结论。
     */
    public boolean isOnline(DeviceState state, long atMillis) {
        long last;
        synchronized (state) {
            last = state.lastValidIngestTime();
        }
        return last >= 0 && (atMillis - last) <= properties.getOfflineMillis();
    }
}

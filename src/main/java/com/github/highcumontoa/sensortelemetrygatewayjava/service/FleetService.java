package com.github.highcumontoa.sensortelemetrygatewayjava.service;

import com.github.highcumontoa.sensortelemetrygatewayjava.clock.Clock;
import com.github.highcumontoa.sensortelemetrygatewayjava.config.GatewayProperties;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceOverview;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceState;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.FleetOverview;
import com.github.highcumontoa.sensortelemetrygatewayjava.registry.DeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 机群级在线概览。所有设备共用同一个判定时刻 evaluatedAt；
 * 每台设备的状态与最近一次有效上报时间在同一临界区读取，
 * 因此不会出现“被判离线却又显示一个很新的最近上报时间”这类自相矛盾。
 * 只按设备逐个短暂持锁、不加全局锁，设备增多时上报路径不受拖慢。
 */
@Service
public class FleetService {

    private static final Logger log = LoggerFactory.getLogger(FleetService.class);

    private final DeviceRegistry registry;
    private final Clock clock;
    private final GatewayProperties properties;

    public FleetService(DeviceRegistry registry, Clock clock, GatewayProperties properties) {
        this.registry = registry;
        this.clock = clock;
        this.properties = properties;
    }

    public FleetOverview overview() {
        long at = clock.nowMillis();
        List<DeviceOverview> devices = new ArrayList<>();
        int online = 0;
        for (DeviceState state : registry.findAll()) {
            long lastIngest;
            long lastEvent;
            synchronized (state) {
                lastIngest = state.lastValidIngestTime();
                lastEvent = state.lastValidEventTime();
            }
            // 与单台设备状态查询完全相同的判定口径：基于最近一次有效上报的接收时间。
            boolean isOnline = lastIngest >= 0 && (at - lastIngest) <= properties.getOfflineMillis();
            if (isOnline) {
                online++;
            }
            devices.add(new DeviceOverview(state.deviceId(), isOnline ? "ONLINE" : "OFFLINE",
                    lastIngest, lastEvent));
        }
        // 固定排序，保证同一时刻反复查询返回顺序稳定、结果可复现。
        devices.sort(Comparator.comparing(DeviceOverview::deviceId));
        FleetOverview overview = new FleetOverview(at, devices.size(), online,
                devices.size() - online, List.copyOf(devices));
        log.info("机群概览 evaluatedAt={} 判定依据: at-lastValidIngestTime<=offlineMillis({}) 在线 "
                        + "=> total={} online={} offline={}",
                at, properties.getOfflineMillis(), devices.size(), online, devices.size() - online);
        return overview;
    }
}

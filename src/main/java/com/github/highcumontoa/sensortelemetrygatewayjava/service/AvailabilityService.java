package com.github.highcumontoa.sensortelemetrygatewayjava.service;

import com.github.highcumontoa.sensortelemetrygatewayjava.clock.Clock;
import com.github.highcumontoa.sensortelemetrygatewayjava.config.GatewayProperties;
import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.GatewayException;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.AvailabilityStats;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceOverview;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceState;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.FleetOverview;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.OfflineInterval;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectionReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.registry.DeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 可用性统计与机群级在线概览。
 *
 * <p>在线口径与单台状态查询完全一致：设备在时刻 t 在线，当且仅当存在一条
 * <b>被接受</b>的上报，其<b>接收时间</b>落在 [t-offlineMillis, t] 内。
 * 判定只依赖接收时间，与上报携带的事件时间无关——乱序/滞后到达的有效上报
 * 照样使设备在线；被拒绝的上报不产生任何影响。因此同一区间反复查询，
 * 只要历史未被淘汰，结果逐值相同。</p>
 */
@Service
public class AvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    private final DeviceRegistry registry;
    private final Clock clock;
    private final GatewayProperties properties;
    private final AggregationService aggregationService;

    public AvailabilityService(DeviceRegistry registry, Clock clock, GatewayProperties properties,
                               AggregationService aggregationService) {
        this.registry = registry;
        this.clock = clock;
        this.properties = properties;
        this.aggregationService = aggregationService;
    }

    /**
     * 查询设备在 [from, to] 区间内的累计在线时长、在线占比与掉线时间段。
     * 区间起点早于已保留历史的覆盖下界时明确拒绝（RETENTION_EXCEEDED），
     * 绝不静默返回一个看似正常实则不准的结果。
     */
    public AvailabilityStats availability(String deviceId, long from, long to) {
        DeviceState state = registry.find(deviceId);
        if (state == null) {
            throw new GatewayException(RejectionReason.DEVICE_NOT_REGISTERED,
                    "设备 '" + deviceId + "' 未注册");
        }
        if (from >= to) {
            throw new GatewayException(RejectionReason.INVALID_TIME_RANGE,
                    "可用性查询要求 from < to，收到 from=" + from + ", to=" + to);
        }

        long[] history;
        long floor;
        synchronized (state) {
            floor = state.historyFloor();
            history = state.ingestHistory().stream().mapToLong(Long::longValue).toArray();
        }
        if (from < floor) {
            throw new GatewayException(RejectionReason.RETENTION_EXCEEDED,
                    "查询起点 " + from + " 早于可用性历史覆盖下界 " + floor
                            + "（保留时长 gateway.availability-retention-millis="
                            + properties.getAvailabilityRetentionMillis()
                            + "ms，条数上限 gateway.max-ingest-history="
                            + properties.getMaxIngestHistory() + "），该段历史已淘汰，结果不可用");
        }

        // 防御性排序：正常时钟下接收时间本非递减，排序保证任何情况下结论确定。
        Arrays.sort(history);
        long offlineMillis = properties.getOfflineMillis();

        // 每条接受记录贡献在线区间 [s, s+offlineMillis]，合并后与 [from, to] 求交。
        List<long[]> merged = new ArrayList<>();
        for (long s : history) {
            if (s > to) {
                break;
            }
            long start = Math.max(s, from);
            long end = Math.min(s + offlineMillis, to);
            if (end < from || start > end) {
                continue;
            }
            int last = merged.size() - 1;
            if (merged.isEmpty() || start > merged.get(last)[1]) {
                merged.add(new long[]{start, end});
            } else if (end > merged.get(last)[1]) {
                merged.get(last)[1] = end;
            }
        }

        long onlineMillis = 0;
        List<OfflineInterval> offlineIntervals = new ArrayList<>();
        long cursor = from;
        for (long[] interval : merged) {
            onlineMillis += interval[1] - interval[0];
            if (interval[0] > cursor) {
                offlineIntervals.add(new OfflineInterval(cursor, interval[0]));
            }
            cursor = Math.max(cursor, interval[1]);
        }
        if (to > cursor) {
            offlineIntervals.add(new OfflineInterval(cursor, to));
        }
        double ratio = onlineMillis / (double) (to - from);
        log.info("可用性查询 device={} from={} to={} 判定依据: 保留接入记录 {} 条, floor={}, "
                        + "offlineMillis={} => onlineMillis={}, ratio={}, 掉线段={}",
                deviceId, from, to, history.length, floor, offlineMillis,
                onlineMillis, ratio, offlineIntervals);
        return new AvailabilityStats(deviceId, from, to, onlineMillis, ratio,
                List.copyOf(offlineIntervals));
    }

    /**
     * 当前所有已注册设备的整体在线概览。
     * 判定时刻 at 只取一次；每台设备在其自身锁内读取最后有效接收时间，
     * 状态与计数由同一份快照派生，因此与上报并发时结果仍然自洽：
     * 不会出现“被判离线却展示了很新的最近上报时间”这类矛盾。
     */
    public FleetOverview fleetOverview() {
        long at = clock.nowMillis();
        List<DeviceOverview> rows = new ArrayList<>();
        for (DeviceState state : registry.snapshot()) {
            long lastIngest;
            long lastEvent;
            synchronized (state) {
                lastIngest = state.lastValidIngestTime();
                lastEvent = state.lastValidEventTime();
            }
            boolean online = aggregationService.isOnline(state, at);
            rows.add(new DeviceOverview(state.deviceId(), online ? "ONLINE" : "OFFLINE",
                    lastIngest >= 0 ? lastIngest : null,
                    lastEvent >= 0 ? lastEvent : null));
        }
        rows.sort(Comparator.comparing(DeviceOverview::deviceId));
        int onlineCount = 0;
        for (DeviceOverview row : rows) {
            if ("ONLINE".equals(row.status())) {
                onlineCount++;
            }
        }
        log.info("机群概览 at={} 设备数={} 在线={} 离线={}", at, rows.size(), onlineCount,
                rows.size() - onlineCount);
        return new FleetOverview(at, properties.getOfflineMillis(), onlineCount,
                rows.size() - onlineCount, List.copyOf(rows));
    }
}

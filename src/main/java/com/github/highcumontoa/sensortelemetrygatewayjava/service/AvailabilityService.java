package com.github.highcumontoa.sensortelemetrygatewayjava.service;

import com.github.highcumontoa.sensortelemetrygatewayjava.clock.Clock;
import com.github.highcumontoa.sensortelemetrygatewayjava.config.GatewayProperties;
import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.GatewayException;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.AvailabilityReport;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceState;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.OfflinePeriod;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectionReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.registry.DeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备可用性统计。在线区间在写入路径（DeviceRegistry.applySample）按
 * “接受上报的接收时间 + offlineMillis”增量合并维护，查询只是对同一批区间做投影，
 * 因此判定口径与单台设备在线状态完全一致；同一区间反复查询结果逐值相同。
 * 查询起点早于已保留历史（availabilityCoveredFrom）时明确报 OUT_OF_RETENTION，
 * 绝不静默给出看似正常实则不准的数字。
 */
@Service
public class AvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    private final DeviceRegistry registry;
    private final Clock clock;
    private final GatewayProperties properties;

    public AvailabilityService(DeviceRegistry registry, Clock clock, GatewayProperties properties) {
        this.registry = registry;
        this.clock = clock;
        this.properties = properties;
    }

    public AvailabilityReport availability(String deviceId, Long fromMillis, Long toMillis) {
        DeviceState state = registry.find(deviceId);
        if (state == null) {
            throw new GatewayException(RejectionReason.DEVICE_NOT_REGISTERED,
                    "设备 '" + deviceId + "' 未注册");
        }
        if (fromMillis == null || toMillis == null) {
            throw new GatewayException(RejectionReason.MISSING_FIELD,
                    "可用性查询必须同时提供 from 与 to（epoch millis）");
        }
        long from = fromMillis;
        long to = toMillis;
        if (from >= to) {
            throw new GatewayException(RejectionReason.INVALID_TIME_RANGE,
                    "可用性查询区间非法：from=" + from + " 必须小于 to=" + to);
        }
        // 未来部分尚无定论，统一截断到当前判定时刻，保证同一区间反复查询结果一致。
        long now = clock.nowMillis();
        long effectiveTo = Math.min(to, now);
        if (from >= effectiveTo) {
            throw new GatewayException(RejectionReason.INVALID_TIME_RANGE,
                    "可用性查询区间 [from=" + from + ", to=" + to + "] 完全落在当前时刻 "
                            + now + " 之后，尚无可用数据");
        }

        List<OfflinePeriod> offlinePeriods = new ArrayList<>();
        synchronized (state) {
            if (from < state.availabilityCoveredFrom()) {
                throw new GatewayException(RejectionReason.OUT_OF_RETENTION,
                        "设备 '" + deviceId + "' 的可用性查询起点 " + from
                                + " 早于已保留历史的下界 " + state.availabilityCoveredFrom()
                                + "（保留时长 " + properties.getAvailabilityRetentionMillis()
                                + "ms），结果不可用");
            }
            // 掉线时间段 = 查询区间减去各在线区间的投影；在线时长取补集，
            // 因此 onlineMillis + Σoffline = effectiveTo - from 恒成立，不会重复或丢数。
            long cursor = from;
            for (long[] segment : state.onlineSegments()) {
                long segStart = segment[0];
                long segEnd = segment[1];
                if (segEnd < from) {
                    continue;
                }
                if (segStart > effectiveTo) {
                    break;
                }
                if (segStart > cursor) {
                    offlinePeriods.add(new OfflinePeriod(cursor, segStart));
                }
                cursor = Math.max(cursor, segEnd);
                if (cursor >= effectiveTo) {
                    break;
                }
            }
            if (cursor < effectiveTo) {
                offlinePeriods.add(new OfflinePeriod(cursor, effectiveTo));
            }
        }
        long total = effectiveTo - from;
        long offlineMillis = 0L;
        for (OfflinePeriod period : offlinePeriods) {
            offlineMillis += period.to() - period.from();
        }
        long onlineMillis = total - offlineMillis;
        double ratio = total == 0 ? 0.0d : (double) onlineMillis / (double) total;
        AvailabilityReport report = new AvailabilityReport(deviceId, from, effectiveTo,
                onlineMillis, total, ratio, List.copyOf(offlinePeriods));
        log.info("可用性查询 device={} from={} to={} effectiveTo={} 判定依据: 在线区间按接收时间合并, "
                        + "offlineMillis={} => online={}ms ratio={} 掉线时段={}",
                deviceId, from, to, effectiveTo, properties.getOfflineMillis(),
                onlineMillis, ratio, offlinePeriods);
        return report;
    }
}

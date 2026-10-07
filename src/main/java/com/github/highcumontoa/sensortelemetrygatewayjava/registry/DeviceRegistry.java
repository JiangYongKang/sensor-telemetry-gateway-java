package com.github.highcumontoa.sensortelemetrygatewayjava.registry;

import com.github.highcumontoa.sensortelemetrygatewayjava.clock.Clock;
import com.github.highcumontoa.sensortelemetrygatewayjava.config.GatewayProperties;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.AcceptedSample;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.CanonicalReading;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceState;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.ProtocolVersion;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RegistrationResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectionReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.GatewayException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 线程安全的设备注册表：注册幂等，以及每台设备状态写入的串行化。 */
@Component
public class DeviceRegistry {

    private final Clock clock;
    private final GatewayProperties properties;
    private final ConcurrentHashMap<String, DeviceState> devices = new ConcurrentHashMap<>();

    public DeviceRegistry(Clock clock, GatewayProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * 注册或幂等重复注册。
     * 同 deviceId + 同版本 + 同能力集合（与声明顺序无关）返回既有设备；
     * 版本或能力不一致抛 REGISTRATION_CONFLICT。
     */
    public RegistrationResult register(String deviceId, ProtocolVersion version, Set<SensorType> capabilities) {
        boolean[] created = {false};
        DeviceState state = devices.compute(deviceId, (id, existing) -> {
            if (existing == null) {
                created[0] = true;
                return new DeviceState(id, version, capabilities, clock.nowMillis());
            }
            if (existing.protocolVersion() != version
                    || !existing.capabilities().equals(Set.copyOf(capabilities))) {
                throw new GatewayException(RejectionReason.REGISTRATION_CONFLICT,
                        "设备 '" + id + "' 已注册，但协议版本或能力声明与本次请求不一致："
                                + "已注册 version=" + existing.protocolVersion()
                                + ", capabilities=" + existing.capabilities());
            }
            return existing;
        });
        return new RegistrationResult(deviceId, state.protocolVersion(), state.capabilities(), created[0]);
    }

    public DeviceState find(String deviceId) {
        return devices.get(deviceId);
    }

    /** 当前全部已注册设备状态的快照（弱一致迭代，不阻塞上报写入）。 */
    public java.util.Collection<DeviceState> findAll() {
        return java.util.List.copyOf(devices.values());
    }

    /**
     * 将一条已通过校验的样本原子写入设备状态：
     * V2 序列号去重、窗口追加、最新值更新均在同一把锁内完成，
     * 保证并发写入不丢数、不重复计数、最新值不回退。
     *
     * @return true 写入成功；false 序列号重复（调用方记为拒绝）
     */
    public boolean applySample(DeviceState state, AcceptedSample sample) {
        synchronized (state) {
            if (sample.sequence() >= 0 && !state.seenSequences().add(sample.sequence())) {
                return false;
            }
            // 基于事件时间的插入排序：窗口顺序与到达顺序无关，聚合结果可复现。
            var window = state.window();
            int pos = window.size();
            while (pos > 0 && earlierThan(sample, window.get(pos - 1))) {
                pos--;
            }
            window.add(pos, sample);

            for (CanonicalReading reading : sample.readings()) {
                Long previous = state.latestEventTime().get(reading.type());
                // 仅当样本事件时间不旧时更新最新值；同事件时间后到者不覆盖，语义确定。
                if (previous == null || sample.eventTime() >= previous) {
                    state.latest().put(reading.type(), reading);
                    state.latestEventTime().put(reading.type(), sample.eventTime());
                }
            }
            if (sample.eventTime() > state.lastValidEventTime()) {
                state.setLastValidEventTime(sample.eventTime());
            }
            state.setLastValidIngestTime(sample.ingestTime());
            // 在线判定口径：以“最近一次有效上报的接收时间”为准。
            // 乱序/滞后上报只要被接受，就同样把在线区间延展到 ingestTime + offlineMillis，
            // 事件时间再旧也不会把设备误判为离线。
            state.recordOnlineSegment(sample.ingestTime(), properties.getOfflineMillis());
            evictExpiredLocked(state);
            return true;
        }
    }

    /** 事件时间升序；相同事件时间按序列号升序，保证乱序到达后的存储顺序确定。 */
    private boolean earlierThan(AcceptedSample incoming, AcceptedSample existing) {
        if (incoming.eventTime() != existing.eventTime()) {
            return incoming.eventTime() < existing.eventTime();
        }
        return incoming.sequence() < existing.sequence();
    }

    /**
     * 容量与保留控制，全部在设备锁内完成，淘汰只移除旧数据、不触碰当前在线结论：
     * 1) 窗口样本按事件时间淘汰（相对当前时钟）；
     * 2) 窗口样本条数上限，超出淘汰最旧样本；
     * 3) 序列号去重状态条数上限，超出淘汰最旧序列号；
     * 4) 可用性在线区间按保留时长淘汰，并推进 availabilityCoveredFrom。
     * 被淘汰的窗口样本记录到 windowEvictedUpTo，查询若涉及该范围将明确报 OUT_OF_RETENTION。
     */
    private void evictExpiredLocked(DeviceState state) {
        long now = clock.nowMillis();
        long cutoff = now - properties.getWindowMillis();
        var window = state.window();
        // 窗口按事件时间升序，被淘汰的样本是前缀。
        int evictCount = 0;
        while (evictCount < window.size() && window.get(evictCount).eventTime() < cutoff) {
            evictCount++;
        }
        // 条数上限：继续从头部淘汰最旧样本。
        int overflow = window.size() - evictCount - properties.getMaxWindowSamples();
        if (overflow > 0) {
            evictCount += overflow;
        }
        if (evictCount > 0) {
            long maxEvicted = Long.MIN_VALUE;
            for (int i = 0; i < evictCount; i++) {
                maxEvicted = Math.max(maxEvicted, window.get(i).eventTime());
            }
            window.subList(0, evictCount).clear();
            state.setWindowEvictedUpTo(Math.max(state.windowEvictedUpTo(), maxEvicted));
        }

        // 序列号去重状态上限：LinkedHashSet 按插入序淘汰最旧。
        var seen = state.seenSequences();
        while (seen.size() > properties.getMaxSeenSequences()) {
            var it = seen.iterator();
            it.next();
            it.remove();
        }

        // 可用性历史保留：淘汰结束时刻早于保留边界的在线区间。
        long retentionCutoff = now - properties.getAvailabilityRetentionMillis();
        var segments = state.onlineSegments();
        long lastEvictedEnd = -1L;
        int segEvict = 0;
        while (segEvict < segments.size() && segments.get(segEvict)[1] < retentionCutoff) {
            lastEvictedEnd = Math.max(lastEvictedEnd, segments.get(segEvict)[1]);
            segEvict++;
        }
        if (segEvict > 0) {
            segments.subList(0, segEvict).clear();
            // 被淘汰区间之后到第一段保留区间之间的历史不再可知，推进可查下界。
            state.setAvailabilityCoveredFrom(Math.max(state.availabilityCoveredFrom(), lastEvictedEnd));
        }
    }
}

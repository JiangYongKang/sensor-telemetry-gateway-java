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

    /** 已注册设备的快照（用于机群级概览；返回副本，遍历与上报并发安全）。 */
    public java.util.List<DeviceState> snapshot() {
        return java.util.List.copyOf(devices.values());
    }

    /**
     * 将一条已通过校验的样本原子写入设备状态：
     * V2 序列号去重、窗口追加、最新值更新、接入时间历史追加均在同一把锁内完成，
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
            // 可用性判定依据：只记录被接受上报的接收时间，与事件时间无关。
            state.ingestHistory().addLast(sample.ingestTime());
            evictWindowLocked(state);
            evictIngestHistoryLocked(state);
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
     * 窗口淘汰（在同一临界区内执行，淘汰不会打断任何判定）：
     * 1) 淘汰当前窗口左边界之前的旧样本——仅为内存优化，查询本身始终重新按窗口过滤；
     * 2) 条数硬上限 maxWindowSamples——窗口按事件时间升序，淘汰最旧样本。
     * 被淘汰样本的序列号同步移出去重集合（去重语义本就限定在窗口内），
     * 因此去重状态随窗口一起有界，且不会出现同一样本被重复计数。
     */
    private void evictWindowLocked(DeviceState state) {
        long cutoff = clock.nowMillis() - properties.getWindowMillis();
        state.window().removeIf(sample -> {
            if (sample.eventTime() < cutoff) {
                if (sample.sequence() >= 0) {
                    state.seenSequences().remove(sample.sequence());
                }
                return true;
            }
            return false;
        });
        while (state.window().size() > properties.getMaxWindowSamples()) {
            AcceptedSample evicted = state.window().remove(0);
            if (evicted.sequence() >= 0) {
                state.seenSequences().remove(evicted.sequence());
            }
        }
    }

    /**
     * 接入时间历史淘汰（有界保留，可用性判定的唯一依据）：
     * 1) 按 availabilityRetentionMillis 淘汰过旧记录；
     * 2) 按 maxIngestHistory 限制条数。
     * 每淘汰一段前缀，就把 historyFloor 提升到“保留起点 + 离线阈值”：
     * 早于 floor 的区间无法保证结论准确，查询必须明确拒绝而非静默算错。
     * 淘汰只影响历史查询范围，不影响当前在线状态（由 lastValidIngestTime 判定），
     * 因此淘汰过程不会造成在线状态抖动。
     */
    private void evictIngestHistoryLocked(DeviceState state) {
        long cutoff = clock.nowMillis() - properties.getAvailabilityRetentionMillis();
        var history = state.ingestHistory();
        boolean evictedByTime = false;
        while (!history.isEmpty() && history.peekFirst() < cutoff) {
            history.pollFirst();
            evictedByTime = true;
        }
        if (evictedByTime) {
            state.setHistoryFloor(Math.max(state.historyFloor(),
                    cutoff + properties.getOfflineMillis()));
        }
        while (history.size() > properties.getMaxIngestHistory()) {
            history.pollFirst();
            Long head = history.peekFirst();
            if (head != null) {
                state.setHistoryFloor(Math.max(state.historyFloor(),
                        head + properties.getOfflineMillis()));
            }
        }
    }
}

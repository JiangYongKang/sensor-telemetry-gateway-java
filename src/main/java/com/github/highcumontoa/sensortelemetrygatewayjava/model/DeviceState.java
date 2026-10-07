package com.github.highcumontoa.sensortelemetrygatewayjava.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设备的本地可变状态：声明信息、各传感器最新值（带其事件时间）、滑动窗口样本，
 * 以及可用性判定所需的接入时间历史（有界）。
 * 所有可变字段的读写必须持有实例自身的监视器锁（见 DeviceRegistry）。
 */
public final class DeviceState {

    private final String deviceId;
    private final ProtocolVersion protocolVersion;
    private final Set<SensorType> capabilities;
    private final long registeredAt;
    private final Map<SensorType, CanonicalReading> latest = new HashMap<>();
    private final Map<SensorType, Long> latestEventTime = new HashMap<>();
    private final List<AcceptedSample> window = new ArrayList<>();
    private final Set<Long> seenSequences = new HashSet<>();
    /** 已接受上报的接收时间（升序），可用性判定的唯一依据；有界淘汰。 */
    private final Deque<Long> ingestHistory = new ArrayDeque<>();
    /** 可用性可准确回答的最早时刻：早于该时刻的历史已被淘汰，查询必须明确拒绝。 */
    private long historyFloor;
    private long lastValidEventTime = -1L;
    private long lastValidIngestTime = -1L;

    public DeviceState(String deviceId, ProtocolVersion protocolVersion, Set<SensorType> capabilities,
                       long registeredAt) {
        this.deviceId = deviceId;
        this.protocolVersion = protocolVersion;
        this.capabilities = Set.copyOf(capabilities);
        this.registeredAt = registeredAt;
        this.historyFloor = registeredAt;
    }

    public String deviceId() {
        return deviceId;
    }

    public ProtocolVersion protocolVersion() {
        return protocolVersion;
    }

    public Set<SensorType> capabilities() {
        return capabilities;
    }

    public long registeredAt() {
        return registeredAt;
    }

    public Deque<Long> ingestHistory() {
        return ingestHistory;
    }

    public long historyFloor() {
        return historyFloor;
    }

    public void setHistoryFloor(long value) {
        this.historyFloor = value;
    }

    public Map<SensorType, CanonicalReading> latest() {
        return latest;
    }

    public Map<SensorType, Long> latestEventTime() {
        return latestEventTime;
    }

    public List<AcceptedSample> window() {
        return window;
    }

    public Set<Long> seenSequences() {
        return seenSequences;
    }

    public long lastValidEventTime() {
        return lastValidEventTime;
    }

    public void setLastValidEventTime(long value) {
        this.lastValidEventTime = value;
    }

    public long lastValidIngestTime() {
        return lastValidIngestTime;
    }

    public void setLastValidIngestTime(long value) {
        this.lastValidIngestTime = value;
    }
}

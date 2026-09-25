package com.github.highcumontoa.sensortelemetrygatewayjava.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设备的本地可变状态：声明信息、各传感器最新值（带其事件时间）与滑动窗口样本。
 * 所有可变字段的读写必须持有实例自身的监视器锁（见 DeviceRegistry）。
 */
public final class DeviceState {

    private final String deviceId;
    private final ProtocolVersion protocolVersion;
    private final Set<SensorType> capabilities;
    private final Map<SensorType, CanonicalReading> latest = new HashMap<>();
    private final Map<SensorType, Long> latestEventTime = new HashMap<>();
    private final List<AcceptedSample> window = new ArrayList<>();
    private final Set<Long> seenSequences = new HashSet<>();
    private long lastValidEventTime = -1L;
    private long lastValidIngestTime = -1L;

    public DeviceState(String deviceId, ProtocolVersion protocolVersion, Set<SensorType> capabilities) {
        this.deviceId = deviceId;
        this.protocolVersion = protocolVersion;
        this.capabilities = Set.copyOf(capabilities);
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

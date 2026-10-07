package com.github.highcumontoa.sensortelemetrygatewayjava.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设备的本地可变状态：声明信息、各传感器最新值（带其事件时间）、滑动窗口样本、
 * 序列号去重状态与可用性在线区间。
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
    /** 插入有序的序列号集合，便于按容量上限淘汰最旧序列号。 */
    private final LinkedHashSet<Long> seenSequences = new LinkedHashSet<>();
    /**
     * 已合并的在线区间段，每段为 [startMillis, endMillis]（闭区间，epoch millis），
     * 按 start 升序且互不重叠、互不相邻。设备在时刻 t 在线当且仅当 t 落在某段内。
     */
    private final List<long[]> onlineSegments = new ArrayList<>();
    /** 可用性历史已知的最早时刻：注册时间或被保留策略淘汰掉的最后一段在线区间的结束时刻。 */
    private long availabilityCoveredFrom;
    /** 因窗口淘汰而被移除的样本的最大事件时间；-1 表示尚未发生过淘汰。用于识别超出保留范围的查询。 */
    private long windowEvictedUpTo = -1L;
    private long lastValidEventTime = -1L;
    private long lastValidIngestTime = -1L;

    public DeviceState(String deviceId, ProtocolVersion protocolVersion, Set<SensorType> capabilities,
                       long registeredAt) {
        this.deviceId = deviceId;
        this.protocolVersion = protocolVersion;
        this.capabilities = Set.copyOf(capabilities);
        this.registeredAt = registeredAt;
        this.availabilityCoveredFrom = registeredAt;
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

    public long registeredAt() {
        return registeredAt;
    }

    public List<long[]> onlineSegments() {
        return onlineSegments;
    }

    public long availabilityCoveredFrom() {
        return availabilityCoveredFrom;
    }

    public void setAvailabilityCoveredFrom(long value) {
        this.availabilityCoveredFrom = value;
    }

    public long windowEvictedUpTo() {
        return windowEvictedUpTo;
    }

    public void setWindowEvictedUpTo(long value) {
        this.windowEvictedUpTo = value;
    }

    /**
     * 记录一次被接受上报的接收时间：在线区间 [ingestAt, ingestAt + offlineMillis]。
     * 接收时间单调不减，因此只需与最后一段尝试合并；重叠或相邻（毫秒离散）即合并，
     * 保证区间段数量有界且在线状态不会因合并而抖动。
     */
    public void recordOnlineSegment(long ingestAt, long offlineMillis) {
        long end = ingestAt + offlineMillis;
        int size = onlineSegments.size();
        if (size > 0) {
            long[] last = onlineSegments.get(size - 1);
            if (ingestAt <= last[1] + 1) {
                if (end > last[1]) {
                    last[1] = end;
                }
                return;
            }
        }
        onlineSegments.add(new long[]{ingestAt, end});
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

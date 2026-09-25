package com.github.highcumontoa.sensortelemetrygatewayjava.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.github.highcumontoa.sensortelemetrygatewayjava.config.TelemetryProperties;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceStatus;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.NormalizedReading;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.ProtocolVersion;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectedReport;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.WindowAggregate;
import org.springframework.stereotype.Component;

/**
 * 本地遥测状态。并发与乱序语义（确定且可复现）：
 *
 * <ol>
 *   <li>同一设备的所有写入在其 {@link DeviceState} 监视器上串行化，不同设备可并行；
 *       因此窗口结果不会因并发而回退、重复计数或丢数。</li>
 *   <li>事件时间水位 watermark = 该设备已接受读数的最大事件时间，单调不减；
 *       乱序（事件时间早于水位但不超过 maxLateMillis）允许并入窗且计入聚合；
 *       迟到（eventTime &lt; watermark - maxLateMillis）以 {@link RejectReason#LATE_REPORT} 拒绝。</li>
 *   <li>同一 (事件时间) 的并发写入按接受顺序（acceptedAt）稳定决胜，最新值不回退。</li>
 *   <li>窗口为 [now - windowMillis, now]；now 显式取当前水位，保证给定输入序列下结果与
 *       线程调度、墙钟无关；出窗读数被确定性裁剪。</li>
 *   <li>最后有效上报时间 = 最近一次被接受读数的接收时间，离线判定只依赖它。</li>
 * </ol>
 */
@Component
public class TelemetryStore {

    private final Clock clock;
    private final long windowMillis;
    private final long maxLateMillis;
    private final long offlineThresholdMillis;
    private final int rejectedHistoryPerDevice;

    private final ConcurrentMap<String, DeviceState> devices = new ConcurrentHashMap<>();

    public TelemetryStore(Clock clock, TelemetryProperties properties) {
        this.clock = clock;
        this.windowMillis = properties.getWindowMillis();
        this.maxLateMillis = properties.getMaxLateMillis();
        this.offlineThresholdMillis = properties.getOfflineThresholdMillis();
        this.rejectedHistoryPerDevice = properties.getRejectedHistoryPerDevice();
    }

    /** 无法解析出设备标识的拒绝留档归到此桶，仍可追溯。 */
    public static final String UNKNOWN_DEVICE_BUCKET = "__UNKNOWN_DEVICE__";

    private DeviceState stateFor(String deviceId) {
        String key = deviceId == null ? UNKNOWN_DEVICE_BUCKET : deviceId;
        return devices.computeIfAbsent(key, DeviceState::new);
    }

    /**
     * 留档任意一条被拒绝的上报（含原始报文、原因与判定依据）。
     * 每设备按拒绝发生顺序保存，超出容量淘汰最旧记录。
     */
    public void recordRejected(com.github.highcumontoa.sensortelemetrygatewayjava.model.RawReport raw,
                               RejectReason reason, String detail) {
        DeviceState state = stateFor(raw == null ? null : raw.deviceId());
        synchronized (state) {
            state.addRejected(new RejectedReport(raw, reason, detail, clock.nowMillis()));
        }
    }

    /**
     * 串行化地接纳一条已归一化读数。
     *
     * @return 拒绝原因描述；empty 表示已接受并入窗。
     *         注意：迟到拒绝不在此留档，由上层与其它拒绝原因统一留档，保证每条拒绝恰好一条记录。
     */
    public Optional<RejectedReport> accept(NormalizedReading reading) {
        DeviceState state = stateFor(reading.deviceId());
        synchronized (state) {
            if (state.watermark != Long.MIN_VALUE
                    && reading.eventTimeMillis() < state.watermark - maxLateMillis) {
                return Optional.of(new RejectedReport(
                        null, RejectReason.LATE_REPORT,
                        "eventTime=" + reading.eventTimeMillis()
                                + " is later than allowed; watermark=" + state.watermark
                                + ", maxLateMillis=" + maxLateMillis,
                        clock.nowMillis()));
            }

            state.sensors
                    .computeIfAbsent(reading.sensorType(), t -> new SensorState())
                    .add(reading);

            if (reading.eventTimeMillis() > state.watermark) {
                state.watermark = reading.eventTimeMillis();
            }
            // 最后有效上报时间按接受时刻推进（单调不减），与乱序无关。
            if (reading.acceptedAtMillis() > state.lastAcceptedAt) {
                state.lastAcceptedAt = reading.acceptedAtMillis();
            }
            // 水位推进后裁剪所有已出窗的历史读数。
            long windowStart = state.watermark - windowMillis;
            state.sensors.values().forEach(sensor -> sensor.pruneBefore(windowStart));
            return Optional.empty();
        }
    }

    public WindowAggregate aggregate(String deviceId, SensorType sensorType) {
        DeviceState state = stateFor(deviceId);
        synchronized (state) {
            SensorState sensor = state.sensors.get(sensorType);
            long watermark = state.watermark == Long.MIN_VALUE ? clock.nowMillis() : state.watermark;
            long windowStart = watermark - windowMillis;
            if (sensor == null) {
                return new WindowAggregate(deviceId, sensorType, 0, null, Long.MIN_VALUE,
                        null, null, null, windowStart, watermark, watermark);
            }
            return sensor.aggregate(deviceId, sensorType, windowStart, watermark);
        }
    }

    /**
     * 设备状态。registered=false 时不尝试版本信息；离线判定仅基于最后有效上报时间：
     * now - lastValidReportTime &lt;= threshold 即在线（从未上报者视为离线）。
     */
    public DeviceStatus status(String deviceId, DeviceLookup lookup) {
        DeviceState state = devices.get(deviceId);
        long now = clock.nowMillis();
        if (state == null) {
            ProtocolVersion version = lookup == null ? null : lookup.version(deviceId);
            boolean registered = lookup != null && lookup.registered(deviceId);
            return new DeviceStatus(deviceId, version, registered, Long.MIN_VALUE, now,
                    false, offlineThresholdMillis);
        }
        synchronized (state) {
            ProtocolVersion version = lookup == null ? null : lookup.version(deviceId);
            boolean registered = lookup != null && lookup.registered(deviceId);
            boolean online = state.lastAcceptedAt != Long.MIN_VALUE
                    && now - state.lastAcceptedAt <= offlineThresholdMillis;
            return new DeviceStatus(deviceId, version, registered, state.lastAcceptedAt, now,
                    online, offlineThresholdMillis);
        }
    }

    public List<RejectedReport> rejected(String deviceId) {
        DeviceState state = devices.get(deviceId);
        if (state == null) {
            return List.of();
        }
        synchronized (state) {
            return List.copyOf(state.rejected);
        }
    }

    /** 注册表查询抽象，避免状态层反向依赖注册组件。 */
    public interface DeviceLookup {
        boolean registered(String deviceId);

        ProtocolVersion version(String deviceId);
    }

    private final class DeviceState {
        private final String deviceId;
        private final Map<SensorType, SensorState> sensors = new java.util.HashMap<>();
        private final Deque<RejectedReport> rejected = new ArrayDeque<>();
        private long watermark = Long.MIN_VALUE;
        private long lastAcceptedAt = Long.MIN_VALUE;

        private DeviceState(String deviceId) {
            this.deviceId = deviceId;
        }

        private void addRejected(RejectedReport report) {
            rejected.addLast(report);
            while (rejected.size() > rejectedHistoryPerDevice) {
                rejected.removeFirst();
            }
        }
    }

    /**
     * 单传感器窗口状态。读数按事件时间有序保存；相同事件时间用底层列表顺序保持
     * 接受顺序，从而得到确定的最新值决胜。
     */
    private static final class SensorState {
        // key: eventTime -> 该事件时间下按接受顺序排列的读数
        private final TreeMap<Long, List<NormalizedReading>> byEventTime = new TreeMap<>();

        private void add(NormalizedReading reading) {
            byEventTime.computeIfAbsent(reading.eventTimeMillis(), t -> new ArrayList<>()).add(reading);
        }

        private void pruneBefore(long exclusiveStart) {
            byEventTime.navigableKeySet().removeIf(t -> t < exclusiveStart);
        }

        private WindowAggregate aggregate(String deviceId, SensorType sensorType,
                                          long windowStart, long windowEnd) {
            List<NormalizedReading> inWindow = new ArrayList<>();
            for (Map.Entry<Long, List<NormalizedReading>> entry
                    : byEventTime.tailMap(windowStart, true).entrySet()) {
                inWindow.addAll(entry.getValue());
            }
            if (inWindow.isEmpty()) {
                return new WindowAggregate(deviceId, sensorType, 0, null, Long.MIN_VALUE,
                        null, null, null, windowStart, windowEnd, windowEnd);
            }
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            double sum = 0.0;
            for (NormalizedReading r : inWindow) {
                min = Math.min(min, r.value());
                max = Math.max(max, r.value());
                sum += r.value();
            }
            // 最新值：窗口内最大事件时间；同事件时间取最后接受者（列表末尾），保证不回退。
            List<NormalizedReading> latestBucket = byEventTime.floorEntry(windowEnd).getValue();
            NormalizedReading latest = latestBucket.get(latestBucket.size() - 1);
            return new WindowAggregate(deviceId, sensorType, inWindow.size(),
                    latest.value(), latest.eventTimeMillis(), min, max, sum / inWindow.size(),
                    windowStart, windowEnd, windowEnd);
        }
    }
}

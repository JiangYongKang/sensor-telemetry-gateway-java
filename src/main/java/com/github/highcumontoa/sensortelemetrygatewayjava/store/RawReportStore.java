package com.github.highcumontoa.sensortelemetrygatewayjava.store;

import com.github.highcumontoa.sensortelemetrygatewayjava.config.GatewayProperties;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RawReport;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 原始上报留档：接受与拒绝均保留，按接收顺序排列，便于追溯与审计。
 * 容量有明确上限（gateway.max-raw-reports），超出时淘汰最旧记录，
 * 长期高频上报内存占用有界；淘汰只移除最旧留档，不影响任何在线/聚合判定。
 */
@Component
public class RawReportStore {

    private final GatewayProperties properties;
    private final ArrayDeque<RawReport> reports = new ArrayDeque<>();
    /** 因容量上限被淘汰的记录总数，便于观测。 */
    private long evictedCount = 0L;

    public RawReportStore(GatewayProperties properties) {
        this.properties = properties;
    }

    public synchronized void save(RawReport rawReport) {
        reports.addLast(rawReport);
        while (reports.size() > properties.getMaxRawReports()) {
            reports.pollFirst();
            evictedCount++;
        }
    }

    public synchronized List<RawReport> findByDevice(String deviceId) {
        List<RawReport> result = new ArrayList<>();
        for (RawReport report : reports) {
            if (deviceId.equals(report.deviceId())) {
                result.add(report);
            }
        }
        return result;
    }

    public synchronized List<RawReport> findAll() {
        return List.copyOf(reports);
    }

    public synchronized long evictedCount() {
        return evictedCount;
    }
}

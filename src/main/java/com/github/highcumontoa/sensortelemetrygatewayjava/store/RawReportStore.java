package com.github.highcumontoa.sensortelemetrygatewayjava.store;

import com.github.highcumontoa.sensortelemetrygatewayjava.config.GatewayProperties;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RawReport;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 原始上报留档：接受与拒绝均保留，按接收顺序排列，便于追溯与审计。
 * 总条数受 gateway.raw-report-capacity 限制，超出时按先进先出淘汰最旧记录，
 * 长期运行内存占用有界。
 */
@Component
public class RawReportStore {

    private final GatewayProperties properties;
    private final Deque<RawReport> reports = new ArrayDeque<>();

    public RawReportStore(GatewayProperties properties) {
        this.properties = properties;
    }

    public synchronized void save(RawReport rawReport) {
        reports.addLast(rawReport);
        // 每次写入动态读取上限，配置调整即时生效。
        while (reports.size() > properties.getRawReportCapacity()) {
            reports.pollFirst();
        }
    }

    public synchronized List<RawReport> findByDevice(String deviceId) {
        List<RawReport> matched = new ArrayList<>();
        for (RawReport report : reports) {
            if (deviceId.equals(report.deviceId())) {
                matched.add(report);
            }
        }
        return List.copyOf(matched);
    }

    public synchronized List<RawReport> findAll() {
        return List.copyOf(reports);
    }
}

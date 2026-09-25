package com.github.highcumontoa.sensortelemetrygatewayjava.store;

import com.github.highcumontoa.sensortelemetrygatewayjava.model.RawReport;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 原始上报留档：接受与拒绝均保留，按接收顺序排列，便于追溯与审计。 */
@Component
public class RawReportStore {

    private final CopyOnWriteArrayList<RawReport> reports = new CopyOnWriteArrayList<>();

    public void save(RawReport rawReport) {
        reports.add(rawReport);
    }

    public List<RawReport> findByDevice(String deviceId) {
        return reports.stream().filter(r -> deviceId.equals(r.deviceId())).toList();
    }

    public List<RawReport> findAll() {
        return List.copyOf(reports);
    }
}

package com.github.highcumontoa.sensortelemetrygatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 遥测规则参数（可通过 application.properties 覆盖）：
 * windowMillis 窗口长度；maxLateMillis 允许迟到时长；
 * offlineThresholdMillis 离线判定阈值；rejectedHistoryPerDevice 每设备拒绝留档上限。
 */
@ConfigurationProperties(prefix = "telemetry")
public class TelemetryProperties {

    private long windowMillis = 60_000L;
    private long maxLateMillis = 10_000L;
    private long offlineThresholdMillis = 30_000L;
    private int rejectedHistoryPerDevice = 200;

    public long getWindowMillis() {
        return windowMillis;
    }

    public void setWindowMillis(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    public long getMaxLateMillis() {
        return maxLateMillis;
    }

    public void setMaxLateMillis(long maxLateMillis) {
        this.maxLateMillis = maxLateMillis;
    }

    public long getOfflineThresholdMillis() {
        return offlineThresholdMillis;
    }

    public void setOfflineThresholdMillis(long offlineThresholdMillis) {
        this.offlineThresholdMillis = offlineThresholdMillis;
    }

    public int getRejectedHistoryPerDevice() {
        return rejectedHistoryPerDevice;
    }

    public void setRejectedHistoryPerDevice(int rejectedHistoryPerDevice) {
        this.rejectedHistoryPerDevice = rejectedHistoryPerDevice;
    }
}

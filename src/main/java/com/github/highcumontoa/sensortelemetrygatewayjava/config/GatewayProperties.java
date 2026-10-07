package com.github.highcumontoa.sensortelemetrygatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 网关可配置参数：窗口长度、迟到容忍、未来时间容忍、离线阈值与各类容量/保留上限。 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private long windowMillis = 60_000L;
    private long maxFutureMillis = 5_000L;
    private long offlineMillis = 30_000L;
    /** 每台设备窗口样本的硬上限（超出时淘汰最旧样本及其去重序列号）。 */
    private int maxWindowSamples = 10_000;
    /** 可用性判定所需的接入时间历史保留时长。 */
    private long availabilityRetentionMillis = 3_600_000L;
    /** 每台设备接入时间历史的条数硬上限。 */
    private int maxIngestHistory = 100_000;
    /** 原始报文留档总条数上限（先进先出淘汰）。 */
    private int rawReportCapacity = 100_000;

    public long getWindowMillis() {
        return windowMillis;
    }

    public void setWindowMillis(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    public long getMaxFutureMillis() {
        return maxFutureMillis;
    }

    public void setMaxFutureMillis(long maxFutureMillis) {
        this.maxFutureMillis = maxFutureMillis;
    }

    public long getOfflineMillis() {
        return offlineMillis;
    }

    public void setOfflineMillis(long offlineMillis) {
        this.offlineMillis = offlineMillis;
    }

    public int getMaxWindowSamples() {
        return maxWindowSamples;
    }

    public void setMaxWindowSamples(int maxWindowSamples) {
        this.maxWindowSamples = maxWindowSamples;
    }

    public long getAvailabilityRetentionMillis() {
        return availabilityRetentionMillis;
    }

    public void setAvailabilityRetentionMillis(long availabilityRetentionMillis) {
        this.availabilityRetentionMillis = availabilityRetentionMillis;
    }

    public int getMaxIngestHistory() {
        return maxIngestHistory;
    }

    public void setMaxIngestHistory(int maxIngestHistory) {
        this.maxIngestHistory = maxIngestHistory;
    }

    public int getRawReportCapacity() {
        return rawReportCapacity;
    }

    public void setRawReportCapacity(int rawReportCapacity) {
        this.rawReportCapacity = rawReportCapacity;
    }
}

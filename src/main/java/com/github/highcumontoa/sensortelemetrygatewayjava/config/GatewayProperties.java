package com.github.highcumontoa.sensortelemetrygatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 网关可配置参数：窗口长度、迟到容忍、未来时间容忍、离线阈值与各类容量/保留上限。 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private long windowMillis = 60_000L;
    private long maxFutureMillis = 5_000L;
    private long offlineMillis = 30_000L;
    /** 每台设备窗口内最多保留的样本条数，超出时淘汰最旧样本。 */
    private int maxWindowSamples = 10_000;
    /** 每台设备序列号去重状态的最大条数，超出时淘汰最旧序列号。 */
    private int maxSeenSequences = 100_000;
    /** 可用性（在线区间）历史的保留时长，超出部分查询时明确报 OUT_OF_RETENTION。 */
    private long availabilityRetentionMillis = 86_400_000L;
    /** 原始报文留档的最大条数（全局），超出时淘汰最旧记录。 */
    private int maxRawReports = 100_000;

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

    public int getMaxSeenSequences() {
        return maxSeenSequences;
    }

    public void setMaxSeenSequences(int maxSeenSequences) {
        this.maxSeenSequences = maxSeenSequences;
    }

    public long getAvailabilityRetentionMillis() {
        return availabilityRetentionMillis;
    }

    public void setAvailabilityRetentionMillis(long availabilityRetentionMillis) {
        this.availabilityRetentionMillis = availabilityRetentionMillis;
    }

    public int getMaxRawReports() {
        return maxRawReports;
    }

    public void setMaxRawReports(int maxRawReports) {
        this.maxRawReports = maxRawReports;
    }
}

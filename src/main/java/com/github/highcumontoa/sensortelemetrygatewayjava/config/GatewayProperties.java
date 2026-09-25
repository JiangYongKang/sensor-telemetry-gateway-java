package com.github.highcumontoa.sensortelemetrygatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 网关可配置参数：窗口长度、迟到容忍、未来时间容忍、离线阈值。 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private long windowMillis = 60_000L;
    private long maxFutureMillis = 5_000L;
    private long offlineMillis = 30_000L;

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
}

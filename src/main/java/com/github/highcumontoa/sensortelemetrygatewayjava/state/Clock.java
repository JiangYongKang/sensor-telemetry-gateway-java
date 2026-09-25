package com.github.highcumontoa.sensortelemetrygatewayjava.state;

/**
 * 可注入时钟，使窗口与离线判定完全可复现。
 */
public interface Clock {
    long nowMillis();
}

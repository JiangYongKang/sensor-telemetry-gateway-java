package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/** 一段连续的掉线时间段（闭区间，epoch 毫秒）。 */
public record OfflineInterval(
        long from,
        long to
) {
}

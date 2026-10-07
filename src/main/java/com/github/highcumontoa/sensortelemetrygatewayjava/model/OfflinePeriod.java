package com.github.highcumontoa.sensortelemetrygatewayjava.model;

/**
 * 一段掉线区间 [from, to]（epoch millis）。
 * from 为上一在线区间的结束时刻（或查询起点），to 为下一在线区间的开始时刻（或查询终点）。
 */
public record OfflinePeriod(long from, long to) {
}

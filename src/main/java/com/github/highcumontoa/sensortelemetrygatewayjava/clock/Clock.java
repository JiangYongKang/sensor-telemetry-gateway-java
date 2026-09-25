package com.github.highcumontoa.sensortelemetrygatewayjava.clock;

/** 可注入的时钟，便于在测试中复现时间相关行为。 */
public interface Clock {
    long nowMillis();
}

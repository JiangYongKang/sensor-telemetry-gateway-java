package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import com.github.highcumontoa.sensortelemetrygatewayjava.clock.Clock;

/** 可控时钟：测试可任意设定/推进当前时间，使离线与窗口判定可复现。 */
public class ManualClock implements Clock {

    private long now;

    public ManualClock(long start) {
        this.now = start;
    }

    @Override
    public long nowMillis() {
        return now;
    }

    public void set(long value) {
        this.now = value;
    }

    public void advance(long deltaMillis) {
        this.now += deltaMillis;
    }
}

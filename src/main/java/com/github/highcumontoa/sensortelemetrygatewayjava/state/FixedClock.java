package com.github.highcumontoa.sensortelemetrygatewayjava.state;

/**
 * 测试使用的固定/步进时钟：时间只随测试设定推进，使窗口与离线判定完全可复现。
 */
public class FixedClock implements Clock {

    private long current;

    public FixedClock(long initialMillis) {
        this.current = initialMillis;
    }

    public void setTime(long millis) {
        this.current = millis;
    }

    public void advanceMillis(long delta) {
        this.current += delta;
    }

    @Override
    public long nowMillis() {
        return current;
    }
}

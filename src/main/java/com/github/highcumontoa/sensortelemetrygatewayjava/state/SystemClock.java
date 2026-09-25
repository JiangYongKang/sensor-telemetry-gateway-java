package com.github.highcumontoa.sensortelemetrygatewayjava.state;

import org.springframework.stereotype.Component;

/** 生产环境使用的墙钟。 */
@Component
public class SystemClock implements Clock {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}

package com.github.highcumontoa.sensortelemetrygatewayjava.config;

import com.github.highcumontoa.sensortelemetrygatewayjava.protocol.ProtocolMappers;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bean 装配（骨架桩）。 */
@Configuration
@EnableConfigurationProperties(TelemetryProperties.class)
public class TelemetryConfiguration {

    @Bean
    public ProtocolMappers protocolMappers() {
        return new ProtocolMappers();
    }
}

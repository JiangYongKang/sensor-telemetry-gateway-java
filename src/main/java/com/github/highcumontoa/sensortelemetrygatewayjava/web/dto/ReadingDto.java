package com.github.highcumontoa.sensortelemetrygatewayjava.web.dto;

public record ReadingDto(
        String type,
        Double value,
        String unit
) {
}

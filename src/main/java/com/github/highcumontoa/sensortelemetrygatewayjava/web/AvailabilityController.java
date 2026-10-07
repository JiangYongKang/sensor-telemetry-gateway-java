package com.github.highcumontoa.sensortelemetrygatewayjava.web;

import com.github.highcumontoa.sensortelemetrygatewayjava.model.AvailabilityReport;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.AvailabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 设备可用性统计查询入口。 */
@RestController
@RequestMapping("/api/devices")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping("/{deviceId}/availability")
    public AvailabilityReport availability(@PathVariable String deviceId,
                                           @RequestParam(required = false) Long from,
                                           @RequestParam(required = false) Long to) {
        return availabilityService.availability(deviceId, from, to);
    }
}

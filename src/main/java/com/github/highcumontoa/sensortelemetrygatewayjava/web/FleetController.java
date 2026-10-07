package com.github.highcumontoa.sensortelemetrygatewayjava.web;

import com.github.highcumontoa.sensortelemetrygatewayjava.model.AvailabilityStats;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.FleetOverview;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.AvailabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 值班看板查询入口：单机可用性统计与机群级在线概览。 */
@RestController
public class FleetController {

    private final AvailabilityService availabilityService;

    public FleetController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping("/api/devices/{deviceId}/availability")
    public AvailabilityStats availability(@PathVariable String deviceId,
                                          @RequestParam long from,
                                          @RequestParam long to) {
        return availabilityService.availability(deviceId, from, to);
    }

    @GetMapping("/api/fleet/overview")
    public FleetOverview overview() {
        return availabilityService.fleetOverview();
    }
}

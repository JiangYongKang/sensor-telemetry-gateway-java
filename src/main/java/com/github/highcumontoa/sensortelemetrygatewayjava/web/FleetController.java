package com.github.highcumontoa.sensortelemetrygatewayjava.web;

import com.github.highcumontoa.sensortelemetrygatewayjava.model.FleetOverview;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.FleetService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 机群级在线概览查询入口。 */
@RestController
@RequestMapping("/api/fleet")
public class FleetController {

    private final FleetService fleetService;

    public FleetController(FleetService fleetService) {
        this.fleetService = fleetService;
    }

    @GetMapping("/overview")
    public FleetOverview overview() {
        return fleetService.overview();
    }
}

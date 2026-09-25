package com.github.highcumontoa.sensortelemetrygatewayjava.web;

import com.github.highcumontoa.sensortelemetrygatewayjava.model.RegistrationResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.DeviceService;
import com.github.highcumontoa.sensortelemetrygatewayjava.web.dto.RegisterRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping
    public ResponseEntity<RegistrationResult> register(@RequestBody RegisterRequest request) {
        RegistrationResult result = deviceService.register(
                request.deviceId(), request.protocolVersion(), request.capabilities());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }
}

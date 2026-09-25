package com.github.highcumontoa.sensortelemetrygatewayjava.web;

import java.util.List;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.Device;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceRegistration;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceStatus;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.IngestResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.ProtocolVersion;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectedReport;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RegistrationResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.WindowAggregate;
import com.github.highcumontoa.sensortelemetrygatewayjava.service.TelemetryService;
import com.github.highcumontoa.sensortelemetrygatewayjava.state.DeviceRegistry;
import com.github.highcumontoa.sensortelemetrygatewayjava.state.TelemetryStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP 入口。控制器不承载业务规则：注册与上报全部委托 {@link TelemetryService}，
 * 因此 HTTP 单条、HTTP 批量与服务直调对同一输入给出一致结论。
 * 上报接口消费原始文本 JSON，确保被拒绝时原始报文可原样留档。
 */
@RestController
@RequestMapping("/api")
public class TelemetryController {

    private final TelemetryService service;
    private final DeviceRegistry registry;
    private final TelemetryStore store;

    public TelemetryController(TelemetryService service, DeviceRegistry registry, TelemetryStore store) {
        this.service = service;
        this.registry = registry;
        this.store = store;
    }

    @PostMapping("/devices")
    public RegistrationResult register(@RequestBody DeviceRegistration registration) {
        return service.register(registration);
    }

    @PostMapping(path = "/telemetry", consumes = MediaType.APPLICATION_JSON_VALUE)
    public IngestResult ingest(@RequestBody String rawJson) {
        return service.ingest(rawJson, TelemetryService.ENTRY_SINGLE_HTTP);
    }

    @PostMapping(path = "/telemetry/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<IngestResult> ingestBatch(@RequestBody String rawJsonArray) {
        return service.ingestBatch(rawJsonArray, TelemetryService.ENTRY_BATCH_HTTP);
    }

    @GetMapping("/devices/{deviceId}/aggregate/{sensorType}")
    public WindowAggregate aggregate(@PathVariable String deviceId,
                                     @PathVariable SensorType sensorType) {
        return service.aggregate(deviceId, sensorType);
    }

    @GetMapping("/devices/{deviceId}/status")
    public DeviceStatus status(@PathVariable String deviceId) {
        return store.status(deviceId, new RegistryLookup());
    }

    @GetMapping("/devices/{deviceId}/rejected")
    public List<RejectedReport> rejected(@PathVariable String deviceId) {
        return store.rejected(deviceId);
    }

    /** 状态层通过此查找读取注册信息，避免反向依赖。 */
    private final class RegistryLookup implements TelemetryStore.DeviceLookup {
        @Override
        public boolean registered(String deviceId) {
            return registry.find(deviceId).isPresent();
        }

        @Override
        public ProtocolVersion version(String deviceId) {
            return registry.find(deviceId).map(Device::version).orElse(null);
        }
    }
}

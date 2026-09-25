package com.github.highcumontoa.sensortelemetrygatewayjava.service;

import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.GatewayException;
import com.github.highcumontoa.sensortelemetrygatewayjava.ingest.ProtocolSupport;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceState;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.ProtocolVersion;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RegistrationResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectionReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.registry.DeviceRegistry;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 设备注册与状态查询服务。 */
@Service
public class DeviceService {

    private final DeviceRegistry registry;
    private final ProtocolSupport protocolSupport;

    public DeviceService(DeviceRegistry registry, ProtocolSupport protocolSupport) {
        this.registry = registry;
        this.protocolSupport = protocolSupport;
    }

    public RegistrationResult register(String deviceId, String version, List<String> capabilities) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new GatewayException(RejectionReason.MISSING_FIELD, "注册缺少必填字段 deviceId");
        }
        if (capabilities == null || capabilities.isEmpty()) {
            throw new GatewayException(RejectionReason.MISSING_FIELD,
                    "设备 '" + deviceId + "' 注册必须声明至少一项能力 capabilities");
        }
        ProtocolVersion parsedVersion = protocolSupport.parse(version);
        Set<SensorType> parsedCapabilities = new HashSet<>();
        for (String raw : capabilities) {
            SensorType type;
            try {
                type = SensorType.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new GatewayException(RejectionReason.SENSOR_TYPE_UNKNOWN,
                        "注册能力中包含未知传感器类型 '" + raw + "'");
            }
            parsedCapabilities.add(type);
        }
        // 固定排序，保证幂等比较不受声明顺序影响。
        EnumSet<SensorType> normalized = EnumSet.copyOf(parsedCapabilities);
        return registry.register(deviceId, parsedVersion, normalized);
    }

    public DeviceState getDevice(String deviceId) {
        DeviceState state = registry.find(deviceId);
        if (state == null) {
            throw new GatewayException(RejectionReason.DEVICE_NOT_REGISTERED,
                    "设备 '" + deviceId + "' 未注册");
        }
        return state;
    }
}

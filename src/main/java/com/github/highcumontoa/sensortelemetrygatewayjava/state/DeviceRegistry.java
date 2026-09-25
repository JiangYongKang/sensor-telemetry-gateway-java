package com.github.highcumontoa.sensortelemetrygatewayjava.state;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.Capability;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.Device;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceRegistration;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.ProtocolVersion;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RegistrationResult;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;

/**
 * 本地设备注册表。
 *
 * <p>幂等规则：同一 deviceId 重复注册，当且仅当协议版本与能力范围（集合语义，与顺序无关）
 * 完全一致时按成功处理（idempotent=true）；任一不一致则以
 * {@link RejectReason#DUPLICATE_REGISTRATION_CONFLICT} 拒绝。
 * 注册原子化，并发重复注册只能得到上述两种确定结论之一。
 */
@Component
public class DeviceRegistry {

    private final ConcurrentMap<String, Device> devices = new ConcurrentHashMap<>();

    public RegistrationResult register(DeviceRegistration registration) {
        if (registration == null || isBlank(registration.deviceId())) {
            return rejected(null, RejectReason.INVALID_PAYLOAD, "deviceId is required");
        }
        String deviceId = registration.deviceId().trim();

        Optional<ProtocolVersion> parsedVersion = ProtocolVersion.fromString(registration.protocolVersion());
        if (parsedVersion.isEmpty()) {
            return rejected(deviceId, RejectReason.UNSUPPORTED_PROTOCOL_VERSION,
                    "unsupported or missing protocol version: " + registration.protocolVersion());
        }
        ProtocolVersion version = parsedVersion.get();

        Capability capability = registration.capability();
        if (capability == null || capability.sensorTypes() == null
                || capability.sensorTypes().isEmpty()) {
            return rejected(deviceId, RejectReason.INVALID_PAYLOAD,
                    "capability with at least one sensor type is required");
        }
        Set<SensorType> sensorTypes = Set.copyOf(capability.sensorTypes());
        Device incoming = new Device(deviceId, version, new Capability(sensorTypes));

        // 原子的"不存在则放入"，存在则取回，保证并发重复注册结论确定。
        Device existing = devices.putIfAbsent(deviceId, incoming);
        if (existing == null) {
            return new RegistrationResult(true, false, deviceId, null, "registered");
        }
        if (sameDeclaration(existing, incoming)) {
            return new RegistrationResult(true, true, deviceId, null,
                    "already registered with identical declaration");
        }
        return rejected(deviceId, RejectReason.DUPLICATE_REGISTRATION_CONFLICT,
                "existing=" + existing + " vs incoming=" + incoming);
    }

    public Optional<Device> find(String deviceId) {
        return Optional.ofNullable(devices.get(deviceId));
    }

    public int size() {
        return devices.size();
    }

    private static boolean sameDeclaration(Device a, Device b) {
        return a.version() == b.version()
                && new TreeSet<>(a.capability().sensorTypes())
                        .equals(new TreeSet<>(b.capability().sensorTypes()));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static RegistrationResult rejected(String deviceId, RejectReason reason, String detail) {
        return new RegistrationResult(false, false, deviceId, reason, detail);
    }
}

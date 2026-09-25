package com.github.highcumontoa.sensortelemetrygatewayjava.ingest;

import com.github.highcumontoa.sensortelemetrygatewayjava.config.GatewayProperties;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.AcceptedSample;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.CanonicalReading;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceState;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RawReading;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RawReport;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectionReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorCatalog;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.registry.DeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 上报校验与规范化管线。拒绝原因按确定的优先级产生第一条即拒绝：
 * 设备存在性 → 版本匹配 → 时间合法性 → 逐条（字段缺失 → 类型未知 →
 * 能力未声明 → 单位非法 → 量程越界）。整条上报原子接受或拒绝。
 */
@Component
public class ReportEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ReportEvaluator.class);

    private final DeviceRegistry registry;
    private final SensorCatalog catalog;
    private final ProtocolSupport protocolSupport;
    private final GatewayProperties properties;

    public ReportEvaluator(DeviceRegistry registry, SensorCatalog catalog,
                           ProtocolSupport protocolSupport, GatewayProperties properties) {
        this.registry = registry;
        this.catalog = catalog;
        this.protocolSupport = protocolSupport;
        this.properties = properties;
    }

    public AcceptedSample evaluate(RawReport raw, MappedReport mapped) {
        DeviceState device = registry.find(mapped.deviceId());
        if (device == null) {
            throw reject(RejectionReason.DEVICE_NOT_REGISTERED, mapped.deviceId(),
                    "设备未注册，拒绝接入");
        }
        protocolSupport.requireMatches(device, mapped.version());

        long ingestAt = raw.receivedAt();
        Long providedTimestamp = mapped.timestamp();
        long eventTime = providedTimestamp == null ? ingestAt : providedTimestamp;
        if (eventTime > ingestAt + properties.getMaxFutureMillis()) {
            throw reject(RejectionReason.FUTURE_TIMESTAMP, mapped.deviceId(),
                    "事件时间 " + eventTime + " 超过接收时间 " + ingestAt
                            + " 的未来容忍上限 " + properties.getMaxFutureMillis() + "ms");
        }

        List<CanonicalReading> canonical = new ArrayList<>();
        for (RawReading reading : mapped.readings()) {
            canonical.add(validateReading(device, reading));
        }

        AcceptedSample sample = new AcceptedSample(mapped.deviceId(), eventTime, ingestAt,
                mapped.sequence(), List.copyOf(canonical));
        log.info("校验通过 device={} version={} eventTime={} ingestAt={} sequence={} readings={}",
                mapped.deviceId(), mapped.version(), eventTime, ingestAt, mapped.sequence(), canonical);
        return sample;
    }

    private CanonicalReading validateReading(DeviceState device, RawReading reading) {
        if (reading.type() == null || reading.type().isBlank()) {
            throw reject(RejectionReason.MISSING_FIELD, device.deviceId(),
                    "读数缺少必填字段 type；原始读数=" + reading);
        }
        if (reading.value() == null) {
            throw reject(RejectionReason.MISSING_FIELD, device.deviceId(),
                    "读数缺少必填字段 value；原始读数=" + reading);
        }
        Optional<SensorType> typeOpt = catalog.resolveType(reading.type());
        if (typeOpt.isEmpty()) {
            throw reject(RejectionReason.SENSOR_TYPE_UNKNOWN, device.deviceId(),
                    "未知传感器类型 '" + reading.type() + "'");
        }
        SensorType type = typeOpt.get();
        if (!device.capabilities().contains(type)) {
            throw reject(RejectionReason.CAPABILITY_NOT_DECLARED, device.deviceId(),
                    "设备未声明能力 " + type + "，已声明能力=" + device.capabilities());
        }

        String rawUnit = reading.unit();
        String unitForConvert;
        if (rawUnit == null || rawUnit.isBlank()) {
            if (device.protocolVersion() == com.github.highcumontoa.sensortelemetrygatewayjava.model.ProtocolVersion.V1) {
                // V1 稳定缺省语义：缺单位即标准单位。
                unitForConvert = catalog.canonicalUnit(type);
            } else {
                throw reject(RejectionReason.MISSING_FIELD, device.deviceId(),
                        "协议 V2 要求读数显式携带 unit；原始读数=" + reading);
            }
        } else {
            unitForConvert = rawUnit.trim();
        }

        Optional<Double> converted = catalog.convertToCanonical(type, reading.value(), unitForConvert);
        if (converted.isEmpty()) {
            throw reject(RejectionReason.INVALID_UNIT, device.deviceId(),
                    "单位 '" + rawUnit + "' 不是 " + type + " 支持的单位（标准单位 "
                            + catalog.canonicalUnit(type) + "，支持 "
                            + catalog.spec(type).acceptedUnits() + "）；原始读数=" + reading);
        }
        double canonicalValue = converted.get();
        if (!catalog.inRange(type, canonicalValue)) {
            var spec = catalog.spec(type);
            throw reject(RejectionReason.VALUE_OUT_OF_RANGE, device.deviceId(),
                    type + " 规范化值 " + canonicalValue + " " + spec.canonicalUnit()
                            + " 超出量程 [" + spec.min() + ", " + spec.max() + "]；原始读数=" + reading);
        }
        return new CanonicalReading(type, canonicalValue, catalog.canonicalUnit(type));
    }

    private GatewayException reject(RejectionReason reason, String deviceId, String detail) {
        log.warn("拒绝上报 device={} reason={} 判定依据: {}", deviceId, reason, detail);
        return new GatewayException(reason, detail);
    }
}

package com.github.highcumontoa.sensortelemetrygatewayjava.normalization;

import org.springframework.stereotype.Component;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.MappedFields;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.NormalizedReading;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.ProtocolVersion;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectReason;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorCatalog;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.UnitSpec;
import com.github.highcumontoa.sensortelemetrygatewayjava.protocol.ReportMappingException;

/**
 * 读数归一化：
 * <ol>
 *   <li>按版本编码表解析传感器类型，未知编码以 UNKNOWN_SENSOR_TYPE 拒绝；</li>
 *   <li>单位缺省（仅 V1 允许）补规范单位；受支持单位做仿射换算，不支持以 UNSUPPORTED_UNIT 拒绝；</li>
 *   <li>换算后做闭区间量程校验，越界以 VALUE_OUT_OF_RANGE 拒绝。</li>
 * </ol>
 * 各拒绝原因互不重叠，保证各入口结论一致、可解释。
 */
@Component
public class ReadingNormalizer {

    /**
     * @param deviceId         上报设备
     * @param version          经严格校验、且与注册版本一致的协议版本
     * @param mapped           版本映射后的字段
     * @param acceptedAtMillis 网关接收时刻（作为 acceptedAt 留档）
     */
    public NormalizedReading normalize(String deviceId, ProtocolVersion version,
                                       MappedFields mapped, long acceptedAtMillis)
            throws ReportMappingException {

        SensorType sensorType = SensorCatalog.resolve(mapped.sensorCode(), version)
                .orElseThrow(() -> new ReportMappingException(RejectReason.UNKNOWN_SENSOR_TYPE,
                        "unknown sensor code for " + version + ": " + mapped.sensorCode()));

        UnitSpec spec = SensorCatalog.spec(sensorType);

        String unit;
        double canonicalValue;
        if (mapped.unitDefaulted() || mapped.rawUnit() == null) {
            // 稳定缺省语义：旧版本缺单位即按该类型规范单位解释，不做任何换算。
            unit = spec.canonicalUnit();
            canonicalValue = mapped.rawValue();
        } else {
            String rawUnit = mapped.rawUnit().trim();
            if (!spec.isSupportedUnit(rawUnit)) {
                throw new ReportMappingException(RejectReason.UNSUPPORTED_UNIT,
                        "unit '" + rawUnit + "' is not supported for " + sensorType
                                + "; supported units: " + spec.canonicalUnit()
                                + " " + spec.conversions().keySet());
            }
            unit = spec.canonicalUnit();
            canonicalValue = spec.toCanonical(mapped.rawValue(), rawUnit);
        }

        if (!spec.isInRange(canonicalValue)) {
            throw new ReportMappingException(RejectReason.VALUE_OUT_OF_RANGE,
                    "canonical value " + canonicalValue + " " + unit + " out of range ["
                            + spec.minValue() + ", " + spec.maxValue() + "] for " + sensorType);
        }

        return new NormalizedReading(deviceId, sensorType, canonicalValue, unit,
                mapped.eventTimeMillis(), acceptedAtMillis);
    }
}

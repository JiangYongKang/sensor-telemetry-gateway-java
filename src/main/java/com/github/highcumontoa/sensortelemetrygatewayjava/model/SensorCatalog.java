package com.github.highcumontoa.sensortelemetrygatewayjava.model;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;

/**
 * 传感器类型目录：单位别名、到标准单位的换算与有效量程的唯一事实来源。
 * 换算与量程判定均为纯函数，结论与调用入口无关。
 */
@Component
public class SensorCatalog {

    private final Map<SensorType, SpecDef> definitions;

    public SensorCatalog() {
        this.definitions = Map.of(
                SensorType.TEMPERATURE, new SpecDef(
                        "C",
                        Map.of(
                                "C", DoubleUnaryOperator.identity(),
                                "°C", DoubleUnaryOperator.identity(),
                                "F", v -> (v - 32.0d) * 5.0d / 9.0d,
                                "K", v -> v - 273.15d),
                        -50.0d, 150.0d),
                SensorType.HUMIDITY, new SpecDef(
                        "%",
                        Map.of("%", DoubleUnaryOperator.identity(),
                               "percent", DoubleUnaryOperator.identity()),
                        0.0d, 100.0d),
                SensorType.PRESSURE, new SpecDef(
                        "hPa",
                        Map.of(
                                "hPa", DoubleUnaryOperator.identity(),
                                "mbar", DoubleUnaryOperator.identity(),
                                "Pa", v -> v / 100.0d,
                                "kPa", v -> v * 10.0d,
                                "bar", v -> v * 1000.0d),
                        300.0d, 1100.0d),
                SensorType.ILLUMINANCE, new SpecDef(
                        "lx",
                        Map.of(
                                "lx", DoubleUnaryOperator.identity(),
                                "lux", DoubleUnaryOperator.identity(),
                                "klx", v -> v * 1000.0d),
                        0.0d, 200_000.0d)
        );
    }

    /** 大小写不敏感地解析类型名；未知类型返回 empty。 */
    public Optional<SensorType> resolveType(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(SensorType.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public SensorSpec spec(SensorType type) {
        SpecDef def = definitions.get(type);
        return new SensorSpec(type, def.canonicalUnit,
                Set.copyOf(def.converters.keySet()), def.min, def.max);
    }

    public String canonicalUnit(SensorType type) {
        return definitions.get(type).canonicalUnit;
    }

    /**
     * 将原始值按给定单位换算到标准单位。
     * 单位无法识别（含 null）时返回 empty，由上层判定为 INVALID_UNIT。
     */
    public Optional<Double> convertToCanonical(SensorType type, double value, String unit) {
        if (unit == null) {
            return Optional.empty();
        }
        SpecDef def = definitions.get(type);
        DoubleUnaryOperator converter = def.converters.get(unit.trim());
        if (converter == null) {
            return Optional.empty();
        }
        double converted = converter.applyAsDouble(value);
        if (!Double.isFinite(converted)) {
            return Optional.empty();
        }
        return Optional.of(converted);
    }

    /** 量程为闭区间；入参必须是标准单位下的值。 */
    public boolean inRange(SensorType type, double canonicalValue) {
        SpecDef def = definitions.get(type);
        return canonicalValue >= def.min && canonicalValue <= def.max;
    }

    private record SpecDef(String canonicalUnit,
                           Map<String, DoubleUnaryOperator> converters,
                           double min, double max) {
    }
}

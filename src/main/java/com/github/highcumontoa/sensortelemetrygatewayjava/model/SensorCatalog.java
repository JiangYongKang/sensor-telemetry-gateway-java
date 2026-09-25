package com.github.highcumontoa.sensortelemetrygatewayjava.model;

import java.util.Map;
import java.util.Optional;

/**
 * 传感器类型目录：单位、量程与版本编码映射规则的唯一来源。
 *
 * <p>量程（换算为规范单位后的闭区间）：
 * <ul>
 *   <li>温度：-40.0 ~ 85.0 ℃，支持 ℃(C) 与 ℉(F)，换算 C=(F-32)/1.8</li>
 *   <li>湿度：0.0 ~ 100.0 %，支持 % 与比例(ratio，0~1，乘以 100)</li>
 *   <li>气压：300.0 ~ 1100.0 hPa，支持 hPa、kPa(×10)、bar(×1000)</li>
 * </ul>
 *
 * <p>版本编码：V1 使用数字编码 1/2/3；V2 使用类型枚举名。
 */
public final class SensorCatalog {

    private static final Map<SensorType, UnitSpec> SPECS = Map.of(
            SensorType.TEMPERATURE, new UnitSpec(
                    "C",
                    Map.of("F", new UnitSpec.UnitConversion(1.0 / 1.8, -32.0 / 1.8)),
                    -40.0, 85.0),
            SensorType.HUMIDITY, new UnitSpec(
                    "%",
                    Map.of("ratio", new UnitSpec.UnitConversion(100.0, 0.0)),
                    0.0, 100.0),
            SensorType.PRESSURE, new UnitSpec(
                    "hPa",
                    Map.of("kPa", new UnitSpec.UnitConversion(10.0, 0.0),
                           "bar", new UnitSpec.UnitConversion(1000.0, 0.0)),
                    300.0, 1100.0));

    private static final Map<ProtocolVersion, Map<String, SensorType>> CODES = Map.of(
            ProtocolVersion.V1, Map.of(
                    "1", SensorType.TEMPERATURE,
                    "2", SensorType.HUMIDITY,
                    "3", SensorType.PRESSURE),
            ProtocolVersion.V2, Map.of(
                    SensorType.TEMPERATURE.name(), SensorType.TEMPERATURE,
                    SensorType.HUMIDITY.name(), SensorType.HUMIDITY,
                    SensorType.PRESSURE.name(), SensorType.PRESSURE));

    private SensorCatalog() {
    }

    public static UnitSpec spec(SensorType type) {
        return SPECS.get(type);
    }

    /**
     * 按协议版本解析传感器编码。编码未知时返回 empty，由上层以 UNKNOWN_SENSOR_TYPE 拒绝，
     * 绝不跨版本猜测编码含义。
     */
    public static Optional<SensorType> resolve(String code, ProtocolVersion version) {
        if (code == null) {
            return Optional.empty();
        }
        Map<String, SensorType> versionCodes = CODES.get(version);
        return Optional.ofNullable(versionCodes.get(code.trim()));
    }
}

package com.github.highcumontoa.sensortelemetrygatewayjava.model;

import java.util.Map;

/**
 * 传感器单位与量程规范。
 *
 * @param canonicalUnit   规范单位，归一化后统一使用
 * @param conversions     其它受支持单位到规范单位的仿射换算：canonicalValue = raw * factor + offset
 * @param minValue        规范单位下的量程下限（含）
 * @param maxValue        规范单位下的量程上限（含）
 */
public record UnitSpec(String canonicalUnit, Map<String, UnitConversion> conversions,
                       double minValue, double maxValue) {

    /** 单位仿射换算：canonicalValue = raw * factor + offset。 */
    public record UnitConversion(double factor, double offset) {
    }

    /** 规范单位自身或声明过换算关系的单位均视为受支持。 */
    public boolean isSupportedUnit(String unit) {
        return canonicalUnit.equals(unit) || conversions.containsKey(unit);
    }

    /**
     * 将给定单位下的原始值换算为规范单位值。
     *
     * @throws IllegalArgumentException 单位不受支持（调用方应先经 isSupportedUnit 判断并拒绝）
     */
    public double toCanonical(double rawValue, String rawUnit) {
        if (canonicalUnit.equals(rawUnit)) {
            return rawValue;
        }
        UnitConversion conversion = conversions.get(rawUnit);
        if (conversion == null) {
            throw new IllegalArgumentException("unsupported unit: " + rawUnit);
        }
        return rawValue * conversion.factor() + conversion.offset();
    }

    /** 量程判断为闭区间，边界值合法。 */
    public boolean isInRange(double canonicalValue) {
        return canonicalValue >= minValue && canonicalValue <= maxValue;
    }
}

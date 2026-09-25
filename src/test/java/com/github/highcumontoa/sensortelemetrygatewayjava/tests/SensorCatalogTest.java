package com.github.highcumontoa.sensortelemetrygatewayjava.tests;

import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorCatalog;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.SensorType;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 单位统一换算与量程规则：纯函数，任意入口结论一致。 */
class SensorCatalogTest {

    private static final Logger log = LoggerFactory.getLogger(SensorCatalogTest.class);

    private final SensorCatalog catalog = new SensorCatalog();

    @Test
    void convertsAllSupportedTemperatureUnitsToCelsius() {
        check("32F -> 0C", SensorType.TEMPERATURE, 32, "F", 0.0);
        check("212F -> 100C", SensorType.TEMPERATURE, 212, "F", 100.0);
        check("273.15K -> 0C", SensorType.TEMPERATURE, 273.15, "K", 0.0);
        check("37 C -> 37C", SensorType.TEMPERATURE, 37, "C", 37.0);
    }

    @Test
    void convertsPressureUnitsToHpa() {
        check("1bar -> 1000hPa", SensorType.PRESSURE, 1, "bar", 1000.0);
        check("101325Pa -> 1013.25hPa", SensorType.PRESSURE, 101325, "Pa", 1013.25);
        check("100kPa -> 1000hPa", SensorType.PRESSURE, 100, "kPa", 1000.0);
        check("1010mbar -> 1010hPa", SensorType.PRESSURE, 1010, "mbar", 1010.0);
    }

    @Test
    void convertsIlluminancePrefixUnits() {
        check("1.5klx -> 1500lx", SensorType.ILLUMINANCE, 1.5, "klx", 1500.0);
        check("500lux -> 500lx", SensorType.ILLUMINANCE, 500, "lux", 500.0);
    }

    @Test
    void rangeChecksAreInclusive() {
        log.info("输入: 温度边界 -50/150/151/-51；判定依据: 量程闭区间 [-50,150]C");
        assertTrue(catalog.inRange(SensorType.TEMPERATURE, -50.0));
        assertTrue(catalog.inRange(SensorType.TEMPERATURE, 150.0));
        assertFalse(catalog.inRange(SensorType.TEMPERATURE, 150.0001));
        assertFalse(catalog.inRange(SensorType.TEMPERATURE, -50.0001));
        assertTrue(catalog.inRange(SensorType.HUMIDITY, 0.0));
        assertTrue(catalog.inRange(SensorType.HUMIDITY, 100.0));
        assertFalse(catalog.inRange(SensorType.HUMIDITY, 100.0001));
    }

    @Test
    void unknownUnitRejected() {
        log.info("输入: TEMPERATURE value=25 unit='Pa'；判定依据: Pa 不在温度支持单位集合中");
        assertEquals(Optional.empty(), catalog.convertToCanonical(SensorType.TEMPERATURE, 25, "Pa"));
        assertEquals(Optional.empty(), catalog.convertToCanonical(SensorType.TEMPERATURE, 25, null));
    }

    private void check(String label, SensorType type, double raw, String unit, double expected) {
        log.info("输入: {} {} {}；判定依据: 按 {} 规则换算到标准单位", raw, unit, type, unit);
        Optional<Double> converted = catalog.convertToCanonical(type, raw, unit);
        assertTrue(converted.isPresent(), label);
        assertEquals(expected, converted.get(), 1e-9, label);
    }
}

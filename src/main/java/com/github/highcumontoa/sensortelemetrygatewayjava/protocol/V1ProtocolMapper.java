package com.github.highcumontoa.sensortelemetrygatewayjava.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.MappedFields;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.ProtocolVersion;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectReason;

/**
 * V1 旧协议映射器。V1 报文布局：
 * <pre>
 * { "deviceId": "...", "ver": 1, "type": 1|2|3, "value": 23.5,
 *   "unit": "C"?,   // 可选：缺失时缺省为该类型的规范单位（稳定缺省语义）
 *   "ts": 1700000000000? } // 可选：缺失时缺省为网关接收时间
 * </pre>
 * 本映射器只认识 V1 字段，绝不回退或猜测 V2 语义。
 */
public class V1ProtocolMapper implements ProtocolMapper {

    @Override
    public ProtocolVersion version() {
        return ProtocolVersion.V1;
    }

    @Override
    public MappedFields map(JsonNode node, long receivedAtMillis) throws ReportMappingException {
        String deviceId = requiredText(node, "deviceId");
        String sensorCode = requiredV1Code(node, "type");
        double rawValue = requiredNumber(node, "value");

        // 单位缺失：缺省语义为“该传感器类型的规范单位”，具体单位在归一化阶段依据类型补全。
        boolean unitDefaulted = !node.hasNonNull("unit");
        String rawUnit = unitDefaulted ? null : requiredText(node, "unit");

        // 事件时间缺失：缺省语义为“网关接收时间”，确定且稳定。
        boolean timeDefaulted = !node.hasNonNull("ts");
        long eventTime = timeDefaulted ? receivedAtMillis : requiredLong(node, "ts");

        return new MappedFields(eventTime, timeDefaulted, sensorCode, rawValue,
                rawUnit, unitDefaulted);
    }

    private static String requiredText(JsonNode node, String field) throws ReportMappingException {
        JsonNode valueNode = node.get(field);
        if (valueNode == null || valueNode.isNull()) {
            throw new ReportMappingException(RejectReason.MISSING_REQUIRED_FIELD,
                    "V1 missing required field: " + field);
        }
        if (!valueNode.isTextual() || valueNode.asText().isBlank()) {
            throw new ReportMappingException(RejectReason.INVALID_PAYLOAD,
                    "V1 field must be a non-blank string: " + field);
        }
        return valueNode.asText().trim();
    }

    private static double requiredNumber(JsonNode node, String field) throws ReportMappingException {
        JsonNode valueNode = node.get(field);
        if (valueNode == null || valueNode.isNull()) {
            throw new ReportMappingException(RejectReason.MISSING_REQUIRED_FIELD,
                    "V1 missing required field: " + field);
        }
        if (!valueNode.isNumber()) {
            throw new ReportMappingException(RejectReason.INVALID_PAYLOAD,
                    "V1 field must be numeric: " + field);
        }
        return valueNode.asDouble();
    }

    private static long requiredLong(JsonNode node, String field) throws ReportMappingException {
        JsonNode valueNode = node.get(field);
        if (valueNode == null || valueNode.isNull()) {
            throw new ReportMappingException(RejectReason.MISSING_REQUIRED_FIELD,
                    "V1 missing required field: " + field);
        }
        if (!valueNode.isNumber()) {
            throw new ReportMappingException(RejectReason.INVALID_PAYLOAD,
                    "V1 field must be numeric epoch millis: " + field);
        }
        return valueNode.asLong();
    }

    /** V1 使用数字类型编码，仅接受整型 1/2/3；非整型一律按载荷非法拒绝，不猜测。 */
    private static String requiredV1Code(JsonNode node, String field) throws ReportMappingException {
        JsonNode valueNode = node.get(field);
        if (valueNode == null || valueNode.isNull()) {
            throw new ReportMappingException(RejectReason.MISSING_REQUIRED_FIELD,
                    "V1 missing required field: " + field);
        }
        if (!valueNode.isIntegralNumber()) {
            throw new ReportMappingException(RejectReason.INVALID_PAYLOAD,
                    "V1 sensor type code must be an integer 1/2/3, got: " + valueNode);
        }
        return String.valueOf(valueNode.asLong());
    }
}

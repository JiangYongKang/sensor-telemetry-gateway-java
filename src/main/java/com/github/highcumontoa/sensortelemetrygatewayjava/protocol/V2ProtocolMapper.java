package com.github.highcumontoa.sensortelemetrygatewayjava.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.MappedFields;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.ProtocolVersion;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectReason;

/**
 * V2 新协议映射器。V2 报文布局：
 * <pre>
 * { "deviceId": "...", "protocolVersion": "V2", "sensor": "TEMPERATURE|HUMIDITY|PRESSURE",
 *   "value": 23.5, "unit": "C", "eventTime": 1700000000000 }
 * </pre>
 * V2 为显式协议：unit 与 eventTime 均为必填，缺失即拒绝，不提供缺省值；
 * 类型名是否受支持交由归一化阶段按 V2 编码表判定。
 */
public class V2ProtocolMapper implements ProtocolMapper {

    @Override
    public ProtocolVersion version() {
        return ProtocolVersion.V2;
    }

    @Override
    public MappedFields map(JsonNode node, long receivedAtMillis) throws ReportMappingException {
        // deviceId 仍做版本内校验，保证不同入口的判定依据一致。
        requiredText(node, "deviceId");
        String sensorCode = requiredText(node, "sensor");
        double rawValue = requiredNumber(node, "value");
        String rawUnit = requiredText(node, "unit");
        long eventTime = requiredLong(node, "eventTime");

        return new MappedFields(eventTime, false, sensorCode, rawValue, rawUnit, false);
    }

    private static String requiredText(JsonNode node, String field) throws ReportMappingException {
        JsonNode valueNode = node.get(field);
        if (valueNode == null || valueNode.isNull()) {
            throw new ReportMappingException(RejectReason.MISSING_REQUIRED_FIELD,
                    "V2 missing required field: " + field);
        }
        if (!valueNode.isTextual() || valueNode.asText().isBlank()) {
            throw new ReportMappingException(RejectReason.INVALID_PAYLOAD,
                    "V2 field must be a non-blank string: " + field);
        }
        return valueNode.asText().trim();
    }

    private static double requiredNumber(JsonNode node, String field) throws ReportMappingException {
        JsonNode valueNode = node.get(field);
        if (valueNode == null || valueNode.isNull()) {
            throw new ReportMappingException(RejectReason.MISSING_REQUIRED_FIELD,
                    "V2 missing required field: " + field);
        }
        if (!valueNode.isNumber()) {
            throw new ReportMappingException(RejectReason.INVALID_PAYLOAD,
                    "V2 field must be numeric: " + field);
        }
        return valueNode.asDouble();
    }

    private static long requiredLong(JsonNode node, String field) throws ReportMappingException {
        JsonNode valueNode = node.get(field);
        if (valueNode == null || valueNode.isNull()) {
            throw new ReportMappingException(RejectReason.MISSING_REQUIRED_FIELD,
                    "V2 missing required field: " + field);
        }
        if (!valueNode.isNumber()) {
            throw new ReportMappingException(RejectReason.INVALID_PAYLOAD,
                    "V2 field must be numeric epoch millis: " + field);
        }
        return valueNode.asLong();
    }
}

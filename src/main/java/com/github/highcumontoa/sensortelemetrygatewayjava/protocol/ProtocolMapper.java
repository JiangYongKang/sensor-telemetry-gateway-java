package com.github.highcumontoa.sensortelemetrygatewayjava.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.MappedFields;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.ProtocolVersion;

/**
 * 单一协议版本的兼容映射器：仅按该版本字段布局解析，绝不猜测其它版本。
 * 旧版本缺失字段的缺省语义必须确定且稳定。
 */
public interface ProtocolMapper {

    ProtocolVersion version();

    /**
     * @param node              已完成 JSON 语法解析的报文
     * @param receivedAtMillis  网关接收时刻，用于旧版本缺失事件时间时补默认值
     * @throws ReportMappingException 字段缺失等无法映射的情况
     */
    MappedFields map(JsonNode node, long receivedAtMillis) throws ReportMappingException;
}

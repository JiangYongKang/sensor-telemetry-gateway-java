package com.github.highcumontoa.sensortelemetrygatewayjava.ingest;

import com.github.highcumontoa.sensortelemetrygatewayjava.model.DeviceState;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.ProtocolVersion;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.RejectionReason;
import org.springframework.stereotype.Component;

/**
 * 协议版本兼容策略：
 * <ul>
 *   <li>版本字符串仅接受精确的 "V1"/"V2"（大小写不敏感）；其余一律 UNSUPPORTED_PROTOCOL_VERSION，
 *       绝不回退到其他版本解析。</li>
 *   <li>上报版本必须与设备注册版本逐字节一致，否则 PROTOCOL_VERSION_MISMATCH。</li>
 *   <li>V1 没有序列号概念，映射为 -1（不去重）；V2 缺省序列号同为 -1；显式提供（含 0）则按值去重。</li>
 * </ul>
 */
@Component
public class ProtocolSupport {

    public ProtocolVersion parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new GatewayException(RejectionReason.MISSING_FIELD, "上报缺少必填字段 protocolVersion");
        }
        try {
            return ProtocolVersion.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new GatewayException(RejectionReason.UNSUPPORTED_PROTOCOL_VERSION,
                    "不支持的协议版本 '" + raw + "'，仅支持 V1、V2");
        }
    }

    public void requireMatches(DeviceState device, ProtocolVersion reportVersion) {
        if (device.protocolVersion() != reportVersion) {
            throw new GatewayException(RejectionReason.PROTOCOL_VERSION_MISMATCH,
                    "设备 '" + device.deviceId() + "' 注册版本为 " + device.protocolVersion()
                            + "，拒绝按 " + reportVersion + " 解析");
        }
    }

    /** 按版本确定序列号缺省语义。 */
    public long mapSequence(ProtocolVersion version, Long rawSequence) {
        return switch (version) {
            case V1 -> -1L;
            case V2 -> rawSequence == null ? -1L : rawSequence;
        };
    }
}

package com.github.highcumontoa.sensortelemetrygatewayjava.protocol;

import java.util.EnumMap;
import java.util.Map;
import com.github.highcumontoa.sensortelemetrygatewayjava.model.ProtocolVersion;

/**
 * 协议映射器注册表：版本与解析器一一对应。
 * 解析只可能经由明确支持的版本进入对应映射器，未知版本在上游即被拒绝。
 */
public final class ProtocolMappers {

    private final Map<ProtocolVersion, ProtocolMapper> mappers = new EnumMap<>(ProtocolVersion.class);

    public ProtocolMappers() {
        register(new V1ProtocolMapper());
        register(new V2ProtocolMapper());
    }

    private void register(ProtocolMapper mapper) {
        mappers.put(mapper.version(), mapper);
    }

    public ProtocolMapper get(ProtocolVersion version) {
        ProtocolMapper mapper = mappers.get(version);
        if (mapper == null) {
            // 理论不可达：未知版本已在进入此处前被拒绝；保留防御性失败而非猜测解析。
            throw new IllegalArgumentException("no protocol mapper for version: " + version);
        }
        return mapper;
    }
}

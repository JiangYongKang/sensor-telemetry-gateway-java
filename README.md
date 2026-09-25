# sensor-telemetry-gateway-java-bootstrap

基于 Spring Boot 3 / Java 21 的本地传感器遥测接入与状态维护服务（无外部依赖）。

- 多传感器类型上报，统一单位换算与量程校验，拒绝原因可区分且原始报文留档；
- 设备注册（协议版本 + 能力范围），重复注册幂等、冲突可区分；
- V1/V2 协议版本兼容映射，旧版本缺失字段使用确定缺省语义，未知版本明确拒绝；
- 高频上报的最新值与事件时间滑动窗口聚合，乱序接纳、迟到拒绝、并发与串行结果一致；
- 仅基于最后有效上报时间的可复现离线判定。

详细数据模型、单位量程、版本兼容、乱序/迟到与离线规则见
[`docs/TELEMETRY.md`](docs/TELEMETRY.md)。

## 构建与验证

```bash
mvn test      # 运行全部单测（29 个用例，日志打印输入与判定依据）
mvn spring-boot:run   # 本地启动，接口前缀 /api
```

核心接口：`POST /api/devices`、`POST /api/telemetry`、
`POST /api/telemetry/batch`、`GET /api/devices/{id}/aggregate/{sensorType}`、
`GET /api/devices/{id}/status`、`GET /api/devices/{id}/rejected`。

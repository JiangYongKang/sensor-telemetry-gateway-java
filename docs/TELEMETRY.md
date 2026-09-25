# 传感器遥测接入与状态维护说明

本地实现的传感器遥测网关：多设备注册、多类型上报接入、单位/量程统一规范、
按协议版本兼容映射、高频上报的最新值与滑动窗口聚合、乱序/迟到确定性处理、
设备离线判定，以及完整的拒绝留档。不依赖任何外部服务或凭据。

## 1. 总体结构

```
web/TelemetryController      HTTP 入口（薄适配，无业务规则）
service/TelemetryService     所有入口共用的唯一业务流水线
protocol/                    协议版本 -> 字段映射（V1/V2，一一对应）
normalization/ReadingNormalizer  类型编码、单位换算、量程校验
model/SensorCatalog          单位/量程/版本编码规则的唯一来源
state/DeviceRegistry         设备注册（幂等）
state/TelemetryStore         最新值、事件时间水位、滑动窗口、离线判定、拒绝留档
state/Clock                  可注入时钟（生产墙钟 / 测试固定时钟）
```

HTTP 单条、HTTP 批量、服务直调三个入口对同一条输入调用同一个
`TelemetryService.ingest`，因此接受/拒绝结论与拒绝原因必然一致。
HTTP 接口以原始文本接收 JSON，确保报文语法错误时也能原样留档。

## 2. 数据模型

| 模型 | 含义 |
| --- | --- |
| `DeviceRegistration` / `Device` | 注册请求 / 已注册设备（deviceId、协议版本、能力范围） |
| `Capability` | 设备允许上报的传感器类型集合（集合语义，与声明顺序无关） |
| `RawReport` | 原始上报留档：原始 JSON、尽力解析的 deviceId/版本、接收时刻、接收入口 |
| `MappedFields` | 按版本映射后的字段（含“是否使用缺省值”标记） |
| `NormalizedReading` | 归一化读数：规范单位、量程内、事件时间、接受时间 |
| `RejectedReport` | 拒绝留档：原始报文 + 拒绝原因 + 判定依据 |
| `IngestResult` | 统一结论：accepted、reading、rejectReason、detail、raw |
| `WindowAggregate` | 窗口聚合：count/latest/min/max/avg、窗口区间、水位 |
| `DeviceStatus` | 注册与否、最后有效上报时间、判定时刻、在线与否、阈值 |

## 3. 传感器类型、单位与量程规则

归一化统一采用仿射换算：`canonicalValue = rawValue * factor + offset`，
量程为换算后闭区间（边界合法）。规则集中在 `SensorCatalog`：

| 类型 | 规范单位 | 兼容单位与换算 | 量程（规范单位，闭区间） |
| --- | --- | --- | --- |
| TEMPERATURE | `C`（摄氏度） | `F`：C = (F − 32) / 1.8 | [-40.0, 85.0] |
| HUMIDITY | `%`（百分比） | `ratio`（0~1 比例）：% = ratio × 100 | [0.0, 100.0] |
| PRESSURE | `hPa` | `kPa`：×10；`bar`：×1000 | [300.0, 1100.0] |

判定顺序（原因互不重叠，可区分）：
1. 单位是否受支持 → 否则 `UNSUPPORTED_UNIT`（先换算再判量程）；
2. 换算为规范单位后做闭区间量程校验 → 越界 `VALUE_OUT_OF_RANGE`。
例如 200°F 会先换算为 93.33°C，再因超过 85°C 被拒绝，不会按原始数值放行。

## 4. 协议版本兼容策略

只支持 `V1`、`V2`。版本字段：V2 使用文本 `protocolVersion`，V1 使用数字 `ver`
（1 规范为 "V1"）。版本解析严格：无法识别一律 `UNSUPPORTED_PROTOCOL_VERSION`，
**绝不按其它版本猜测解析**。

- **V2（显式协议）**：`deviceId`、`sensor`(TEMPERATURE/HUMIDITY/PRESSURE)、
  `value`、`unit`、`eventTime`（epoch 毫秒）全部必填，缺失即
  `MISSING_REQUIRED_FIELD`，不提供缺省值。
- **V1（旧协议）**：字段为 `deviceId`、`ver`、`type`(数字编码 1/2/3)、`value`，
  `unit` 与 `ts` 可选。旧版本缺省语义确定且稳定：
  - 缺 `unit`：按该类型的**规范单位**解释（不做换算）；
  - 缺 `ts`：事件时间缺省为**网关接收时间**。
  结论中以 `unitDefaulted` / `eventTimeDefaulted` 标记并写入日志判定依据。
- 版本内类型编码仅按该版本编码表解析：V1 的 1/2/3 与 V2 的类型名互不相通，
  未知编码为 `UNKNOWN_SENSOR_TYPE`。

设备注册时声明协议版本；上报版本必须与注册版本完全一致，否则
`DEVICE_VERSION_MISMATCH`。

## 5. 注册与幂等

- 注册需声明 deviceId、协议版本与非空能力范围；未知版本 `UNSUPPORTED_PROTOCOL_VERSION`。
- 同一 deviceId 重复注册：
  - 协议版本与能力集合完全一致 → 成功且 `idempotent=true`（能力按集合比较，顺序无关）；
  - 任一不一致 → `DUPLICATE_REGISTRATION_CONFLICT`，保留原注册不变。
- 注册基于 `ConcurrentHashMap.putIfAbsent` 原子完成，并发重复注册结论确定。

## 6. 上报校验流水线（固定顺序）

`TelemetryService.ingest` 严格按以下顺序判定，先命中先拒绝，原因唯一可区分：

1. JSON 语法 → `MALFORMED_JSON`（非对象 → `INVALID_PAYLOAD`）
2. 版本字段存在 → `MISSING_REQUIRED_FIELD`；版本受支持 → `UNSUPPORTED_PROTOCOL_VERSION`
3. 设备已注册 → `UNKNOWN_DEVICE`
4. 上报版本 == 注册版本 → `DEVICE_VERSION_MISMATCH`
5. 版本内字段映射 → `MISSING_REQUIRED_FIELD` / `INVALID_PAYLOAD`
6. 类型编码可解析 → `UNKNOWN_SENSOR_TYPE`
7. 类型在能力范围内 → `SENSOR_NOT_IN_CAPABILITY`
8. 单位/量程归一化 → `UNSUPPORTED_UNIT` / `VALUE_OUT_OF_RANGE`
9. 乱序/迟到 → `LATE_REPORT`

**所有拒绝都通过 `recordRejected` 统一留档**（原始报文 + 原因 + 判定依据），
每条拒绝恰好一条记录；无法解析出 deviceId 的归入 `__UNKNOWN_DEVICE__` 桶。
可通过 `GET /api/devices/{id}/rejected` 追溯。

## 7. 乱序、迟到与滑动窗口

状态全部以**事件时间**为准，规则确定、与线程调度和墙钟无关：

- **串行化**：同一设备的写入在该设备状态监视器上串行执行，不同设备可并行；
  窗口结果不会因并发而回退、重复计数或丢数。
- **水位 watermark**：该设备已接受读数的最大事件时间，单调不减。
- **乱序接纳**：`eventTime >= watermark - maxLateMillis` 的旧事件允许并入窗并计入聚合。
- **迟到拒绝**：`eventTime < watermark - maxLateMillis` 为 `LATE_REPORT`。
- **最新值决胜**：窗口内取最大事件时间；同一事件时间取最后接受者，最新值不回退。
- **滑动窗口**：区间 `[watermark - windowMillis, watermark]`（闭区间），
  水位推进时确定性裁剪出窗读数。默认窗口 60s，最大迟到 10s。

## 8. 离线判定

仅基于**最后有效上报时间**（最近一次被接受读数的接收时间 `acceptedAt`，单调不减）：

```
online = (now - lastValidReportTime) <= offlineThresholdMillis
```

从未有效上报的设备视为离线。默认阈值 30s，边界（恰好等于阈值）视为在线。
判定时刻来自可注入时钟，测试使用固定/步进时钟，结论可复现。
被拒绝的上报不更新最后有效上报时间。

参数均可在 `application.properties` 覆盖：
`telemetry.window-millis`、`telemetry.max-late-millis`、
`telemetry.offline-threshold-millis`、`telemetry.rejected-history-per-device`。

## 9. HTTP 接口

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/devices` | 注册（请求体：DeviceRegistration JSON） |
| `POST /api/telemetry` | 单条上报（原始 JSON 文本） |
| `POST /api/telemetry/batch` | 批量上报（JSON 数组，逐条走同一流水线） |
| `GET  /api/devices/{id}/aggregate/{sensorType}` | 窗口聚合 |
| `GET  /api/devices/{id}/status` | 设备状态/在线判定 |
| `GET  /api/devices/{id}/rejected` | 该设备拒绝留档（含原始报文） |

### 报文示例

V2：
```json
{"deviceId":"dev-1","protocolVersion":"V2","sensor":"TEMPERATURE",
 "value":77.0,"unit":"F","eventTime":1700000000000}
```
V1（缺单位/时间，使用确定缺省语义）：
```json
{"deviceId":"dev-1","ver":1,"type":1,"value":23.5}
```

结论示例：接受时 `accepted=true` 且 `reading.unit=C, reading.value=25.0`；
拒绝时 `accepted=false`、`rejectReason` 为上述枚举之一、`raw.rawJson` 为原文。

## 10. 验证方法

```bash
mvn test
```

测试沿用 Spring Boot Test / JUnit 5 / MockMvc，不引入外部服务。覆盖：

- `DeviceRegistrationTest`：重复注册幂等、版本/能力冲突可区分、未知版本拒绝；
- `ValidationRejectionTest`：F/ratio/kPa/bar 换算、换算后越界、闭区间边界、
  单位不一致、未知类型、能力越界、缺字段、坏 JSON、未知设备、版本不匹配/不支持、
  V1 缺省语义；原始报文留档；
- `OutOfOrderAndLateReportTest`：乱序接纳且仅计一次、超阈值迟到拒绝、
  窗口裁剪、最新值不回退、离线阈值边界可复现；
- `ConcurrentIngestConsistencyTest`：200 条乱序输入 8 线程并发，
  最终聚合（count/latest/min/max/avg/水位）与严格串行基线完全一致，无丢数；
  同序列两次串行结果逐字段相等（可复现）；
- `CrossEntryConsistencyTest`：同一输入经 HTTP 单条、HTTP 批量、服务直调，
  接受结论、拒绝原因、归一化值一致，拒绝原文可经接口追溯。

所有测试用例在日志中打印 **INPUT（原始输入）** 与 **DECISION/QUERY（结论与判定依据）**，
便于核对输入与结论的对应关系。

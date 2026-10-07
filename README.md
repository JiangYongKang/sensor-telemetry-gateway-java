# 传感器遥测接入网关（本地状态模拟）

基于 Spring Boot 3.4 / Java 21 的传感器遥测接入与设备状态维护服务。
所有状态保存在进程内存中，**不依赖任何外部服务或凭据**。多设备上报、
不同传感器类型、乱序/迟到上报与不同协议版本，在单条与批量两个入口下
都会得到**一致且可解释**的结论。

## 1. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/devices` | 注册设备（幂等），声明协议版本与能力集合 |
| POST | `/api/telemetry` | 单条上报，请求体为一个上报 JSON 对象 |
| POST | `/api/telemetry/batch` | 批量上报，请求体为上报对象的 JSON 数组 |
| GET  | `/api/devices/{id}/status` | 在线状态、最后有效事件/接收时间、各传感器最新值 |
| GET  | `/api/devices/{id}/stats?type=TEMPERATURE&at=<epochMillis>` | 指定时刻的滑动窗口聚合（`at` 缺省为当前时间） |
| GET  | `/api/devices/{id}/availability?from=<epochMillis>&to=<epochMillis>` | 区间内累计在线时长、在线占比与具体掉线时间段 |
| GET  | `/api/fleet/overview` | 机群级在线概览：在线/离线台数与每台设备最近有效上报时间 |

注册请求示例：

```json
{ "deviceId": "th-1", "protocolVersion": "V2",
  "capabilities": ["TEMPERATURE", "HUMIDITY"] }
```

上报请求示例：

```json
{ "deviceId": "th-1", "protocolVersion": "V2", "timestamp": 9990000,
  "sequence": 1,
  "readings": [ { "type": "temperature", "value": 86, "unit": "F" } ] }
```

接受返回 `202` 与 `accepted=true`；拒绝返回结构化错误体，
`rejectionReason` 为稳定枚举、`message` 给出可解释的判定依据：

```json
{ "accepted": false, "rejectionReason": "VALUE_OUT_OF_RANGE",
  "message": "TEMPERATURE 规范化值 999.0 C 超出量程 [-50.0, 150.0] ..." }
```

### 拒绝原因（两两可区分，结论稳定）

| rejectionReason | HTTP 状态 | 触发条件 |
| --- | --- | --- |
| `PAYLOAD_INVALID` | 422 | JSON 无法解析；批量请求不是 JSON 数组 |
| `MISSING_FIELD` | 422 | 缺 `deviceId`/`readings`/读数的 `type`/`value`；V2 读数缺 `unit` |
| `DEVICE_NOT_REGISTERED` | 404 | 上报或查询的设备未注册 |
| `UNSUPPORTED_PROTOCOL_VERSION` | 409 | 版本字符串不是 `V1`/`V2` |
| `PROTOCOL_VERSION_MISMATCH` | 409 | 上报版本与设备注册版本不一致 |
| `REGISTRATION_CONFLICT` | 409 | 重复注册但版本或能力集合与首次不同 |
| `SENSOR_TYPE_UNKNOWN` | 422 | 读数类型或注册能力不在传感器目录中 |
| `CAPABILITY_NOT_DECLARED` | 422 | 上报了设备能力集合之外的传感器 |
| `INVALID_UNIT` | 422 | 单位不在该类型可接受单位集合中（先换算、后判量程） |
| `VALUE_OUT_OF_RANGE` | 422 | 换算到标准单位后超出量程闭区间 |
| `FUTURE_TIMESTAMP` | 422 | 事件时间超过接收时间 `gateway.max-future-millis` |
| `DUPLICATE_SEQUENCE` | 409 | V2 显式序列号在窗口内重复 |
| `INVALID_TIME_RANGE` | 422 | 可用性查询区间非法（`from >= to`） |
| `RETENTION_EXCEEDED` | 422 | 可用性查询起点早于已保留历史的覆盖下界，结果不可用 |

拒绝遵循固定优先级（设备存在 → 版本匹配 → 时间合法 →
逐条“字段缺失 → 类型未知 → 能力未声明 → 单位非法 → 量程越界”），
同一条输入永远得到同一条拒绝原因。整条上报原子接受或拒绝；
无论接受与否，**原始报文都会逐字留档**，可通过 `RawReportStore` 追溯
（含原始 JSON、解析出的原始读数、拒绝原因、接收时间）。

## 2. 传感器数据模型、单位与量程规则

`SensorCatalog` 是单位别名、换算公式与量程的**唯一事实来源**，纯函数实现，
因此任何入口的判定结果完全一致。所有内部值都以下表的标准单位保存与比较。

| 类型 | 标准单位 | 可接受单位（→ 标准单位） | 有效量程（闭区间，标准单位） |
| --- | --- | --- | --- |
| `TEMPERATURE` | `C`（摄氏度） | `C`/`°C` 原值；`F`：`(v-32)*5/9`；`K`：`v-273.15` | [-50, 150] |
| `HUMIDITY` | `%` | `%`/`percent` | [0, 100] |
| `PRESSURE` | `hPa` | `hPa`/`mbar` 原值；`Pa`：v/100；`kPa`：v*10；`bar`：v*1000 | [300, 1100] |
| `ILLUMINANCE` | `lx` | `lx`/`lux` 原值；`klx`：v*1000 | [0, 200000] |

规则要点：

- 类型名大小写不敏感（`temperature` 等同 `TEMPERATURE`），但必须是已知类型。
- **先换算到标准单位，再做量程判定**。例如 200°F = 93.3°C 合法，
  400°F = 204.4°C 越界；100 kPa = 1000 hPa 合法，100 Pa 误标为温度单位则 `INVALID_UNIT`。
- 单位字符串大小写敏感、去除首尾空白后精确匹配；无法识别即 `INVALID_UNIT`，
  绝不“猜测”单位。
- 量程为闭区间，边界值合法（如湿度 0% 与 100% 均接受）。

## 3. 协议版本兼容策略

当前支持 `V1`、`V2`，版本字符串大小写不敏感但必须精确匹配；
任何其他版本（`V3`、空值、乱填）一律 `UNSUPPORTED_PROTOCOL_VERSION`，
**明确拒绝，不回退按其他版本解析**。

| 字段 | V1 语义 | V2 语义 |
| --- | --- | --- |
| `protocolVersion` | 必填，且必须等于注册版本 | 同左；不一致为 `PROTOCOL_VERSION_MISMATCH` |
| `timestamp` | 可缺省；缺省补**接收时间**（确定、稳定） | 同左 |
| `sequence` | 协议无此概念，统一映射为 -1，**不去重** | 缺省映射为 -1（不去重，语义确定）；显式提供（含 0）则在窗口内去重 |
| 读数 `unit` | 可缺省；缺省稳定取该类型**标准单位** | 必填，缺失为 `MISSING_FIELD` |

设备注册的幂等规则：同 `deviceId` + 同版本 + 同能力集合
（与声明顺序无关，按集合比较）重复注册返回既有设备（`created=false`）；
版本或能力不一致为 `REGISTRATION_CONFLICT`，不会静默覆盖。

### 乱序与迟到的确定处理

- 每条样本同时记录**事件时间**（上报的 `timestamp`）与**接收时间**（网关注入的时钟）。
- 窗口样本按 `(eventTime, sequence)` 升序插入存储，聚合时再按事件时间过滤，
  因此**聚合结果只取决于已接收的样本集合，与到达先后无关**，完全可复现。
- 迟到样本只要落在查询窗口内就计入聚合；但**最新值只随事件时间不旧的样本更新**
  （同事件时间后到者不覆盖），所以迟到数据不会让最新值“回退”。
- 超前于接收时间超过 `gateway.max-future-millis`（默认 5 秒）的事件时间
  判为 `FUTURE_TIMESTAMP` 拒绝，防止时钟错乱污染状态。

## 4. 滑动窗口聚合与在线判定口径

参数均可在 `application.properties` 配置：

```properties
gateway.window-millis=60000      # 滑动窗口长度
gateway.max-future-millis=5000   # 事件时间相对接收时间的最大超前量
gateway.offline-millis=30000     # 离线阈值
```

- 窗口为基于**事件时间**的左开右闭区间 `(at - windowMillis, at]`，
  统计 `count/min/max/avg`（平均值基于标准单位值）。窗口内无样本时
  返回 `count=0` 且 min/max/avg 为 null。
- **在线判定口径（单台状态、可用性统计、机群概览三处共用同一条规则）**：
  设备在时刻 `t` 在线，当且仅当存在一条**被接受**的上报，其**接收时间**
  落在 `[t - offlineMillis, t]` 内（闭区间，恰好 30 秒仍在线）。
  - 判定只看**接收时间**，与上报携带的事件时间无关：乱序/滞后到达的
    有效上报（事件时间远早于接收时间）照样使设备在线，绝不会因为
    时间戳落后被误判离线；只有长时间没有任何有效上报才离线。
  - `lastValidIngestTime` 只在上报被**接受**后更新，被拒绝的上报不影响
    在线结论；同一判定时刻反复查询结论一致、可复现。
- 并发安全：每台设备一把锁，序列号去重、窗口插入、最新值更新、
  最后有效时间更新在同一临界区原子完成，配合事件时间有序插入，
  保证并发写入不丢数、不重复计数、窗口结果不回退，
  且并发聚合结果与同样输入串行执行**逐值相等**（见并发一致性测试）。

## 5. 设备可用性统计

`GET /api/devices/{id}/availability?from=<epochMillis>&to=<epochMillis>`

返回该设备在闭区间 `[from, to]` 内的：

| 字段 | 含义 |
| --- | --- |
| `onlineMillis` | 累计在线时长（毫秒） |
| `onlineRatio` | 在线占比，`onlineMillis / (to - from)` |
| `offlineIntervals` | 区间内具体的掉线时间段 `[{from, to}, ...]`，按开始时间升序、互不重叠 |

计算方式：每条被接受的上报（按**接收时间** `s`）贡献在线区间
`[s, s + offlineMillis]`，全部合并后与查询区间求交；其余部分即为掉线时间段。
判定只依赖已接受的接收时间序列，因此**同一区间反复查询结果逐值相同**
（前提是历史未被淘汰，见下节），也与单台状态查询的在线口径完全一致。
`from >= to` 返回 422 `INVALID_TIME_RANGE`。

## 6. 机群级在线概览

`GET /api/fleet/overview`

```json
{ "evaluatedAt": 10000000, "offlineThresholdMillis": 30000,
  "onlineCount": 2, "offlineCount": 1,
  "devices": [ { "deviceId": "d1", "status": "ONLINE",
                 "lastValidIngestTime": 9999000, "lastValidEventTime": 9998000 } ] }
```

- `evaluatedAt` 为本次概览的**唯一判定时刻**；每台设备的 `status` 与其
  `lastValidIngestTime`（最近一次**被接受**上报的接收时间，从未上报为 `null`）
  在该设备自身的锁内读取，计数由同一份设备列表派生。因此与上报并发时
  结果仍然**自洽**：不会出现“被判离线却展示了很新的最近上报时间”的矛盾，
  且恒有 `onlineCount + offlineCount == devices.size()`。
- 概览对每台设备只读取两个长整型，O(设备数) 且无嵌套锁，
  设备数量增长不会拖慢上报链路（上报路径不经过概览代码）。

## 7. 容量与保留策略

长期运行的内存占用有明确且可配置的上限（`application.properties`）：

```properties
gateway.max-window-samples=10000              # 每台设备窗口样本硬上限
gateway.availability-retention-millis=3600000 # 可用性历史（接收时间序列）保留时长
gateway.max-ingest-history=100000             # 每台设备可用性历史条数硬上限
gateway.raw-report-capacity=100000            # 原始报文留档总条数上限（FIFO 淘汰）
```

- **窗口数据**：既按 `window-millis` 淘汰过旧样本，也受 `max-window-samples`
  条数硬上限约束；被淘汰样本的序列号同步移出去重集合（去重语义本就限定在
  窗口内），因此**去重/幂等判定状态随窗口一起有界**，且淘汰不会造成
  重复计数或丢数（未到期数据绝不淘汰）。
- **可用性历史**：每台设备只保留接收时间序列（每条 8 字节量级），按
  `availability-retention-millis` 与 `max-ingest-history` 双重上限淘汰。
  每次淘汰都会提升该设备的**历史覆盖下界** `historyFloor`
  （= 保留起点 + 离线阈值）；查询起点早于 `historyFloor` 时返回
  422 `RETENTION_EXCEEDED`，**明确告知结果不可用**，绝不静默算出一个
  看着正常其实不准的数。覆盖范围内（含全新设备自注册时刻起）的结论
  始终准确且可复现。
- **淘汰不影响在线状态**：在线判定只看 `lastValidIngestTime`（单个长整型，
  不参与淘汰），因此淘汰过程不会引起在线状态抖动或来回翻转。
- **原始报文留档**：`RawReportStore` 总条数受 `raw-report-capacity` 限制，
  超出时先进先出淘汰最旧记录。

## 8. 验证方法

沿用仓库既有技术栈（JUnit 5 + Spring Boot Test + MockMvc），无外部依赖。

```bash
mvn test            # 离线运行全部 53 个测试用例
```

测试均通过可注入的 `ManualClock` 固定/推进时间，单测日志会打印
**输入报文与判定依据**（见 `ReportEvaluator`/`TelemetryService`/`AvailabilityService`
及各测试的日志输出）。

覆盖场景：

- `SensorCatalogTest`：各单位换算、闭区间边界、未知单位拒绝。
- `RegistrationTest`：幂等重复注册、版本/能力冲突、不支持版本、未知能力。
- `ReportValidationTest`：字段缺失、单位不一致、换算后越界、类型未知、
  能力未声明、版本不匹配/不支持、未来时间戳、非法 JSON，
  以及拒绝报文的原始报文留档。
- `VersionSemanticsTest`：V1 无序列号去重、V2 `sequence=0` 去重、
  V2 缺省序列号不去重、时间戳缺省补接收时间。
- `OutOfOrderWindowTest`：乱序到达与串行同结果、窗口边界排除、
  迟到计入但不回退最新值、离线边界与恢复、拒绝不改变最后有效时间。
- `ConcurrencyConsistencyTest`：200 条样本 8 线程并发，聚合与串行逐值相等；
  16 线程竞争同一序列号恰好接受一次。
- `EntryPointConsistencyTest`：单条入口与批量入口同输入同结论、同拒绝原因。
- `HttpEndToEndTest`：经真实 HTTP 栈走查注册/上报/拒绝/状态/聚合全链路。
- `AvailabilityStatsTest`：持续上报全在线、上报间隔产生掉线段、
  乱序/滞后上报不影响在线结论、拒绝不改变可用性、同区间反复查询逐值相同、
  超出保留范围明确拒绝、非法区间与未注册设备。
- `FleetOverviewTest`：概览计数与最近上报时间、沉默超时全部离线；
  4 上报线程 + 4 概览线程并发下每次概览行级自洽。
- `CapacityRetentionTest`：窗口样本与去重状态有界且不重复计数、
  可用性历史按条数/保留时长淘汰并提升覆盖下界、原始留档有界、
  淘汰过程在线状态不抖动。
- `FleetHttpTest`：经真实 HTTP 栈验证可用性与机群概览端点，
  以及 `RETENTION_EXCEEDED`/`INVALID_TIME_RANGE` 的可解释错误映射。

### 本地手动验证新能力

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080

# 注册并上报一条
curl -X POST localhost:8080/api/devices -H 'Content-Type: application/json' \
  -d '{"deviceId":"th-1","protocolVersion":"V2","capabilities":["TEMPERATURE"]}'
curl -X POST localhost:8080/api/telemetry -H 'Content-Type: application/json' \
  -d '{"deviceId":"th-1","protocolVersion":"V2","sequence":1,
       "readings":[{"type":"temperature","value":30,"unit":"C"}]}'

# 机群概览：th-1 应在 ONLINE 且 lastValidIngestTime 为刚才的接收时刻
curl localhost:8080/api/fleet/overview

# 可用性：把 FROM/TO 换成覆盖“刚才到现在”的 epoch 毫秒
curl "localhost:8080/api/devices/th-1/availability?from=$FROM&to=$TO"

# 把 from 改成一个很久之前的时间（早于保留范围），应返回 422 RETENTION_EXCEEDED
curl "localhost:8080/api/devices/th-1/availability?from=0&to=$TO"
```

## 9. 代码结构

```
clock/      可注入时钟（SystemClock 生产实现，ManualClock 测试用）
model/      枚举、记录类型与 SensorCatalog（单位/量程事实来源）
config/     GatewayProperties（窗口、离线与容量/保留参数）
registry/   DeviceRegistry（线程安全注册表、状态原子写入、有界淘汰）
store/      RawReportStore（原始报文留档，容量有界）
ingest/     ProtocolSupport（版本映射）、ReportEvaluator（校验规范化管线）、异常
service/    DeviceService、TelemetryService、AggregationService、AvailabilityService
web/        REST 控制器、DTO、全局异常映射
```

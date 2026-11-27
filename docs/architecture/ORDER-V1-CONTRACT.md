# order V1 命令与结果契约

## 1. 适用边界

本契约冻结未来 `order-service` 接收的业务命令和跨服务结果信封。M3.5 已建立 Java V1 类型、兼容 fixture、
模块内 application port 和持久化 Process Manager/Outbox/Inbox；CREATE 准入已通过，但尚未开放远程入口。

命令表示调用方希望 order 执行的业务意图；结果表示 inventory、payment、refund 或 logistics 已经提交的
权威事实。两者都不是远程 Mapper，也不允许调用方指定任意目标状态。

## 2. OrderCommandV1

| 字段 | 规则 |
| --- | --- |
| `schemaVersion` | 固定为 1 |
| `commandId` | UUID；重试必须保持不变 |
| `commandType` | `CANCEL`、`CONFIRM_FULFILLMENT`、`SHIP`、`CONFIRM_RECEIPT` |
| `occurredAt` / `producer` | 原始命令时间和生产边界 |
| `orderId` / `actorId` | 正数稳定 ID；身份仍由入口鉴权，不能只信 payload |
| `expectedStatus` | 调用方观察到的前置状态，用于条件转换，不是强制覆盖 |
| `reason` | 取消必填；其他命令可选 |
| `carrier` / `trackingNumber` | `SHIP` 必填，其他命令忽略 |
| `correlationId` / `causationId` / `traceparent` | 业务关联、直接原因和 W3C Trace 上下文 |

固定前置状态为：`CANCEL=PENDING_PAYMENT`、`CONFIRM_FULFILLMENT=PAID`、
`SHIP=CONFIRMED`、`CONFIRM_RECEIPT=SHIPPED`。服务端仍须锁定订单并重读事实；不匹配时返回稳定拒绝结果，
不得盲目覆盖。

## 3. OrderResultV1

| 字段 | 规则 |
| --- | --- |
| `schemaVersion` | 固定为 1 |
| `resultId` | UUID；Consumer Inbox 的首要去重键 |
| `resultType` | `INVENTORY`、`PAYMENT`、`REFUND`、`LOGISTICS` |
| `outcome` | `SUCCEEDED`、`REJECTED`、`FAILED`；超时不是结果，不定义 `UNKNOWN` |
| `producer` / `orderId` | 权威生产边界和订单稳定 ID |
| `externalReference` | 原命令、支付、退款、运单等稳定引用 |
| `sourceStatus` | 生产方自己的状态，order 只能按允许的映射推进 |
| `errorCode` / `message` | 非成功结果必须带稳定错误码；message 只用于诊断 |
| `correlationId` / `causationId` / `traceparent` | 与原命令和跨进程 Trace 串联 |

结果不携带可任意指定的 `targetOrderStatus`。order 的状态机根据 `resultType + outcome + sourceStatus +
当前状态` 决定是否推进、忽略重复、等待其他步骤或进入人工对账。

## 4. 幂等、乱序和未知结果

- 生产方业务事实与 Outbox 同一本地事务提交。
- order 先按 `resultId` 写 Consumer Inbox，再执行条件状态转换和订单事件写入。
- 同一外部引用的重复结果只能产生一次业务副作用；不同结果相互冲突时不得用“最后到达者覆盖”。
- 旧状态结果记录后忽略；跨版本缺口或非法转换进入待对账，不得倒退订单状态。
- 请求超时只表示结果未知。调用方使用原 commandId 查询或重试，禁止立即补发新 ID，禁止静默走本地路径。
- 业务补偿生成新的命令和事件，保留原支付、退款、库存等已确认事实。

## 5. 当前本地端口

`ordering/application/port` 已按意图拆为 fulfillment、logistics、payment、receipt、refund 五个端口；
`LocalOrderBoundaryAdapter` 是当前模块化单体内唯一适配器。外部模块对 `ShopOrderMapper` 的直接依赖已归零，
并由架构测试和双平台审计锁定。

支付通知自己的锁定、通知、支付状态和支付事件 SQL 已迁到 `payment/infrastructure/PaymentNotificationMapper`，
不再借用 order Mapper。当前适配器仍是同进程、本地事务实现，不应描述成跨进程 Saga 已完成。

兼容 fixture：

- `contracts/ordering/order-ship-command-v1.json`
- `contracts/ordering/order-payment-result-v1.json`

## 6. 下一步门禁

下一批允许创建分层 `order-service` 与独立原数据库`order-db`，只做无流量影子运行、可重复数据迁移和远程契约
适配。跨进程 Trace、重复/乱序/超时/结果丢失、停机恢复、最终追平和回退演练完成以前不得 CUTOVER；
PostgreSQL 仍等待 M3 全部服务完成单一所有权切换并验收。

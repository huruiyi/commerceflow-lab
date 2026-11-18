# payment-orchestration 一次性切换运行手册

## 1. 当前评审结论

M3.6 第十一批已在明确授权的受控窗口完成 **CUTOVER**。普通支付/退款流量、渠道回调和资金副作用现在由
`payment-orchestration-service` 唯一处理；commerce 只保留身份薄入口、稳定 Payment V1 契约和唯一远程适配器，
不保留本地实现、选择器、双写或数据库回退。

真正切换必须在一个受控窗口内原子完成，不能拆成“先开回调”“先开远程写”或“保留本地兜底”几个长期阶段。

## 2. 切换前必须为零

1. payment-db 的 14 张所有权表完成最终增量追平，逐表行数和指纹全部 `MATCH`。
2. `payment_outbox` 无 NEW/RETRY/PROCESSING，`payment_command_outbox` 无 NEW/RETRY/IN_FLIGHT，Process Manager
   无超时或待人工恢复，孤儿隔离 OPEN 为 0。
3. commerce 源端没有正在提交的支付创建、关闭、回调、退款和退款回调事务。
4. Payment V1、渠道回调、Order V1、Inventory COMMIT/REFUND 使用的独立服务身份和密钥均已验证。
5. Windows、Ubuntu WSL 的预切换、远程协议、副作用重试、服务/数据库停机恢复和快速回归全部通过。

## 3. 单窗口原子顺序

1. 进入停写：以 `PAYMENT_WRITE_FROZEN=true` 重启 commerce，阻止新支付、订单取消中的支付关闭和退款发起；
   等待当前资金事务结束。支付/退款签名回调不经过该闸门，必须继续排空。
2. 保持 payment 的流量门和副作用门关闭，执行最后一次快照/增量追平和 14 表水位、指纹、孤儿核对。
3. 停止 commerce-service，确保旧回调 Controller 和本地调度任务不再消费新请求。
4. 部署只含唯一远程 `PaymentOrchestrationV1` 适配器的 commerce 镜像；同时删除本地支付/退款 Service、Mapper、
   回调 Controller、对账任务和渠道 Gateway 运行路径，不保留 LOCAL/REMOTE/COMPARE。
5. 把支付平台的支付与退款回调一次性改到 payment-orchestration-service。
6. 在 `commerce_runtime` 授权清单中排除 14 张 payment/refund 源表，并验证 SELECT/INSERT/UPDATE/DELETE 均拒绝。
7. 关闭影子复制，打开 payment 流量门和副作用门，再启动唯一远程 commerce 调用方。
8. 用一笔受控支付和一笔全额退款验证：Payment V1、签名回调、Order V1、Inventory、Trace 和幂等重放。
9. 验收通过后解除停写；源表只保留为历史证据，不是运行回退路径。

步骤 3 至 7 必须使用同一提交和同一变更窗口。任一步失败且 payment-db 尚未接收新业务写时，可恢复原提交、
原回调和原授权；一旦独立库接收新写，禁止直接切回旧库。

## 4. 必须删除和必须保留

必须删除的是 commerce 的本地支付/退款业务实现、Mapper/XML、支付与退款回调、渠道 Gateway、资金对账任务以及
对 14 张源表的运行权限。历史 Flyway 不改写，源表不删除。

必须保留的是 commerce 的客户/商家 HTTP 薄入口、Session/RBAC/CSRF、稳定 Payment V1 契约、订单侧调用端口和
跨服务签名客户端。`RefundController` 是身份薄入口，不再被视为本地资金所有者；它只能调用
`PaymentOrchestrationV1`。

## 5. 验收与停止规则

| 门禁 | 通过标准 |
| --- | --- |
| 数据 | 14 表最终水位、行数、指纹一致，OPEN 孤儿/恢复为 0 |
| 单一实现 | commerce 只有一个远程适配器，无本地/比较/数据库回退 |
| 回调 | 支付与退款回调只指向独立服务，旧 Controller 不存在 |
| 权限 | commerce 运行账号对 14 张源表无任何表权限 |
| 可靠性 | 重复、超时、结果丢失、服务/数据库重启均以原业务 ID 收敛 |
| 副作用 | 支付、取消竞态、退款的订单和库存状态正确且可恢复 |
| 回归 | Java、Flyway、Windows/Bash、快速回归和代码质量全部通过 |

停写闸门必须先于订单 `CANCELLING` 状态变更生效。使用
`verify-payment-write-freeze.ps1` 验证创建、关闭、退款均返回 503、业务事实不变且旧回调可继续收敛；脚本退出时
必须恢复 `PAYMENT_WRITE_FROZEN=false`。

任一门禁失败都停止切换。不得用打开本地兜底、恢复源表权限或同时运行两套回调来绕过失败。

## 6. 2026-11-18 实际切换证据

- 强停写后最终快照为 generation 16、水位 610，14 张表逐表行数和指纹全部 `MATCH`。
- OPEN 孤儿、待发布 Payment Outbox、待处理 Command Outbox、未完成 Process Manager 均为 0。
- payment 模式为 `acceptingTraffic=true/effectsEnabled=true/shadowEnabled=false`；两类回调只指向独立服务。
- `commerce_runtime` 对 14 张旧源表的表权限计数为 0，直接 SELECT 返回 MySQL 1142。
- 受控订单 486 完成支付、支付重放、全额退款、退款重放；最终订单 `REFUNDED`、退款 `SUCCEEDED`、库存
  `REFUNDED`，独立库未决项为 0，commerce 旧源表没有该订单支付记录。
- 独立库已接收新业务写，旧库仅作历史证据，不再是运行回退路径。

## 7. 切流后观察证据

M3.6 第十二批新增 `verify-payment-post-cutover-observation.ps1`，在 Windows 与 Ubuntu WSL 对等执行两轮
真实支付/退款、待支付关闭、payment-orchestration-service 重启、支付模拟器重启和 payment-db 停机恢复。
Windows 使用订单 517/520，WSL 使用订单 527/530；观察前后 commerce 支付源水位均为 610，14 张源表授权为 0，
Payment/Command Outbox、未完成 Process Manager 和 OPEN 孤儿全部为 0。

独立服务暴露 10 个 `commerceflow_payment_*` Prometheus 指标，覆盖两类 Outbox、流程未决/人工恢复、OPEN 孤儿、
处理中退款、指标刷新及 `acceptingTraffic/effectsEnabled/shadowEnabled`。Prometheus 已新增独立服务采集目标，
14 条告警规则通过 promtool；正式切流模式必须持续为 `true/true/false`。

## 8. 历史支付超时对账结案

四条异常均生成于 2026-11-17 UTC。当时旧商城扫描仍为 `PENDING` 且超过渠道有效期的支付，并写入 `PAYMENT_TIMEOUT`。
随后订单付款期限届满，超时任务成功执行关单。旧扫描只登记异常，关单流程也未更新对账记录。切流后，四条记录仍为
`OPEN`，而正式支付库已记录 `PAYMENT_CLOSED_BY_ORDER`。冻结的商城源库中，支付 400 仍为 `PENDING`，
正式支付库与渠道均为 `CLOSED`，按正式库和渠道核对该笔。

| 对账 ID | 支付 ID | 订单 ID | 原渠道交易号 | 检测 UTC | 模拟渠道关闭 UTC |
| --- | --- | --- | --- | --- | --- |
| 204 | 387 | 468 | CP607A237CE30A4D139E19BCE6E6C6EF68 | 20:50:54.666324 | 21:05:52.208131 |
| 207 | 388 | 469 | CPFE8FB4BD73AD44628E851725A081F5B8 | 20:53:44.615820 | 21:08:07.565872 |
| 208 | 389 | 470 | CPF2E02BCD35974104BDB692E0AC2D722D | 20:54:44.638365 | 21:09:37.946910 |
| 225 | 400 | 483 | CP08013F0CA587487BB615E1E9A557D16F | 21:14:13.197721 | 21:28:30.577782 |

每笔核对了支付单号、渠道交易号、渠道、金额、币种，支付库和渠道的支付时间均为空，退款均为 0。正式支付库无有效
成功回调，四笔订单均为 `CANCELLED / RELEASED`、超时任务为 `SUCCEEDED`、无运单和库存变动。

2026-11-19 02:27（北京时间），在 `payment-db.payment_reconciliation_issue` 按上表四组 ID 和检测时刻
精确匹配，事务内再次检查支付关闭、无扣款、无退款及关单事件。受影响行数必须为 4。结案状态为 `RESOLVED`，
解析码为 `TERMINAL_PAYMENT_CONFIRMED`，同时记录时间、执行者和逐笔渠道证据，原异常信息保留。
复核真实对账表 `OPEN=0`，支付、订单与五条独立的库存待对账记录在结案前后均一致。

## 9. 支付对账与流程恢复监控

直接从 `payment-db.payment_reconciliation_issue` 读取 `status='OPEN'`，对应
`commerceflow_payment_reconciliation_open` 和 `CommerceFlowPaymentReconciliationIssueOpen`。
保留 Process Manager 指标 `commerceflow_payment_process_recovery_required` 和现有告警
`CommerceFlowPaymentReconciliationBacklog`。两条告警按各自读数持续 10 分钟触发，
新对账告警要求最新数据库读取成功。详细规则见[可观测性与告警](../ALERTING.md)。

隔离 MySQL 通过支付服务真实采集，依次观察到两项读数 `0/0 → 4/0 → 0/2 → 0/0`。已结案账本行及
`WAITING_RESULT` 流程未被计入对应指标。Promtool 分别验证了仅账本积压、仅流程待恢复、未达 10 分钟、
结案清零和数据库读取失败时的告警行为。现有本地环境没有写入告警测试数据。

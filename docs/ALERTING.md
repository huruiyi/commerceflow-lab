# CommerceFlow 告警管道

## 1. 实现边界

本地 `observability` Profile 已形成可运行的告警链：

```text
商城 / 目录 / 通知 / 订单 / 库存 / 支付编排 / 履约 / 支付模拟器 / 物流模拟器
    -> Prometheus 抓取与规则计算
    -> Alertmanager 分组、抑制和重复间隔
    -> alert-webhook 接收通知
    -> Grafana 查询 Prometheus 与 Alertmanager
```

默认 `alert-webhook` 保存最近 100 次通知于内存，容器重建后清空。通知值班专项通过隔离覆盖配置启用
持久化接收端，使用主值班与备值班两个受保护的收件箱。真实通知供应商和值班平台接入属于发布准备。
[Alertmanager 配置](../infrastructure/monitoring/alertmanager.yml) · [通知值班流程](#通知告警的隔离值班流程)

## 2. 启动与入口

默认业务栈不自动启动监控组件，也不增加日常资源占用。显式启用：

```powershell
docker compose --profile observability up -d --wait
```

| 组件 | 地址 | 作用 |
| --- | --- | --- |
| Prometheus | `http://localhost:19090` | 指标、Targets、Rules 和 Alerts |
| Alertmanager | `http://localhost:19093` | 告警分组、抑制、静默和通知状态 |
| Grafana | `http://localhost:13000` | 已预置 Prometheus 与 Alertmanager 数据源 |
| 本地 Webhook | `http://localhost:18085/api/alerts` | 查看最近收到的 Alertmanager 通知 |

Grafana 教学账号为 `admin` / `commerceflow-grafana`。这些端口和演示凭据只适用于本地学习环境。

## 3. 告警规则

规则位于 [核心服务告警](../infrastructure/monitoring/rules/commerceflow-alerts.yml)和[模拟服务告警](../infrastructure/monitoring/rules/simulator-alerts.yml)。

| 告警 | 条件 | 等待 | 级别 |
| --- | --- | --- | --- |
| `CommerceFlowWatchdog` | 固定心跳 | 20 秒 | `none` |
| `CommerceFlowServiceDown` | 商城、目录、通知、订单、库存、支付编排、支付模拟器或物流模拟器抓取失败 | 1 分钟 | `critical` |
| `CommerceFlowHighServerErrorRate` | HTTP 5xx 比例超过 5% | 5 分钟 | `warning` |
| `CommerceFlowJvmHeapPressure` | JVM Heap 超过 90% | 10 分钟 | `warning` |
| `CommerceFlowScheduledTaskFailures` | 5 分钟内出现任务执行失败 | 立即 | `warning` |
| `CommerceFlowMqPublishFailures` | 5 分钟内 RabbitMQ/RocketMQ 发布失败 | 立即 | `warning` |
| `CommerceFlowRefundBacklog` | 存在处理中的退款 | 10 分钟 | `warning` |
| `CommerceFlowPaymentReconciliationBacklog` | 支付 Process Manager 有需要对账或补偿的流程 | 10 分钟 | `warning` |
| `CommerceFlowPaymentReconciliationIssueOpen` | 支付对账表存在 `OPEN`，且最新指标刷新成功 | 10 分钟 | `warning` |
| `CommerceFlowCatalogEventDeliveryBacklog` / `CommerceFlowNotificationDeliveryBacklog` | 目录或商城通知的最老未投递行达到 120 秒，且各自指标刷新成功 | 1 分钟 | `warning` |
| `CommerceFlowCatalogEventDeliveryDead` / `CommerceFlowNotificationDeliveryDead` | 对应发送 Outbox 有 `DEAD`，且指标刷新成功 | 1 分钟 | `warning` |
| `CommerceFlowCatalogEventDeliveryMetricsRefreshFailed` / `CommerceFlowNotificationDeliveryMetricsRefreshFailed` | 对应发送服务的 DB 指标刷新失败 | 1 分钟 | `warning` |
| `CommerceFlowNotificationConsumerLag` | 通知消费组未消费的偏移总量大于 0，且偏移采集成功 | 1 分钟 | `warning` |
| `CommerceFlowNotificationConsumerDeadLetter` | 通知消费者在最近 2 分钟内成功写入 Kafka 死信主题 | 立即 | `warning` |
| `CommerceFlowNotificationConsumerMetricsRefreshFailed` | 通知消费组的偏移采集失败 | 1 分钟 | `warning` |
| `CommerceFlowOrderShipmentIntentStalled` | 订单库有未完成发货意图，最长无更新时间达到 120 秒，且最新指标刷新成功 | 1 分钟 | `warning` |
| `CommerceFlowOrderCancellationRecoveryRequired` | 订单取消协议存在待对账、待补偿或人工复核的流程，且最新指标刷新成功 | 1 分钟 | `warning` |
| `CommerceFlowOrderMetricsRefreshFailed` | 订单指标刷新未成功 | 1 分钟 | `warning` |
| `CommerceFlowInventoryCommandReconciliationRequired` | 商城有库存命令待人工对账，且最新指标刷新成功 | 1 分钟 | `warning` |
| `CommerceFlowInventoryCommandDeliveryBacklog` | 商城有投递中的库存命令，最老一条创建已满 120 秒，且最新指标刷新成功 | 1 分钟 | `warning` |
| `CommerceFlowInventoryCommandDeliveryMetricsRefreshFailed` | 商城的库存命令指标刷新未成功 | 1 分钟 | `warning` |
| `CommerceFlowInventoryOutboundPublishFailed` | 最近 5 分钟内出库事件发布或确认写回失败 | 立即 | `warning` |
| `CommerceFlowInventoryOutboundBacklog` | 出库事件仍待发布，最老一条已等待 120 秒，且读数新鲜 | 1 分钟 | `warning` |
| `CommerceFlowLogisticsReconciliationFailure` | 5 分钟内物流对账失败 | 立即 | `warning` |
| `CommerceFlowLogisticsCallbackDeadLetter` | 物流回调 Outbox 存在 `DEAD` | 5 分钟 | `critical` |
| `CommerceFlowInvoicePendingTooLong` | 正票或红冲最老待处理请求、最老待确认回执已等待至少 300 秒 | 1 分钟 | `warning` |
| `CommerceFlowMessagingDeliveryFailures` | 单个渠道在最近 10 分钟内至少有 3 条终态投递失败 | 2 分钟 | `warning` |

`CommerceFlowWatchdog` 是故意保持 firing 的“死信号开关”，用于发现 Prometheus、Alertmanager 或接收端本身断链，不代表业务故障。Alertmanager 对同一告警的 `critical` 抑制 `warning`，普通重复间隔为 4 小时，严重告警为 15 分钟。

支付服务直接从 `payment_reconciliation_issue` 读取 `OPEN` 总量，指标为
`commerceflow_payment_reconciliation_open`。流程待恢复继续使用
`commerceflow_payment_process_recovery_required`，来源为 `payment_process_managers` 中的
`RECONCILIATION_REQUIRED` 与 `COMPENSATION_REQUIRED`。数据库读取失败时，指标保留上一轮读数，
新对账告警只使用刷新成功的读数，刷新故障由 `CommerceFlowPaymentMetricsRefreshFailed` 告警。

目录使用 `commerceflow_catalog_event_delivery_` 前缀，查询 `catalog-db.catalog_event_outbox`。商城使用
`commerceflow_notification_delivery_` 前缀，查询商城库活跃的 `notification_delivery_outbox`。
两个发送端每 5 秒采集一次，`pending` 和 `oldest_seconds` 仅覆盖 `NEW`、`RETRY`。最老等待时长是从
最早的 `created_at` 到数据库当前时间的非负整秒。无待发时为 0，重试时间变化不重置年龄。`dead` 只计
`DEAD`，`PUBLISHED` 排除在业务指标之外。查询失败时保留上一轮的读数，`metrics_refresh_success` 为 0，
业务告警仅使用刷新成功的读数。

通知服务的 `commerceflow_notification_consumer_lag` 由 Kafka broker 偏移计算，覆盖通知命令主题各分区。
每分区取末端偏移减去已提交偏移。未提交时从保留起点计算，提交偏移已过期时也从保留起点计算。
消费者暂停时，通知服务实例仍可从 broker 取得 lag。`commerceflow_notification_consumer_metrics_refresh_success`
区分无积压与读偏移失败。`commerceflow_notification_consumer_dead_letters_total` 统计通知消费者收到
Kafka 确认的死信发布。两分钟内无新发布时，死信告警解除，累计值保留。

订单服务每 5 秒从自己的原数据库刷新指标，采集目标为 `order-service`。

| Prometheus 指标 | 统计口径 |
| --- | --- |
| `commerceflow_order_shipment_intents_pending` | `shop_orders` 中状态为 `CONFIRMED` 且 `shipment_intent_key` 非空的订单数。 |
| `commerceflow_order_shipment_intents_oldest_seconds` | 上述订单最早的 `updated_at` 到原数据库当前时间的非负整秒，无未完成意图时为 0。该字段反映最近一次订单更新，重试写入会刷新时间。 |
| `commerceflow_order_cancellation_recovery_required` | `order_process_managers` 中类型为 `CANCELLATION` 且状态为 `WAITING_RECONCILIATION`、`COMPENSATION_REQUIRED` 或 `MANUAL_REVIEW` 的流程数。 |
| `commerceflow_order_metrics_refresh_success` | 最近一次查询成功为 1，首次成功前或查询失败时为 0。失败时保留上轮业务读数，业务告警要求刷新成功。 |

取消恢复指标覆盖订单侧协议流程。日常顾客取消跨库链路另外按订单号核对商城取消效果、库存释放和通知投递，见[订单监控与只读核对](architecture/ORDER-SERVICE-EVOLUTION-PLAN.md#10-订单监控与只读核对)。

商城从自己的 `inventory_command_delivery_outbox` 每 5 秒刷新一次，指标和三条告警均归 `commerce-service`。

| Prometheus 指标 | 统计口径 |
| --- | --- |
| `commerceflow_inventory_command_delivery_reconciliation_required` | 仅计 `RECONCILIATION_REQUIRED`，每条命令计一笔。 |
| `commerceflow_inventory_command_delivery_pending` | 计 `NEW`、`IN_FLIGHT`、`RETRY`，包括尚未到重试时间或仍在等待回执的命令。 |
| `commerceflow_inventory_command_delivery_oldest_seconds` | 用原数据库当前时间减去上述三种状态中最早的 `created_at`，取非负整秒。无积压时为 0。重试及回执截止时间变化均不重置等待时长。 |
| `commerceflow_inventory_command_delivery_metrics_refresh_success` | 最近一次聚合查询成功为 1，首次成功前或最近一次失败为 0。刷新失败时保留上次业务读数，两条业务告警只使用刷新成功的读数，采集故障由单独告警报告。 |

`ACKNOWLEDGED` 和 `CLOSED` 不参与上述三项业务统计。

库存服务从自身 Outbox 读取 `commerceflow_inventory_outbound_events_pending` 和
`commerceflow_inventory_outbound_events_oldest_seconds`。积压年龄按出库记账时间计算，重试不会重置年龄。
`commerceflow_inventory_outbound_publish_failures_total` 统计发布或确认写回失败的尝试。
数据库采集失败时保留上一轮积压读数，`commerceflow_inventory_outbound_metrics_refresh_success` 置 0。

## 4. 验收

```powershell
pwsh -File ./scripts/operations/verify-alerting.ps1
```

验收入口默认启动 `observability` Profile，但不停止业务服务、不删除容器或数据卷。它执行：

1. `promtool check config` 与核心规则加载检查；
2. `promtool test rules`，离线验证服务宕机和业务故障规则，包括库存命令的年龄门槛、刷新故障和恢复；
3. `amtool check-config`；
4. 检查商城、目录、通知、订单、库存、支付编排及支付、物流、短信邮件模拟器的采集目标，远程履约启用时同时检查履约目标。发票是发布覆盖服务，启用后目标才可抓取；
5. Watchdog 经 Alertmanager 真实到达 Webhook。

已有监控容器时可传 `-SkipStart`。脚本只验证告警链，不通过停止业务服务制造故障。

库存命令和出库事件的隔离原数据库专项见[验收记录](architecture/INVENTORY-RECONCILIATION-REVIEW.md#2026-11-19-库存命令监控验收)。

### 用业务编号定位模拟服务告警

告警只带稳定标签，比如发票的 `kind`、`phase` 和消息的 `channel`。用有查询权限的账号在对应数据库识别积压记录；
单笔状态以账本及回执为准。以下查询中的示例编号替换为实际业务编号。只知道告警而没有编号时，可去掉等值条件，
按时间查看最老的发票任务或最近失败的记录。

**发票开具与红冲。** 在发票库 `invoice_platform` 按 `reference_no` 查开票申请号或退款单号。正票对应 `ISSUE`，
红冲对应 `RED`。处理告警只涉及 `ACCEPTED`。回执告警则从请求完成时间开始，直到收件端确认该回执；
签名接口 `GET /api/invoice-requests/{requestId}` 可核对模拟平台记录的单笔结果。

```sql
SELECT request.request_id, request.reference_no, request.kind, request.status,
       request.accepted_at, request.completed_at, receipt.event_id, receipt.attempts, receipt.delivered_at
FROM invoice_requests request
LEFT JOIN invoice_receipts receipt ON receipt.request_id = request.request_id
WHERE request.reference_no = '实际开票申请号或退款单号'
  AND (request.status = 'ACCEPTED' OR (receipt.event_id IS NOT NULL AND receipt.delivered_at IS NULL))
ORDER BY request.accepted_at
LIMIT 50;
```

**物流回调终止。** 在运单管理按商城运单号、订单号或物流单号查到运单，点「查看此运单回调」，筛选「已终止」，
按事件编号核对最新失败与重试审计。列表只保留最近 500 条投递，需查看更久的记录时在 `logistics_platform` 只读查询：

```sql
SELECT shipment.merchant_shipment_no, shipment.tracking_number, callback.id, callback.event_id,
       callback.status, callback.attempts, callback.updated_at
FROM callback_outbox callback
JOIN shipment_records shipment ON shipment.id = callback.shipment_id
WHERE callback.status = 'DEAD' AND shipment.merchant_shipment_no = '实际商城运单号'
ORDER BY callback.updated_at DESC
LIMIT 50;
```

**短信邮件持续失败。** 在消息模拟平台选择告警渠道，按业务关联号和「送达失败」筛选。直接排查滚动窗口时，
在 `messaging_platform` 查看失败请求号和业务关联号。失败数量随窗口滑动下降，历史失败记录继续保留。

```sql
SELECT channel, reference, request_id, status, finished_at
FROM messaging_deliveries
WHERE channel = 'SMS' AND status = 'FAILED'
  AND finished_at >= CURRENT_TIMESTAMP - INTERVAL '10' MINUTE
ORDER BY finished_at DESC
LIMIT 50;
```

发票、物流和消息告警可用 `pwsh -File ./scripts/operations/verify-simulator-monitoring.ps1` 做隔离验收。
脚本先用 promtool 验证正式规则的等待时间，再缩短隔离阈值，实测 Prometheus、Alertmanager 和 Webhook 的触发及恢复。

### 通知告警的隔离值班流程

用 PowerShell 7 运行专项。脚本建独立 Compose 项目，启动通知 PostgreSQL、Kafka、Prometheus、
Alertmanager 和接收端，通过两个带可识别事件 ID 的不支持版本命令触发实际 Kafka 死信。

```powershell
pwsh -NoProfile -File scripts/operations/verify-notification-alert-workflow.ps1
```

脚本可以通过 `-NotificationImage` 复用已构建的通知镜像。隔离配置将现有死信规则的时间窗口从两分钟缩短
到 32 秒，主值班确认期限设为 12 秒。日常规则和路由使用原有配置。

| 动作 | 隔离接口与行为 |
| --- | --- |
| 送达 | Alertmanager 通过专属 Bearer 凭据调用 `POST /api/alerts`。接收端按告警指纹和开始时间生成事件编号，提交 SQLite 后为主值班保留一次 `firing`。 |
| 查收和确认 | 值班账号用各自的 Bearer 凭据读取 `GET /api/on-call/inbox`，再调用 `POST /api/on-call/incidents/{incidentId}/ack`。回执记录确认人和时间，重复确认返回首次结果。 |
| 升级和恢复 | 达到期限且当前告警仍未确认、未恢复时，备值班收到一次 `escalated`。恢复后已收到触发或升级通知的账号获得一条 `resolved`。 |

收到死信告警后，从收件箱的 `alert.annotations.lookup` 确认主题和查找线索。用告警开始时间及规则窗口确定
需要读取的分区偏移范围，从 `customer-notification-commands-dlt` 读取候选记录。记录 `eventId`、
死信分区和偏移，同一窗口有多条死信时保留每个候选事件。读取使用明确的分区、偏移和数量，并关闭自动提交。

```powershell
docker exec $isolatedBroker /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic customer-notification-commands-dlt --partition $partition --offset $offset --max-messages $count --consumer-property enable.auto.commit=false --property print.partition=true --property print.offset=true
```

验收脚本在每次故障前保存三个死信分区的末尾偏移，从新增记录读回事件 ID。随后以相同事件 ID 发送符合协议的命令，
核对 PostgreSQL Inbox 恰有一行，并等待死信规则窗口结束后收到恢复通知。`workflow-result.json` 保存
事件编号、通知确认和恢复时间、死信位置。收件箱持久化、轮次凭据和核验日志随隔离项目保留。

## 5. 生产部署前仍需完成

- Prometheus、Alertmanager、Grafana 和 Actuator 不应直接暴露到公网；应放入受控网络并启用 TLS、身份认证和访问审计。
- 接入真实通知供应商，使用环境注入或 Secret 管理保存令牌，禁止提交凭据。
- 根据真实流量、SLO、值班轮次和容量基线调整阈值、分组、静默和升级策略。
- 为 Prometheus/Alertmanager 配置持久存储、高可用副本和异地保留；当前单机 Compose 不代表生产高可用。
- 在实际值班渠道验证人员送达、确认、升级与复盘，沿用隔离环境已验证的事件与回执关联。

### 用订单定位投递与监控

1. 用订单号在订单库 `shop_orders` 查订单 ID，再用该 ID 查取消审计及
   `order_remote_message_inbox` 中的 `finish-cancellation:<订单 ID>` 和 `audit-cancellation:<订单 ID>`。
2. 用订单 ID 查商城 `order_cancellation_completion`、`order_effect_outbox.aggregate_id` 和
   `inventory_command_delivery_outbox.order_id`。库存命令的 `command_id` 对照库存库 `inventory_command_inbox`，
   释放结果核对 `inventory_movements`。取消效果的 `event_id` 加 `:notification` 对应商城通知 Outbox、
   通知库 Inbox 和用户可见记录。
3. 用这些标识筛选商城、订单、库存或通知容器的日志，并在 Prometheus 按 `job` 查询本节对应指标。
   看板上的计数反映服务整体状态，单笔处理结果以各自账本为准。V52 未完成行以
   `order_cancellation_completion.completed_at IS NULL` 查询；订单服务取消协议告警使用订单库
   `order_process_managers` 的恢复状态。

### 冻结账单与售后异常恢复

| 现象 | 定位和恢复 |
| --- | --- |
| 账单可打开，库存或仓库凭证不可用 | 检查对应来源的健康检查及签名配置，再用原订单和出库号对照库存出库行与 WMS 实发回执。恢复来源后刷新同一账单，检查两项可用性及同一出库编号。历史订单的 WMS 回执可能为空，留存与来源停机不同的对账状态。 |
| 部分退货停在入库或缺补充质检 | 用售后单号对照原出库号、WMS 实收和补充质检、支付退款单、库存行回补。确认原工单有权继续后，在原售后入口恢复；按售后单号查退款和库存入账的唯一记录，回到异常待办核对阶段。 |
| 打款迟迟未确认或已失败 | 用账单号和尝试号查商家打款记录及模拟渠道回执。渠道已受理时使用同一请求等待查询补偿。渠道确认失败后从原打款入口恢复，关单前核对渠道成功金额及尝试号与复核关联。 |
| 红票或通知回执积压 | 用退款单号查红票请求号和渠道回执，观察 `commerceflow_invoice_pending_oldest_seconds`。用事件 ID 查 PostgreSQL Inbox、投递与供应商请求号，结合 `commerceflow_notification_delivery_pending`、`commerceflow_notification_delivery_oldest_seconds` 和 `commerceflow_notification_consumer_lag` 确认恢复。供应商已受理时核对原请求号，回执缺失时使用现有查询或恢复入口。 |

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
| `CommerceFlowOrderCancellationReleaseReconciliationRequired` | 商城有取消后的库存释放命令待对账，且最新库存命令指标刷新成功 | 1 分钟 | `warning` |
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

商城从自己的 `inventory_command_delivery_outbox` 每 5 秒刷新一次，指标和告警均归 `commerce-service`。

| Prometheus 指标 | 统计口径 |
| --- | --- |
| `commerceflow_inventory_command_delivery_reconciliation_required` | 仅计 `RECONCILIATION_REQUIRED`，每条命令计一笔。 |
| `commerceflow_order_cancellation_release_reconciliation_required` | 上述命令中类型为 `RELEASE` 的子集，来自商城取消事件的库存释放。 |
| `commerceflow_inventory_command_delivery_pending` | 计 `NEW`、`IN_FLIGHT`、`RETRY`，包括尚未到重试时间或仍在等待回执的命令。 |
| `commerceflow_inventory_command_delivery_oldest_seconds` | 用原数据库当前时间减去上述三种状态中最早的 `created_at`，取非负整秒。无积压时为 0。重试及回执截止时间变化均不重置等待时长。 |
| `commerceflow_inventory_command_delivery_metrics_refresh_success` | 最近一次聚合查询成功为 1，首次成功前或最近一次失败为 0。刷新失败时保留上次业务读数，业务告警只使用刷新成功的读数，采集故障由单独告警报告。 |

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

### PostgreSQL 备份和隔离恢复告警

Windows 任务 `CommerceFlow PostgreSQL Recovery Alerts` 每 15 分钟读取备份和隔离恢复的执行状态，并通过 Alertmanager 投递到本地 Webhook。状态与执行日志位于 `%LOCALAPPDATA%/CommerceFlow/postgresql-maintenance/`。[备份与恢复任务](architecture/DATABASE-EVOLUTION-PLAN.md#备份和恢复)

| 告警 | 触发条件 | 排查动作 |
| --- | --- | --- |
| `CommerceFlowPostgresqlBackupFailed` | 每日备份执行失败，或连续 30 分钟未结束 | 读取 `backup.json` 中的 `log`，确认 Docker Desktop、备份磁盘和 13 个日常 PostgreSQL 实例后重跑每日任务。 |
| `CommerceFlowPostgresqlBackupStale` | 没有成功的备份，或距上次成功超过 26 小时 | 检查每日任务的最近结果、登录状态和备份清单，触发备份并确认 SHA-256 校验。 |
| `CommerceFlowPostgresqlRestoreFailed` | 隔离恢复执行失败，或连续 30 分钟未结束 | 读取 `restore.json` 中的 `log`，复核归档、校验和、镜像可用性和临时恢复实例后重跑恢复。 |
| `CommerceFlowPostgresqlRestoreStale` | 没有成功的恢复演练，或距上次成功超过 8 天 | 检查周度任务的最近结果和完整备份，在新的隔离实例中恢复并查看报告。 |

条件解除后发送恢复通知。Alertmanager 暂时不可用时，任务计划程序记录该次告警检查失败，下一轮重新检查。同一主机离线时无法通过这条本地链路通知。验收采用独立状态文件和 `acceptance-*` 告警标签，接收结果可从本地 Webhook 查阅。

### Kafka 定期备份告警

`CommerceFlow Kafka Backup Alerts` 每 15 分钟核对 `%LOCALAPPDATA%/CommerceFlow/kafka-maintenance/kafka-backup.json`，沿用本地 Alertmanager 和 Webhook。任务按独立状态跟踪触发与恢复。[Kafka 归档与恢复](architecture/DATABASE-EVOLUTION-PLAN.md#kafka-定期归档与恢复)

| 告警 | 触发条件 | 排查动作 |
| --- | --- | --- |
| `CommerceFlowKafkaBackupFailed` | 归档失败，或运行状态持续超过 30 分钟 | 查 `kafka-backup.json` 的 `log`。核对 Docker Desktop、broker 进程的实际卷、暂存空间和备份盘，确认采集条件后重新触发备份。 |
| `CommerceFlowKafkaBackupStale` | 尚无成功归档，或上一次成功距今超过 26 小时 | 查计划任务结果、用户登录和备份盘，重跑任务并校验完整归档。 |

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

### 退款、红票与通知处置手册

启动 `observability` 后，在 Grafana 的 CommerceFlow 文件夹打开「退款、红票与通知」。先确认「采集状态」中相关服务为 1，再查看退款处理中、红票最老等待时间、商城通知命令、Kafka 通知消费积压和短信邮件近期失败。发票模拟器随对应部署覆盖启动。退款短信和邮件的单笔送达以通知账本和渠道回执为准。[Grafana 看板](http://localhost:13000/d/commerceflow-refund-operations)

1. 收到 `CommerceFlowInvoicePendingTooLong` 时确认 `kind=RED` 和 `phase=receipt`。该规则在最老回执等待至少 300 秒并持续 1 分钟时触发。看板的 `processing` 表示等待发票平台处理，`receipt` 表示发票平台已完成、仍待确认回执。告警标签提供故障阶段，业务编号从账本读取。
2. 在发票库 `invoice_platform` 用下方查询取最老的未确认回执。记录退款号、红票请求号和事件号，再在支付编排库 `refund_records` 按退款号查订单号、金额和退款状态。用同一订单检查商城发票详情，核对原蓝票和当前红票。

   ```sql
   SELECT request.reference_no AS refund_no, request.request_id, request.status, request.invoice_no,
          receipt.event_id, receipt.attempts, request.completed_at
   FROM invoice_requests request
   JOIN invoice_receipts receipt USING (request_id)
   WHERE request.kind = 'RED' AND receipt.delivered_at IS NULL
   ORDER BY request.completed_at, request.request_id
   LIMIT 20;
   ```

3. 检查商城回执接收端的健康状态和签名配置，恢复后让发票平台沿用原事件号重试。若平台返回 `FAILED` 或商城已进入人工复核，先查原请求的渠道结果，再由有发票运营权限的人员使用原复核入口。记录解除告警的时间、原事件号、重试次数和最终红票号。
4. 核对红票金额等于原退款金额，商城与发票平台认同同一张红票。以 `offsite-refund:<退款号>` 查通知库的 `offsite_notification_inbox` 和 `offsite_notification_deliveries`，再用渠道请求号核对模拟平台回执。验收需看到红票等待值回到 0、Webhook 收到 `resolved`、红票只生效一张、短信和邮件各送达一次。

在 PowerShell 7 下运行 `pwsh -File ./scripts/operations/verify-refund-observability.ps1` 可在新卷上复验。已有同版本验收镜像时可加 `-SkipBuild`。脚本使用正式告警阈值，由隔离回执代理暂缓投递，再放行原回执并让其重试。

### 订单发货、WMS 实发与库存出库处置手册

在 Grafana 的 CommerceFlow 文件夹打开「订单发货、WMS 实发与库存出库」。先检查订单、履约、WMS 和库存出库的指标刷新状态，再对照发货意图、履约超期、WMS 出库状态和 Kafka 投递等待。WMS 的 `DISPATCHED` 为已有实发回执的出库单总数，件数从对应原单的 `actual` 明细读取。看板和告警使用有限的服务、状态标签，原订单号和命令号从业务账本查找。[Grafana 看板](http://localhost:13000/d/commerceflow-fulfillment-operations)

| 告警或现象 | 定位原单 | 恢复与确认 |
| --- | --- | --- |
| `CommerceFlowOrderShipmentIntentStalled`，最老发货意图至少 120 秒且持续 1 分钟 | 在订单库按发货意图查订单 ID 和命令 ID，用完整订单号打开商家订单。继续查履约命令和 WMS 接单记录。 | 恢复卡住的服务或签名调用，查看原发货意图是否完成。 |
| `CommerceFlowFulfillmentJobsOverdue`，履约任务超期超过 120 秒且持续 2 分钟 | 在 `fulfillment` 库定位最老命令。按该命令号查 WMS `warehouse_outbounds.outbound_id`、`status` 和 `actual`，并对照商家订单的命令状态。 | 已实发时保留原出库单。服务恢复后等待原待执行任务接续，或在原商家入口用相同 `packageId` 和原请求重试。进入 `REVIEW_REQUIRED` 时，由有权限的人员用平台原命令复核入口处理。核对同一出库单、运单与库存流水。 |
| `CommerceFlowFulfillmentReviewRequired`，履约执行已启用且待复核任务持续 3 分钟 | 平台管理员通过 `GET /api/admin/fulfillment/shipments` 取候选命令，再按 `orderId` 查询详情，记录原订单号、履约命令 ID 和对应 WMS 原出库单。 | 对已到仓库或后续阶段的发货命令，先取得与原单一致的 WMS `DISPATCHED` 回执，再用 `POST /api/admin/fulfillment/shipments/{orderId}/retry` 提交原 `commandId`、`recoveryId`、`reason` 和 `evidence`。缺失或不匹配时返回 HTTP 409，任务继续待复核。重复提交同一恢复请求时核对审计记录和 Kafka 事件。 |
| `CommerceFlowInventoryOutboundBacklog`，有待投递且最老事件至少 120 秒并持续 1 分钟 | 从库存 Outbox 的 `outbound_id` 关联库存出库单和订单行，记录原 `event_id`。用同一单号核对 WMS 实发、履约完成状态和库存出库凭证。 | 恢复 Kafka 与库存发布连接，由库存服务沿用原事件投递。核对原 Outbox 行的 `published_at`、Kafka 中的原消息与原业务流水。 |

在对应 PostgreSQL 库用只读查询取最老候选。履约结果的 `command_id` 是 WMS 出库单号，库存结果保留原订单行与事件编号。

```sql
-- 履约库
SELECT order_number, order_id, command_id, warehouse_id, step, status, next_attempt_at, last_error
FROM fulfillment_shipment_jobs
WHERE status = 'PENDING' AND next_attempt_at <= statement_timestamp()
ORDER BY next_attempt_at, command_id
LIMIT 20;

-- 库存库
SELECT document.order_id, event.event_id, event.outbound_id, event.order_line_id, event.next_attempt_at
FROM inventory_outbound_event_outbox event
JOIN inventory_outbound_documents document USING (outbound_id)
WHERE event.published_at IS NULL
ORDER BY event.next_attempt_at, event.event_id
LIMIT 20;
```

确认解除时保存原订单号、出库命令、事件号、告警触发和恢复时间。检查履约待执行与库存待投递回到基线，Webhook 收到 `resolved`。按原命令及订单行逐笔核对 WMS 回执、库存出库流水和 Kafka 事件各一份。验收注入在独立 Compose 项目和新卷中执行。

在 PowerShell 7 运行 `pwsh -File ./scripts/operations/verify-fulfillment-review-observability.ps1` 可复验已有实发回执及回执暂缺两种场景。脚本在独立 Compose 项目中新建测试订单，采集触发和解除通知、看板截图、订单及库存流水和 Kafka 消息，并验证证据归档的 SHA-256。结束后停止隔离项目并保留其命名卷。连接同一个 Docker 引擎时，脚本还会只读记录日常 `fairy-commerceflow` 容器和数据卷，核对容器状态及原有卷是否保留。

### 订单取消补偿与库存命令待对账处置手册

在 Grafana 的 CommerceFlow 文件夹打开「订单取消补偿与库存命令待对账」。先看商城命令账本和订单协议的指标刷新是否为 1，再核对取消释放、全部待对账以及投递积压。取消释放是全部待对账的子集，同一笔故障可以同时触发两条告警。订单服务的协议告警覆盖独立的 `order_process_managers`，收到该告警时按[订单协议只读核对](architecture/ORDER-SERVICE-EVOLUTION-PLAN.md#10-订单监控与只读核对)追踪流程。[Grafana 看板](http://localhost:13000/d/commerceflow-cancellation-operations)

1. 在商城以管理员身份调用 `GET /api/admin/inventory-command-recoveries`，记录待对账的 `commandId`、`orderId`、尝试次数和最后错误。也可在商城库用下面的只读查询取最早候选。在订单库按 `orderId` 查 `shop_orders.order_number`、`status`、`inventory_status`。订单可能已显示 `CANCELLED`，此时库存仍可处于 `RECONCILIATION_REQUIRED`。用命令 ID 对应的事件号核对商城 `order_effect_outbox` 中的原 `ORDER_CANCELLED`。

   ```sql
   SELECT command_id, order_id, attempts, last_error, created_at
   FROM commerceflow.inventory_command_delivery_outbox
   WHERE command_type = 'RELEASE' AND status = 'RECONCILIATION_REQUIRED'
   ORDER BY created_at, command_id
   LIMIT 20;
   ```

2. 在库存库以原命令查 `inventory_command_inbox`，再查原订单的预占和释放流水。在商城查 `inventory_command_result_inbox` 是否已写入对应结果。

   | 现场 | 判定与处置 |
   | --- | --- |
   | 库存仍为 `RESERVED`，原释放命令无成功 Inbox、无释放流水 | 检查库存服务和数据库的写入错误。修复后重试原命令，确认预占转为 `RELEASED`、库存回补。 |
   | 库存已为 `RELEASED`，原命令 Inbox 为 `SUCCEEDED`，商城没有结果 | 检查库存响应链路和商城结果入库错误。修复后重试原命令，库存返回已有执行结果，商城接收确认，既有释放流水保持一份。 |
   | 两侧证据相互矛盾，或存在库存流水但无法关联原命令 | 保留原单、命令和两侧日志，交由库存与订单负责人复核。核对完成前暂停该单重试。 |

   ```sql
   -- 库存库。将命令 ID 和订单 ID 替换为前一步读取的值。
   SELECT command_id, status, processed_at
   FROM public.inventory_command_inbox
   WHERE command_id = '原命令 ID';

   SELECT id, product_id, warehouse_id, quantity, status
   FROM public.inventory_reservations WHERE order_id = :order_id;

   SELECT event_id, product_id, warehouse_id, quantity
   FROM public.inventory_movements
   WHERE order_id = :order_id AND movement_type = 'RELEASED';
   ```

3. 故障修复后，由有权限的管理员通过原会话提交 `POST /api/admin/inventory-command-recoveries/{commandId}/retry`，附 CSRF 请求头和正文 `{"reason":"实际故障及修复记录"}`。路径参数使用原命令 ID，接口读取账本保存的原请求并记录处置人。仍在 `NEW`、`IN_FLIGHT` 或 `RETRY` 的命令由商城调度接续。
4. 复核命令为 `ACKNOWLEDGED`，商城结果 Inbox 有一条 `SUCCEEDED`，订单为 `CANCELLED` 且库存投影为 `RELEASED`。对照每笔原预占的商品、仓库和数量，释放流水各一条，释放量与原预占量相符。确认命令从待对账列表消失，指标回到基线，Webhook 对原告警指纹收到 `resolved`。保存原订单号、事件号、命令 ID、恢复结果、耗时和相关日志。

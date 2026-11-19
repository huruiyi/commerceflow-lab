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

`alert-webhook` 是学习环境中的外部通知接收端模拟器，保存最近 100 次通知于内存，容器重建后清空。它证明 Webhook 协议和路由链真实可用，但不等同于已经接入企业邮箱、短信、电话、Slack、Teams 或值班平台。生产部署应把 [alertmanager.yml](../infrastructure/monitoring/alertmanager.yml) 中的接收地址替换为受 TLS、鉴权和密钥管理保护的真实接收端。

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

规则位于 [commerceflow-alerts.yml](../infrastructure/monitoring/rules/commerceflow-alerts.yml)。

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

订单服务每 5 秒从自己的 MySQL 刷新指标，采集目标为 `order-service`。

| Prometheus 指标 | 统计口径 |
| --- | --- |
| `commerceflow_order_shipment_intents_pending` | `shop_orders` 中状态为 `CONFIRMED` 且 `shipment_intent_key` 非空的订单数。 |
| `commerceflow_order_shipment_intents_oldest_seconds` | 上述订单最早的 `updated_at` 到 MySQL 当前时间的非负整秒，无未完成意图时为 0。该字段反映最近一次订单更新，重试写入会刷新时间。 |
| `commerceflow_order_cancellation_recovery_required` | `order_process_managers` 中类型为 `CANCELLATION` 且状态为 `WAITING_RECONCILIATION`、`COMPENSATION_REQUIRED` 或 `MANUAL_REVIEW` 的流程数。 |
| `commerceflow_order_metrics_refresh_success` | 最近一次查询成功为 1，首次成功前或查询失败时为 0。失败时保留上轮业务读数，业务告警要求刷新成功。 |

取消恢复指标覆盖订单侧协议流程。日常顾客取消跨库链路另外按订单号核对商城取消效果、库存释放和通知投递，见[订单监控与只读核对](architecture/ORDER-SERVICE-EVOLUTION-PLAN.md#10-订单监控与只读核对)。

商城从自己的 `inventory_command_delivery_outbox` 每 5 秒刷新一次，指标和三条告警均归 `commerce-service`。

| Prometheus 指标 | 统计口径 |
| --- | --- |
| `commerceflow_inventory_command_delivery_reconciliation_required` | 仅计 `RECONCILIATION_REQUIRED`，每条命令计一笔。 |
| `commerceflow_inventory_command_delivery_pending` | 计 `NEW`、`IN_FLIGHT`、`RETRY`，包括尚未到重试时间或仍在等待回执的命令。 |
| `commerceflow_inventory_command_delivery_oldest_seconds` | 用 MySQL 当前时间减去上述三种状态中最早的 `created_at`，取非负整秒。无积压时为 0。重试及回执截止时间变化均不重置等待时长。 |
| `commerceflow_inventory_command_delivery_metrics_refresh_success` | 最近一次聚合查询成功为 1，首次成功前或最近一次失败为 0。刷新失败时保留上次业务读数，两条业务告警只使用刷新成功的读数，采集故障由单独告警报告。 |

`ACKNOWLEDGED` 和 `CLOSED` 不参与上述三项业务统计。

库存服务从自身 Outbox 读取 `commerceflow_inventory_outbound_events_pending` 和
`commerceflow_inventory_outbound_events_oldest_seconds`。积压年龄按出库记账时间计算，重试不会重置年龄。
`commerceflow_inventory_outbound_publish_failures_total` 统计发布或确认写回失败的尝试。
数据库采集失败时保留上一轮积压读数，`commerceflow_inventory_outbound_metrics_refresh_success` 置 0。

## 4. 验收

```powershell
pwsh -File ./scripts/verify-alerting.ps1
```

验收入口默认启动 `observability` Profile，但不停止业务服务、不删除容器或数据卷。它执行：

1. `promtool check config` 与核心规则加载检查；
2. `promtool test rules`，离线验证服务宕机和业务故障规则，包括库存命令的年龄门槛、刷新故障和恢复；
3. `amtool check-config`；
4. 检查商城、目录、通知、订单、库存、支付编排、支付模拟器和物流模拟器的采集目标，远程履约启用时同时检查履约目标；
5. Watchdog 经 Alertmanager 真实到达 Webhook。

已有监控容器时可传 `-SkipStart`。脚本只验证告警链，不通过停止业务服务制造故障。

库存命令和出库事件的隔离 MySQL 专项见[验收记录](architecture/INVENTORY-RECONCILIATION-REVIEW.md#2026-11-19-库存命令监控验收)。

## 5. 生产部署前仍需完成

- Prometheus、Alertmanager、Grafana 和 Actuator 不应直接暴露到公网；应放入受控网络并启用 TLS、身份认证和访问审计。
- 接入真实通知供应商，使用环境注入或 Secret 管理保存令牌，禁止提交凭据。
- 根据真实流量、SLO、值班轮次和容量基线调整阈值、分组、静默和升级策略。
- 为 Prometheus/Alertmanager 配置持久存储、高可用副本和异地保留；当前单机 Compose 不代表生产高可用。
- 通过真实故障演练验证通知到人、确认、升级和复盘流程；本项目当前只证明本地 Webhook 端到端投递。

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

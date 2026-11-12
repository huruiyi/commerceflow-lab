# 已归档：第 11 章订单生命周期旧实施计划

> 本文档已被 [`../TODO.md`](../TODO.md) 取代，仅保留早期架构推演和历史上下文。后续实施状态只维护 `TODO.md`，不再同步本文档的清单。

## 1. 文档状态

| 项目 | 内容 |
| --- | --- |
| 当前状态 | 实施计划已制定，业务代码尚未开始 |
| 目标章节 | `11-0` 至 `11-10` |
| 核心范围 | 未支付订单取消、六种超时组合、策略快照、模拟物流、自动收货、`COMPLETED`、客户信息展示 |
| 默认轻量模式 | Database 主触发 + Database 对账 |
| 生产学习模式 | RabbitMQ 主触发 + Database 对账 |
| 高级实验模式 | RocketMQ 主触发 + Database 对账 |
| UI 边界 | 先补齐功能和信息，继续保持详情始终展开，暂不进行摘要/折叠和整体美化 |
| 最后更新 | 2026-11-12 |

本文把 [通用定时任务触发策略与数据库补偿](../reference/SCHEDULED-TASK-STRATEGIES.md) 转换为早期工程计划。每个阶段必须能够独立构建、验证和提交；不得在状态机、资金状态或任务幂等尚未完成前先堆叠前端按钮。

## 2. 目标与非目标

### 2.1 本轮目标

1. 建立订单状态、支付状态、退款状态、物流状态和定时任务状态的明确边界。
2. 支持客户主动取消未支付订单。
3. 支持待支付订单超时自动取消。
4. 以策略模式支持 Database、RabbitMQ、RocketMQ 三种主触发器。
5. 支持三种主策略分别开启或关闭 Database 对账，共六种组合。
6. 任务创建时保存策略快照，管理员修改默认策略只影响新任务。
7. 接入独立模拟物流服务，客户可查看物流信息与轨迹。
8. 引入 `RECEIVED -> COMPLETED`，并支持自动收货与自动完成。
9. 已发货、已收货和已完成订单继续展示支付与物流信息。
10. 为 Windows PowerShell 与 Bash 提供同名验收脚本。

### 2.2 本轮不做

- 不重构订单卡片为摘要、折叠详情或抽屉。
- 不进行退款卡片、评价表单和订单页的整体视觉美化。
- 不接入真实快递公司、真实短信、邮件或资金渠道。
- 不实现部分退款、退货、换货和逆向物流。
- 不让 RabbitMQ 与 RocketMQ 同时成为同一任务的主触发器。
- 不实现已调度任务的在线跨 MQ 迁移；策略修改只影响新任务。
- 不把 Kafka 改造成延迟消息队列；Kafka继续承载 Outbox 业务事件。

## 3. 最终状态机

### 3.1 订单状态

```text
PENDING_PAYMENT
    ├─ 支付成功 ─────────────────────────> PAID
    ├─ 客户取消/订单超时 ─> CANCELLING ─> CANCELLED
    └─ 单次支付失败、关闭或过期 ─────────> PENDING_PAYMENT（截止时间内允许重试）

PAID ─> CONFIRMED ─> SHIPPED ─> RECEIVED ─> COMPLETED
  │          │
  └──────────┴─> REFUNDING ─> REFUNDED
                         └─失败后恢复 PAID/CONFIRMED
```

### 3.2 状态语义

| 状态 | 含义 | 客户主要动作 | 商家主要动作 |
| --- | --- | --- | --- |
| `PENDING_PAYMENT` | 订单已创建，仍在整单支付期限内 | 支付、重试、主动取消 | 只读或不可见 |
| `CANCELLING` | 正在关闭渠道待支付交易 | 查看进度，禁止再次支付/取消 | 只读 |
| `CANCELLED` | 所有待支付交易已关闭，订单终止 | 查看取消原因和支付尝试 | 只读 |
| `PAID` | 已收到验签成功支付通知 | 查看支付、申请退款 | 确认订单 |
| `CONFIRMED` | 商家已接受订单 | 查看支付、申请退款 | 填写物流并发货 |
| `SHIPPED` | 已创建运单并发货 | 查看物流、复制单号、确认收货 | 更正物流信息 |
| `RECEIVED` | 客户确认或系统自动确认收货 | 查看支付/物流、评价 | 只读 |
| `COMPLETED` | 收货观察期结束，订单进入稳定终态 | 查看支付/物流、评价未评价商品 | 只读 |
| `REFUNDING` | 全额退款处理中 | 查看支付与退款进度 | 只读 |
| `REFUNDED` | 资金退款完成 | 查看原支付与退款记录 | 只读 |

## 4. 目标架构

```text
订单创建
  └─ ScheduledTaskPolicyResolver
       └─ 保存任务与策略快照
            ├─ DatabaseScheduledTaskTrigger
            ├─ RabbitScheduledTaskTrigger
            └─ RocketScheduledTaskTrigger
                     ↓
              ScheduledTaskExecutor
                     ↓
              原子抢占 + 统一业务 Handler
                     ↓
              订单事务 + Outbox
                     ↓
                    Kafka

Database Reconciliation
  └─ 宽限期后修复遗漏/卡住任务，不改变 primary_strategy
```

统一任务执行器按 `task_type` 路由到：

- `PAYMENT_TIMEOUT` -> `CancelUnpaidOrderService`
- `AUTO_RECEIVE` -> `AutoReceiveOrderService`
- `AUTO_COMPLETE` -> `CompleteOrderService`
- 后续 `REVIEW_REMINDER` -> 通知服务（本轮只预留任务类型，不实现通知渠道）

## 5. 数据库演进计划

已经发布的 Flyway 文件不得修改。商城后端从当前下一个版本新增迁移，建议按以下边界拆分：

### 5.1 订单生命周期迁移

为 `shop_orders` 增加：

```text
payment_due_at
cancelling_at
cancelled_at
cancel_reason
received_at
completed_at
logistics_delivered_at
```

关键索引：

```text
(status, payment_due_at)
(status, received_at)
(status, logistics_delivered_at)
```

### 5.2 定时任务与策略迁移

新增：

```text
scheduled_task_policies
scheduled_order_tasks
scheduled_task_attempts
```

`scheduled_order_tasks` 保存：

- `order_id`、`task_type`、`task_sequence`、`due_at`
- `primary_strategy`、`reconciliation_enabled`、`reconciliation_grace_seconds`
- `policy_id`、`policy_version`
- `status`、`message_id`、`claimed_by`、`lease_until`
- `last_trigger_source`、调度次数、执行次数、最后错误和版本

约束：

```text
UNIQUE(order_id, task_type, task_sequence)
```

### 5.3 商城物流迁移

新增：

```text
shipment_records
shipment_events
shipment_notifications
```

保存商城运单号、渠道运单号、承运商、物流单号、当前状态、轨迹节点、签名通知摘要和幂等通知 ID。`shop_orders.carrier/tracking_number` 保留为当前快照，物流明细以新表为准。

### 5.4 模拟物流平台迁移

独立 `logistics-simulator-service` 使用独立数据库和 Flyway `V1`，至少包含：

```text
logistics_merchants
channel_shipments
channel_shipment_events
callback_attempts
```

模拟物流服务不得直接访问商城数据库。

## 6. 分阶段实施

### 11-0：状态契约、配置绑定与文档基线

- [ ] 新增 `docs/ORDER-LIFECYCLE.md`，统一订单、支付、退款、物流、任务状态。
- [ ] 把六种完整配置绑定为 `@ConfigurationProperties`，包含全部 37 个字段。
- [ ] 增加 Duration、批次、并发、TTL 与策略枚举启动校验。
- [ ] 建立任务类型、主策略、触发来源和任务状态枚举。
- [ ] 单元测试覆盖六种组合绑定、默认值和非法配置。

验收：六种 YAML 均能绑定成一致对象；非法主策略、非正数批次、TTL 与订单期限冲突时启动失败并给出稳定错误。

计划提交：`docs: define order lifecycle and scheduling contracts`

### 11-1：统一定时任务领域模型

- [ ] 新增生命周期和任务表迁移。
- [ ] 实现策略解析器、任务创建、策略快照和策略版本。
- [ ] 实现原子抢占、处理租约、执行尝试和任务终态。
- [ ] 实现 `ScheduledTaskHandler` 路由，不包含具体 MQ 代码。
- [ ] 增加任务查询与管理员只读 API。

验收：同一任务重复创建只保留一条；重复抢占只有一个执行者成功；管理员修改策略后旧任务快照不变。

计划提交：`feat: add scheduled order task domain`

### 11-2：未支付订单主动取消与统一关单服务

- [ ] 新增 `POST /api/purchase-orders/{id}/cancel`。
- [ ] 实现 `PENDING_PAYMENT -> CANCELLING -> CANCELLED`。
- [ ] 查询并关闭订单全部 `PENDING` 渠道交易。
- [ ] 没有待支付交易时直接完成取消。
- [ ] 已支付订单拒绝普通取消并引导退款。
- [ ] 重复取消幂等返回原结果。
- [ ] 处理支付成功通知与取消并发：支付先成功则支付优先。
- [ ] 意外晚到成功支付写入资金对账异常，不能静默忽略。

验收：无支付单、有一笔/多笔待支付、已失败、已过期、已成功、重复取消和越权取消全部通过。

计划提交：`feat: add idempotent unpaid order cancellation`

### 11-3：Database 主触发与深度对账

- [ ] 结算时写 `payment_due_at` 并创建 `PAYMENT_TIMEOUT` 任务。
- [ ] 实现高频 `DatabaseDueTaskJob`，分批领取正常到期任务。
- [ ] 实现低频 `OrderExpirationReconciliationJob`。
- [ ] 对账覆盖过期 `SCHEDULED`、租约过期 `PROCESSING` 和长期 `CANCELLING`。
- [ ] 使用 ShedLock、数据库时间、批次限制和条件更新。
- [ ] 实现 Database + 对账关闭/开启两种组合。

验收：服务停机跨过截止时间后重启能够补关订单；多个应用实例不会重复关单；关闭对账后不会暗中运行深度补偿。

计划提交：`feat: add database order expiration scheduler`

### 11-4：RabbitMQ 延迟触发适配器

- [ ] 增加 Spring AMQP 和 RabbitMQ Compose Profile。
- [ ] 声明持久化延迟交换机/队列、TTL、DLX、Ready Queue。
- [ ] 实现发布 Outbox、Publisher Confirm 和未知结果重试。
- [ ] 实现手动 ACK、并发、Prefetch、重试和死信。
- [ ] MQ消费者只传递 `taskId/generation`，业务执行仍由统一服务完成。
- [ ] 实现 RabbitMQ + 对账关闭/开启两种组合。

验收：正常到期、重复消息、消费者宕机、Broker 重启、消息积压、死信和数据库宽限期补偿通过。

计划提交：`feat: add RabbitMQ delayed order expiration`

### 11-5：RocketMQ 原生延迟触发适配器

- [ ] 增加 RocketMQ 客户端与互斥 Compose Profile。
- [ ] 创建 Delay Topic、生产者组和消费者组。
- [ ] 使用 `due_at` 生成绝对投递时间戳。
- [ ] 实现发送结果、重新消费、死信和幂等抢占。
- [ ] 启动校验拒绝超过当前平台延迟上限的任务策略。
- [ ] 实现 RocketMQ + 对账关闭/开启两种组合。

验收：正常延迟、重复消费、Broker/消费者恢复、数据库补偿和超过延迟上限的配置拒绝通过。

计划提交：`feat: add RocketMQ delayed order expiration`

### 11-6：管理员任务策略配置与实验入口

- [ ] 新增系统默认、任务类型和商户级策略表。
- [ ] 实现优先级：实例实验覆盖 > 商户策略 > 任务类型默认 > 系统默认。
- [ ] Angular ADMIN 增加“定时任务策略”页面。
- [ ] 页面使用“主策略单选 + Database 对账开关”，不维护五个混合枚举。
- [ ] 只有对应适配器已启用并健康时，策略才能激活。
- [ ] 已 `SCHEDULED` 的任务禁止普通修改策略。
- [ ] 所有配置变更写审计记录。
- [ ] 提供稳定哈希灰度实验，但解析结果必须立即保存为任务快照。

验收：修改默认策略只影响新任务；旧任务继续使用原策略；无 RabbitMQ/RocketMQ Profile 时不能误启用对应策略。

计划提交：`feat: add scheduled task policy administration`

### 11-7：独立模拟物流服务

- [ ] 新建 `logistics-simulator-service`、独立数据库、Dockerfile 和健康检查。
- [ ] 实现签名创建运单、查询运单、推进状态和通知补发 API。
- [ ] 物流状态支持 `CREATED/PICKED_UP/IN_TRANSIT/AT_STATION/OUT_FOR_DELIVERY/DELIVERED/EXCEPTION`。
- [ ] 使用商户运单号保证创建幂等。
- [ ] 使用签名回调把物流结果送回商城。
- [ ] 商城验签、通知幂等、轨迹入库、Outbox 和对账。
- [ ] 商家发货改为创建商城物流单并调用模拟物流平台。
- [ ] 更正物流信息不能产生无变化事件，也不能覆盖历史轨迹。

验收：创建、重复创建、自动推进、通知丢失、重复通知、伪造通知、越权查询和物流异常通过。

计划提交：`feat: add signed mock logistics platform`

### 11-8：客户物流、自动收货与 COMPLETED

- [ ] 客户订单响应增加 `confirmedAt/shippedAt/receivedAt/completedAt`。
- [ ] 增加 `carrier/trackingNumber/logisticsStatus/shipmentEvents/orderEvents`。
- [ ] 新增 `GET /api/purchase-orders/{id}/logistics` 并强制客户归属。
- [ ] 客户手动确认收货写 `received_at` 并创建 `AUTO_COMPLETE` 任务。
- [ ] 物流 `DELIVERED` 创建 `AUTO_RECEIVE` 任务；到期仍为 `SHIPPED` 时自动收货。
- [ ] `RECEIVED` 到期后由系统进入 `COMPLETED`。
- [ ] `RECEIVED` 和 `COMPLETED` 都允许尚未评价的订单项提交一次评价。
- [ ] 七天类任务默认使用 Database；RocketMQ策略超过延迟上限时拒绝激活。

验收：手动收货、自动收货、自动完成、任务重复触发、状态已变化后的迟到任务和完成后评价均通过。

计划提交：`feat: complete logistics and order lifecycle`

### 11-9：客户页面必要功能

按当前页面继续向下展开，不做摘要和折叠：

- [ ] `PENDING_PAYMENT` 显示支付、刷新和取消订单。
- [ ] `CANCELLING` 禁用支付与重复取消并显示处理状态。
- [ ] `CANCELLED` 显示取消原因、取消时间和历史支付尝试。
- [ ] `SHIPPED/RECEIVED/COMPLETED` 始终显示物流公司、单号、复制按钮和完整轨迹。
- [ ] 任何存在支付记录的后续状态都继续显示支付信息。
- [ ] `RECEIVED/COMPLETED` 继续显示商品评价入口。
- [ ] 修正支付时间线在 `CONFIRMED/SHIPPED/RECEIVED/COMPLETED/REFUNDING/REFUNDED` 状态下错误回退的问题。
- [ ] 补齐所有新增状态、动作、错误和轨迹的中英文文案。

验收：状态变化不会让支付或物流信息消失；客户不能看到他人订单物流；桌面和窄屏可完成全部操作。视觉美化留到下一章。

计划提交：`feat: expose complete customer order lifecycle`

### 11-10：故障实验、双平台脚本与交付

- [ ] 新增 Windows/Bash 同名脚本：生命周期、取消、任务策略、RabbitMQ、RocketMQ、物流、自动完成。
- [ ] `verify-all` 按当前 Profile 选择对应验收，不要求默认环境同时启动两个 MQ。
- [ ] 增加 `expiration-database`、`expiration-rabbit`、`expiration-rocket` 和专用 `expiration-compare` Profile。
- [ ] 验证六种配置，每份完整字段均能绑定。
- [ ] 验证消息丢失、重复、积压、消费者宕机、Broker 重启、数据库补偿和迟到消息。
- [ ] 验证支付成功与取消竞态，以及物流/收货/完成任务竞态。
- [ ] 从空数据卷重建每个 Profile。
- [ ] 更新 README、API、架构、生产边界和实施记录。

验收：每种主策略至少有一套独立验收；组合四和组合六必须证明 MQ 故障后 Database 能补偿，MQ 恢复后的旧消息不会重复执行。

计划提交：`test: verify configurable order lifecycle scheduling`

## 7. 接口计划

### 7.1 客户订单

```http
POST /api/purchase-orders/{orderId}/cancel
GET  /api/purchase-orders/{orderId}/logistics
GET  /api/purchase-orders/{orderId}
```

### 7.2 管理员策略与任务

```http
GET  /api/admin/scheduled-task-policies
PUT  /api/admin/scheduled-task-policies/{taskType}
GET  /api/admin/scheduled-order-tasks
GET  /api/admin/scheduled-order-tasks/{taskId}
POST /api/admin/scheduled-order-tasks/{taskId}/retry
```

人工重试只能重新执行同一任务，不能创建第二条资金或关单副作用；所有操作必须鉴权并审计。

### 7.3 模拟物流平台

```http
POST /api/channel/shipments
GET  /api/channel/shipments?merchantShipmentNo=...
POST /api/channel/shipments/{merchantShipmentNo}/advance
POST /api/channel/shipments/{merchantShipmentNo}/notify
POST /api/logistics-notifications/callback
```

## 8. Docker 与运行模式

### 8.1 默认轻量环境

```text
primary-trigger=DATABASE
reconciliation.enabled=true
```

默认 `docker compose up` 不启动 RabbitMQ 和 RocketMQ，保证公开仓库克隆后的资源要求可控。

### 8.2 RabbitMQ 生产学习环境

```bash
docker compose --profile expiration-rabbit up --build -d --wait
```

启用 RabbitMQ，策略为 RabbitMQ 主触发 + Database 对账。

### 8.3 RocketMQ 高级实验环境

```bash
docker compose --profile expiration-rocket up --build -d --wait
```

启用 RocketMQ，策略为 RocketMQ 主触发 + Database 对账。

### 8.4 对比环境

```bash
docker compose --profile expiration-compare up --build -d --wait
```

允许两个 MQ 同时运行以执行不同订单/任务的对比实验，但同一任务仍只能保存一个主策略。该 Profile 不作为默认开发环境。

## 9. 测试与验收矩阵

### 9.1 单元与集成测试

- 六种配置绑定和校验。
- 策略解析优先级与快照。
- 任务唯一约束、原子抢占、租约恢复和最大尝试次数。
- 每个触发适配器的契约测试。
- 关单、支付成功、退款、物流、收货和完成状态转换。
- 回调验签、通知幂等和资源归属。

### 9.2 关键并发测试

- 客户取消与支付成功同时发生。
- 自动取消与支付回调同时发生。
- MQ消息与Database对账同时抢占。
- 商家发货与退款申请同时发生。
- 客户确认收货与自动收货同时发生。
- 手动收货后迟到的物流签收通知。
- 自动完成与客户评价同时发生。

### 9.3 故障测试

- RabbitMQ/RocketMQ 发布结果未知。
- MQ Broker 停止、重启和消息积压。
- 消费者处理后未 ACK。
- 应用抢占任务后宕机。
- 模拟物流回调丢失、重复和伪造。
- Kafka不可用时 Outbox 保留并恢复发布。
- 数据库对账关闭时不产生隐式补偿。

## 10. 可观测性

至少增加：

```text
commerceflow_scheduled_tasks_created_total{task_type,strategy}
commerceflow_scheduled_tasks_triggered_total{task_type,source}
commerceflow_scheduled_tasks_completed_total{task_type,strategy}
commerceflow_scheduled_tasks_failed_total{task_type,strategy}
commerceflow_scheduled_tasks_reconciled_total{task_type,primary_strategy}
commerceflow_scheduled_tasks_open{task_type,status}
commerceflow_order_cancellation_duration_seconds
commerceflow_logistics_notifications_total{status}
```

日志与审计必须能够回答：

- 任务计划使用哪个主策略和策略版本。
- 实际由哪个来源触发。
- 是否经过 Database 补偿。
- 谁修改了任务策略。
- 订单为什么取消、自动收货或自动完成。

指标标签不能包含订单号、任务 ID 等高基数值。

## 11. 实施顺序与依赖

```text
11-0 配置与状态契约
  -> 11-1 统一任务领域
      -> 11-2 统一关单服务
          -> 11-3 Database 基线
              ├-> 11-4 RabbitMQ
              ├-> 11-5 RocketMQ
              └-> 11-6 管理员策略

11-1 + 11-3
  -> 11-7 模拟物流
      -> 11-8 自动收货与完成
          -> 11-9 客户页面功能
              -> 11-10 全量验收与交付
```

RabbitMQ 与 RocketMQ 两个适配器可以在 Database 基线完成后分别开发，但合并前必须共享同一任务接口和验收契约。

## 12. 完成定义

只有同时满足以下条件，第 11 章才可标记完成：

- 六种组合均能通过完整配置启动和执行。
- 同一任务只有一个主策略，重复触发不会重复产生业务副作用。
- 管理员修改策略只影响新任务，旧任务保留策略快照。
- 客户主动取消和超时自动取消均正确关闭渠道待支付交易。
- 支付成功与取消竞态不会产生已收款却静默取消的订单。
- 模拟物流具有独立数据、签名协议、回调幂等和补偿。
- 客户能在已发货、已收货、已完成状态查看物流与支付信息。
- 自动收货和自动完成可以恢复、重放且不会越过非法状态。
- Windows、Bash、Docker空卷和三种主要 Profile 验收通过。
- 文档记录实际迁移版本、接口、限制、验证结果和最终提交号。

## 13. 实施记录模板

```text
日期：YYYY-MM-DD
阶段：11-x
变更：表、接口、状态机、适配器、前端或脚本
配置：使用的 primary-trigger 与 reconciliation 设置
验证：测试、脚本、故障实验和 Docker Profile
遗留：未完成项和生产边界
提交：Git 提交号；未提交时明确写“未提交”
```

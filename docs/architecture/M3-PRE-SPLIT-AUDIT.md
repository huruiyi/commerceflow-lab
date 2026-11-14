# M3.1 微服务拆分前置条件审计

## 1. 结论

当前模块化单体**尚不满足 M3.2 试拆条件**。代码已经按业务能力分包并具备基础层次门禁，但数据、
Mapper、同步调用和事件契约仍有明显共享边界。此结论不否定现有单体实现；它只表示不能把某个包直接复制为
独立服务并继续共享商城数据库。

本轮冻结的是审计基线，不是微服务实现：

- 39 张商城表均声明当前唯一责任模块；新增表必须同步声明所有者。
- 当前存在 41 组跨模块 Java 依赖、118 个导入引用，涉及 38 个源码文件。
- 其中 21 个引用直接指向其他模块的 `infrastructure`，属于拆分前必须消除的硬阻塞项。
- 新增的 `MicroserviceReadinessBaselineTest` 允许依赖减少，但禁止出现未经评审的新依赖方向、引用数量增长、
  跨模块基础设施引用增长或未声明所有者的新表。

## 2. 数据所有权

| 当前责任模块 | 表 | 拆分前注意事项 |
| --- | --- | --- |
| `identity` | `app_users`、`roles`、`user_roles`、`persistent_logins` | 其他模块广泛直接使用 `AppUser`；需改为调用方需要的身份快照或查询端口。 |
| `catalog` | `products`、`product_translations` | `products` 同时保存库存列，是 catalog/inventory 的混合聚合。 |
| `cart` | `carts`、`cart_items`、`customer_orders` | `customer_orders` 是早期模型；需确认保留、迁移或退役策略。 |
| `customer` | `shipping_addresses` | 下单同时读取地址并写订单地址快照，服务化后只能传快照，不能跨库读取。 |
| `ordering` | `shop_orders`、`order_items`、`order_shipping_addresses`、`order_events` | 当前是支付、退款、履约、物流、收货和评价的共享事实中心。 |
| `payment` | `payment_records`、`payment_merchant_accounts`、`payment_notifications`、`payment_events`、`payment_idempotency_locks`、`payment_reconciliation_issue`、`payment_outbox`、`kafka_consumed_event` | `kafka_consumed_event` 实际承担 Inbox 去重，但由支付 Mapper 管理，不能直接作为所有未来消费者的共享表。 |
| `refund` | `refund_records`、`refund_events`、`refund_notifications` | 直接锁定/更新支付记录并读取订单，尚未形成独立事务边界。 |
| `logistics` | `shipment_records`、`shipment_events`、`shipment_notifications` | 商城侧投影已有独立表，但仍直接读写订单并调用收货模块。 |
| `inventory` | `inventory_reservations`、`inventory_movements` | 可售、预占、已售数量仍位于 `products`，所有权尚未真正独立。 |
| `review` | `product_reviews` | 查询跨订单、商品和用户表，拆分时需要只读快照或组合查询边界。 |
| `notification` | `customer_notifications`、`notification_inbox`、`notification_delivery_outbox` | V1 命令支持进程内/Kafka 适配器、独立幂等消费和持久恢复；尚未独立 Schema。 |
| `scheduling` | `scheduled_task_policies`、`scheduled_tasks`、`scheduled_task_events`、`scheduled_task_policy_events`、`shedlock` | 调度记录可独立，但 Handler 仍同步调用 ordering/receipt。 |

`receipt` 与 `fulfillment` 当前没有自有表，分别直接修改 ordering 状态或组合 ordering、refund、logistics 数据；
它们是应用能力边界，不是已具备独立数据所有权的服务边界。

## 3. 跨模块依赖

### 3.1 当前规模

| 指标 | 当前值 |
| --- | ---: |
| 跨模块依赖方向 | 41 |
| 跨模块 import 引用 | 118 |
| 涉及源码文件 | 38 |
| 指向其他模块 `infrastructure` 的引用 | 21 |

引用最多的方向为 `ordering -> payment`（16）、`refund -> payment`（8）、
`fulfillment -> ordering`（7）、`cart -> catalog`（5）、`cart -> identity`（5）和
`ordering -> cart`（5）。完整方向和上限由自动基线测试维护。

### 3.2 直接基础设施耦合

当前跨模块 Mapper/基础设施依赖集中在以下边界：

- `cart -> catalog`：购物车和早期订单服务直接使用 `ProductMapper`。
- `customer -> cart`：地址删除规则直接查询 `CartMapper`。
- `catalog -> review`：商品目录直接使用 `ReviewMapper` 计算评价摘要。
- `ordering -> cart/payment/refund/scheduling`：下单、取消和支付编排直接访问四个模块的 Mapper。
- `payment -> ordering`：支付回调直接使用 `ShopOrderMapper`。
- `refund -> payment/ordering`：退款直接使用支付签名与订单 Mapper。
- `fulfillment -> ordering/refund/logistics`：商家履约直接组合三个模块的 Mapper。
- `receipt -> ordering/scheduling`：收货/完成直接操作订单和调度 Mapper。
- `logistics -> ordering`：物流通知和查询直接访问订单 Mapper。

这些依赖在单体事务中是有效实现，但在拆分后会变成跨库事务、远程锁或共享数据库。M3.2 前必须先以应用端口、
明确的命令或事件替换，且不能只把 Mapper 包装成远程 CRUD。

## 4. 应用接口与 HTTP 边界

商城当前有 17 个 Controller、57 个 HTTP 操作，面向浏览器、管理端以及支付/物流模拟平台。它们是现有外部
接口，不等同于稳定的内部服务契约。模块间主要注入具体 `*Service`、领域对象和 Mapper，尚未形成独立的
inbound/outbound port。

拆分前优先固化以下应用接口：

1. notification：V1 写入命令、查询接口、独立 Inbox、可替换异步适配器和恢复门禁已完成；下一步定义
   双写/投影核对窗口、停止条件和独立 Schema 迁移演练。
2. catalog/inventory：商品资料查询与库存预占/转正/释放分离，明确 `products` 中库存列的迁移归属。
3. ordering：订单状态查询、条件迁移和订单快照接口，不暴露 `ShopOrderMapper` 或跨服务行锁。
4. payment/refund：支付结果、退款请求及回调投影使用版本化命令/事件，不跨模块更新支付记录。
5. fulfillment/logistics/receipt：以履约命令和状态事件连接，保留重复、迟到和乱序保护。

## 5. 事件、Outbox 与 Inbox

### 5.1 已有能力

- `payment_outbox` 在业务事务中记录事件，后台发布到 5 个 Kafka topic：`payment-events`、
  `order-events`、`payment-callback-events`、`payment-reconciliation-events`、
  `merchant-notification-events`。
- Kafka 信封当前包含 `eventId`、`eventType`、`aggregateId` 和 `payload`。
- `kafka_consumed_event` 通过 `(consumer_group, event_id)` 唯一约束提供 Inbox 等价的幂等去重。
- `ORDER_CANCELLED` 消费处理在同一数据库事务中先登记 Inbox，再释放库存；集成测试覆盖重复信封只产生一次副作用。
- RabbitMQ/RocketMQ 定时任务消息包含 `taskId`、`taskType`、`dueAt`、`strategySnapshot`，现有专项覆盖重复、
  迟到、毒消息、DLQ、未确认重投和 Broker 故障恢复。

### 5.2 阻塞缺口

- Kafka 信封没有 `schemaVersion`、`occurredAt`、`producer`、`correlationId`、`causationId` 或追踪上下文。
- `payload` 是动态 JSON，没有按事件类型固定 schema，也没有兼容性策略或 schema registry。
- `kafka_consumed_event` 由支付模块 Mapper 维护；notification 已建立自己的 Inbox/Outbox，其他未来服务仍需独立。
- 现有测试主要验证本仓库生产者与消费者的当前实现，没有独立消费者契约、旧版本 fixture 或向后兼容测试。
- RabbitMQ 与 RocketMQ 使用两个结构相同但独立的消息 record，尚未声明统一版本化契约。

因此本轮只认定“已有可复用的可靠性机制”，不认定“已具备跨服务事件契约”。

## 6. 可观测性与分布式追踪

三个 Java 服务已有 Actuator、Prometheus 指标、告警规则和关键失败计数器，但当前后端没有
Micrometer Tracing/OpenTelemetry 依赖，没有 OTLP/Zipkin/Tempo 后端，也没有 HTTP、Kafka、RabbitMQ、
RocketMQ 跨进程 trace context 验收。RabbitMQ 的 publisher `CorrelationData` 仅用于发布确认，不能替代
业务关联 ID 或分布式 trace。

notification V1 已先行使用 W3C traceparent，并在 Kafka payload/Header 双通道传播校验；correlationId、
causationId 和 traceparent 最终保存到 notification Inbox。该证据只覆盖 notification Kafka 边界，不能冒充
HTTP、RabbitMQ、RocketMQ 的全系统追踪或可查询的追踪后端。

进入 M3.2 前至少需要：

- 统一 W3C Trace Context，并验证 HTTP 与三种消息中间件的上下文传播。
- 日志稳定输出 `traceId/spanId`，业务事件保留 `correlationId/causationId`。
- 建立本地追踪后端和一条“下单 → 支付 → 通知”跨边界验收脚本。
- 对采样、敏感字段、异步 span 生命周期和追踪后端故障降级作出明确约束。

## 7. M3.2 启动判定

`notification-service` 仍是首个候选，但只有以下条件全部满足才可开始试拆：

- [x] InventoryMapper 不再直接写 `customer_notifications`，通知写入通过 V1 命令进入。
- [x] notification application 不依赖共享 Session、`AppUser` 实体或商城 Mapper；查询只接收 customerId。
- [x] 拥有独立 Inbox、幂等键、消费者契约 fixture 和进程内重复回放测试。
- [x] 进程内/Kafka 可替换适配器具备 Outbox 重试、本地 DEAD/重放、Kafka DLT、Broker 恢复和重复幂等证据。
- [x] notification Kafka 边界具备 trace/correlation 传播和单体回退开关。
- [ ] 双写或投影核对窗口有明确停止条件，且不共享商城 Schema。

在这些条件完成前，下一步仍属于 M3.1 基线治理，而不是创建新服务或新数据库。

## 8. 自动基线

[`MicroserviceReadinessBaselineTest`](../../services/commerce-service/src/test/java/vip/fairy/commerceflow/commerce/architecture/MicroserviceReadinessBaselineTest.java)
已接入商城 Maven 测试：

- 从 Flyway 迁移读取 `CREATE TABLE`，当前 39 张表的任何新增/移除都必须同步评审所有权。
- 从 Java package/import 读取跨模块依赖；现有依赖可减少，不允许未经评审增加方向、引用或基础设施耦合。
- 当前基线为 41 组方向、118 个 import、38 个涉及文件和 21 个基础设施引用；基线不是白名单式“合格证明”，
  已记录的基础设施耦合仍是待消减债务。

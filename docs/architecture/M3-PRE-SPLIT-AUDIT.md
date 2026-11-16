# M3.1 微服务拆分前置条件审计

## 1. 结论

M3.1 最终门禁已经完成；M3.2 已将 `notification-service` 切换为默认运行的唯一通知读写实现，并补齐
HTTP、Kafka、RabbitMQ 和 RocketMQ 跨进程追踪。commerce 只保留订单事务侧 Outbox 和身份薄代理，
不保留 notification 本地读模型或数据库回退。
其他模块仍存在数据、Mapper 和同步调用共享边界，不能把任意业务包直接复制为独立服务并继续共享商城数据库。

本轮冻结的是审计基线，不是微服务实现：

- 48 张商城表均声明当前唯一责任模块；新增表必须同步声明所有者。
- 当前存在 39 组跨模块 Java 依赖、109 个导入引用，涉及 35 个源码文件。
- 其中 18 个引用直接指向其他模块的 `infrastructure`，属于拆分前必须消除的硬阻塞项。
- 新增的 `MicroserviceReadinessBaselineTest` 允许依赖减少，但禁止出现未经评审的新依赖方向、引用数量增长、
  跨模块基础设施引用增长或未声明所有者的新表。

## 2. 数据所有权

| 当前责任模块 | 表 | 拆分前注意事项 |
| --- | --- | --- |
| `identity` | `app_users`、`roles`、`user_roles`、`persistent_logins` | 其他模块广泛直接使用 `AppUser`；需改为调用方需要的身份快照或查询端口。 |
| `catalog` | `products`、`product_translations`、`catalog_event_outbox` | 只持有商品主数据、标准售价、翻译和变更事件；merchantId 是稳定标识，不使用 identity 数据库外键。 |
| `cart` | `carts`、`cart_items`、`customer_orders` | 两类明细均已固化商品快照；`customer_orders` 是早期模型，仍需确认长期保留或退役策略。 |
| `customer` | `shipping_addresses` | 下单同时读取地址并写订单地址快照，服务化后只能传快照，不能跨库读取。 |
| `ordering` | `shop_orders`、`order_items`、`order_shipping_addresses`、`order_events` | 当前是支付、退款、履约、物流、收货和评价的共享事实中心。 |
| `payment` | `payment_records`、`payment_merchant_accounts`、`payment_notifications`、`payment_events`、`payment_idempotency_locks`、`payment_reconciliation_issue`、`payment_outbox`、`kafka_consumed_event` | `kafka_consumed_event` 实际承担 Inbox 去重，但由支付 Mapper 管理，不能直接作为所有未来消费者的共享表。 |
| `refund` | `refund_records`、`refund_events`、`refund_notifications` | 直接锁定/更新支付记录并读取订单，尚未形成独立事务边界。 |
| `logistics` | `shipment_records`、`shipment_events`、`shipment_notifications` | 商城侧投影已有独立表，但仍直接读写订单并调用收货模块。 |
| `inventory` | `product_inventory`、`inventory_reservations`、`inventory_movements`、`inventory_command_inbox`、`inventory_catalog_event_inbox`、`inventory_command_delivery_outbox`、`inventory_command_result_inbox` | 独占可售、预占、已售数量、流水、命令/事件 Inbox 及当前投递协议证据；商品资料仅为版本化快照。 |
| `review` | `product_reviews` | 查询跨订单、商品和用户表，拆分时需要只读快照或组合查询边界。 |
| `notification` | commerce 仅运行 `notification_delivery_outbox`；独立 Schema 持有通知、Inbox 及历史切换证据表 | notification-service 是唯一运行读写实现；commerce 历史 Flyway 表保留但无运行代码访问。 |
| `scheduling` | `scheduled_task_policies`、`scheduled_tasks`、`scheduled_task_events`、`scheduled_task_policy_events`、`shedlock` | 调度记录可独立，但 Handler 仍同步调用 ordering/receipt。 |

`receipt` 与 `fulfillment` 当前没有自有表，分别直接修改 ordering 状态或组合 ordering、refund、logistics 数据；
它们是应用能力边界，不是已具备独立数据所有权的服务边界。

## 3. 跨模块依赖

### 3.1 当前规模

| 指标 | 当前值 |
| --- | ---: |
| 跨模块依赖方向 | 39 |
| 跨模块 import 引用 | 109 |
| 涉及源码文件 | 35 |
| 指向其他模块 `infrastructure` 的引用 | 18 |

引用最多的方向为 `ordering -> payment`（16）、`refund -> payment`（8）、
`fulfillment -> ordering`（7）、`cart -> catalog`（5）、`cart -> identity`（5）和
`ordering -> cart`（5）。完整方向和上限由自动基线测试维护。

### 3.2 直接基础设施耦合

当前跨模块 Mapper/基础设施依赖集中在以下边界：

- `customer -> cart`：地址删除规则直接查询 `CartMapper`。
- `ordering -> cart/payment/refund/scheduling`：下单、取消和支付编排直接访问四个模块的 Mapper。
- `payment -> ordering`：支付回调直接使用 `ShopOrderMapper`。
- `refund -> payment/ordering`：退款直接使用支付签名与订单 Mapper。
- `fulfillment -> ordering/refund/logistics`：商家履约直接组合三个模块的 Mapper。
- `receipt -> ordering/scheduling`：收货/完成直接操作订单和调度 Mapper。
- `logistics -> ordering`：物流通知和查询直接访问订单 Mapper。

这些依赖在单体事务中是有效实现，但在拆分后会变成跨库事务、远程锁或共享数据库。M3.2 前必须先以应用端口、
明确的命令或事件替换，且不能只把 Mapper 包装成远程 CRUD。

## 4. 应用接口与 HTTP 边界

商城当前有 21 个 Controller、67 个 HTTP 操作，面向浏览器、管理端、内部服务以及支付/物流模拟平台。它们是现有外部
接口，不等同于稳定的内部服务契约。模块间主要注入具体 `*Service`、领域对象和 Mapper，尚未形成独立的
inbound/outbound port。

拆分前优先固化以下应用接口：

1. notification：单一所有权切换已完成，独立服务是唯一通知读写实现，commerce 只保留 Outbox 和身份薄代理。
2. catalog/inventory：catalog 已完成独立 Schema 和单一所有权切换；inventory 已冻结命令 V1/Inbox、消费
   catalog 事件并移除 ordering/notification 与数据库外键耦合，下一步评审跨进程结果与补偿协议。
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

三个 Java 服务现已接入 OpenTelemetry，统一 W3C Trace Context、日志 `traceId/spanId`、采样和 OTLP
开关；Compose 提供可选 Tempo，Grafana 可查询 Trace。Windows 与容器化 Bash 已实测
commerce-service → logistics-simulator-service 的 HTTP 父子 Span，并在 Tempo 停机后确认业务健康。
默认 `TRACING_EXPORT_ENABLED=false`，Prometheus 继续承担指标采集，追踪后端不成为业务启动依赖。

Kafka Producer/Listener Observation 已开启，实测存在同一 Trace 下的 `send/process` Span。notification V1
保留 correlationId/causationId，并通过 `commerceflow-origin-traceparent` 保存业务来源上下文；Kafka
instrumentation 改写的传输 traceparent 经格式校验后进入 Inbox。合法的两位 W3C trace-flags 已纳入回归。

RabbitMQ 同时使用 Spring Observation 和显式 W3C 注入/提取，TTL 队列与 DLX 转发后仍保持上下文；
RocketMQ 使用 Micrometer Tracer/Propagator 在消息 properties 中传播。Windows 与容器化 Bash 均以
taskId 查询到同一 Trace 的 publish/process Span，并验证 Tempo 停机不影响商城健康。Span 仅记录消息系统
和 taskId，不记录密码、令牌、完整通知正文等敏感字段。

## 7. M3.2 启动判定

`notification-service` 仍是首个候选，但只有以下条件全部满足才可开始试拆：

- [x] InventoryMapper 不再直接写 `customer_notifications`，通知写入通过 V1 命令进入。
- [x] notification application 不依赖共享 Session、`AppUser` 实体或商城 Mapper；查询只接收 customerId。
- [x] 拥有独立 Inbox、幂等键、消费者契约 fixture 和进程内重复回放测试。
- [x] 进程内/Kafka 可替换适配器具备 Outbox 重试、本地 DEAD/重放、Kafka DLT、Broker 恢复和重复幂等证据。
- [x] notification Kafka 边界具备 trace/correlation 传播和单体回退开关。
- [x] 影子投影核对窗口具备最小样本、零差异、零阻塞 Outbox、连续通过和显式停止条件。
- [x] 独立 notification Schema 的 Flyway 迁移已在隔离 MySQL 中验证三张自有表、零外键和 Inbox 幂等。
- [x] 实际运行目标使用独立 Schema，并完成历史回填、实时镜像、读切换、数据库停机和商城回滚验收。
- [x] HTTP 与 Kafka 已具备可查询的跨进程 Trace、日志关联和追踪后端故障降级证据。
- [x] RabbitMQ/RocketMQ 已具备真实跨消息 Trace 和追踪后端故障降级证据。

M3.2 已完成 notification 单一所有权切换，M3.3 已完成 catalog 独立 Schema、数据交接和单一所有权切换。
M3.4 已建立 inventory application port、V1 命令及自有 Inbox，移除 ordering 外键和 notification 运行依赖，
完成 catalog V1 事件投影、命令 delivery/Result 协议、订单/退款等待态、履约阻断和人工恢复门禁。最终 CREATE
准入评审已冻结五表迁移、停写追平、唯一远程适配器、Trace 和失败回退手册；不接流量的独立 MySQL 影子服务
和可重复快照已落地，最终追平、故障演练和 CUTOVER 仍未完成。

## 8. 自动基线

[`MicroserviceReadinessBaselineTest`](../../services/commerce-service/src/test/java/vip/fairy/commerceflow/commerce/architecture/MicroserviceReadinessBaselineTest.java)
已接入商城 Maven 测试：

- 从商城 Flyway 迁移读取 `CREATE TABLE`，当前 48 张表的任何新增/移除都必须同步评审所有权；独立
  notification Schema 的迁移目录单独演练，不混入商城表所有权计数。
- 从 Java package/import 读取跨模块依赖；现有依赖可减少，不允许未经评审增加方向、引用或基础设施耦合。
- 当前实际为 39 组方向、109 个 import、35 个涉及文件和 18 个基础设施引用；基线不是白名单式“合格证明”，
  已记录的基础设施耦合仍是待消减债务。

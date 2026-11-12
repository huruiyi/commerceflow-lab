# 通用定时任务触发策略与数据库补偿（设计参考）

## 1. 文档状态

| 项目 | 内容 |
| --- | --- |
| 当前状态 | 通用任务/策略/Database 执行底座已实现；MQ 适配器和业务 Handler 待实现 |
| 适用任务 | 所有需要“在某个时间后幂等执行”的业务任务 |
| 主触发策略 | `DATABASE`、`RABBITMQ`、`ROCKETMQ` |
| 补偿机制 | 数据库对账，可独立开启或关闭 |
| 合理组合数 | `3 × 2 = 6` |
| 最后更新 | 2026-11-12 |

本文定义通用定时任务的可配置触发策略、数据库补偿职责、六种合理组合、策略快照和切换边界。订单支付超时只是本文的第一个完整配置示例，不是框架边界。当前 `commerceflow.scheduling`、数据库策略覆盖、任务快照、Database 扫描/对账/租约恢复和管理 API 已实现；本文后续 RabbitMQ、RocketMQ 拓扑和六组合示例仍是待实施设计，不能视为已经可用。

### 1.1 首批与可扩展任务场景

| 任务类型 | 到期条件 | 执行目标 |
| --- | --- | --- |
| `PAYMENT_TIMEOUT` | 订单超过 `payment_due_at` | 关闭待支付交易并取消订单 |
| `AUTO_RECEIVE` | 物流签收后观察期结束 | `SHIPPED -> RECEIVED` |
| `AUTO_COMPLETE` | 收货后观察期结束 | `RECEIVED -> COMPLETED` |
| `REFUND_RECONCILIATION` | 退款处理超过宽限期 | 查询渠道并修复退款投影 |
| `PAYMENT_RECONCILIATION` | 支付处理超过宽限期 | 查询渠道并修复支付投影 |
| `LOGISTICS_RECONCILIATION` | 物流长时间未更新 | 主动查询物流并补齐轨迹 |
| `NOTIFICATION_RETRY` | 通知下次重试时间到达 | 重发幂等回调/通知 |
| `REVIEW_REMINDER` | 收货后到达提醒时间 | 发布评价提醒事件 |
| `INVENTORY_RELEASE` | 预占库存超时 | 幂等释放未转正库存 |
| `AFTER_SALE_DEADLINE` | 售后申请期结束 | 关闭售后入口或转换工单状态 |

即时操作（如客户主动取消、支付成功回调、商家确认）不应为了复用框架而强制改成定时任务。

### 1.2 通用配置分层

```yaml
commerceflow:
  scheduling:
    default-policy:
      primary-trigger: DATABASE
      reconciliation-enabled: true

    task-policies:
      PAYMENT_TIMEOUT:
        primary-trigger: RABBITMQ
        reconciliation-enabled: true
      AUTO_RECEIVE:
        primary-trigger: DATABASE
        reconciliation-enabled: true
      AUTO_COMPLETE:
        primary-trigger: ROCKETMQ
        reconciliation-enabled: true
```

配置文件提供系统默认和初始任务类型策略；管理员后台在数据库中维护启用状态、任务类型覆盖、时间参数和策略版本。优先级为：

```text
创建任务时的显式覆盖
  > 管理员启用的任务类型策略
    > 配置文件任务类型策略
      > 系统默认策略
```

任务创建后必须保存解析后的策略和业务参数快照。管理员后续改配置只影响新任务，不得让旧任务在 Database、RabbitMQ 和 RocketMQ 之间中途切换。

## 2. 为什么拆成两个维度

任意定时任务在 `due_at` 到期后都需要被唤醒，再由对应 Handler 重新检查业务条件。这里实际存在两个独立问题：

1. **正常情况下由谁准时触发任务？** 这是 `primary-trigger`。
2. **正常机制遗漏或卡住后，是否允许数据库巡检修复？** 这是 `reconciliation.enabled`。

例如选择 RabbitMQ 主触发并开启数据库对账时，其完整配置见 [2.1 完整配置契约](#21-完整配置契约) 和 [组合四](#7-组合四rabbitmq-主触发启用数据库对账)。这个配置的含义是：

- RabbitMQ 是正常路径，负责在订单到期时发送延迟消息。
- 数据库对账是异常路径，只有超过两分钟宽限期仍未完成时才介入。
- 数据库补偿不会把任务的主策略从 RabbitMQ 改成 Database。
- 无论由谁触发，最终都调用同一个幂等关单服务。

因此，不应维护以下五个混合枚举：

```text
DATABASE
RABBITMQ
ROCKETMQ
RABBITMQ_WITH_DATABASE
ROCKETMQ_WITH_DATABASE
```

应拆成：

```text
primary-trigger = DATABASE | RABBITMQ | ROCKETMQ
reconciliation.enabled = true | false
```

以后增加新的主触发器时，不需要继续增加 `XXX_WITH_DATABASE` 枚举。

### 2.1 完整配置契约

六种组合使用完全相同的配置结构。每个示例都必须显式写出默认值，便于直接比较和复制；未被 `primary-trigger` 选中的适配器节点只作为配置快照保留，不建立连接、不声明队列，也不启动消费者。

```yaml
commerceflow:
  order-expiration:
    enabled: true
    payment-timeout: 30m
    primary-trigger: RABBITMQ

    task:
      claim-lease: 2m
      max-execute-attempts: 5

    database-scan:
      initial-delay: 10s
      fixed-delay: 15s
      batch-size: 100

    rabbitmq:
      delay-exchange: order.expiration.delay
      delay-routing-key: order.expiration.delay
      delay-queue: order.expiration.delay
      ready-exchange: order.expiration.ready
      ready-routing-key: order.expiration.ready
      ready-queue: order.expiration.ready
      message-ttl: 30m
      publisher-confirm-timeout: 5s
      consumer-concurrency: 2
      prefetch: 20

    rocketmq:
      name-server: localhost:9876
      topic: order-expiration-delay
      producer-group: commerceflow-order-expiration-producer
      consumer-group: commerceflow-order-expiration-consumer
      send-timeout: 5s
      consumer-concurrency: 2
      max-reconsume-times: 5

    reconciliation:
      enabled: true
      initial-delay: 30s
      grace-period: 2m
      fixed-delay: 5m
      batch-size: 100
      stuck-threshold: 5m
```

连接地址、用户名、密码、TLS 等中间件通用连接参数继续放在各客户端的标准配置节点或环境变量中；本节点只保存订单超时领域需要的拓扑与执行参数。例如 RabbitMQ 连接使用 `spring.rabbitmq.*`，RocketMQ 连接地址在本设计中通过 `rocketmq.name-server` 显式快照。

### 2.2 顶层与任务字段

| 字段 | 默认值 | 含义 |
| --- | --- | --- |
| `commerceflow` | 无 | CommerceFlow 自定义配置的统一根节点，与 Spring Boot 标准配置隔离。 |
| `commerceflow.order-expiration` | 无 | 订单超时调度领域的配置根节点，包含开关、主策略、适配器和对账配置。 |
| `commerceflow.order-expiration.enabled` | `true` | 是否启用订单超时任务体系。关闭后不创建新超时任务，也不启动任何主触发器或对账任务。 |
| `commerceflow.order-expiration.payment-timeout` | `30m` | 订单从创建到支付截止的默认时长，用于计算 `payment_due_at`。修改默认值只影响新订单。 |
| `commerceflow.order-expiration.primary-trigger` | `RABBITMQ` | 正常路径的唯一主触发器，可取 `DATABASE`、`RABBITMQ` 或 `ROCKETMQ`。 |
| `commerceflow.order-expiration.task` | 无 | 所有触发方式共享的任务执行、租约和重试配置节点。 |
| `commerceflow.order-expiration.task.claim-lease` | `2m` | 任务被执行器抢占后的处理租约。执行器宕机且租约过期后，对账任务才允许重新抢占。 |
| `commerceflow.order-expiration.task.max-execute-attempts` | `5` | 同一任务允许的最大业务执行次数。超过后转入人工处理或终态失败，不能无限重试。 |

### 2.3 Database 扫描字段

| 字段 | 默认值 | 含义 |
| --- | --- | --- |
| `database-scan` | 无 | Database 主触发器的高频正常扫描配置节点；不是异常对账。 |
| `database-scan.initial-delay` | `10s` | 应用启动后第一次执行正常到期扫描前的等待时间，避免迁移和依赖尚未就绪时立即运行。 |
| `database-scan.fixed-delay` | `15s` | 上一轮正常扫描结束到下一轮开始之间的间隔；只在 `primary-trigger=DATABASE` 时生效。 |
| `database-scan.batch-size` | `100` | 每轮最多领取的正常到期任务数，避免一次事务和数据库锁范围过大。 |

`database-scan` 是 Database 主策略的正常路径，不等同于 `reconciliation`。当主策略为 RabbitMQ 或 RocketMQ 时，这三个字段仍保留默认值，但正常扫描任务不启动。

### 2.4 RabbitMQ 字段

| 字段 | 默认值 | 含义 |
| --- | --- | --- |
| `rabbitmq` | 无 | RabbitMQ 延迟触发适配器的拓扑和消费配置节点。未被选为主策略时不生效。 |
| `rabbitmq.delay-exchange` | `order.expiration.delay` | 接收新建延迟任务消息的交换机。 |
| `rabbitmq.delay-routing-key` | `order.expiration.delay` | 把消息路由到延迟队列的 Routing Key。 |
| `rabbitmq.delay-queue` | `order.expiration.delay` | 保存尚未到期消息的持久化队列。 |
| `rabbitmq.ready-exchange` | `order.expiration.ready` | 消息 TTL 到期后通过死信机制进入的交换机。 |
| `rabbitmq.ready-routing-key` | `order.expiration.ready` | 把已到期消息路由到可消费队列的 Routing Key。 |
| `rabbitmq.ready-queue` | `order.expiration.ready` | 消费者实际监听的到期任务队列。 |
| `rabbitmq.message-ttl` | `30m` | 延迟队列消息 TTL，默认与订单支付期限一致。若以后允许每单不同期限，需要分桶或其他延迟实现。 |
| `rabbitmq.publisher-confirm-timeout` | `5s` | 等待 Broker 发布确认的最长时间。超时表示结果未知，应由 Outbox 重试，不能直接切换主策略。 |
| `rabbitmq.consumer-concurrency` | `2` | 到期任务消费者并发实例数。业务层仍通过原子抢占保证每条任务只执行一次。 |
| `rabbitmq.prefetch` | `20` | 每个消费者允许预取但尚未确认的最大消息数，限制单实例占用和故障重投范围。 |

这些字段只在 `primary-trigger=RABBITMQ` 时生效。RabbitMQ 的主机、端口、凭据和 TLS 使用 `spring.rabbitmq.*` 配置，不在这里重复定义。

### 2.5 RocketMQ 字段

| 字段 | 默认值 | 含义 |
| --- | --- | --- |
| `rocketmq` | 无 | RocketMQ 延迟触发适配器的连接、Topic、生产和消费配置节点。未被选为主策略时不生效。 |
| `rocketmq.name-server` | `localhost:9876` | RocketMQ NameServer 地址；部署环境可通过环境变量覆盖。 |
| `rocketmq.topic` | `order-expiration-delay` | 延迟任务 Topic，必须按 RocketMQ 版本要求声明为延迟消息类型。 |
| `rocketmq.producer-group` | `commerceflow-order-expiration-producer` | 订单超时延迟消息生产者组。 |
| `rocketmq.consumer-group` | `commerceflow-order-expiration-consumer` | 订单超时消息消费者组，同组实例共同分担任务。 |
| `rocketmq.send-timeout` | `5s` | 发送延迟消息等待 Broker 结果的最长时间。超时按未知结果处理并由 Outbox 重试。 |
| `rocketmq.consumer-concurrency` | `2` | 延迟消息消费并发数。原子抢占仍是业务唯一执行保护。 |
| `rocketmq.max-reconsume-times` | `5` | 消费失败后的最大重新消费次数，超过后进入死信或人工处理。 |

这些字段只在 `primary-trigger=ROCKETMQ` 时生效。订单 `payment_due_at` 会转换成消息的绝对投递时间戳。

### 2.6 数据库对账字段

| 字段 | 默认值 | 含义 |
| --- | --- | --- |
| `reconciliation` | 无 | 与主触发策略独立的数据库异常对账节点。开启时只负责补偿，不改变主策略。 |
| `reconciliation.enabled` | `true` | 是否启用数据库异常对账。它与主触发策略正交，不会改变任务保存的主策略。 |
| `reconciliation.initial-delay` | `30s` | 应用启动后第一次对账前的等待时间。 |
| `reconciliation.grace-period` | `2m` | 任务到期后留给主触发器的处理宽限期。宽限期内数据库不抢占 MQ 正常任务。 |
| `reconciliation.fixed-delay` | `5m` | 上一轮对账完成到下一轮开始之间的间隔。 |
| `reconciliation.batch-size` | `100` | 每轮最多检查或领取的异常任务数。 |
| `reconciliation.stuck-threshold` | `5m` | 订单或任务停在 `CANCELLING`、异常 `PROCESSING` 等中间状态多久后被视为卡住。任务租约已明确过期时可更早恢复。 |

当 `reconciliation.enabled=false` 时，其他对账字段仍显式保留，但对账任务不启动。持续保留完整字段可以保证六种组合的配置结构一致，并使配置差异只集中在 `primary-trigger` 和 `reconciliation.enabled`。

### 2.7 Duration 与数值约束

- 时间使用 Spring Boot Duration 格式，例如 `10s`、`2m`、`30m`。
- `batch-size`、并发数、`prefetch` 和最大重试次数必须大于零。
- `rabbitmq.message-ttl` 默认应与 `payment-timeout` 相等；不相等时应在启动校验中给出明确警告或拒绝启动。
- `reconciliation.grace-period` 应大于正常消息投递的容忍延迟，并小于业务允许的最长关单延迟。
- `task.claim-lease` 必须覆盖一次正常关单调用的最大预期耗时。

## 3. 六种合理组合总览

| 编号 | 主触发策略 | 数据库对账 | 正常路径 | 异常恢复 | 推荐用途 |
| --- | --- | --- | --- | --- | --- |
| 1 | `DATABASE` | 关闭 | 高频数据库到期扫描 | 依赖下一轮正常扫描 | 最小实现、扫描机制实验 |
| 2 | `DATABASE` | 开启 | 高频数据库到期扫描 | 低频深度对账修复卡住任务 | 无额外 MQ 的可靠模式 |
| 3 | `RABBITMQ` | 关闭 | RabbitMQ 延迟消息 | 依赖 MQ 重试和人工处理 | RabbitMQ 单机制实验 |
| 4 | `RABBITMQ` | 开启 | RabbitMQ 延迟消息 | 数据库宽限期后兜底 | Spring 生产学习推荐 |
| 5 | `ROCKETMQ` | 关闭 | RocketMQ 原生延迟消息 | 依赖 MQ 重试和人工处理 | RocketMQ 单机制实验 |
| 6 | `ROCKETMQ` | 开启 | RocketMQ 原生延迟消息 | 数据库宽限期后兜底 | 大量短周期定时任务实验 |

六种组合都可以作为独立实验运行，但关闭数据库对账的纯 MQ 模式不建议作为生产默认配置。

## 4. 组合一：Database 主触发，不启用深度对账

```yaml
commerceflow:
  order-expiration:
    enabled: true
    payment-timeout: 30m
    primary-trigger: DATABASE

    task:
      claim-lease: 2m
      max-execute-attempts: 5

    database-scan:
      initial-delay: 10s
      fixed-delay: 15s
      batch-size: 100

    rabbitmq:
      delay-exchange: order.expiration.delay
      delay-routing-key: order.expiration.delay
      delay-queue: order.expiration.delay
      ready-exchange: order.expiration.ready
      ready-routing-key: order.expiration.ready
      ready-queue: order.expiration.ready
      message-ttl: 30m
      publisher-confirm-timeout: 5s
      consumer-concurrency: 2
      prefetch: 20

    rocketmq:
      name-server: localhost:9876
      topic: order-expiration-delay
      producer-group: commerceflow-order-expiration-producer
      consumer-group: commerceflow-order-expiration-consumer
      send-timeout: 5s
      consumer-concurrency: 2
      max-reconsume-times: 5

    reconciliation:
      enabled: false
      initial-delay: 30s
      grace-period: 2m
      fixed-delay: 5m
      batch-size: 100
      stuck-threshold: 5m
```

### 4.1 工作方式

`DatabaseDueTaskJob` 每十五秒扫描一次已经到期的 `SCHEDULED` 任务，使用条件更新抢占任务，再调用统一关单服务。

```text
数据库到期扫描
  -> 原子抢占任务
  -> 关闭渠道待支付交易
  -> 订单进入 CANCELLED
  -> 写订单事件和 Outbox
```

### 4.2 优点

- 不增加新的消息中间件。
- 服务停机后重启仍能找到所有过期任务。
- 容易学习索引、批处理、条件更新、租约和 ShedLock。
- 适合中小规模订单或本地开发环境。

### 4.3 风险

- 触发精度受扫描间隔影响。
- 大量到期任务会增加数据库扫描压力。
- 如果任务已经进入 `PROCESSING`，处理实例随后宕机，普通到期扫描可能不会修复它。
- 缺少专门的深度对账，异常任务需要人工处理或依赖额外逻辑。

### 4.4 适用范围

适合最小功能实现和单独观察数据库扫描行为，不建议作为最终生产配置。

## 5. 组合二：Database 主触发，启用数据库深度对账

```yaml
commerceflow:
  order-expiration:
    enabled: true
    payment-timeout: 30m
    primary-trigger: DATABASE

    task:
      claim-lease: 2m
      max-execute-attempts: 5

    database-scan:
      initial-delay: 10s
      fixed-delay: 15s
      batch-size: 100

    rabbitmq:
      delay-exchange: order.expiration.delay
      delay-routing-key: order.expiration.delay
      delay-queue: order.expiration.delay
      ready-exchange: order.expiration.ready
      ready-routing-key: order.expiration.ready
      ready-queue: order.expiration.ready
      message-ttl: 30m
      publisher-confirm-timeout: 5s
      consumer-concurrency: 2
      prefetch: 20

    rocketmq:
      name-server: localhost:9876
      topic: order-expiration-delay
      producer-group: commerceflow-order-expiration-producer
      consumer-group: commerceflow-order-expiration-consumer
      send-timeout: 5s
      consumer-concurrency: 2
      max-reconsume-times: 5

    reconciliation:
      enabled: true
      initial-delay: 30s
      grace-period: 2m
      fixed-delay: 5m
      batch-size: 100
      stuck-threshold: 5m
```

### 5.1 为什么两个任务都访问数据库

它们职责不同：

| 任务 | 频率 | 处理目标 |
| --- | --- | --- |
| `DatabaseDueTaskJob` | 高频，例如 15 秒 | 正常到期且状态为 `SCHEDULED` 的任务 |
| `OrderExpirationReconciliationJob` | 低频，例如 5 分钟 | 长期未处理、租约过期、订单与支付状态不一致的异常任务 |

深度对账至少检查：

- 到期很久仍为 `SCHEDULED` 的任务。
- `PROCESSING` 但 `lease_until` 已经过期的任务。
- 长时间停在 `CANCELLING` 的订单。
- 订单已取消但仍存在 `PENDING` 支付记录。
- 支付平台已经关闭但商城订单仍未取消。

### 5.2 优点

- 不依赖新的 MQ，也具备较完整的自动恢复能力。
- 容易从停机、执行中断和数据库锁竞争中恢复。
- 是其他 MQ 模式的可靠性基线和兜底实现。

### 5.3 风险

- 正常扫描和对账查询都需要合理索引和批次限制。
- 高订单量下需要分片、`SKIP LOCKED` 或任务表分区等扩展。
- 精度仍由扫描频率决定。

### 5.4 适用范围

推荐作为项目第一阶段的完整实现，也是没有延迟消息中间件时的生产候选方案。

## 6. 组合三：RabbitMQ 主触发，不启用数据库对账

```yaml
commerceflow:
  order-expiration:
    enabled: true
    payment-timeout: 30m
    primary-trigger: RABBITMQ

    task:
      claim-lease: 2m
      max-execute-attempts: 5

    database-scan:
      initial-delay: 10s
      fixed-delay: 15s
      batch-size: 100

    rabbitmq:
      delay-exchange: order.expiration.delay
      delay-routing-key: order.expiration.delay
      delay-queue: order.expiration.delay
      ready-exchange: order.expiration.ready
      ready-routing-key: order.expiration.ready
      ready-queue: order.expiration.ready
      message-ttl: 30m
      publisher-confirm-timeout: 5s
      consumer-concurrency: 2
      prefetch: 20

    rocketmq:
      name-server: localhost:9876
      topic: order-expiration-delay
      producer-group: commerceflow-order-expiration-producer
      consumer-group: commerceflow-order-expiration-consumer
      send-timeout: 5s
      consumer-concurrency: 2
      max-reconsume-times: 5

    reconciliation:
      enabled: false
      initial-delay: 30s
      grace-period: 2m
      fixed-delay: 5m
      batch-size: 100
      stuck-threshold: 5m
```

### 6.1 工作方式

订单创建时发送延迟消息。第一版可以使用固定 TTL 队列加死信交换机，到期后消息进入可消费队列。

```text
创建订单
  -> 延迟队列保存消息
  -> 消息到期进入 ready queue
  -> 消费者原子抢占任务
  -> 统一关单服务
```

### 6.2 优点

- 到期后通常能够较快触发。
- Spring Boot 与 Spring AMQP 集成直接。
- 可以学习 Publisher Confirm、Consumer ACK、TTL、DLX、重试和死信。
- 固定三十分钟支付期限很适合 TTL 队列。

### 6.3 风险

- 发布失败、路由错误、消息丢失或消费者长期停机会造成任务遗漏。
- 不同单条 TTL 可能产生队首阻塞，需要固定期限或分桶队列。
- 没有数据库兜底时，恢复能力取决于 RabbitMQ 配置、重试和人工运维。

### 6.4 适用范围

适合隔离验证 RabbitMQ 行为和故障实验，不建议作为生产默认配置。

## 7. 组合四：RabbitMQ 主触发，启用数据库对账

```yaml
commerceflow:
  order-expiration:
    enabled: true
    payment-timeout: 30m
    primary-trigger: RABBITMQ

    task:
      claim-lease: 2m
      max-execute-attempts: 5

    database-scan:
      initial-delay: 10s
      fixed-delay: 15s
      batch-size: 100

    rabbitmq:
      delay-exchange: order.expiration.delay
      delay-routing-key: order.expiration.delay
      delay-queue: order.expiration.delay
      ready-exchange: order.expiration.ready
      ready-routing-key: order.expiration.ready
      ready-queue: order.expiration.ready
      message-ttl: 30m
      publisher-confirm-timeout: 5s
      consumer-concurrency: 2
      prefetch: 20

    rocketmq:
      name-server: localhost:9876
      topic: order-expiration-delay
      producer-group: commerceflow-order-expiration-producer
      consumer-group: commerceflow-order-expiration-consumer
      send-timeout: 5s
      consumer-concurrency: 2
      max-reconsume-times: 5

    reconciliation:
      enabled: true
      initial-delay: 30s
      grace-period: 2m
      fixed-delay: 5m
      batch-size: 100
      stuck-threshold: 5m
```

### 7.1 工作方式

```text
10:30 RabbitMQ 正常投递
  -> 原子抢占并关单

10:32 数据库检查两分钟前已经到期但仍未完成的任务
  -> RabbitMQ 已完成：跳过
  -> RabbitMQ 遗漏：数据库抢占并补偿
```

数据库对账使用宽限期，避免与 MQ 在正常到期时刻频繁竞争。即使两边同时到达，也必须通过任务状态条件更新保证只有一方执行。

### 7.2 优点

- RabbitMQ 提供及时触发。
- 数据库提供消息丢失、积压、消费者停机和处理中断的恢复能力。
- Spring 生态集成成本相对较低。
- 能完整学习延迟消息与生产补偿设计。

### 7.3 风险

- 比纯数据库模式多一套中间件和监控。
- 必须保证消息重复投递和数据库重复扫描不会重复执行关单副作用。
- 必须配置持久化、发布确认、消费确认、死信和积压告警。

### 7.4 适用范围

这是当前项目推荐的生产学习模式。

## 8. 组合五：RocketMQ 主触发，不启用数据库对账

```yaml
commerceflow:
  order-expiration:
    enabled: true
    payment-timeout: 30m
    primary-trigger: ROCKETMQ

    task:
      claim-lease: 2m
      max-execute-attempts: 5

    database-scan:
      initial-delay: 10s
      fixed-delay: 15s
      batch-size: 100

    rabbitmq:
      delay-exchange: order.expiration.delay
      delay-routing-key: order.expiration.delay
      delay-queue: order.expiration.delay
      ready-exchange: order.expiration.ready
      ready-routing-key: order.expiration.ready
      ready-queue: order.expiration.ready
      message-ttl: 30m
      publisher-confirm-timeout: 5s
      consumer-concurrency: 2
      prefetch: 20

    rocketmq:
      name-server: localhost:9876
      topic: order-expiration-delay
      producer-group: commerceflow-order-expiration-producer
      consumer-group: commerceflow-order-expiration-consumer
      send-timeout: 5s
      consumer-concurrency: 2
      max-reconsume-times: 5

    reconciliation:
      enabled: false
      initial-delay: 30s
      grace-period: 2m
      fixed-delay: 5m
      batch-size: 100
      stuck-threshold: 5m
```

### 8.1 工作方式

创建订单时将 `payment_due_at` 转换为投递时间戳，发送 RocketMQ 延迟消息。Broker 到达指定时间后才让消费者读取。

### 8.2 优点

- 延迟消息是原生消息类型，模型比 TTL + DLX 更直接。
- 适合大量短周期定时任务。
- 可以学习延迟消息、消费重试、顺序消息和事务消息。
- 三十分钟待支付订单超时符合其使用场景。

### 8.3 风险

- 部署和运维成本高于当前项目直接增加 RabbitMQ。
- Spring Boot 核心自动配置没有 RabbitMQ 那样直接，需要额外生态集成。
- RocketMQ 5 延迟消息默认最大范围为二十四小时，不适合直接承担七天自动收货或自动完成任务。
- 没有数据库兜底时仍然存在消息或消费异常导致任务遗漏的风险。

### 8.4 适用范围

适合 RocketMQ 专项学习和大量订单定时任务实验，不建议关闭对账后直接作为生产默认配置。

## 9. 组合六：RocketMQ 主触发，启用数据库对账

```yaml
commerceflow:
  order-expiration:
    enabled: true
    payment-timeout: 30m
    primary-trigger: ROCKETMQ

    task:
      claim-lease: 2m
      max-execute-attempts: 5

    database-scan:
      initial-delay: 10s
      fixed-delay: 15s
      batch-size: 100

    rabbitmq:
      delay-exchange: order.expiration.delay
      delay-routing-key: order.expiration.delay
      delay-queue: order.expiration.delay
      ready-exchange: order.expiration.ready
      ready-routing-key: order.expiration.ready
      ready-queue: order.expiration.ready
      message-ttl: 30m
      publisher-confirm-timeout: 5s
      consumer-concurrency: 2
      prefetch: 20

    rocketmq:
      name-server: localhost:9876
      topic: order-expiration-delay
      producer-group: commerceflow-order-expiration-producer
      consumer-group: commerceflow-order-expiration-consumer
      send-timeout: 5s
      consumer-concurrency: 2
      max-reconsume-times: 5

    reconciliation:
      enabled: true
      initial-delay: 30s
      grace-period: 2m
      fixed-delay: 5m
      batch-size: 100
      stuck-threshold: 5m
```

### 9.1 工作方式

- RocketMQ 负责订单到期后的正常延迟投递。
- 数据库只扫描超过宽限期仍未完成的任务。
- 两个来源调用同一关单服务。
- 原子抢占保证业务只执行一次。
- 数据库补偿不改变任务保存的 `ROCKETMQ` 主策略。

### 9.2 优点

- 原生延迟投递与数据库恢复能力结合。
- 适合研究高吞吐订单超时处理。
- 能覆盖消息积压、Broker 重启、消费者停机和补偿恢复实验。

### 9.3 风险

- 基础设施和客户端集成更复杂。
- 仍需为超过二十四小时的任务选择数据库或专业调度平台。
- 必须统一消息幂等、数据库租约、监控和故障审计。

### 9.4 适用范围

适合高级生产对比实验。如果团队生产环境已经采用 RocketMQ，也可以成为正式方案。

## 10. 不属于合理主策略组合的方式

### 10.1 RabbitMQ 与 RocketMQ 同时作为同一任务的主触发器

不建议让同一条 `PAYMENT_TIMEOUT` 任务同时发送到 RabbitMQ 和 RocketMQ：

- 两边都会保存一条未到期消息。
- 故障定位时难以确认哪边应该负责。
- 切换和撤销需要处理两套消息生命周期。
- 增加重复触发、积压和资源成本，却没有比“一个 MQ + DB 兜底”更清晰的可靠性收益。

RabbitMQ 与 RocketMQ 可以同时存在于专门的对比环境，但每条任务只能选择其中一个作为 `primary_strategy`。

### 10.2 Database、RabbitMQ 和 RocketMQ 同时作为三个主触发器

数据库正常扫描、RabbitMQ 和 RocketMQ 三路同时竞争不是组合模式，而是重复调度。正确结构是：

```text
一个主触发器
  +
一个数据库异常对账器
```

### 10.3 Kafka 作为普通延迟触发器

Kafka 在本项目中继续负责 Outbox 业务事件，例如 `ORDER_PAYMENT_EXPIRED` 和 `ORDER_CANCELLED`。Kafka 核心模型不提供与 RocketMQ 相同的原生计划投递能力；自建时间轮或重投 Topic 可以作为高级实验，但不进入本章的正式主策略枚举。

## 11. 统一任务与原子抢占

建议新增统一任务表：

```text
scheduled_order_tasks
```

关键字段：

```text
id
order_id
task_type
task_sequence
due_at
primary_strategy
reconciliation_enabled
reconciliation_grace_seconds
policy_id
policy_version
status
message_id
claimed_by
lease_until
last_trigger_source
schedule_attempts
execute_attempts
last_error
version
created_at
updated_at
```

任务状态：

```text
CREATED -> SCHEDULED -> PROCESSING -> SUCCEEDED
                  |          |
                  |          +-> FAILED / 重新进入 SCHEDULED
                  +------------> CANCELLED
```

所有触发器都必须执行类似的原子抢占：

```sql
UPDATE scheduled_order_tasks
SET status = 'PROCESSING',
    claimed_by = :worker,
    lease_until = :leaseUntil,
    last_trigger_source = :source,
    version = version + 1
WHERE id = :taskId
  AND status = 'SCHEDULED'
  AND due_at <= CURRENT_TIMESTAMP;
```

只有影响一行的触发器可以继续执行。其他重复消息或数据库扫描直接确认并退出。

## 12. 策略快照与管理员配置

管理员页面分成两个独立区域：

```text
订单超时主触发方式
  ○ 数据库扫描
  ● RabbitMQ 延迟消息
  ○ RocketMQ 延迟消息

安全补偿
  ☑ 启用数据库对账兜底
  宽限期：2 分钟
  对账间隔：5 分钟
```

配置解析优先级建议为：

```text
任务显式实验策略
  > 商户/租户策略
  > 任务类型默认策略
  > 系统默认策略
```

任务创建时把解析结果写入任务表：

```text
primary_strategy = RABBITMQ
reconciliation_enabled = true
policy_version = 3
```

以后管理员把系统默认值改成 RocketMQ：

- 已创建任务继续使用保存的 RabbitMQ 策略。
- 新创建任务使用 RocketMQ。
- 已创建任务不会在运行过程中静默迁移消息系统。
- 如需迁移，必须使用带任务代次、旧消息失效和审计记录的显式迁移流程。

数据库补偿成功时记录：

```text
primary_strategy = RABBITMQ
last_trigger_source = DATABASE_RECONCILIATION
```

这表示 RabbitMQ 仍是计划主策略，但本次由数据库完成故障恢复，不代表策略已经切换。

## 13. 按任务类型选择策略

策略应固定在具体定时任务上，而不是让整张订单永久绑定一个消息中间件：

| 任务类型 | 时间范围 | 推荐主策略 |
| --- | --- | --- |
| `PAYMENT_TIMEOUT` | 约 30 分钟 | RabbitMQ、RocketMQ 或 Database |
| `AUTO_RECEIVE` | 物流签收后约 7 天 | Database |
| `AUTO_COMPLETE` | 收货后约 7 天 | Database |
| `REFUND_RECONCILIATION` | 周期性对账 | Database |

同一条任务从创建到完成固定使用原主策略；同一订单在不同生命周期阶段创建的不同任务，可以选择不同的主策略。

## 14. 推荐环境配置

### 14.1 本地最小环境

使用[组合二](#5-组合二database-主触发启用数据库深度对账)的完整配置：`primary-trigger=DATABASE`、`reconciliation.enabled=true`。不启动新的 MQ，优先验证业务正确性；RabbitMQ 和 RocketMQ 字段仍保留默认值但不生效。

### 14.2 Spring 生产学习环境

使用[组合四](#7-组合四rabbitmq-主触发启用数据库对账)的完整配置：`primary-trigger=RABBITMQ`、`reconciliation.enabled=true`。学习 TTL、DLX、确认机制、重复投递和数据库兜底。

### 14.3 RocketMQ 高级实验环境

使用[组合六](#9-组合六rocketmq-主触发启用数据库对账)的完整配置：`primary-trigger=ROCKETMQ`、`reconciliation.enabled=true`。学习原生延迟投递、大量到期任务和 Broker 故障恢复。

Docker Compose 建议使用互斥 Profile：

```bash
docker compose --profile expiration-database up
docker compose --profile expiration-rabbit up
docker compose --profile expiration-rocket up
```

默认环境不同时启动 RabbitMQ 和 RocketMQ。专门的对比验收环境可以同时启动两者，但每条任务仍只有一个主触发策略。

## 15. 验收矩阵

每一种组合至少验证：

1. 到期任务能够进入统一关单服务。
2. 未到期任务不能提前执行。
3. 重复消息和重复扫描只执行一次。
4. 支付成功与关单并发时不会取消已支付订单。
5. 执行实例宕机后，租约过期任务能够恢复。
6. 开启对账时能够修复主触发器遗漏。
7. 关闭对账时不会暗中运行数据库补偿。
8. 管理员修改默认策略只影响新任务。
9. 已创建任务始终保留原策略快照。
10. Outbox 和 Kafka 只在状态实际变化后发布一次业务事件。

组合四和组合六还需要分别停止 RabbitMQ、RocketMQ，确认数据库在宽限期后完成补偿；恢复 MQ 后到达的旧消息必须被幂等跳过。

## 16. 业务大流程与任务策略的关系

### 16.1 核心结论

不能简单地把整个“订单支付流程”“订单取消流程”或“订单评价流程”配置成 Database、RabbitMQ 或 RocketMQ。一个大流程通常同时包含同步命令、外部调用、定时任务、异步事件和异常对账，只有其中的**定时任务**适合在三种主触发策略之间选择。

| 步骤类型 | 典型场景 | 推荐机制 |
| --- | --- | --- |
| 同步命令 | 创建支付、客户取消、提交评价 | HTTP + 数据库事务 |
| 外部调用或通知 | 调用支付平台、支付回调、物流回调 | 签名 HTTP |
| 定时任务 | 支付超时、自动收货、自动完成、评价提醒 | Database、RabbitMQ 或 RocketMQ 可配置 |
| 异步业务事件 | 订单已支付、已取消、已完成 | Transactional Outbox + Kafka |
| 异常对账 | 取消中卡住、消息遗漏、状态不一致 | Database 定时扫描 |

因此策略选择粒度是：

```text
一条具体的 scheduled task
```

而不是：

```text
整张订单
整个业务大流程
大流程中的所有步骤
```

### 16.2 场景一：订单支付流程

```text
1：订单支付流程
    1.1：创建支付单
    1.2：调用支付平台
    1.3：等待支付结果
    1.4：支付超时检查
    1.5：接收支付回调
    1.6：发布支付成功事件
    1.7：支付状态对账
```

| 步骤 | 类型 | 执行机制 | 是否选择三种主策略 |
| --- | --- | --- | --- |
| 1.1 创建支付单 | 同步命令 | 数据库事务和幂等键 | 否 |
| 1.2 调用支付平台 | 外部调用 | 签名 HTTP | 否 |
| 1.3 等待支付结果 | 状态等待 | 数据库保存 `PENDING` | 否 |
| 1.4 支付超时检查 | 定时任务 | `PAYMENT_TIMEOUT` | **是** |
| 1.5 接收支付回调 | 外部通知 | HMAC/RSA 验签回调 | 否 |
| 1.6 发布支付成功事件 | 异步事件 | Outbox + Kafka | 否 |
| 1.7 支付状态对账 | 异常恢复 | Database 扫描 | 否，固定为对账机制 |

真正参与本章策略选择的是 `PAYMENT_TIMEOUT`。它被触发后不能直接把订单改成 `CANCELLED`，而是调用统一取消服务关闭所有待支付渠道交易，并处理支付成功与取消并发。

### 16.3 场景二：订单取消流程

```text
2：订单取消流程
    2.1：客户申请取消，或 PAYMENT_TIMEOUT 触发自动取消
    2.2：订单进入 CANCELLING
    2.3：关闭渠道待支付交易
    2.4：订单进入 CANCELLED
    2.5：发布订单取消事件
    2.6：取消异常对账
```

| 步骤 | 类型 | 执行机制 | 是否选择三种主策略 |
| --- | --- | --- | --- |
| 2.1 客户主动申请取消 | 同步命令 | HTTP + 数据库事务 | 否，立即执行 |
| 2.1 支付超时自动取消 | 定时任务入口 | 复用 `PAYMENT_TIMEOUT` 策略 | **是** |
| 2.2 进入取消中 | 状态转换 | 条件更新 `PENDING_PAYMENT -> CANCELLING` | 否 |
| 2.3 关闭渠道交易 | 外部调用 | 支付平台签名 HTTP | 否 |
| 2.4 完成取消 | 状态转换 | 数据库事务和订单事件 | 否 |
| 2.5 发布取消事件 | 异步事件 | Outbox + Kafka | 否 |
| 2.6 修复取消中任务 | 异常对账 | Database 扫描 | 否，固定为对账机制 |

客户主动取消与自动取消必须进入同一个 `CancelUnpaidOrderService`。区别只在入口：客户点击是同步入口，超时取消由定时任务入口触发。二者不能各自维护一套状态机。

### 16.4 场景三：订单评价流程

```text
3：订单评价流程
    3.1：检查订单和商品是否可评价
    3.2：客户提交评价
    3.3：保存评价并刷新商品评分
    3.4：发布评价事件
    3.5：收货后仍未评价的提醒
```

| 步骤 | 类型 | 执行机制 | 是否选择三种主策略 |
| --- | --- | --- | --- |
| 3.1 检查评价资格 | 同步校验 | Database | 否 |
| 3.2 提交评价 | 同步命令 | HTTP + 数据库事务 | 否 |
| 3.3 更新评分 | 数据变更 | Database + Redis 缓存失效 | 否 |
| 3.4 发布评价事件 | 异步事件 | Outbox + Kafka | 否 |
| 3.5 未评价提醒 | 定时任务 | `REVIEW_REMINDER` | **是** |

客户提交评价不能因为管理员选择 RocketMQ 就变成异步提交。只有未来的“收货三天后提醒评价”属于可配置定时任务。

### 16.5 同一订单使用多个策略是否冲突

同一订单可以在不同生命周期阶段创建不同定时任务，每条任务保存自己的策略快照：

| 订单 | 任务 ID | 任务类型 | 主策略 | 作用 |
| --- | --- | --- | --- | --- |
| `ORD-10001` | `101` | `PAYMENT_TIMEOUT` | `RABBITMQ` | 三十分钟未支付时触发取消检查 |
| `ORD-10001` | `205` | `AUTO_RECEIVE` | `DATABASE` | 物流签收超过期限后自动确认收货 |
| `ORD-10001` | `309` | `REVIEW_REMINDER` | `ROCKETMQ` | 收货后仍未评价时发送提醒 |
| `ORD-10001` | `412` | `AUTO_COMPLETE` | `DATABASE` | 收货观察期结束后自动完成订单 |

这不是一条任务同时使用多个主策略，而是四条独立任务各自使用最合适的策略。必须保证：

- 同一条任务从创建到完成只有一个 `primary_strategy`。
- 同一条任务的策略和 `policy_version` 在创建时形成快照。
- 管理员修改默认配置只影响以后创建的新任务。
- Database 对账补偿不算主策略切换。

### 16.6 管理员按任务类型配置

管理员不应配置“整个支付流程使用 RabbitMQ”，而应配置具体任务类型：

| 任务类型 | 默认主策略 | 数据库补偿 | 建议原因 |
| --- | --- | --- | --- |
| `PAYMENT_TIMEOUT` | `RABBITMQ` | 开启 | 三十分钟固定延迟，RabbitMQ 及时触发，Database 兜底 |
| `AUTO_RECEIVE` | `DATABASE` | 开启 | 约七天长周期，数据库任务更容易查询和调整 |
| `AUTO_COMPLETE` | `DATABASE` | 开启 | 约七天长周期，需要可靠恢复和人工修正 |
| `REVIEW_REMINDER` | `ROCKETMQ` | 开启 | 用于延迟消息专项实验；超过平台延迟上限时改用 Database |
| `REFUND_RECONCILIATION` | `DATABASE` | 开启 | 周期性状态检查，本身就是数据库对账任务 |

配置解析优先级保持为：

```text
任务实例显式实验策略
  > 商户/租户任务策略
  > 任务类型默认策略
  > 系统默认策略
```

普通客户不能选择基础设施策略。管理员的任务实例覆盖只用于教学实验、灰度和故障验证，并且必须在任务进入 `SCHEDULED` 之前确定。

### 16.7 后续配置结构演进

当前 `commerceflow.order-expiration` 专注于 `PAYMENT_TIMEOUT`。当自动收货、自动完成和评价提醒加入后，建议把完整配置契约提升为任务类型策略映射：

```text
commerceflow.scheduling.task-policies.PAYMENT_TIMEOUT
commerceflow.scheduling.task-policies.AUTO_RECEIVE
commerceflow.scheduling.task-policies.AUTO_COMPLETE
commerceflow.scheduling.task-policies.REVIEW_REMINDER
```

每个任务类型仍复用本文定义的完整字段：`primary-trigger`、`task`、`database-scan`、`rabbitmq`、`rocketmq` 和 `reconciliation`。不能因为某个适配器当前未启用就省略默认字段；完整字段说明继续以第 2 节为准。

## 17. 官方能力参考

- [Spring Boot AMQP / RabbitMQ](https://docs.spring.io/spring-boot/reference/messaging/amqp.html)
- [RabbitMQ TTL](https://www.rabbitmq.com/docs/ttl)
- [RabbitMQ Dead Letter Exchanges](https://www.rabbitmq.com/docs/dlx)
- [RocketMQ Delay Message](https://rocketmq.apache.org/docs/featureBehavior/02delaymessage/)
- [Apache Kafka Introduction](https://kafka.apache.org/documentation/)

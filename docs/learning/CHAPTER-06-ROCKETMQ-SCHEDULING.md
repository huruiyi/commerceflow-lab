# 06：RocketMQ 5.x 延时任务与数据库补偿

## 学习目标

本章使用订单支付超时作为可运行案例，学习 RocketMQ 5.x 的 Delay Topic、毫秒级绝对投递时间、gRPC Java Client、消费重试与死信，以及应用数据库如何承担发布重试、策略快照、原子领取和异常对账。

完成本章后，应能解释以下问题：

1. 为什么 RocketMQ 5.x 延时消息必须发送到 `message.type=DELAY` 的 Topic。
2. 为什么 `setDeliveryTimestamp` 使用绝对时间戳，而不是“延迟几秒”的字符串。
3. Broker 确认发送成功，为什么仍不等于业务只执行一次。
4. 为什么消费失败应该交给 Broker 重试，而重复消息必须由业务幂等处理。
5. 为什么数据库事实、任务快照和条件更新仍然不可省略。
6. Broker 停机时，Database 对账如何兜底但不改变任务原来的 `ROCKETMQ` 策略快照。

## 推荐学习顺序

1. 先读官方[领域模型](https://rocketmq.apache.org/docs/domainModel/01main/)、[Topic](https://rocketmq.apache.org/docs/domainModel/02topic/) 和[消息模型](https://rocketmq.apache.org/docs/domainModel/05message/)，建立 Producer、Topic、Broker、Consumer Group 和消息生命周期概念。
2. 按官方 [Docker Compose 快速开始](https://rocketmq.apache.org/docs/quickStart/03quickstartWithDockercompose/)理解 NameServer、Broker 与 Proxy；再对照本项目 `compose.yaml` 的 `rocketmq` Profile。
3. 阅读官方[延时消息](https://rocketmq.apache.org/docs/featureBehavior/02delaymessage/)，重点关注 Delay Topic、绝对时间戳、消息生命周期和限制。
4. 阅读 [RocketMQ 5.x Java Client](https://github.com/apache/rocketmq-clients/blob/master/java/README.md)及官方[延时消息示例](https://github.com/apache/rocketmq-clients/blob/master/java/client/src/main/java/org/apache/rocketmq/client/java/example/ProducerDelayMessageExample.java)，再看本项目生产者和消费者实现。
5. 运行正常延时关单实验，观察 Broker 回执、任务审计和订单状态变化。
6. 阅读官方[发送重试](https://rocketmq.apache.org/docs/featureBehavior/05sendretrypolicy/)和[消费重试/DLQ](https://rocketmq.apache.org/docs/featureBehavior/10consumerretrypolicy/)，运行 Broker 停机与 Database 对账实验。
7. 最后阅读本项目[通用定时任务策略](../reference/SCHEDULED-TASK-STRATEGIES.md)，比较 Database、RabbitMQ、RocketMQ 三种主触发与对账开关的六种组合。

## 本项目版本与架构

| 组件 | 本项目选择 | 学习重点 |
| --- | --- | --- |
| RocketMQ Server | `apache/rocketmq:5.4.0` | NameServer、Broker、Proxy、本地持久化卷 |
| Java Client | `org.apache.rocketmq:rocketmq-client-java:5.2.2` | RocketMQ 5.x 基于 gRPC 的统一客户端 |
| 客户端入口 | `rocketmq-broker:8081` | Java Client 连接 Proxy endpoint，不直接使用 NameServer 地址 |
| Topic | `commerceflow-scheduled-task` | 初始化时写入 `+message.type=DELAY` |
| Consumer Group | `commerceflow-scheduled-task-consumer` | 同组实例分担消息；业务幂等仍由数据库条件更新保证 |
| Tag | `SCHEDULED_TASK` | 订阅过滤和任务消息分类 |

本项目没有使用旧版 `DefaultMQProducer` 的固定延迟等级，也没有使用非官方 Spring Starter 封装。这样可以直接学习 5.x Java Client 的 `ClientServiceProvider`、`Producer`、`PushConsumer`、`MessageView` 和 `ConsumeResult`。

## 源码阅读顺序

| 顺序 | 文件 | 关注点 |
| --- | --- | --- |
| 1 | [`RocketMqTaskTriggerProperties.java`](../../services/commerce-service/src/main/java/lab/commerceflow/storefront/configuration/RocketMqTaskTriggerProperties.java) | endpoint、Topic、Group、Tag、超时和发布扫描配置 |
| 2 | [`RocketMqTaskTriggerConfiguration.java`](../../services/commerce-service/src/main/java/lab/commerceflow/storefront/configuration/RocketMqTaskTriggerConfiguration.java) | gRPC 客户端、Producer、PushConsumer 和 Tag 订阅 |
| 3 | [`RocketMqTaskTriggerPublisher.java`](../../services/commerce-service/src/main/java/lab/commerceflow/storefront/scheduling/RocketMqTaskTriggerPublisher.java) | `dueAt` 转毫秒时间戳、Key、Tag、消息体和 Broker 回执 |
| 4 | [`RocketMqTaskTriggerDispatcher.java`](../../services/commerce-service/src/main/java/lab/commerceflow/storefront/scheduling/RocketMqTaskTriggerDispatcher.java) | 从数据库扫描未发布任务、发送失败记录和重启补发 |
| 5 | [`RocketMqTaskTriggerConsumer.java`](../../services/commerce-service/src/main/java/lab/commerceflow/storefront/scheduling/RocketMqTaskTriggerConsumer.java) | 消息校验、数据库重读、早到重投、重试/DLQ 返回值 |
| 6 | [`ScheduledTaskExecutor.java`](../../services/commerce-service/src/main/java/lab/commerceflow/storefront/scheduling/ScheduledTaskExecutor.java) | 原子领取、租约、统一 Handler、成功/失败审计 |
| 7 | [`ScheduledTaskMapper.java`](../../services/commerce-service/src/main/java/lab/commerceflow/storefront/mapper/ScheduledTaskMapper.java) | 策略快照、发布状态和只有一个触发者能够成功的条件更新 |

消息只携带 `taskId/taskType/dueAt/strategySnapshot`。消费者不会信任消息中的完整业务快照，而是重新读取数据库中的任务与订单事实。RocketMQ 提供“何时唤醒”，数据库决定“现在是否还能执行”。

## 正常延时关单实验

PowerShell：

```powershell
$env:SCHEDULING_ROCKETMQ_ENABLED='true'
$env:ORDER_PAYMENT_TIMEOUT='6s'
docker compose --profile rocketmq up -d --build --wait rocketmq-topic-init commerce-service commerce-portal-angular
./scripts/windows/verify-rocketmq-scheduling.ps1 -MaxWaitSeconds 40
```

Bash：

```bash
SCHEDULING_ROCKETMQ_ENABLED=true ORDER_PAYMENT_TIMEOUT=6s \
  docker compose --profile rocketmq up -d --build --wait rocketmq-topic-init commerce-service commerce-portal-angular
bash ./scripts/linux/verify-rocketmq-scheduling.sh http://localhost:14200 40
```

验收脚本会临时把 `PAYMENT_TIMEOUT` 策略改为 `ROCKETMQ`，创建待支付订单，然后验证：

- 订单到期后进入 `CANCELLED`。
- 任务状态为 `SUCCEEDED`。
- `primaryTriggerSnapshot=ROCKETMQ` 且 `lastTriggerSource=ROCKETMQ`。
- 审计链包含 `TASK_TRIGGER_PUBLISHED`、`TASK_CLAIMED` 和 `TASK_SUCCEEDED`。
- 脚本结束时恢复原策略，不清空数据库或消息卷。

## Broker 停机与 Database 对账实验

故障实验需要缩短请求超时和对账周期：

```powershell
$env:SCHEDULING_ROCKETMQ_ENABLED='true'
$env:SCHEDULING_ROCKETMQ_REQUEST_TIMEOUT='1s'
$env:SCHEDULING_RECONCILIATION_INITIAL_DELAY='1s'
$env:SCHEDULING_RECONCILIATION_FIXED_DELAY='1s'
$env:ORDER_PAYMENT_TIMEOUT='6s'
docker compose --profile rocketmq up -d --build --wait rocketmq-topic-init commerce-service commerce-portal-angular
./scripts/windows/verify-rocketmq-reconciliation.ps1 -MaxWaitSeconds 45
```

```bash
SCHEDULING_ROCKETMQ_ENABLED=true \
SCHEDULING_ROCKETMQ_REQUEST_TIMEOUT=1s \
SCHEDULING_RECONCILIATION_INITIAL_DELAY=1s \
SCHEDULING_RECONCILIATION_FIXED_DELAY=1s \
ORDER_PAYMENT_TIMEOUT=6s \
  docker compose --profile rocketmq up -d --build --wait rocketmq-topic-init commerce-service commerce-portal-angular
bash ./scripts/linux/verify-rocketmq-reconciliation.sh http://localhost:14200 45
```

脚本会停止并最终恢复 Broker。预期结果是：

- 任务累计发布尝试次数并保存最后一次发布错误。
- 到达 `dueAt + gracePeriod` 后，由 `DATABASE_RECONCILIATION` 原子领取并完成任务。
- `primaryTriggerSnapshot` 始终保持 `ROCKETMQ`，说明这是异常补偿而不是策略切换。
- Broker 恢复后不会让已经完成的任务再次进入发布扫描。

## 重试、重复消息与毒消息

- 发送重试只能缓解瞬时网络或 Broker 故障，最终发送失败仍必须由应用记录并重试。本项目使用数据库的 `trigger_published_at`、`trigger_publish_attempts` 和 `trigger_last_error` 保存这一事实。
- PushConsumer 返回 `ConsumeResult.FAILURE` 后由 Broker 按策略重新投递；超过最大次数后进入对应 Consumer Group 的 DLQ。
- RocketMQ 保证的是至少一次投递，不是业务恰好一次。重复消息、Database 对账和迟到消息都调用同一个 `ScheduledTaskExecutor`，最终由数据库条件更新竞争领取。
- 格式错误或无法解析的消息不能返回成功掩盖问题；本项目返回失败，使其进入重试和 DLQ 路径。
- `verify-mq-scheduling-idempotency` 会重放已完成任务的旧消息并注入非法 JSON：旧消息只增加 ignored 指标，任务仍只领取/完成一次；毒消息经 2 次有限重试后进入 `%DLQ%commerceflow-scheduled-task-consumer`。
- 消费者已取得消息、业务尚未 ACK 时进程退出的窄窗口已完成 RabbitMQ/RocketMQ 实机验收；详见 [06A：MQ 未确认消息与重新投递](CHAPTER-06A-MQ-UNACKED-REDELIVERY.md)。

专项实验：

```powershell
$env:SCHEDULING_RABBITMQ_ENABLED='true'
$env:SCHEDULING_ROCKETMQ_ENABLED='true'
$env:ORDER_PAYMENT_TIMEOUT='6s'
docker compose --profile rabbitmq --profile rocketmq up -d --build --wait rabbitmq-broker rocketmq-topic-init commerce-service commerce-portal-angular
./scripts/windows/verify-mq-scheduling-idempotency.ps1 -MaxWaitSeconds 60
```

RocketMQ Profile 初始化 Consumer Group 时使用 `retryMaxTimes=2` 和 `CUSTOMIZED [1000,2000]` 重试间隔，目的是让本地毒消息实验可在几十秒内完成；生产值必须结合业务恢复时间、消息堆积和告警流程重新设计。

## 延时上限的版本差异

Apache RocketMQ 当前官方页面对最大延时范围存在不一致：

- [消息域模型](https://rocketmq.apache.org/docs/domainModel/05message/)把 Scheduled time 的最大持续时间写为 40 天。
- [延时消息专题](https://rocketmq.apache.org/docs/featureBehavior/02delaymessage/)仍写默认最大范围为 24 小时且不可修改。

因此不要仅凭一个文档数字设计长周期业务。应以实际部署版本、Broker/Proxy 配置和实机边界实验为准，并在升级时重新验证。本项目的 RocketMQ 验收使用秒级任务；约七天的 `AUTO_RECEIVE`、`AUTO_COMPLETE` 默认继续使用 Database，避免把业务正确性依赖在未经当前部署验证的最大延时范围上。

## 官方资料推荐

| 优先级 | 资料 | 推荐理由 |
| --- | --- | --- |
| 必读 | [Domain Model](https://rocketmq.apache.org/docs/domainModel/01main/) | 先建立生产、存储、消费三个阶段的整体模型 |
| 必读 | [Topic](https://rocketmq.apache.org/docs/domainModel/02topic/) | 理解 5.x Topic 消息类型约束和业务隔离 |
| 必读 | [Message](https://rocketmq.apache.org/docs/domainModel/05message/) | 理解 ID、Key、Tag、时间戳、重试次数等字段 |
| 必读 | [Delay Message](https://rocketmq.apache.org/docs/featureBehavior/02delaymessage/) | 延时消息机制、Topic 创建、生命周期、限制与示例 |
| 上手 | [Docker Compose Quick Start](https://rocketmq.apache.org/docs/quickStart/03quickstartWithDockercompose/) | 理解本地 NameServer、Broker、Proxy 拓扑 |
| 上手 | [Java Client README](https://github.com/apache/rocketmq-clients/blob/master/java/README.md) | 官方 5.x gRPC Java Client 的依赖和基础用法 |
| 上手 | [ProducerDelayMessageExample](https://github.com/apache/rocketmq-clients/blob/master/java/client/src/main/java/org/apache/rocketmq/client/java/example/ProducerDelayMessageExample.java) | 与本项目 `setDeliveryTimestamp` 实现直接对应 |
| 可靠性 | [Sending Retry and Throttling](https://rocketmq.apache.org/docs/featureBehavior/05sendretrypolicy/) | 理解发送超时、重试和重复消息风险 |
| 可靠性 | [Consumption Retry](https://rocketmq.apache.org/docs/featureBehavior/10consumerretrypolicy/) | 理解 PushConsumer 失败、重投和 DLQ 状态机 |
| 运维 | [Parameter Constraints](https://rocketmq.apache.org/docs/introduction/03limits/) | 检查消息大小、请求超时、重试次数和消费线程建议 |
| 版本 | [RocketMQ Clients Releases](https://github.com/apache/rocketmq-clients/releases) | 升级前核对 Java Client 最新发布与变更说明 |

## 生产边界

本项目的单 Broker Compose Profile 用于学习协议、代码和故障语义，不代表生产高可用集群。生产环境还需要多副本、磁盘与容量规划、鉴权和 TLS、监控告警、DLQ 运维流程、版本兼容验证、时钟同步、跨可用区演练，以及业务侧幂等和人工补偿机制。

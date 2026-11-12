# 06A：MQ 未确认消息、进程退出与重新投递

## 1. 学习目标

本实验专门覆盖一个比“重复发送”更窄的故障窗口：Broker 已把消息交给消费者，但消费者还没有返回成功确认时，进程突然退出。

需要区分三件事：

1. **Broker 重新投递**：未确认消息必须重新变为可消费状态，不能因为客户端断开而丢失。
2. **应用业务幂等**：重新投递不等于业务可以重复执行；数据库条件更新仍只能产生一次有效领取和一次成功。
3. **重新投递不等于重新发布**：恢复后由原 Broker 消息继续投递，应用发布器不应另造一条消息来掩盖问题。

## 2. 本项目中的确认边界

| 适配器 | 消费方法返回前 | 消费方法成功返回后 |
| --- | --- | --- |
| RabbitMQ | Spring AMQP 自动确认尚未发送，消息保持 Unacked | 容器向 Broker 确认成功消费 |
| RocketMQ PushConsumer | 消息处于 Inflight，监听器尚未返回 `ConsumeResult` | 返回 `SUCCESS` 后消费提交 |

`MqConsumerCrashLab` 是默认关闭的教学故障注入点。只有同时设置 `enabled=true` 和精确 `taskId` 才会生效；它在消息校验、数据库任务重读完成后，任务领取和 Broker 确认之前暂停。正常生产路径不会随机休眠。

配置只供本地专项实验使用：

```text
SCHEDULING_MQ_CRASH_LAB_ENABLED=false
SCHEDULING_MQ_CRASH_LAB_TASK_ID=1
SCHEDULING_MQ_CRASH_LAB_PAUSE=5m
```

## 3. 实验步骤

Windows：

```powershell
docker compose build backend
./scripts/windows/verify-mq-scheduling-redelivery.ps1 -MaxWaitSeconds 120
```

Linux/Bash：

```bash
docker compose build backend
bash ./scripts/linux/verify-mq-scheduling-redelivery.sh http://localhost:14200 120
```

脚本会依次执行 RabbitMQ 和 RocketMQ：

1. 创建带对应策略快照的 `PAYMENT_TIMEOUT` 任务，并等待 Broker 确认发布。
2. 针对该任务开启故障注入，等待 `TASK_DELIVERY_PAUSED_BEFORE_ACK` 审计事件。
3. 使用零秒宽限停止 backend，制造消费者进程退出。
4. 关闭故障注入并重建 backend，等待同一消息由 Broker 重新投递。
5. 断言发布事件数量在崩溃前后不变，且 `TASK_CLAIMED`、`TASK_SUCCEEDED` 和订单取消各只有一次。
6. 恢复原策略和原环境配置；脚本不清空数据卷。

RabbitMQ 分桶 TTL 可能在精确 `dueAt` 前触发，因此同一任务在真正到期前可能有多条合法的 `TASK_TRIGGER_PUBLISHED`。验收不假定发布次数必为 1，而是记录崩溃前的发布次数，并确认重启后没有新增发布事件。

## 4. 已验证证据与边界

2026-11-12 Windows Docker 实机结果：

- RabbitMQ 任务 `239`：暂停后终止 backend，恢复后由 `RABBITMQ` 领取并成功，领取/成功各一次。
- RocketMQ 任务 `240`：暂停后终止 backend，经过 Broker 的 Inflight 超时/重试窗口后由 `ROCKETMQ` 领取并成功，领取/成功各一次。
- 两条订单都只进入一次 `CANCELLED`，任务 `attempts=1`。
- 容器化 Bash 脚本也已实机通过，对应 RabbitMQ/RocketMQ 任务为 `241/242`，断言与 Windows 版本一致。

这证明的是当前 Docker 版本、当前客户端和当前脚本下的恢复行为，不等同于长时间网络分区、Broker 集群切换、跨地域灾备或无限消息堆积验收。

## 5. 推荐学习资料

| 资料 | 阅读重点 |
| --- | --- |
| [RabbitMQ AMQP 0-9-1 Model](https://www.rabbitmq.com/tutorials/amqp-concepts) | 消费者未确认即断开时，Broker 为什么重新投递 |
| [RabbitMQ Consumers](https://www.rabbitmq.com/docs/consumers) | Unacked、Prefetch、确认超时和消费者容量 |
| [RocketMQ Consumption Retry](https://rocketmq.apache.org/docs/featureBehavior/10consumerretrypolicy/) | PushConsumer 的 Ready、Inflight、WaitingRetry、Commit 和 DLQ 状态 |
| [RocketMQ Consumer Types](https://rocketmq.apache.org/docs/featureBehavior/06consumertype/) | PushConsumer 与 SimpleConsumer 的确认及不可见时间差异 |

推荐阅读顺序：先看 RabbitMQ 的显式确认模型，再看 RocketMQ PushConsumer 的 Inflight 状态机，最后对照本项目两个 Consumer 和统一 `ScheduledTaskExecutor`。

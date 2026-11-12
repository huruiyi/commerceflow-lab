# CommerceFlow Lab 文档中心

本目录按“现状、代办、接口、专题参考、学习材料、历史归档”分工。不要在多份文档里同时维护同一份勾选清单。

## 主入口

| 文档 | 职责 | 更新规则 |
| --- | --- | --- |
| [IMPLEMENTED.md](IMPLEMENTED.md) | 已实现能力、验证边界和已知限制 | 只写有代码和验证证据的事实 |
| [TODO.md](TODO.md) | 唯一未实现代办和实施顺序 | 所有未完成任务只在这里勾选 |
| [API.md](API.md) | 已有 API 学习索引 | 接口落地后再加入 |

## 专题参考 `reference/`

- [高级模拟支付](reference/ADVANCED-PAYMENT.md)：已实现的支付架构、安全与验收记录。
- [退款实现说明](reference/REFUND.md)：已实现首版退款的状态机、协议和限制。
- [通用定时任务策略](reference/SCHEDULED-TASK-STRATEGIES.md)：六种触发/补偿组合、任务类型策略、快照和完整配置参考；执行清单以 `TODO.md` 为准。
- [独立模拟物流](MOCK-LOGISTICS.md)：服务边界、签名协议、运单状态机、投影一致性和验收说明。
- [Redis 业务边界](reference/REDIS-BUSINESS.md)：Key、降级和数据一致性边界。

## 学习材料 `learning/`

- [05A-2：独立多渠道模拟支付](learning/CHAPTER-05A-2.md)
- [05A-3：签名、验签与防重放](learning/CHAPTER-05A-3.md)
- [05A-5～05A-8：可靠事件、收银台、高可用与验收](learning/CHAPTER-05A-5-TO-8.md)
- [06：RocketMQ 5.x 延时任务与数据库补偿](learning/CHAPTER-06-ROCKETMQ-SCHEDULING.md)：官方资料阅读顺序、源码映射、正常链路与 Broker 停机实验。
- [06A：MQ 未确认消息与重新投递](learning/CHAPTER-06A-MQ-UNACKED-REDELIVERY.md)：RabbitMQ/RocketMQ 在消费者已取消息、未确认时退出的故障注入、重投和业务幂等。
- [06B：Flyway 空库与旧库升级](learning/CHAPTER-06B-FLYWAY-LIFECYCLE.md)：隔离 MySQL 中的全量迁移、目标版本升级、历史数据回填和非法状态防护。

## 历史归档 `archive/`

- `TEMP-ORDER-LIFECYCLE-DRAFT.md`：本轮订单改造的原始问题草案，已合并到 `TODO.md`。
- `ORDER-LIFECYCLE-IMPLEMENTATION-PLAN.md`：被 `TODO.md` 取代的旧实施计划。

归档文档用于回顾决策过程，不代表当前实现状态，也不再维护。

# 06B：Flyway 空库、旧库升级与状态防护

## 1. 学习目标

本实验把“应用能启动”拆成三个可单独证明的问题：

1. 新环境能否从空 Schema 按顺序建立到当前版本。
2. 已停在旧版本的数据库能否保留历史数据并完成回填。
3. 数据库约束与 Java 领域服务能否共同拒绝非法状态。

它们分别对应“可重建”、“可升级”和“升级后仍保持业务不变式”。

## 2. 实验设计

```text
临时 MySQL A（空库）
    -> Flyway migrate V1..V24
    -> 校验当前表与 schema history

临时 MySQL B（旧库）
    -> Flyway target=16
    -> 插入旧版待支付订单
    -> Flyway migrate V17..V24
    -> 校验 payment_due_at = created_at + 30m
    -> 插入非法任务状态，期待 CHECK 约束拒绝
```

脚本固定 `redgate/flyway:12.4.0`，与当前 Spring Boot 后端打包的 Flyway 版本一致。两个 MySQL 容器和专用网络都使用随机名称，不连接现有 Compose MySQL，也不挂载开发数据卷。

## 3. 运行方式

Windows：

```powershell
./scripts/windows/verify-flyway-lifecycle.ps1
```

Linux/Bash：

```bash
bash ./scripts/linux/verify-flyway-lifecycle.sh
```

领域层非法转换由 `CommerceFlowIntegrationTest.illegalOrderStateTransitionsAreRejectedWithoutChangingFacts` 覆盖，可通过构建后端执行：

```powershell
docker compose build commerce-service
```

## 4. 为什么要同时测数据库和领域层

| 层次 | 适合防护 | 不适合单独承担 |
| --- | --- | --- |
| MySQL 约束 | 枚举范围、非负数、唯一性、外键 | “只有当前用户且当前订单未支付才能取消”这类跨表规则 |
| Java 领域服务 | 身份、当前状态、资金事实和幂等规则 | 防止绕过应用直接写入不合法枚举值 |

因此本项目用 `CHECK` 约束保护任务状态的有限集，同时在 Service 中保护订单转换。`PAID` 订单不能再发起普通支付、取消或确认收货；自动任务收到非目标状态时则幂等结束。

## 5. 验证边界

本实验已证明：

- 23 个 SQL 迁移可从空库执行。
- V16 历史数据可升级到 V24，V17 回填符合预期，V24 核心表列注释完整。
- MySQL 与 Java 两层都会拒绝已覆盖的非法状态。
- Windows 和容器化 Bash 实机结果一致。

它尚不证明：全部服务在空数据卷上的端到端业务链路，或者携带 RabbitMQ/RocketMQ/Kafka 运行状态的旧卷升级与重启恢复。这两项仍保留在 `TODO.md`。

## 6. 推荐学习资料

| 资料 | 阅读重点 |
| --- | --- |
| [Flyway Commands](https://documentation.red-gate.com/flyway/reference/commands) | `migrate` 执行待应用迁移，`validate` 检查本地脚本与历史表是否一致 |
| [Flyway Target Setting](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-target-setting) | 为什么实验可以先停在 V16，再执行真实的增量升级 |
| [Flyway Validate](https://documentation.red-gate.com/flyway/reference/commands/validate) | 版本、名称和 checksum 不一致时如何失败 |
| [MySQL 8.4 CHECK Constraints](https://dev.mysql.com/doc/refman/8.4/en/create-table-check-constraints.html) | `CHECK` 何时被执行，以及约束表达式的能力边界 |

建议先跟踪 `flyway_schema_history`，再逐条阅读 V15–V24，最后对照 `OrderCancellationService`、`PurchaseOrderService`和 `OrderReceiptLifecycleService` 理解“数据库约束 + 领域门禁”的分工。

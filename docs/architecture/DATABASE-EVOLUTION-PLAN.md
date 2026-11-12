# 数据库演进计划（架构待办）

## 当前决策

当前保留 MySQL 8.4，三个系统继续拥有独立数据库：

- `commerce-db` / `commerceflow`
- `payment-simulator-db` / `payment_platform`
- `logistics-simulator-db` / `logistics_platform`

目录和命名重构不同时迁移 PostgreSQL。MySQL 并非商城的不合理选择；当前行锁、唯一约束、幂等、Outbox 和事务竞态基线均已在 InnoDB 上实测。

## PostgreSQL 专项的启动条件

- 明确的学习目标包含 JSONB、部分/表达式索引、Advisory Lock、`SKIP LOCKED` 或 PostgreSQL 锁模型。
- 结构命名和 MyBatis 数据访问治理已完成，不与包/目录迁移混合。
- 允许从空卷重建，并愿意重新执行 M1 全部并发与故障门禁。

## 迁移工作包

1. 建立 PostgreSQL 方言分支，不长期同时维护两套生产 SQL。
2. 重写 Flyway 基线：Identity、时间精度、JSONB、约束和索引。
3. 转换 `INSERT IGNORE`、`ON DUPLICATE KEY UPDATE`、`DATE_ADD`、MySQL JSON 和锁等待配置。
4. 核对 MyBatis Mapper XML 与 PostgreSQL 返回类型。
5. 重新验证支付/取消锁超时、崩溃恢复、物流乱序、收货/完成竞态和 MQ 故障。
6. 记录两种数据库在锁、索引、执行计划和故障恢复方面的学习对比。

## 微服务后的数据库原则

- 每个服务独立拥有 Schema/实例和 Flyway 历史。
- 不为技术多样性强行引入多种数据库；只在查询、一致性或扩展需求不同时使用多模存储。
- 跨服务一致性使用事件、Outbox/Inbox、幂等和可观测补偿，不通过跨库事务掩盖边界。

本计划尚未启动，属于架构待办，不是当前业务 TODO。

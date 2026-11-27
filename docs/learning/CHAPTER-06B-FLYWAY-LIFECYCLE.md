# 06B：PostgreSQL 的 Flyway 启动与状态防护

本章使用 Compose 新卷验证当前 PostgreSQL 结构。Flyway 根据各模块的 `db/postgresql` 执行版本化 SQL。核心商城从 V74 基线建立表，目录、订单、库存、履约和模拟器各自维护迁移历史。行政区划基础数据使用模块自己的迁移目录。

## 运行新卷验收

从仓库根目录用 PowerShell 7 执行：

```powershell
./scripts/verify-quick.ps1
```

脚本为本轮验收单独分配 Compose 项目、网络、端口和 PostgreSQL 命名卷。数据库迁移完成后，初始化演示账号、商品、分仓库存和支付商户。结束时保留验收卷，可查看各业务库的 `flyway_schema_history` 并对照对应模块的迁移文件。

## 数据库约束和业务规则

| 层次 | 防护范围 |
| --- | --- |
| PostgreSQL | 枚举范围、非负数、唯一性、外键及索引 |
| Java 领域服务 | 当前用户权限、订单状态转换、资金事实和幂等边界 |

`CommerceFlowIntegrationTest.illegalOrderStateTransitionsAreRejectedWithoutChangingFacts` 验证非法操作后订单事实保持一致。快速回归在独立 PostgreSQL 环境验证下单、取消、付款、退款和服务恢复。

现有卷变更前先做备份和隔离恢复。[PostgreSQL 运维与数据库演进](../architecture/DATABASE-EVOLUTION-PLAN.md)

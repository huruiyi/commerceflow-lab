# order-service 准入与单一所有权演进计划

## 1. 当前结论

M3.5 第一批只完成边界审计和基线冻结，`order-service` 的 **CREATE 准入暂不通过**。当前订单数据、
状态和关联完整性均一致，但共享数据库中仍有 9 条跨边界外键，另有 7 个外部类直接依赖
`ShopOrderMapper`。在这些依赖收口到版本化应用端口前，不创建 `order-service`、`order-db`，不迁移数据，
不切换流量。

本阶段继续使用 MySQL 8.4。不得并行拆分 `payment-orchestration-service` 或 `fulfillment-service`；
PostgreSQL 只有在当前规划的全部服务以及以后新增的待拆服务都完成单一所有权切换并逐一验收后，才能另立专项。

## 2. 唯一所有权

order 边界最终只拥有以下四张表及其表达的事实：

| 所有权表 | 唯一事实 |
| --- | --- |
| `shop_orders` | 订单身份、客户稳定 ID、总额、订单状态和下游结果投影 |
| `order_items` | 下单时商品、商家、单价和数量快照 |
| `order_shipping_addresses` | 下单时收货信息快照 |
| `order_events` | 订单状态转换审计、触发来源和因果关联 |

边界规则如下：

- identity 只向订单提供稳定的 `customerId`。
- 商品、商家和地址信息作为下单时快照保存，不跨服务读取当前表。
- payment、inventory、refund、fulfillment/logistics 分别拥有自己的权威事实，order 只保存必要结果投影。
- scheduling 独占任务记录；订单事件只保存稳定任务标识，不建立跨库外键。

## 3. 当前阻塞项

### 3.1 九条跨边界外键

order 四表指向外部边界的 4 条外键：

| 当前外键 | 收口要求 |
| --- | --- |
| `shop_orders.customer_id -> app_users.id` | 保留稳定 ID，迁移前解除数据库外键 |
| `order_items.merchant_id -> app_users.id` | 保留商家稳定 ID，迁移前解除数据库外键 |
| `order_events.actor_id -> app_users.id` | 保留可空审计 ID，迁移前解除数据库外键 |
| `order_events.scheduled_task_id -> scheduled_tasks.id` | 保留任务标识，迁移前解除数据库外键 |

外部边界指向 order 四表的 5 条外键：

| 当前外键 | 未来所有者 |
| --- | --- |
| `customer_notifications.order_id -> shop_orders.id` | notification |
| `payment_records.order_id -> shop_orders.id` | payment-orchestration |
| `product_reviews.order_item_id -> order_items.id` | review |
| `refund_records.order_id -> shop_orders.id` | refund |
| `shipment_records.order_id -> shop_orders.id` | fulfillment/logistics |

解除外键不是删除业务校验。消费者必须通过稳定 ID、版本化契约、Inbox 幂等和对账发现缺失事实。

### 3.2 七个外部 Mapper 消费者

以下应用服务仍直接依赖 order 的基础设施 Mapper：

| 模块 | 当前消费者 |
| --- | --- |
| fulfillment | `MerchantFulfillmentService` |
| logistics | `LogisticsNotificationService`、`LogisticsService` |
| payment | `PaymentNotificationService` |
| receipt | `OrderReceiptLifecycleService` |
| refund | `RefundNotificationService`、`RefundService` |

这些依赖必须逐个改为明确的 order application port。端口应按意图命名，例如支付结果确认、退款结果确认、
履约状态推进和收货状态推进，不得创建一个远程版通用 `ShopOrderMapper`。

### 3.3 Mapper 与编排职责过宽

`ShopOrderMapper.xml` 除四张 order 表外还访问 cart、address 和 payment 表。`PurchaseOrderService` 也直接组合
购物车、身份、支付、退款、调度、物流和库存能力。下一批必须先拆出应用端口和稳定命令/结果契约，再决定哪些编排
留在 order Process Manager；禁止把当前单体事务原样包装成一次跨服务同步调用链。

## 4. 分布式事务决策

本项目不引入 Seata、DTM、XA、TCC 框架或全局事务协调器。拆分后的正确性基线统一为：

1. 每个服务只在自己的数据库内使用本地事务。
2. 业务事实与 Transactional Outbox 在同一本地事务提交。
3. 消费方以 Consumer Inbox、稳定 `commandId/eventId` 和条件状态更新保证幂等。
4. order 持久化 Saga/Process Manager 状态，不用内存等待串联跨进程步骤。
5. 超时只代表“结果未知”，进入等待或待对账，不能立即推断失败，也不能静默回退本地实现。
6. 补偿是新的可审计业务动作，不回滚已被外部系统确认的支付等历史事实。

| 流程 | order 本地提交 | 远端结果与恢复 |
| --- | --- | --- |
| 下单 | 订单与预占命令 Outbox | inventory 以原 commandId 返回结果；未知结果等待对账 |
| 支付 | 支付编排请求引用和流程状态 | 回调与主动查询共同收口；成功资金事实优先 |
| 取消 | `CANCELLING` 与补偿意图 | 关闭支付、释放库存均确认后进入 `CANCELLED` |
| 退款 | 退款流程引用和等待状态 | 保留原支付事实；失败恢复或进入人工对账 |
| 履约 | 条件推进允许的订单状态 | 重复/乱序结果不倒退，缺失结果可主动对账 |

## 5. 后续批次顺序

1. **应用端口收口**：拆分 `ShopOrderMapper` 外部消费者，冻结禁止新增规则，清除 Mapper 对外部表的新访问。
2. **Order V1 契约与状态机**：冻结创建、支付、取消、退款、履约命令/结果，补齐非法转换和兼容 fixture。
3. **持久化 Saga 基线**：建立 order 侧 Outbox、结果 Inbox、流程状态、超时待对账和人工恢复入口。
4. **最终 CREATE 评审**：九条跨边界外键和七个 Mapper 消费者归零，完整回归、故障与数据指纹通过后才允许建服务。
5. **影子运行与 MySQL 迁移**：创建分层 `order-service` 和独立 MySQL，先不接流量；完成可重复快照和增量追平。
6. **停写、最终追平与切换**：整体切换订单写入，验证重复、乱序、结果丢失、服务/数据库停机和跨进程 Trace。
7. **单一所有权冻结**：删除 commerce 中的 order 运行路径和源表权限，不保留 LOCAL/COMPARE、双写或数据库回退。
8. order 完成后才依次进入 `payment-orchestration-service`、`fulfillment-service` 以及其余新增候选边界。

## 6. 当前验收证据与复跑

2026-11-17 的默认 MySQL 持久卷审计结果：Flyway V34；订单 313、商品项 313、地址快照 313、事件
1477；订单金额与商品项汇总均为 `406587.00`。重复订单号、孤儿记录、无商品/地址/事件订单、金额不一致、
非法状态、非法库存投影和待恢复流程均为 0。指纹如下：

```text
orders|313|406587.00|1799638222
items|313|313|406587.00
addresses|313
events|1477
```

复跑入口：

```powershell
.\scripts\windows\audit-order-service-admission.ps1
```

```bash
./scripts/linux/audit-order-service-admission.sh
```

两套脚本只启动并读取现有 `commerce-db`，不清空卷；同时冻结 4 张所有权表、4+5 条跨边界外键、7 个外部
Mapper 消费者、状态/关联不变量及 Seata/DTM/XA 缺席门禁。Java 架构测试允许旧依赖逐步减少，但禁止新增消费者、
新增 Mapper 外部表职责或全局事务协调器。

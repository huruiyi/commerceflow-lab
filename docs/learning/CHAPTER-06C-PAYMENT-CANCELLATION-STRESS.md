# 06C：支付/取消随机化竞态压测

## 1. 学习目标

本实验不是单纯追求 QPS，而是在真实 Docker MySQL 和独立模拟支付平台上，反复改变“支付成功回调”与“客户取消”的先后次序，验证每轮都只收敛到可解释的账务事实。

必须同时覆盖：

- 支付先完成：`PAID/SUCCESS`。
- 取消先关闭渠道：`CANCELLED/CLOSED`。
- 极近同时：随机抖动决定谁获得数据库锁和条件更新。
- 迟到成功：如出现，必须是 `REFUNDING/SUCCESS` 或 `REFUNDED/SUCCESS`，不能留在 `CANCELLED/SUCCESS`。

## 2. 脚本入口

Windows：

```powershell
./scripts/windows/verify-payment-cancellation-stress.ps1 `
  -DurationSeconds 300 -MinimumIterations 30 -Seed 20261113
```

Linux/Bash：

```bash
bash ./scripts/linux/verify-payment-cancellation-stress.sh \
  http://localhost:14200 http://localhost:14201 300 30 20261113
```

`DurationSeconds` 和 `MinimumIterations` 是双门禁：脚本必须同时达到最少时长和最少轮数才停止。固定 `Seed` 使随机延迟可重放；不同平台的随机数实现不同，因此相同种子保证各自可重现，不保证 Windows 与 Bash 每轮延迟完全一样。

## 3. 为什么不关闭支付平台限流

支付平台默认限制每个商户每分钟 120 个签名请求。无节流实验在第 81 轮真实收到 `429 Too Many Requests`，说明压测已越过对手服务的协议容量，不是订单一致性错误。

最终脚本保留限流，默认在每轮间暂停 900ms，且只对明确包含平台 `429` 的创建支付失败做 5 秒、最多 15 次退避。其他 `409`、鉴权或业务错误立即失败，不被压测脚本掩盖。

## 4. MySQL 一致性审计

每轮 API 断言通过后，stress 入口会使用本轮收集的订单 ID 直接查询 Docker MySQL，同时断言：

1. 订单数 = 本轮数。
2. 支付记录数 = 本轮数。
3. `CANCELLED` 只能搭配 `CLOSED`。
4. `PAID/REFUNDING/REFUNDED` 只能搭配 `SUCCESS`。

脚本通过商家库存 API 把商品 1 的可售库存提高到安全水位，会保留可审计的人工调整流水和压测订单；它不直接改表、不删除压测数据。

## 5. 已验证结果与边界

2026-11-13 本机 Docker 结果：

| 平台 | 时长 | 轮数 | `PAID/SUCCESS` | `CANCELLED/CLOSED` | MySQL 矛盾 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Windows PowerShell | 120.6s | 95 | 55 | 40 | 0 |
| 容器化 Bash | 121s | 97 | 58 | 39 | 0 |

这证明的是当前单机 Docker、当前 MySQL/支付平台版本下的两分钟浸泡结果。它不等同于多实例、多节点数据库、网络分区、多小时/多日容量规划或生产可用性认证。

## 6. 推荐学习资料

| 资料 | 阅读重点 |
| --- | --- |
| [MySQL 8.4 InnoDB Locking](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html) | 行锁、索引记录锁与并发写入的基础模型 |
| [MySQL 8.4 Transaction Isolation](https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html) | 不同隔离级别下一致性读、锁定读和 UPDATE 的差异 |
| [MySQL 8.4 Deadlocks](https://dev.mysql.com/doc/refman/8.4/en/innodb-deadlocks.html) | 写竞争为什么可能死锁，以及为什么应用必须准备处理事务回滚 |

建议对照 `ShopOrderMapper.lock/lockById`、`OrderCancellationService`和 `PaymentNotificationService` 阅读：行锁只负责串行化关键窗口，真正的安全性仍来自状态条件更新、资金事实优先和幂等补偿。

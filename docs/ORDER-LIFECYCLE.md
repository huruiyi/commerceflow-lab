# 订单生命周期

## 1. 文档边界

本文档是订单、支付、退款、履约和定时任务的统一业务语义。已实现与未实现项目以 `IMPLEMENTED.md` 和 `TODO.md` 为准。

## 2. 订单状态机

```text
PENDING_PAYMENT
    ├─ 支付成功 ─> PAID ─> CONFIRMED ─> SHIPPED ─> RECEIVED ─> COMPLETED
    └─ 客户取消/支付超时 ─> CANCELLING ─> CANCELLED

PAID / CONFIRMED
    └─ 整单退款 ─> REFUNDING ─> REFUNDED
```

| 状态 | 含义 | 客户主要动作 | 商家主要动作 |
| --- | --- | --- | --- |
| `PENDING_PAYMENT` | 整单支付截止时间前待支付 | 支付、刷新、取消 | 无 |
| `CANCELLING` | 正在关闭渠道交易 | 查看进度 | 无 |
| `CANCELLED` | 未支付订单已关闭 | 查看原因和历史支付 | 无 |
| `PAID` | 支付成功 | 查看支付、申请退款 | 确认订单 |
| `CONFIRMED` | 商家已确认 | 查看支付、申请退款 | 发货 |
| `SHIPPED` | 商家已发货 | 查物流、确认收货 | 修正物流 |
| `RECEIVED` | 客户或系统已确认收货 | 查物流、评价 | 只读 |
| `COMPLETED` | 收货观察期结束 | 查历史信息、未评价商品可评价 | 只读 |
| `REFUNDING` | 退款处理中 | 查支付和退款进度 | 禁止发货 |
| `REFUNDED` | 整单退款成功 | 查原支付和退款凭据 | 只读 |

## 3. 支付超时与取消

- 单次支付尝试的 `expires_at` 只代表该渠道交易过期，整单 `payment_due_at` 未到时仍可换渠道重试。
- 客户取消和 `PAYMENT_TIMEOUT` 任务共用 `OrderCancellationService`，不允许用批量 SQL 直接改终态。
- 流程先锁定订单进入 `CANCELLING`，逐个签名关闭待支付渠道交易，再进入 `CANCELLED`。
- 取消成功在同一本地事务中写入订单事件和 `ORDER_CANCELLED` Outbox。
- 支付成功与取消并发时，资金事实优先；若订单已进入 `CANCELLED`，回调事务会同时创建幂等自动退款单，订单转入 `REFUNDING`。

## 4. `availableActions`

客户订单响应由后端统一计算可用动作：

- `PAY`、`REFRESH_PAYMENT`、`CANCEL_ORDER`
- `REQUEST_REFUND`、`VIEW_PAYMENT`、`VIEW_REFUND`
- `VIEW_LOGISTICS`、`COPY_TRACKING_NUMBER`、`CONFIRM_RECEIPT`
- `REVIEW_ITEM`

Angular 只使用这个集合决定是否展示操作。它不是授权凭证；每个写接口仍必须在后端重新验证当前用户、订单归属、状态和资金事实。

## 5. 定时任务与策略快照

- 每个业务截止点创建独立任务，例如 `PAYMENT_TIMEOUT`、`AUTO_RECEIVE`、`AUTO_COMPLETE`。
- 任务保存创建时的主触发、对账开关、宽限期、租约和重试快照。
- 管理员修改配置只影响新任务；旧任务继续使用原快照。
- Database、RabbitMQ 和 RocketMQ 只是“何时唤醒任务”的适配器，必须调用同一 Handler 和统一执行器。

## 6. 送达、收货与完成

`DELIVERED（已送达）`、`RECEIVED（已收货）` 和 `COMPLETED（已完成）` 是三个不同的业务事实：

```text
物流 DELIVERED（已送达）
    -> 创建 AUTO_RECEIVE（自动确认收货）
    -> 客户可在观察期内手动确认收货，并取消 AUTO_RECEIVE
    -> 否则任务到期后 SHIPPED（已发货） -> RECEIVED（已收货）
    -> 创建 AUTO_COMPLETE（自动完成订单）
    -> 任务到期后 RECEIVED -> COMPLETED（已完成）
```

- 默认送达后 7 天自动确认收货，收货后 7 天自动完成；两个时长可通过环境变量调整。
- 任务执行前必须重读并锁定订单；状态已被手动操作推进时，迟到任务不再重复改状态。
- 手动与自动收货共用同一领域服务、条件更新和稳定任务键；重复调用保持幂等。
- `COMPLETED` 是履约终态，不覆盖支付、退款或物流事实，也不要求商品已评价。

## 7. 可配置时长

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `ORDER_AUTO_RECEIVE_AFTER_DELIVERY` | `7d` | 物流送达后，系统等待多久才自动确认收货 |
| `ORDER_AUTO_COMPLETE_AFTER_RECEIPT` | `7d` | 订单收货后，系统等待多久才自动完成订单 |

当管理员禁用对应任务策略时，业务状态转换仍可成功，但不会新建该自动任务；这样不会因运维暂停自动化而阻断物流回调或客户手动收货。

## 8. 待实现流程

- RabbitMQ/RocketMQ 主触发适配器及六种组合故障验收。
- 订单取消 Outbox 的库存释放与客户通知消费者、迟到支付自动退款已实现；后续继续补齐高强度竞态验收。
- 物流 Outbox 的 `DEAD（停止自动重试）` 管理/人工重放、指标告警和长时间并发乱序压力。
- 手动/自动收货的长时间高并发压力实验。

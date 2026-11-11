# CommerceFlow Lab 统一未实现代办

## 1. 文档职责

| 项目 | 内容 |
| --- | --- |
| 性质 | 全项目唯一的未实现任务清单 |
| 主线 | 原 `temp.md` 中准备实施的订单闭环方案 |
| 合并内容 | 订单超时策略、Database/RabbitMQ/RocketMQ 讨论、截图阶段 4～6 和旧实施计划 |
| 当前状态 | 待审核，本轮订单生命周期代码尚未开始 |
| UI 边界 | 先补功能与数据，订单信息继续全部展开，暂不做摘要、折叠和整体美化 |
| 最后更新 | 2026-11-12 |

本文档只记录未完成项。已实现事实见 [IMPLEMENTED.md](IMPLEMENTED.md)，详细策略见 [通用定时任务策略](reference/SCHEDULED-TASK-STRATEGIES.md)。旧草案和旧实施计划移入 `archive/`，不再同步勾选状态。

## 2. 当前基线与本轮边界

### 2.1 直接复用的现有能力

- [x] `PENDING_PAYMENT -> PAID -> CONFIRMED -> SHIPPED -> RECEIVED` 基础流转。
- [x] 三渠道模拟支付、签名通知、幂等、Outbox、Kafka 和支付对账。
- [x] 未发货已支付订单首版整单全额退款。
- [x] 商家确认、手工录入承运商/单号、发货和物流信息更正。
- [x] 客户手动确认收货和一次性商品评价。
- [x] ShedLock、Windows PowerShell 与 Bash 验收脚本框架。

### 2.2 本轮必须补齐

- [ ] `CANCELLING`、`CANCELLED`、`COMPLETED` 订单状态。
- [ ] 客户主动取消未支付订单。
- [ ] 整单支付截止时间、自动关单及与支付成功的并发处理。
- [ ] Database、RabbitMQ、RocketMQ 可插拔主触发和可独立开关的 Database 对账补偿。
- [ ] 任务策略快照，保证同一任务不随全局配置中途换策略。
- [ ] 独立模拟物流服务、签名回调、轨迹投影与客户查询。
- [ ] 物流签收后自动收货、`RECEIVED -> COMPLETED` 自动完成。
- [ ] 订单响应 `availableActions` 和客户页面完整信息。

### 2.3 本轮不做

- 退货、换货、部分退款和售后工单。
- 真实支付机构或真实物流渠道接入。
- 订单卡片摘要、可折叠详情、抽屉和整体视觉改版。
- 退款区域和评价表单美化。

## 3. 必须先固定的业务语义

### 3.1 目标订单状态机

```text
PENDING_PAYMENT
    ├─ 支付成功 ─> PAID
    └─ 客户取消/整单超时 ─> CANCELLING ─> CANCELLED

PAID
    ├─ 客户退款 ─> REFUNDING ─> REFUNDED
    └─ 商家确认 ─> CONFIRMED ─> SHIPPED ─> RECEIVED ─> COMPLETED
```

退款失败时恢复到退款前的 `PAID` 或 `CONFIRMED`，不能改写原支付成功事实。

### 3.2 三个不可混淆的边界

1. **支付尝试过期 ≠ 整单超时**：单次 `expires_at` 到期只使该尝试进入 `EXPIRED`；整单 `payment_due_at` 未到时仍可换渠道重试。
2. **物流签收 ≠ 客户收货**：`DELIVERED` 只代表物流渠道认为送达；客户可手动确认，或等待观察期后自动进入 `RECEIVED`。
3. **`RECEIVED` ≠ `COMPLETED`**：已收货后仍有观察期；观察期结束才进入稳定终态。`COMPLETED` 不依赖商品已评价。

## 4. 开工前需确认的决策

| 决策 | 建议默认值 |
| --- | --- |
| 整单支付超时 | 30 分钟 |
| 物流签收后自动收货 | 7 天 |
| 收货后自动完成 | 7 天 |
| 默认轻量方案 | Database 主触发 + Database 对账 |
| 生产学习推荐 | RabbitMQ 主触发 + Database 对账 |
| 高级实验 | RocketMQ 主触发 + Database 对账 |
| 已取消后意外支付成功 | 恢复 `PAID` 并记录资金异常；后续再升级自动退款 |
| 管理员策略入口 | Angular `ADMIN` 端 |

以上均应可配置；验收环境使用秒级时长。

## 5. 阶段 1：订单生命周期基础

- [ ] 新增 `CANCELLING`、`CANCELLED`、`COMPLETED`。
- [ ] `shop_orders` 新增 `payment_due_at`、`cancelled_at`、`cancel_reason`、`received_at`、`completed_at`、`logistics_delivered_at`。
- [ ] 新订单写入 `payment_due_at = created_at + payment-timeout`，历史数据有明确回填策略。
- [ ] 新增 `payment-timeout`、`auto-receive-after-delivery`、`auto-complete-after-receipt` 配置。
- [ ] 订单事件保留操作人、触发源、旧/新状态、原因和任务 ID。
- [ ] 新增 `docs/ORDER-LIFECYCLE.md`，统一订单、支付、退款、物流和任务状态。
- [ ] 订单响应新增 `availableActions`；写接口仍在后端重新验证。
- [ ] Flyway 空库创建、旧数据升级和非法状态转换测试通过。

## 6. 阶段 2：未支付订单关闭与可插拔触发

先实现唯一正确的关单 Handler，再接三种触发器；不得各写一套业务逻辑。

### 6.1 客户主动取消

- [ ] 新增 `POST /api/purchase-orders/{orderId}/cancel`，仅订单所属客户可取消 `PENDING_PAYMENT`。
- [ ] 锁定订单，执行 `PENDING_PAYMENT -> CANCELLING`，关闭所有 `PENDING` 渠道交易。
- [ ] 全部关闭后执行 `CANCELLING -> CANCELLED`，写入时间、原因、事件和 Outbox。
- [ ] 无支付记录或全部已 `FAILED/CLOSED/EXPIRED` 时可直接完成取消。
- [ ] 存在成功支付时拒绝普通取消并引导退款。
- [ ] 重复请求幂等返回原结果，不重复关闭渠道交易。

### 6.2 支付/取消并发

- [ ] 渠道关闭先成功时进入 `CANCELLED`。
- [ ] 支付通知先落库时支付优先，订单进入 `PAID`，取消失败。
- [ ] `CANCELLED` 后意外成功通知不得忽略；按第 4 节决策处理并创建资金异常。
- [ ] 用数据库锁、条件更新和幂等键执行真实竞态测试。

### 6.3 统一定时任务领域

- [ ] 首批任务类型：`PAYMENT_TIMEOUT/AUTO_RECEIVE/AUTO_COMPLETE/REFUND_RECONCILIATION/PAYMENT_RECONCILIATION/LOGISTICS_RECONCILIATION/NOTIFICATION_RETRY/REVIEW_REMINDER/INVENTORY_RELEASE/AFTER_SALE_DEADLINE`；类型由管理员启停和配置，Handler 由代码安全注册。
- [ ] 任务状态：`CREATED/SCHEDULED/PROCESSING/SUCCEEDED/FAILED/CANCELLED`。
- [ ] 建立任务表，包含唯一业务键、版本、租约、尝试次数、最后错误、下次执行时间。
- [ ] 保存 `primaryStrategySnapshot`、配置版本和必要参数快照。
- [ ] 同一订单+任务类型只允许一个未完成任务。
- [ ] 所有触发与补偿入口调用同一 `ScheduledTaskExecutor` 和 Handler。
- [ ] 超时关单写入 `ORDER_PAYMENT_EXPIRED/PAYMENT_TIMEOUT`，禁止直接批量 SQL 改状态。

### 6.4 三种主触发

- [ ] **Database**：ShedLock + 分批领取，支持周期、批次、并发、租约和最大尝试次数。
- [ ] **RabbitMQ**：TTL + DLX（或经评审的延迟插件），处理 ACK/NACK、重试、DLQ、重复、早到、晚到和重启。
- [ ] **RocketMQ**：5.x 延时消息，处理生产、消费、重试、死信、Broker 时钟偏差和重启恢复。
- [ ] MQ 消息只携带 `taskId/taskType/dueAt/strategySnapshot`，业务事实从数据库重读。

### 6.5 Database 对账和管理配置

- [ ] `reconciliation.enabled` 是独立补偿开关，不是第四种主策略。
- [ ] 只处理超过 `dueAt + gracePeriod` 仍未完成的任务，保留原策略快照和补偿来源。
- [ ] 通过 `@ConfigurationProperties` 完整绑定策略字段并校验 Duration、批次、TTL、租约和次数。
- [ ] 管理员改全局默认只影响新任务；旧任务继续使用快照。
- [ ] 管理员可查询、重试、取消任务，操作写审计。
- [ ] 验证三种主触发 × 对账关/开的六种组合。

### 6.6 阶段门禁

- [ ] 主动取消无支付、有待支付、重复请求均通过。
- [ ] 单次支付过期后，整单未超时时仍可重试。
- [ ] 整单超时时必须先关闭待支付交易，再进入 `CANCELLED`。
- [ ] 支付/取消竞态、锁超时、进程崩溃和消息重复不造成错账。

## 7. 阶段 3：独立模拟物流

### 7.1 服务、数据与状态

- [ ] 新建独立 `mock-logistics-service`、数据库和 Docker 服务；首版不做物流管理 UI。
- [ ] 物流平台保存渠道运单号、商城请求号、承运商、单号、状态、轨迹、节点时间、回调和幂等信息。
- [ ] 商城保存 `shipment_records`、`shipment_events`、当前投影、发货/签收时间、通知 ID 和回调摘要。
- [ ] 模拟物流不得直接连接商城数据库。
- [ ] 实现 `CREATED -> PICKED_UP -> IN_TRANSIT -> AT_STATION -> OUT_FOR_DELIVERY -> DELIVERED`，及 `EXCEPTION`。
- [ ] 自动轨迹时间可配置，测试环境可压缩为秒级；每节点有稳定事件 ID。

### 7.2 签名接口、回调与对账

- [ ] `POST /api/channel/shipments`。
- [ ] `GET /api/channel/shipments?merchantShipmentNo=...`。
- [ ] `POST /api/channel/shipments/{merchantShipmentNo}/advance`。
- [ ] 回调 `POST /api/logistics-notifications/callback`。
- [ ] 请求/回调实现 App ID、时间戳、Nonce、摘要、HMAC/RSA 签名和防重放。
- [ ] 覆盖回调丢失、重复、乱序、篡改、超时和主动对账。

### 7.3 商城投影与客户查询

- [ ] 商家发货时创建运单，成功后再完成商城发货投影。
- [ ] 新增 `GET /api/purchase-orders/{orderId}/logistics`，严格校验客户归属。
- [ ] `SHIPPED/RECEIVED/COMPLETED` 均返回承运商、单号、当前状态和完整轨迹。
- [ ] 创建运单、自动推进、回调重放、对账恢复和越权阻断验收通过。

## 8. 阶段 4：收货与完成

- [ ] 客户手动 `SHIPPED -> RECEIVED` 时写入 `received_at`，取消 `AUTO_RECEIVE`，幂等创建 `AUTO_COMPLETE`。
- [ ] 首次收到 `DELIVERED` 时写入 `logistics_delivered_at`，创建延时 `AUTO_RECEIVE`。
- [ ] `AUTO_RECEIVE` 执行时重新检查仍为 `SHIPPED`，再写入 `received_at`。
- [ ] 手动/自动收货并发时只产生一次转换和一组事件。
- [ ] 创建 `dueAt = received_at + auto-complete-after-receipt` 的 `AUTO_COMPLETE`。
- [ ] 执行时只允许 `RECEIVED -> COMPLETED`，写入 `completed_at`。
- [ ] `COMPLETED` 后仍可查订单、支付、退款、物流，且未评价商品仍可一次性评价。
- [ ] `COMPLETED` 后禁止取消、修改物流、确认收货和“未发货退款”。
- [ ] 手动/自动收货、重复任务、并发触发、重启恢复和自动完成验收通过。

## 9. 阶段 5：客户页面必要功能

信息顺序统一为：订单头部 → 商品 → 收货地址 → 当前操作 → 物流 → 支付 → 退款 → 时间线 → 评价。

### 9.1 `availableActions`

- [ ] 初始动作包含 `PAY`、`REFRESH_PAYMENT`、`CANCEL_ORDER`、`REQUEST_REFUND`、`VIEW_PAYMENT`、`VIEW_LOGISTICS`、`COPY_TRACKING_NUMBER`、`CONFIRM_RECEIPT`、`REVIEW_ITEM`。
- [ ] Angular 不再到处复制状态判断；但后端不得把 `availableActions` 当授权证据。

### 9.2 状态与展示

| 状态 | 必须展示 |
| --- | --- |
| `PENDING_PAYMENT` | 支付、刷新、取消、支付尝试 |
| `CANCELLING` | 正在关闭交易，禁用支付和重复取消 |
| `CANCELLED` | 取消原因、时间、历史支付尝试 |
| `PAID/CONFIRMED` | 支付记录、退款动作、商家确认信息 |
| `SHIPPED` | 支付、展开的物流、复制单号、确认收货 |
| `RECEIVED` | 支付、完整物流、收货时间、评价 |
| `COMPLETED` | 支付、完整物流、完成时间、评价 |
| `REFUNDING/REFUNDED` | 原支付、退款进度/结果、原因和金额 |

### 9.3 展示要求

- [ ] 物流始终展开，显示承运商、单号、复制、状态和按时间排序的轨迹。
- [ ] `RECEIVED/COMPLETED` 不能隐藏物流或支付。
- [ ] 退款不覆盖支付卡片，作为独立资金事实展示。
- [ ] 按状态展示支付截止、取消、签收、收货和完成时间。
- [ ] 语言切换不闪回旧订单/空数据，不直接显示后端枚举英文值。
- [ ] 移动端无水平滚动，但本阶段不重做视觉。

## 10. 阶段 6：验收、故障实验和文档

### 10.1 业务与故障验收

- [ ] 主动取消无支付/有待支付订单、重复取消、支付/取消并发。
- [ ] 单次支付过期仍可重试，整单超时自动关闭。
- [ ] 六种主触发/对账组合。
- [ ] MQ 未投递、消费者停机、重复、毒消息和 Database 补偿。
- [ ] 物流推进、回调丢失/重放/乱序、对账、越权查询。
- [ ] 手动/自动收货、自动 `COMPLETED`，完成后仍可查支付/退款/物流并评价。

### 10.2 双平台脚本和 Docker

- [ ] Windows PowerShell 和 Bash 提供同名、同参数、同语义脚本。
- [ ] 三种主策略提供独立 Compose Profile；Database 模式不强制启动 MQ。
- [ ] 空数据卷从 Flyway 到全链路验收通过。
- [ ] 旧卷升级、服务/MQ 重启、任务恢复通过。
- [ ] 脚本默认不清空数据；破坏性重建需显式参数。

### 10.3 文档交付与完成门禁

- [ ] 完成 `docs/ORDER-LIFECYCLE.md`、`docs/MOCK-LOGISTICS.md`、`docs/API.md`、根 `README.md` 和 `IMPLEMENTATION-PLAN.md`。
- [ ] 写清六种配置、策略快照、切换边界、回滚、监控和告警。
- [ ] 订单、支付、退款、物流和任务无矛盾状态。
- [ ] 重试、重放、并发和恢复不会重复关单、收货或完成。
- [ ] 客户、商家、管理员和服务回调权限边界通过。
- [ ] Windows 与 Linux/Bash 验收结果对齐。

## 11. 基础稳定后再做的视觉代办

- [ ] 订单卡片改为“摘要 + 当前主操作 + 可展开详情”。
- [ ] 重做退款区域、已收货状态和评价表单。
- [ ] 统一支付、退款、物流和订单时间线视觉。
- [ ] 补齐骨架屏、空状态、错误状态和响应式布局。

启动视觉改造前，状态机和数据契约必须稳定，六种组合和关键竞态必须验收通过，页面不能因状态变化丢失历史信息。

## 12. 实施记录模板

```text
日期：YYYY-MM-DD
阶段：1～6
完成代办：对应复选项
迁移：Flyway 版本和表结构
配置：主策略、对账开关和 Docker Profile
验证：测试、脚本、故障实验和结果
遗留：未完成项、风险和人工验收
提交：Git 提交号；未提交时写“未提交”
```

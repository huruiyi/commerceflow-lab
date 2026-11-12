# CommerceFlow Lab 统一未实现代办

## 1. 文档职责

| 项目 | 内容 |
| --- | --- |
| 性质 | 全项目唯一的未实现任务清单 |
| 主线 | 原 `temp.md` 中准备实施的订单闭环方案 |
| 合并内容 | 订单超时策略、Database/RabbitMQ/RocketMQ 讨论、截图阶段 4～6 和旧实施计划 |
| 当前状态 | 通用定时任务底座、Database/RabbitMQ 触发、订单取消、模拟物流、回调 Outbox/主动对账、自动确认收货/自动完成已实现；RocketMQ 主触发待实现 |
| UI 边界 | 客户订单中心已改为摘要、当前主操作与可展开详情，支付、退款、物流和时间线保持独立事实展示 |
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

- [x] `CANCELLING`、`CANCELLED`、`COMPLETED` 订单状态。
- [x] 客户主动取消未支付订单（客户页面入口待阶段 5 接入）。
- [x] 整单支付截止时间、Database 自动关单及与支付成功的并发状态处理（真实并发压测待补）。
- [ ] Database、RabbitMQ、RocketMQ 可插拔主触发和可独立开关的 Database 对账补偿（Database、RabbitMQ 已完成，RocketMQ 待实现）。
- [x] 任务策略快照，保证同一任务不随全局配置中途换策略。
- [x] 独立模拟物流服务、签名回调、轨迹投影与客户查询。
- [x] 物流签收后由 `AUTO_RECEIVE（自动确认收货）` 推进收货，再由 `AUTO_COMPLETE（自动完成订单）` 执行 `RECEIVED -> COMPLETED`。
- [x] 订单响应新增 `availableActions`，客户页面已接入未支付取消；物流等完整信息随后续阶段补齐。

### 2.3 本轮不做

- 退货、换货、部分退款和售后工单。
- 真实支付机构或真实物流渠道接入。

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

- [x] 新增 `CANCELLING`、`CANCELLED`、`COMPLETED`。
- [x] `shop_orders` 新增 `payment_due_at`、`cancelled_at`、`cancel_reason`、`received_at`、`completed_at`、`logistics_delivered_at`。
- [x] 新订单写入 `payment_due_at = created_at + payment-timeout`；历史订单按 `created_at + 30m` 回填。
- [x] 新增 `payment-timeout`、`auto-receive-after-delivery`、`auto-complete-after-receipt` 配置。
- [x] 订单事件保留操作人、触发源、旧/新状态、原因和任务 ID。
- [x] 新增 `docs/ORDER-LIFECYCLE.md`，统一订单、支付、退款、物流和任务状态。
- [x] 订单响应新增 `availableActions`；写接口仍在后端重新验证。
- [ ] Flyway 空库创建、旧数据升级和非法状态转换测试通过。

## 6. 阶段 2：未支付订单关闭与可插拔触发

先实现唯一正确的关单 Handler，再接三种触发器；不得各写一套业务逻辑。

### 6.1 客户主动取消

- [x] 新增 `POST /api/purchase-orders/{orderId}/cancel`，仅订单所属客户可取消 `PENDING_PAYMENT`。
- [x] 锁定订单，执行 `PENDING_PAYMENT -> CANCELLING`，关闭所有 `PENDING` 渠道交易。
- [x] 全部关闭后执行 `CANCELLING -> CANCELLED`，写入时间、原因和订单事件。
- [x] 取消终态与 `ORDER_CANCELLED` Outbox 在同一本地事务写入。
- [x] 实现订单取消 Outbox 的库存释放、客户通知等下游消费者；Kafka 独立消费组与消费记录保证重复消息不重复释放或通知。
- [x] 无支付记录或全部已 `FAILED/CLOSED/EXPIRED` 时可直接完成取消。
- [x] 存在成功支付时普通取消不会覆盖支付事实，订单保持/恢复 `PAID`；前端引导退款待补。
- [x] 重复请求幂等返回原结果，不重复改变已关闭渠道交易。

### 6.2 支付/取消并发

- [x] 渠道关闭先成功时进入 `CANCELLED`。
- [x] 支付通知先落库时支付优先，订单进入 `PAID`，普通取消不覆盖。
- [x] `CANCELLED` 后意外成功通知不忽略：恢复 `PAID` 并写资金异常订单事件；自动退款仍待后续版本。
- [ ] 用数据库锁、条件更新和幂等键执行真实竞态测试。

### 6.3 统一定时任务领域

- [x] 首批任务类型：`PAYMENT_TIMEOUT/AUTO_RECEIVE/AUTO_COMPLETE/REFUND_RECONCILIATION/PAYMENT_RECONCILIATION/LOGISTICS_RECONCILIATION/NOTIFICATION_RETRY/REVIEW_REMINDER/INVENTORY_RELEASE/AFTER_SALE_DEADLINE`；类型由管理员启停和配置，Handler 由代码安全注册。
- [x] 任务状态：`CREATED/SCHEDULED/PROCESSING/SUCCEEDED/FAILED/CANCELLED`。
- [x] 建立任务表，包含唯一业务键、版本、租约、尝试次数、最后错误、下次执行时间。
- [x] 保存主触发、对账、宽限期、租约、重试和配置版本快照。
- [x] 同一任务类型+业务键只允许一条任务，调用方使用稳定业务键保证聚合幂等。
- [x] Database 主触发与 Database 对账调用同一 `ScheduledTaskExecutor` 和 Handler；后续 MQ 也必须复用该入口。
- [x] `PAYMENT_TIMEOUT` 通过同一取消服务和 Handler 关单，禁止直接批量 SQL 改状态；事件名统一为取消阶段事件并携带任务来源。

### 6.4 三种主触发

- [x] **Database**：ShedLock + 分批领取，支持周期、批次、原子领取、租约恢复和最大尝试次数。
- [x] **RabbitMQ**：持久化分桶 TTL + DLX、Publisher Confirm/Return、有限退避重试、DLQ、早到重新分桶、数据库原子抢占和重启补发。
- [ ] **RocketMQ**：5.x 延时消息，处理生产、消费、重试、死信、Broker 时钟偏差和重启恢复。
- [x] RabbitMQ 消息只携带 `taskId/taskType/dueAt/strategySnapshot`，消费时从数据库重读任务和业务事实；RocketMQ 后续遵循同一契约。

### 6.5 Database 对账和管理配置

- [x] `reconciliation.enabled` 是独立补偿开关，不是第四种主策略。
- [x] 只处理超过 `dueAt + gracePeriod` 仍未完成的任务，保留原策略快照和补偿来源。
- [ ] 通过 `@ConfigurationProperties` 完整绑定并校验全部策略和适配器字段；通用字段与 RabbitMQ 拓扑/分桶/发布字段已绑定并可由环境变量覆盖，RocketMQ 待补。
- [x] 管理员改全局默认只影响新任务；旧任务继续使用快照。
- [x] 管理员 API 与 Angular 管理页可新增/覆盖/恢复策略，查询、重试、取消任务，并查看策略审计与任务事件。
- [ ] 验证三种主触发 × 对账关/开的六种组合（Database 两种和 RabbitMQ 两种已通过，RocketMQ 两种待实现）。

### 6.6 阶段门禁

- [ ] 主动取消无支付、有待支付、重复请求均通过。
- [ ] 单次支付过期后，整单未超时时仍可重试。
- [ ] 整单超时时必须先关闭待支付交易，再进入 `CANCELLED`。
- [ ] 支付/取消竞态、锁超时、进程崩溃和消息重复不造成错账。

## 7. 阶段 3：独立模拟物流

### 7.1 服务、数据与状态

- [x] 新建独立 `mock-logistics-service`、数据库和 Docker 服务；首版不做物流管理 UI。
- [x] 物流平台保存渠道运单号、商城请求号、承运商、单号、状态、轨迹、节点时间、回调和幂等信息。
- [x] 商城保存 `shipment_records`、`shipment_events`、当前投影、发货/签收时间、通知 ID 和回调摘要。
- [x] 模拟物流不得直接连接商城数据库。
- [x] 正常流为 `CREATED -> PICKED_UP -> IN_TRANSIT -> AT_STATION -> OUT_FOR_DELIVERY -> DELIVERED`；非终态可进入 `EXCEPTION（异常件）`，只能通过明确 `RECOVERY（异常恢复）` 回到异常前状态。
- [x] 自动轨迹可启停，扫描周期、每步间隔和批次可配置；默认关闭，测试环境可压缩为秒级，每节点有稳定事件 ID。

### 7.2 签名接口、回调与对账

- [x] `POST /api/channel/shipments`。
- [x] `GET /api/channel/shipments?merchantShipmentNo=...`。
- [x] `PUT /api/channel/shipments/{merchantShipmentNo}` 和 `POST /api/channel/shipments/{merchantShipmentNo}/advance`。
- [x] 回调 `POST /api/logistics-notifications/callback`。
- [x] 请求实现 App ID、时闳、Nonce、摘要、HMAC 和防重放；回调使用独立 HMAC 和通知 ID 去重。
- [x] 物流事件与 Callback Outbox 同事务落库，支持领取租约、HTTP 超时、失败重试、最大次数和重启接管。
- [x] 商城签名查询权威运单并主动对账，永久丢失回调以同一事件 ID 补齐，审计记录 `RECONCILED`。
- [x] `DEAD（停止自动重试）` 管理员查询、必填原因人工重放、操作人/原因审计、重复重放冲突与商城幂等。
- [x] 回调 Outbox 积压、投递结果、人工重放和主动对账 Prometheus 指标。
- [x] 覆盖暂时不可达、永久丢失、重复、迟到可变字段、篡改、超时和主动对账；高并发乱序压力仍待补。

### 7.3 商城投影与客户查询

- [x] 商家发货时创建运单，成功后再完成商城发货投影。
- [x] 新增 `GET /api/purchase-orders/{orderId}/logistics`，严格校验客户归属。
- [x] `SHIPPED/RECEIVED/COMPLETED` 均返回承运商、单号、当前状态和完整轨迹。
- [x] 已通过建单、手动/自动六节点推进、`EXCEPTION -> RECOVERY`、签名/重放拒绝、回调投影、客户查询、Outbox 重试和对账恢复。

## 8. 阶段 4：收货与完成

- [x] 客户手动 `SHIPPED（已发货） -> RECEIVED（已收货）` 时写入 `received_at`，取消 `AUTO_RECEIVE（自动确认收货）`，幂等创建 `AUTO_COMPLETE（自动完成订单）`。
- [x] 首次收到 `DELIVERED（已送达）` 时写入 `logistics_delivered_at`，创建延时 `AUTO_RECEIVE`。
- [x] `AUTO_RECEIVE` 执行时重新锁定订单并检查仍为 `SHIPPED`，再写入 `received_at`。
- [x] 手动/自动路径使用行锁、条件更新和唯一任务键；两线程同时抢占已验证只产生一次收货转换和一个后续任务。
- [x] 创建 `dueAt = received_at + auto-complete-after-receipt` 的 `AUTO_COMPLETE`。
- [x] 执行时只允许 `RECEIVED -> COMPLETED（已完成）`，写入 `completed_at`。
- [x] `COMPLETED` 后仍可查订单、支付、退款、物流，且未评价商品仍可一次性评价。
- [x] `COMPLETED` 后不提供取消、修改物流、确认收货和“未发货退款”动作，写接口仍会独立校验状态。
- [x] 已通过手动收货、任务重放、受控并发抢占、Docker 自动收货/完成，以及“业务已提交但任务未确认”窗口中的后端重启/租约恢复验收。

## 9. 阶段 5：客户页面必要功能

信息顺序统一为：订单头部 → 商品 → 收货地址 → 当前操作 → 物流 → 支付 → 退款 → 时间线 → 评价。

### 9.1 `availableActions`

- [x] 初始动作包含 `PAY`、`REFRESH_PAYMENT`、`CANCEL_ORDER`、`REQUEST_REFUND`、`VIEW_PAYMENT`、`VIEW_REFUND`、`VIEW_LOGISTICS`、`COPY_TRACKING_NUMBER`、`CONFIRM_RECEIPT`、`REVIEW_ITEM`。
- [x] Angular 的主要订单操作按 `availableActions` 展示；后端写接口仍独立验证权限和状态。

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

- [x] 物流始终展开，显示承运商、单号、复制、状态和按时间排序的轨迹。
- [x] `RECEIVED/COMPLETED` 不能隐藏物流或支付。
- [x] 退款不覆盖支付卡片，作为独立资金事实展示。
- [x] 按状态展示支付截止、取消、签收、收货和完成时间。
- [x] 语言切换不闪回旧订单/空数据，不直接显示后端枚举英文值。
- [x] 移动端无水平滚动；已在 Edge Pixel 9 Pro 427×952 设备模式验收。

## 10. 阶段 6：验收、故障实验和文档

### 10.1 业务与故障验收

- [ ] 主动取消无支付/有待支付订单、重复取消、支付/取消并发。
- [ ] 单次支付过期仍可重试，整单超时自动关闭。
- [ ] 六种主触发/对账组合。
- [ ] MQ 未投递、消费者停机、重复、毒消息和 Database 补偿（RabbitMQ 未投递恢复与 Database 补偿已通过；重复/毒消息及 RocketMQ 待补）。
- [x] 物流推进、暂时/永久回调丢失、重放、迟到可变字段、对账和越权查询；长时间并发乱序压力单列后续。
- [x] Windows Docker 已验证手动/自动收货、自动 `COMPLETED`，完成后仍可查支付/物流并评价；退款记录保留由现有响应契约覆盖。

### 10.2 双平台脚本和 Docker

- [ ] Windows PowerShell 和 Bash 提供同名、同参数、同语义脚本。
- [ ] 三种主策略提供独立 Compose Profile；RabbitMQ Profile 已实现且 Database 默认模式不强制启动 MQ，RocketMQ Profile 待补。
- [ ] 空数据卷从 Flyway 到全链路验收通过。
- [ ] 旧卷升级、服务/MQ 重启、任务恢复通过。
- [ ] 脚本默认不清空数据；破坏性重建需显式参数。

### 10.3 文档交付与完成门禁

- [x] `docs/ORDER-LIFECYCLE.md`、`docs/MOCK-LOGISTICS.md`、`IMPLEMENTED.md` 和 README 已同步自动收货/完成的实现边界与验收脚本。
- [ ] 写清六种配置、策略快照、切换边界、回滚、监控和告警。
- [ ] 订单、支付、退款、物流和任务无矛盾状态。
- [ ] 收货/完成已验证重试、重放、受控并发和重启恢复不重复转换；待关单的真实并发与 MQ 故障实验后完整勾选。
- [ ] 客户、商家、管理员和服务回调权限边界通过。
- [ ] Windows 与 Linux/Bash 验收结果对齐。

## 11. 基础稳定后再做的视觉代办

- [x] 订单卡片改为“摘要 + 当前主操作 + 可展开详情”。
- [ ] 重做退款区域、已收货状态和评价表单。
- [x] 统一支付、退款、物流和订单时间线视觉。
- [x] 补齐骨架屏、空状态、错误状态和响应式布局。

启动视觉改造前，状态机和数据契约必须稳定，六种组合和关键竞态必须验收通过，页面不能因状态变化丢失历史信息。

## 12. 实施记录模板

### 2026-11-12：通用定时任务底座阶段 1

- 迁移：`V15__generic_scheduled_tasks.sql`、`V16__scheduled_task_policy_audit.sql`。
- 配置：`commerceflow.scheduling` 默认策略、10 类任务策略、Database 扫描、对账和租约恢复。
- 实现：策略解析/快照、Handler 注册表、统一执行器、Database 扫描、对账、租约恢复、管理员治理 API 与审计。
- 验证：后端 6 个集成测试通过，Flyway 15/16 在现有 MySQL 卷升级成功，Windows/Bash 管理 API 专项脚本通过。
- 遗留：除 `PAYMENT_TIMEOUT` 外的生产业务 Handler、RabbitMQ/RocketMQ 及六组合故障实验。
- 提交：未提交。

### 2026-11-12：订单取消与 Database 超时垂直闭环

- 迁移：`V17__order_lifecycle_foundation.sql`，补充订单截止/取消/收货/完成时间及订单事件任务来源。
- 配置：`commerceflow.order-lifecycle` 三类时限；`PAYMENT_TIMEOUT` 默认使用 Database + 对账。
- 实现：客户主动取消、渠道交易关闭、本地支付关闭、幂等重试、支付/取消状态保护、超时 Handler 和提前取消任务。
- 验证：后端 6 个集成测试、MySQL 旧卷迁移、Windows 与容器化 Bash 专项脚本通过。
- 补充：取消终态同事务写入 `ORDER_CANCELLED` Outbox，订单响应新增 `availableActions`，Angular 接入未支付取消表单。
- 验证：后端集成测试校验 Outbox 和动作契约，Windows/Bash 专项脚本与全量 Docker 回归通过。
- 遗留：Outbox 下游消费者、真实并发压力测试、RabbitMQ/RocketMQ。
- 提交：未提交。

### 2026-11-12：独立模拟物流最小完整闭环

- 迁移：商城 `V18__logistics_projection.sql`；物流平台 `V1__logistics_platform.sql`。
- 组件：新增 `logistics-mysql` 和 `mock-logistics-service`，物流平台不直连商城数据库。
- 实现：幂等建单、运单更正、六段轨迹状态机、请求 HMAC/时间戳/Nonce 防重放、签名回调、回调去重/防倒退、客户归属查询和 Angular 展开轨迹。
- 验证：物流服务构建通过；后端 6 项集成测试通过；Angular 生产构建通过；Docker 旧卷升级后全服务健康；Windows 专项验证签名建单、重放 `409`、伪造签名 `401`、`CREATED -> DELIVERED` 及商城投影；Windows `verify-all -SkipBuild` 全量回归通过。
- 跨平台边界：Bash 同语义脚本已提供；当前 Git Bash 缺少 `jq`，本轮未实机执行 Bash 专项。
- 当时遗留：回调重试 Outbox、主动对账与丢失/乱序回调故障实验；已由后续“物流回调 Outbox 与主动对账”阶段完成主要闭环。
- 提交：未提交。

### 2026-11-12：物流自动轨迹与异常恢复

- 迁移：物流平台 `V2__automatic_progress_and_exceptions.sql`，增加异常前恢复状态、自动扫描索引和轨迹事件类型；商城 `V19__logistics_event_types.sql` 保留事件类型投影。
- 实现：默认关闭的自动轨迹扫描；人工 `advance`、自动推进、异常和恢复均使用行锁与条件更新。
- 异常语义：`EXCEPTION` 暂停自动推进，不创建 `AUTO_RECEIVE`；`RECOVERY` 回到记录的异常前状态，迟到的普通 `PROGRESS` 回调不能误恢复异常件。
- 验证：物流手动专项通过 `EXCEPTION -> RECOVERY -> DELIVERED`；自动专项将每步压缩为 1 秒，通过 `CREATED -> DELIVERED` 并创建 `AUTO_RECEIVE`；后端 11 个集成测试通过。
- 配置恢复：专项脚本结束时必须重建物流服务并恢复默认关闭，避免污染后续手动实验。
- 当时遗留：回调 Outbox/重试、主动对账、丢失与乱序回调实验；已由后续阶段完成暂时/永久丢失与迟到字段场景。
- 提交：未提交。

### 2026-11-12：物流回调 Outbox 与主动对账

- 迁移：物流平台 `V3__callback_outbox.sql`；商城 `V20__logistics_reconciliation.sql`。
- 主路径：轨迹与不可变回调正文同事务落库；投递器使用 `NEW/PROCESSING/RETRY/PUBLISHED/DEAD`、领取租约、连接/读取超时、最大次数和稳定事件 ID。
- 兜底：商城定期签名查询权威运单，缺失事件复用同一投影入口，通知审计记为 `RECONCILED（已对账恢复）`。
- 乱序修正：承运商和快递单号是可更正字段，迟到回调不再用旧快照拒绝；签名、商城运单号和不变平台运单号负责身份校验。
- 验证：真实停止 backend 后看到 `RETRY -> PUBLISHED` 且尝试次数增加；关闭投递器制造永久丢失后，主动对账补齐 `IN_TRANSIT`；专项结束恢复默认配置。
- 跨平台边界：Windows Docker 专项已运行；Bash 同语义脚本已提供，待具备 `jq` 的 Bash 环境实机执行。
- 遗留：Prometheus 告警规则和外部告警接收端、长时间并发乱序压力。

### 2026-11-12：物流回调 `DEAD` 治理

- 迁移：物流平台 `V4__callback_outbox_audit.sql`，为回调创建独立审计轨迹。
- 后端：新增管理员回调查询、审计和人工重放 API；非管理员拒绝，原因必填，只允许 `DEAD -> RETRY`。
- 物流平台：使用条件更新和行锁保证单次重放，保留原始回调正文和事件 ID，投递成功后进入 `PUBLISHED（已发布）`。
- 前端：新增 `/admin/logistics-callbacks` 治理页，支持状态筛选、审计展开和 `DEAD` 原因重放，中英文同步。
- 观测：暴露 Outbox 状态 Gauge（仪表）、投递/重放/对账 Counter（计数器）；外部告警管道留待运维阶段。
- 验证：Windows Docker 专项已通过 `DEAD -> RETRY -> PUBLISHED`、客户 `403`、空原因 `400`、重复重放 `409`、审计人/原因、商城幂等和 Prometheus 指标。
- 提交：未提交。

### 2026-11-12：自动确认收货与自动完成

- 配置：`ORDER_AUTO_RECEIVE_AFTER_DELIVERY`（送达后自动收货观察期）和 `ORDER_AUTO_COMPLETE_AFTER_RECEIPT`（收货后自动完成观察期），默认均为 7 天。
- 实现：`DELIVERED（已送达）` 创建 `AUTO_RECEIVE（自动确认收货）`；手动收货取消该任务；手动或自动收货后创建 `AUTO_COMPLETE（自动完成订单）`；最终执行 `RECEIVED（已收货） -> COMPLETED（已完成）`。
- 一致性：所有路径共用行锁、条件更新、稳定任务键和统一 Handler；任务执行时重读订单状态，迟到或重复触发安全结束。
- 验证：后端 10 个集成测试通过，含手动/自动收货的两线程竞态与过期租约重试；Angular 生产构建通过；Windows Docker 专项脚本完整验证手动取消任务、自动收货、自动完成、支付/物流保留和完成后评价。
- 故障验收：将已执行的 `AUTO_COMPLETE` 伪造为工作进程在确认前崩溃，停止并重启 backend；`LEASE_RECOVERY（租约恢复）` 将其重新领取并成功结束，`ORDER_AUTO_COMPLETED（订单自动完成）` 事件仍只有一条。
- 跨平台边界：Bash 同语义脚本已提供并通过语法检查；当前 Git Bash 缺少 `jq`，未执行真实 Bash 业务链路。
- 当时遗留：长时间高并发压力测试、RabbitMQ/RocketMQ 主触发适配器及 MQ 级故障实验；RabbitMQ 主路径与数据库兜底已由后续阶段完成。
- 提交：未提交。

### 2026-11-12：RabbitMQ 定时任务主触发

- 迁移：`V21__rabbitmq_scheduled_task_trigger.sql`，记录发布时间、尝试次数和最后发布错误，并为待发布扫描建立索引。
- 配置：新增可选 `rabbitmq` Compose Profile；连接/心跳、Publisher Confirm、发布扫描、交换机/队列/路由键、TTL 分桶、Database 扫描、租约恢复和对账周期均可由环境变量覆盖。
- 实现：持久化 TTL 分桶 + DLX、Confirm/Return、未确认任务补发、Ready/DLQ、有限退避消费重试、早到重新分桶、数据库重读和统一执行器原子抢占。
- 策略：任务保存 `RABBITMQ` 快照；管理员后续修改只影响新任务。开启 Database 对账时只改变本次实际来源，不修改主策略快照。
- 验证：后端 12 个集成测试、默认/Profile Compose 配置检查及 Windows `verify-all -SkipBuild` 全量 Docker 回归通过；正常链路验证任务 176；Broker 停机产生 4 次失败并在恢复后由任务 178 补发成功；Broker 停机且开启对账时任务 179 由 `DATABASE_RECONCILIATION` 成功兜底。
- 跨平台边界：Windows 专项脚本已执行；Bash 同语义脚本已提供，但当前 Git Bash 缺少 `jq`，尚未实机执行。
- 遗留：RabbitMQ 重复/毒消息专项验收、RocketMQ 两种组合、长时间压力测试。
- 提交：未提交。

后续记录继续使用以下模板：

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

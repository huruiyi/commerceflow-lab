# CommerceFlow Lab 统一进度与未实现代办

## 1. 文档职责

| 项目 | 内容 |
| --- | --- |
| 性质 | 全项目唯一的进度勾选表与未实现任务清单 |
| 主线 | 原 `temp.md` 中准备实施的订单闭环方案 |
| 合并内容 | 订单超时策略、Database/RabbitMQ/RocketMQ 讨论、截图阶段 4～6 和旧实施计划 |
| 当前状态 | 通用定时任务底座、Database/RabbitMQ/RocketMQ 主触发与 Database 对账、订单取消、模拟物流、回调 Outbox/主动对账、自动确认收货/自动完成已实现 |
| UI 边界 | 客户订单中心已改为摘要、当前主操作与可展开详情，支付、退款、物流和时间线保持独立事实展示 |
| 最后更新 | 2026-11-13 |

本文档保留已完成勾选和未完成项，用于表示实施顺序和阶段门禁。已实现能力的详细证据见 [IMPLEMENTED.md](IMPLEMENTED.md)，当前快照见 [PROJECT-STATUS.md](PROJECT-STATUS.md)，详细策略见 [通用定时任务策略](reference/SCHEDULED-TASK-STRATEGIES.md)。旧草案和旧实施计划移入 `archive/`，不再同步勾选状态。

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
- [x] 客户主动取消未支付订单，Angular 客户页面已按 `availableActions` 接入。
- [x] 整单支付截止时间、Database 自动关单及与支付成功的并发状态处理，Windows/Bash 真实竞态浸泡压测已通过。
- [x] Database、RabbitMQ、RocketMQ 可插拔主触发和可独立开关的 Database 对账补偿。
- [x] 任务策略快照，保证同一任务不随全局配置中途换策略。
- [x] 独立模拟物流服务、签名回调、轨迹投影与客户查询。
- [x] 物流签收后由 `AUTO_RECEIVE（自动确认收货）` 推进收货，再由 `AUTO_COMPLETE（自动完成订单）` 执行 `RECEIVED -> COMPLETED`。
- [x] 订单响应新增 `availableActions`，客户页面已接入取消、支付、退款、物流、收货和评价操作，历史支付/退款/物流信息保留。

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
| 已取消后意外支付成功 | 恢复资金事实，同事务创建自动退款单并进入 `REFUNDING` |
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
- [x] Flyway 空库创建、旧数据升级和非法状态转换测试通过；Windows/容器化 Bash 已使用隔离 MySQL 验证 V1–V23 与 V16→V23，后端 18 个测试包含订单非法转换。

## 6. 阶段 2：未支付订单关闭与可插拔触发

先实现唯一正确的关单 Handler，再接三种触发器；不得各写一套业务逻辑。

### 6.1 客户主动取消

- [x] 新增 `POST /api/purchase-orders/{orderId}/cancel`，仅订单所属客户可取消 `PENDING_PAYMENT`。
- [x] 锁定订单，执行 `PENDING_PAYMENT -> CANCELLING`，关闭所有 `PENDING` 渠道交易。
- [x] 全部关闭后执行 `CANCELLING -> CANCELLED`，写入时间、原因和订单事件。
- [x] 取消终态与 `ORDER_CANCELLED` Outbox 在同一本地事务写入。
- [x] 实现订单取消 Outbox 的库存释放、客户通知等下游消费者；Kafka 独立消费组与消费记录保证重复消息不重复释放或通知。
- [x] 无支付记录或全部已 `FAILED/CLOSED/EXPIRED` 时可直接完成取消。
- [x] 存在成功支付时普通取消不会覆盖支付事实，订单保持/恢复 `PAID`；前端已通过独立退款动作处理已支付订单。
- [x] 重复请求幂等返回原结果，不重复改变已关闭渠道交易。

### 6.2 支付/取消并发

- [x] 渠道关闭先成功时进入 `CANCELLED`。
- [x] 支付通知先落库时支付优先，订单进入 `PAID`，普通取消不覆盖。
- [x] `CANCELLED` 后意外成功通知不忽略：恢复支付事实，使用稳定幂等键创建自动退款单，转入 `REFUNDING` 并复用退款对账。
- [x] 使用两个真实线程和受控门闩复现“取消已进入 `CANCELLING`、支付成功回调随后到达”；数据库锁与条件更新保证最终为 `PAID/SUCCESS`，且不生成 `ORDER_CANCELLED`。
- [x] Docker MySQL + 真实模拟支付平台的长时间随机化竞态压测：Windows 120.6 秒/95 轮、容器化 Bash 121 秒/97 轮，两类合法终态均覆盖且 MySQL 矛盾记录为 0。

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
- [x] **RocketMQ**：5.x gRPC 客户端、原生绝对投递时间、Broker 回执、发布失败重试、消费端重试/死信、早到重投和重启后未发布任务恢复。
- [x] RabbitMQ/RocketMQ 消息只携带 `taskId/taskType/dueAt/strategySnapshot`，消费时从数据库重读任务和业务事实。

### 6.5 Database 对账和管理配置

- [x] `reconciliation.enabled` 是独立补偿开关，不是第四种主策略。
- [x] 只处理超过 `dueAt + gracePeriod` 仍未完成的任务，保留原策略快照和补偿来源。
- [x] 通过 `@ConfigurationProperties` 完整绑定并校验全部通用策略、RabbitMQ 和 RocketMQ 适配器字段，并支持环境变量覆盖。
- [x] 管理员改全局默认只影响新任务；旧任务继续使用快照。
- [x] 管理员 API 与 Angular 管理页可新增/覆盖/恢复策略，查询、重试、取消任务，并查看策略审计与任务事件。
- [x] 验证三种主触发 × 对账关/开的六种组合；RocketMQ 开启对账时已实测 Broker 停机、发布失败记录和 `DATABASE_RECONCILIATION` 兜底。

### 6.6 阶段门禁

- [x] 主动取消无支付、有待支付、重复请求均已通过 Windows 与容器化 Bash 专项验收。
- [x] 单次支付过期后，整单未超时时仍可重试。
- [x] 整单超时时必须先关闭待支付交易，再进入 `CANCELLED`。
- [x] 支付/取消真实竞态与长时间随机化浸泡压测不造成错账。
- [x] RabbitMQ/RocketMQ 重复、迟到旧消息与未确认重投不重复执行业务副作用。
- [x] 支付/取消锁等待超时专项：Windows 与容器化 Bash 均验证超时返回 `503/DATABASE_CONCURRENCY_RETRY` 和 `Retry-After`，事务回滚无中间事实，释放锁后重试成功。
- [x] 支付/取消关键事务窗口进程崩溃恢复专项：双平台 SIGKILL 覆盖 `CANCELLING` 恢复、渠道已关/本地未提交、回调事务回滚和迟到成功回调。

## 7. 阶段 3：独立模拟物流

### 7.1 服务、数据与状态

- [x] 新建独立 `logistics-simulator-service`、数据库和 Docker 服务；首版不做物流管理 UI。
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
- [x] 覆盖暂时不可达、永久丢失、重复、迟到可变字段、篡改、超时和主动对账。
- [x] 物流回调高并发乱序浸泡压测：Windows 120 秒/12 并发/1320 条，容器化 Bash 120 秒/12 并发/2652 条；状态不倒退，不变字段不被旧回调覆盖。

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

- [x] 主动取消无支付/有待支付订单、重复取消、支付/取消并发；Windows 与容器化 Bash 脚本已覆盖 `PAID/SUCCESS` 和 `CANCELLED/CLOSED` 两种竞态终态，并完成 95/97 轮两分钟浸泡实验。
- [x] 单次支付过期仍可重试，整单超时自动关闭；Windows 与容器化 Bash 已验证换渠道重试、渠道关闭和 Database 任务单次执行。
- [x] 六种主触发/对账组合。
- [x] RabbitMQ/RocketMQ 未投递与 Database 补偿。
- [x] RabbitMQ/RocketMQ 重复消息、迟到旧消息和毒消息专项：重放已完成任务消息只增加 ignored 指标，任务领取/完成仍各一次；非法 JSON 分别进入 RabbitMQ Dead Queue 和 RocketMQ `%DLQ%ConsumerGroup`。
- [x] 消费者已取得消息但尚未确认时进程退出、恢复后的重新投递专项：Windows Docker 与容器化 Bash 已分别验证 RabbitMQ/RocketMQ 任务 `239/240` 和 `241/242`，崩溃前后无新增发布，且领取/完成各一次。
- [x] 物流推进、暂时/永久回调丢失、重放、迟到可变字段、对账和越权查询；长时间并发乱序压力仍为独立未完成项。
- [x] Windows Docker 已验证手动/自动收货、自动 `COMPLETED`，完成后仍可查支付/物流并评价；退款记录保留由现有响应契约覆盖。

### 10.2 双平台脚本和 Docker

- [x] Windows PowerShell 和 Bash 的 36 组主要验收入口已通过同名、有序参数和语义对齐审计。
- [x] 三种主策略可独立运行；RabbitMQ 和 RocketMQ 提供可选 Compose Profile，Database 默认模式不强制启动 MQ。
- [x] 空数据卷从 Flyway 到全链路验收通过。
- [x] 隔离旧库升级演练、当前持久卷服务/MQ 重启、任务恢复和历史一致性通过。
- [x] 脚本默认不清空现有数据卷；全新卷专项只在显式参数下删除其自身随机临时项目卷。

### 10.3 文档交付与完成门禁

- [x] `docs/ORDER-LIFECYCLE.md`、`docs/MOCK-LOGISTICS.md`、`IMPLEMENTED.md` 和 README 已同步自动收货/完成的实现边界与验收脚本。
- [x] 已写清六种配置、策略快照、切换边界、回滚、已有监控与待实现告警边界。
- [x] 订单、支付、退款、物流和任务已经快速回归、专项不变量和历史指纹审计，未发现矛盾状态。
- [x] 收货/完成已验证重试、重放、受控并发、Database 重启/租约恢复、RabbitMQ/RocketMQ 主触发故障恢复和双平台 120 秒/12 并发竞态浸泡。
- [x] 客户、商家、管理员和服务回调权限边界通过；快速入口含全角色矩阵，支付/物流专项含签名、伪造回调和越权拒绝。
- [x] Windows 与容器化 Bash 的 M1 专项和快速回归结果已对齐。

## 11. M1：可复测基线冻结与验证体系启动门禁

只有以下项目全部完成，才开始编写“整个开发生命周期的可复测验证体系”文档：

- [x] 完成支付/取消锁等待超时专项。
- [x] 完成支付/取消关键窗口进程崩溃恢复专项。
- [x] 完成物流高并发乱序压测。
- [x] 完成收货/完成的 MQ 故障与长时间高并发实验。
- [x] 全新空数据卷从 Flyway 到登录、下单、支付、履约、物流、收货、完成和评价的全链路通过。
- [x] 隔离 V16→V23 升级演练后，当前持久卷完成服务/MQ 重启、未完成任务恢复和历史数据一致性验收。
- [x] 完成 Windows/Bash 36 组主要脚本的同名、参数和语义对齐审计。
- [x] 确认所有默认脚本不清空现有数据卷，破坏性临时卷清理只能通过显式参数启用。
- [x] 建立可重复的 Windows/Bash 快速回归入口，并以 Git 标签 `m1-baseline-2026-11-13` 冻结 M1 基线。

M1 完成后依次完成了可复测验证体系文档、“退款区域/已收货/评价表单”视觉重做和 Prometheus Rule、Alertmanager/可替换 Webhook 告警集成；各阶段均独立验证后提交。

- [x] 已建立 [全开发生命周期可复测验证体系](VERIFICATION-SYSTEM.md)，统一 L0–L6 分层门禁、改动矩阵、数据安全边界、证据格式、失败处理和基线冻结清单。

## 12. M1 和验证体系文档之后再做的视觉与运维代办

- [x] 订单卡片改为“摘要 + 当前主操作 + 可展开详情”。
- [x] 重做退款申请/回执、已收货/已完成状态和逐商品评价表单；Angular 25 项测试、生产构建、真实数据桌面与 390 px 窄屏验收通过。
- [x] 统一支付、退款、物流和订单时间线视觉。
- [x] 补齐骨架屏、空状态、错误状态和响应式布局。
- [x] 集成 Prometheus Alert Rule、Alertmanager 和可替换 Webhook 通知管道；10 条规则、三服务抓取、规则单测及 Windows/容器化 Bash Watchdog 实时投递均通过。

视觉改造和本地生产式告警管道均已完成。真实邮箱、短信、电话或值班平台凭据不提交到学习仓库，替换接收端和生产高可用边界见 [ALERTING.md](ALERTING.md)。本轮固定序列到此结束，停止新增业务代码并先汇报结果。

## 13. M2：代码质量与模块化单体治理

本节只跟踪跨模块工程质量，不混入新增业务功能，也不替代 `docs/architecture/` 下的架构待办。
详细审计基线和实施门禁见 [CODE-QUALITY-PLAN.md](CODE-QUALITY-PLAN.md)。

- [x] 完成三个 Java 服务的格式、实体注释、SQL 分布和质量门禁只读审计。
- [x] 修正支付退款实体主键映射并完成 9 项 Maven 测试及 Windows/容器化 Bash 真实退款专项回归。
- [x] 分批清理支付模拟服务的通配符导入和过长行，两项审计计数均已归零。
- [x] 完成物流模拟服务类型与格式治理，并复验乱序与 Outbox 恢复。
- [x] 建立商城服务 ArchUnit 模块依赖门禁，并移除用户 API 直接访问 Mapper 的越层依赖。
- [ ] 分业务包治理商城服务的通配符导入和过长行；`identity`、`catalog`、`cart` 已完成，
  其余业务包待继续。
- [ ] 通过新增 Flyway 迁移补充关键数据库列注释，不改历史迁移。
- [ ] 接入与 IntelliJ IDEA 结果一致的自动格式检查，并纳入快速验证矩阵。

## 14. 实施记录模板

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
- 组件：新增 `logistics-mysql` 和 `logistics-simulator-service`，物流平台不直连商城数据库。
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
- 验证：真实停止 commerce-service 后看到 `RETRY -> PUBLISHED` 且尝试次数增加；关闭投递器制造永久丢失后，主动对账补齐 `IN_TRANSIT`；专项结束恢复默认配置。
- 跨平台边界：Windows Docker 专项已运行；Bash 同语义脚本已提供，待具备 `jq` 的 Bash 环境实机执行。
- 当时遗留：Prometheus 告警规则、外部告警接收端和长时间并发乱序压力；后续已补齐告警管道与双平台乱序浸泡。

### 2026-11-12：物流回调 `DEAD` 治理

- 迁移：物流平台 `V4__callback_outbox_audit.sql`，为回调创建独立审计轨迹。
- 后端：新增管理员回调查询、审计和人工重放 API；非管理员拒绝，原因必填，只允许 `DEAD -> RETRY`。
- 物流平台：使用条件更新和行锁保证单次重放，保留原始回调正文和事件 ID，投递成功后进入 `PUBLISHED（已发布）`。
- 前端：新增 `/admin/logistics-callbacks` 治理页，支持状态筛选、审计展开和 `DEAD` 原因重放，中英文同步。
- 观测：暴露 Outbox 状态 Gauge（仪表）、投递/重放/对账 Counter（计数器）；后续运维阶段已接入 Prometheus Rule、Alertmanager 和可替换 Webhook。
- 验证：Windows Docker 专项已通过 `DEAD -> RETRY -> PUBLISHED`、客户 `403`、空原因 `400`、重复重放 `409`、审计人/原因、商城幂等和 Prometheus 指标。
- 提交：未提交。

### 2026-11-12：自动确认收货与自动完成

- 配置：`ORDER_AUTO_RECEIVE_AFTER_DELIVERY`（送达后自动收货观察期）和 `ORDER_AUTO_COMPLETE_AFTER_RECEIPT`（收货后自动完成观察期），默认均为 7 天。
- 实现：`DELIVERED（已送达）` 创建 `AUTO_RECEIVE（自动确认收货）`；手动收货取消该任务；手动或自动收货后创建 `AUTO_COMPLETE（自动完成订单）`；最终执行 `RECEIVED（已收货） -> COMPLETED（已完成）`。
- 一致性：所有路径共用行锁、条件更新、稳定任务键和统一 Handler；任务执行时重读订单状态，迟到或重复触发安全结束。
- 验证：后端 10 个集成测试通过，含手动/自动收货的两线程竞态与过期租约重试；Angular 生产构建通过；Windows Docker 专项脚本完整验证手动取消任务、自动收货、自动完成、支付/物流保留和完成后评价。
- 故障验收：将已执行的 `AUTO_COMPLETE` 伪造为工作进程在确认前崩溃，停止并重启 commerce-service；`LEASE_RECOVERY（租约恢复）` 将其重新领取并成功结束，`ORDER_AUTO_COMPLETED（订单自动完成）` 事件仍只有一条。
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
- 当时遗留：RabbitMQ 重复/毒消息专项验收、RocketMQ 两种组合、长时间压力测试；前两项已由后续 RocketMQ 与 MQ 幂等专项完成。
- 提交：未提交。

### 2026-11-12：RocketMQ 5.x 定时任务主触发

- 依赖：Apache RocketMQ Java gRPC Client `5.2.2`；Docker Profile 使用当前可获取的官方镜像 `apache/rocketmq:5.4.0`。
- 配置：新增可选 `rocketmq` Profile，启动 NameServer 和带 Proxy 的 Broker，持久化日志/存储卷，并初始化 `message.type=DELAY` Topic。
- 实现：将 `dueAt` 转为绝对投递时间；发布成功记录 message ID，失败保留未发布状态以待补发；消费端重读数据库并复用统一执行器，早到消息重新投递，异常由 Broker 重试/死信。
- 验证：后端容器构建通过；默认与 RocketMQ Profile Compose 配置通过；Windows 和容器化 Bash 均完成真实下单、Broker 回执、原生延时投递、原子领取、超时关单和审计链验收（任务 226/227）；Broker 停机时发布失败可观测且 Database 对账成功兜底（任务 228/229）。
- 验证补充：Windows 与容器化 Bash 的 `verify-mq-scheduling-idempotency` 已完成 RabbitMQ/RocketMQ 重复消息、迟到旧消息和毒消息实验（任务 233～236）。
- 验证补充：Windows Docker 与容器化 Bash 的 `verify-mq-scheduling-redelivery` 均已在 RabbitMQ/RocketMQ 消费窗口中强制终止 commerce-service，任务 `239/240`、`241/242` 恢复后均只领取/完成一次。
- 遗留：长时间故障压力测试。
- 提交：待本批次提交。

### 2026-11-12：支付尝试过期与整单截止时间

- 实现：创建新支付时使用订单 `payment_due_at` 做领域门禁；已有幂等请求先返回原结果，截止时间后拒绝产生新渠道交易。
- 语义：单次 `expires_at` 只终止当前渠道尝试；整单 `payment_due_at` 到期后，`PAYMENT_TIMEOUT` 统一执行器先关闭尚在 `PENDING` 的重试交易，再将订单转为 `CANCELLED`。
- 自动验证：后端 17 个测试通过，新集成测试同时覆盖截止时间前换渠道和截止时间后拒绝新支付。
- 实机验证：Windows 订单 `ORD-46AF0F1B96954117`/任务 `244`，容器化 Bash 订单 `ORD-370124308FED4BC0`/任务 `245`，均通过 `ALIPAY EXPIRED -> WECHAT_PAY PENDING -> CLOSED -> order CANCELLED`，任务领取与完成各一次。
- 环境恢复：两个脚本都会恢复 `PAYMENT_TIMEOUT` 策略和原环境变量，并重建商城/支付页代理以避免 Nginx 缓存旧容器地址。
- 提交：待本批次提交。

### 2026-11-12：Flyway 空库、旧库升级与非法状态

- 隔离：脚本为每次运行创建随机命名的 Docker 网络和两个 MySQL 8.4 容器，结束时只删除这些临时资源，不挂载或清空 Compose 开发数据卷。
- 空库：固定 `redgate/flyway:12.4.0` 与后端运行时一致，23 个迁移从空 Schema 全部成功，并确认定时任务、物流投影和库存预占表存在。
- 旧库：先以 `target=16` 建立旧 Schema 并插入历史待支付订单，再升级到 V23；V17 将 `payment_due_at` 精确回填为 `created_at + 30m`。
- 非法状态：MySQL 实机拒绝 `scheduled_tasks.status=CORRUPTED`；新集成测试确认 `PAID` 订单不能重新支付、普通取消或确认收货，自动收货/完成对非目标状态也不会改写事实。
- 验证：Windows 与容器化 Bash 两套脚本实机通过；后端 18 个集成测试通过。
- 边界：本项不勾选“空数据卷全链路”或“旧卷服务/MQ 重启恢复”，那两项仍需独立完成。
- 提交：待本批次提交。

### 2026-11-13：支付/取消随机化竞态浸泡压测

- 工具：扩展快速竞态脚本，支持最少轮数、持续时间、固定随机种子、轮间节流和本轮 Docker MySQL 审计；新增 Windows/Bash 同名 stress 入口。
- 时序：每 3 轮固定覆盖支付优先、取消优先和随机近同时，每轮都轮询到非中间态后再判定订单/支付/退款组合。
- 限流：首轮无节流实验在第 81 轮真实命中支付平台 `120 requests/minute` 签名限流；保留限流后增加 900ms 轮间节流和仅针对该 `429` 的 5 秒有限退避。
- 库存：stress 入口通过商家 API 将演示商品可售库存补到安全水位，保留人工调整流水，不直改库存表或删除压测订单。
- 结果：Windows `120.6s/95` 轮，`PAID/SUCCESS=55`、`CANCELLED/CLOSED=40`；容器化 Bash `121s/97` 轮，分别为 `58/39`。两次 MySQL 审计的订单数=支付记录数=本轮轮数，矛盾记录为 0。
- 边界：这是单机 Docker 两分钟浸泡验收，不等同于多小时/多日容量规划、多实例压力或网络分区验收；脚本可将时长调至 86400 秒继续扩展。
- 提交：待本批次提交。

### 2026-11-13：支付/取消锁等待超时专项

- 配置：commerce-service MySQL 连接使用可配置的 `innodb_lock_wait_timeout`，Compose 默认 5 秒；专项脚本临时压缩为 2 秒并在结束时恢复 commerce-service，不删除数据卷。
- 契约：Spring 短暂数据访问异常统一返回 HTTP `503`、`DATABASE_CONCURRENCY_RETRY` 与 `Retry-After: 1`；支付回调不再把这类异常包装成不可区分的通用异常。
- 验证：Windows 和容器化 Bash 同名脚本分别持有订单行与支付行锁；首次取消/回调超时后订单、支付、通知和事件仍为原始事实，释放锁后重试分别达到 `CANCELLED` 与 `PAID/SUCCESS`。
- 自动测试：Docker Maven 构建通过 19 个测试，其中新增错误契约单元测试。
- 边界：本项不代表进程在关键事务窗口崩溃的恢复已完成；该项仍是 M1 下一门禁。
- 提交：本阶段独立提交（见 Git 历史）。

### 2026-11-13：支付/取消关键事务窗口进程崩溃恢复

- 故障注入：新增默认关闭、必须同时指定订单 ID 和阶段的实验暂停点；脚本观测到精确窗口后对 commerce-service 发送 SIGKILL，结束后恢复默认关闭。
- 取消窗口：已验证 `PENDING_PAYMENT -> CANCELLING` 提交后崩溃可重试完成；渠道已持久化 `CLOSED`、本地仍为 `CANCELLING/PENDING` 时崩溃，重试不重复业务事实且只写一次 `ORDER_CANCELLED`。
- 回调窗口：支付记录已改为 `SUCCESS` 但回调事务未提交时崩溃，MySQL 完整回滚订单、支付、通知和事件；重放相同回调后只提交一份 `PAID/SUCCESS` 事实。
- 迟到回调：订单留在 `CANCELLING`、渠道成功回调稍后到达时，资金事实优先，订单恢复为 `PAID`。
- 验证：Windows 与容器化 Bash 同名脚本均通过；Docker Maven 19 项测试通过。
- 边界：这是单机容器的精确事务窗口实验，不代表多节点容灾或网络分区验收。
- 提交：本阶段独立提交（见 Git 历史）。

### 2026-11-13：物流高并发乱序浸泡压测

- 语义修正：不同事件 ID 的同级状态仍保留完整轨迹，但只有严格前进的状态才更新当前投影、写订单状态事件或创建自动收货任务；避免多个 `DELIVERED` 事件重复触发副作用。
- 自动验证：Docker Maven 20 项测试通过；新测试固定“两个 `DELIVERED` + 一个迟到 `CREATED`”仍只有一次投影副作用和一条 `AUTO_RECEIVE`。
- 浸泡输入：每条回调使用唯一事件 ID，随机打乱 `CREATED -> DELIVERED` 状态和 `occurredAt`，并故意携带过时承运商/单号字段。
- 结果：Windows 两分钟、12 并发、1320 条全部 `PROCESSED`，投影实际前进 2 次；容器化 Bash 两分钟、12 并发、2652 条全部 `PROCESSED`，投影实际前进 1 次。两轮的轨迹数=通知审计数=发送数，最终状态均为 `DELIVERED`，`AUTO_RECEIVE` 均只有一条，平台运单号/承运商/快递单号不变。
- 边界：这是单机 Docker 两分钟乱序浸泡，不等同于多实例、多小时/多日容量或网络分区测试。
- 提交：本阶段独立提交（见 Git 历史）。

### 2026-11-13：`AUTO_RECEIVE/AUTO_COMPLETE` MQ 故障实验

- 环境：保留现有数据卷，临时开启 RabbitMQ/RocketMQ 适配器和 1 秒发布扫描；任务关闭 Database 对账，确保故障恢复由原 MQ 完成。
- 场景：对 RabbitMQ/RocketMQ 各自验证 `AUTO_RECEIVE` 和 `AUTO_COMPLETE`；Broker 停机后创建任务，确认发布次数/错误落库，将到期时间压缩后恢复 Broker。
- 结果：Windows 任务 `588/592/594/598` 和容器化 Bash 任务 `601/605/607/611` 均按 `primary_trigger_snapshot` 由对应 MQ 执行，`TASK_SUCCEEDED` 与订单转换均只有一次。
- 恢复：两套脚本都在 `finally/trap` 中恢复策略、Broker、环境变量和默认 services/commerce-service/web，不删除或清空任何数据卷。
- 边界：本阶段是单机 Docker Broker 停机/恢复实验，不等同于 MQ 集群多节点灾备。
- 提交：本阶段独立提交（见 Git 历史）。

### 2026-11-13：收货/完成长时间高并发竞态浸泡

- 设计：每轮通过完整支付、商家履约和物流签收链路创建新订单，然后并发客户收货请求与 RabbitMQ 同任务重投；收货后再对 `AUTO_COMPLETE` 重复同等压力。
- 竞态：奇偶轮交替 100ms 偏置，确保 `ORDER_RECEIVED` 与 `ORDER_AUTO_RECEIVED` 两种合法赢家都被覆盖；HTTP `503` 只作为可重试并发结果，最终业务不变量必须全部成立。
- Windows：120 秒、9 轮、12 并发、864 次 HTTP/MQ 操作，手动赢 5 轮、自动赢 4 轮。
- 容器化 Bash：120 秒、11 轮、12 并发、1056 次 HTTP/MQ 操作，手动赢 6 轮、自动赢 5 轮。
- 不变量：每轮收货转换仅一次，`AUTO_RECEIVE/AUTO_COMPLETE` 业务键各一条，完成事件、任务领取和任务成功各一次，且无 `FAILED` 任务。
- 边界：这是单机 Docker 中的长时间业务竞态浸泡，不等同于多日容量测试或多区域灾备。
- 提交：本阶段独立提交（见 Git 历史）。

### 2026-11-13：全新空数据卷完整业务链路

- 环境：使用随机 Compose 项目名和独立新卷；暂停后恢复默认项目，不删除或清空默认 `fairy-commerceflow_*` 卷。
- 发现与修复：空卷首次启动暴露 Spring Boot 4 下支付/物流服务仅引入 `flyway-core` 未激活自动迁移；改用 `spring-boot-starter-flyway` 后完成 V1–V3/V1–V4。
- 验证：商城/支付/物流 Flyway 分别为 V23/V3/V4，无失败迁移；初始数据为 6 用户、6 商品、0 订单。
- 链路：Windows 和容器化 Bash 均跑完登录、下单、签名支付回调、商家履约、物流、手动/自动收货、自动完成、commerce-service 重启租约恢复和评价，业务审计为 `1|1|2|1|1`。
- 破坏性边界：默认保留临时卷以便排查；只有显式 `-RemoveTemporaryVolumes` / `--remove-temporary-volumes` 才删除本次临时项目卷。
- 提交：本阶段独立提交（见 Git 历史）。

### 2026-11-13：旧库升级、全栈重启恢复与历史一致性

- 升级边界：在隔离 MySQL 中重放 V1–V16，插入历史订单后升级到 V23，验证 30 分钟支付截止时间回填和当前约束；这是可重放旧库演练，不冒充生产备份升级。
- 持久卷：现有商城/支付/物流数据保持在 V23/V3/V4，无失败迁移；本阶段未删除任何现有卷。
- 重启范围：三个 MySQL、Redis、Kafka、支付/物流服务、services/commerce-service/web/UI、RabbitMQ 和 RocketMQ 均实际重启并恢复健康。
- 未完成任务：注入已领取但租约过期的 `AUTO_COMPLETE` 任务；Windows 任务 `1391/1392`、容器化 Bash 任务 `1393/1394` 分别由 RabbitMQ/RocketMQ 恢复，`TASK_LEASE_EXPIRED/TASK_CLAIMED/TASK_SUCCEEDED` 均各一次。
- 历史一致性：终态订单、支付、已送达物流和评价的计数+行 CRC 聚合指纹在重启前后均为 `300:3562683193|559:1055010103|83:1695107719|21:1066884382`。
- 提交：本阶段独立提交（见 Git 历史）。

### 2026-11-13：Windows/Bash 脚本契约与默认非破坏性审计

- 文件名：35 组 `verify-*` PowerShell/Bash 入口基名一一对应；Windows-only 的视频生成器不属于业务验收入口。
- 参数修正：对齐物流服务 URL、支付服务 URL、RabbitMQ 用户名/密码/等待时间，以及支付/取消竞态的有序参数。
- 安全语义：不存在默认 `down -v`、`docker volume rm/prune`；空卷专项的临时卷删除只能显式开启，Flyway 临时 MySQL 改用 tmpfs 避免匿名卷泄漏。
- 自动审计：PowerShell 与容器化 Bash 入口均返回通过，契约覆盖 35 组脚本；全部 PowerShell AST 和 Bash `-n` 语法检查通过。
- 轻量实链路：Windows 支付通知的新支付地址参数通过；容器化 Bash 按新顺序运行 1 轮支付/取消竞态，以 `PAID/SUCCESS` 且数据库审计通过结束。
- 提交：本阶段独立提交（见 Git 历史）。

### 2026-11-13：快速回归入口与 M1 基线冻结

- 入口：新增 `verify-quick.ps1/.sh`，默认构建当前源码镜像，支持 `-SkipBuild` / `--skip-build`；不删除或清空数据卷。
- 覆盖：36 组脚本契约、Compose 配置/健康、权限/i18n、订单取消、支付/取消竞态、全额退款、物流、手动/自动收货、自动完成、评价、commerce-service 租约恢复和 Redis 降级。
- 验证：Windows `-SkipBuild`、容器化 Bash `--skip-build` 和 Windows 默认构建模式均通过；默认构建模式使用当前源码重建五个应用镜像。
- 边界：快速回归不代替两分钟浸泡、Broker 停机、空卷全链路或旧卷重启专项；这些已在前置阶段独立验收。
- 冻结：本阶段提交以注释标签 `m1-baseline-2026-11-13` 冻结并推送。M1 后停止新增业务代码；验证体系文档已在 M1 后单独建立。

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

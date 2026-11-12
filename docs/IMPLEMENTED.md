# 已实现能力与验证边界

## 1. 文档规则

本文档是当前源码树的已实现摘要。“已实现”表示已有代码和数据契约；实机或跨平台尚未验收的内容必须单独标注。未实现内容一律放入 [TODO.md](TODO.md)。

## 2. 基础商城

- 用户、角色、Session 登录、CSRF、登录限流和 Remember-Me。
- 商品、购物车、收货地址、下单和订单地址/商品快照。
- 客户、商家和管理员的资源权限边界。
- Angular 运行时中英文切换。
- 客户订单中心使用“摘要 + 当前主操作 + 可展开详情”，展示支付截止、取消、签收、收货和完成时间，并保持支付、退款、物流事实独立。
- 订单页已提供骨架屏、空状态、错误重试、时间线去噪和响应式布局；桌面端与 Edge Pixel 9 Pro 427×952 设备模式已实机验收。

## 3. 支付

- 独立模拟支付服务与支付数据库。
- 支付宝、微信、银联三种模拟渠道与 Vue 收银台。
- 支付单、渠道交易、请求幂等、金额/币种校验。
- HMAC/RSA 签名、防重放、回调白名单、TOTP/恢复码、Step-up 与审计链。
- 异步通知、Transactional Outbox、Kafka 幂等消费、定时补偿和对账。

详见 [高级模拟支付](reference/ADVANCED-PAYMENT.md)。

## 4. 退款首版

- 只支持 `PAID/CONFIRMED` 且未发货订单的原路整单全额退款。
- 订单使用 `REFUNDING/REFUNDED`，原 `payment_record=SUCCESS` 事实不改写。
- 商城和支付平台分别建模退款单、事件、通知和对账。
- 客户申请、商家只读摘要、支付后台台账/通知补发与双前端国际化已实现。
- Windows Docker 专项验收已记录通过；Linux/WSL 实机复验、部分退款、退货/换货不在首版。
- 退款首版已随 `d963e7b` 提交并推送。

详见 [退款实现说明](reference/REFUND.md)。

## 5. 商家履约、收货与评价

- 商家可查看自己的订单、确认订单、录入承运商/物流单号并发货。
- 发货和物流更正都经过独立模拟物流平台；平台使用独立 MySQL，不直连商城库。
- 物流请求支持 HMAC、时间窗口、Nonce 防重放和幂等建单；回调使用独立 HMAC 密钥和通知 ID 去重。
- 已实现 `CREATED -> PICKED_UP -> IN_TRANSIT -> AT_STATION -> OUT_FOR_DELIVERY -> DELIVERED` 轨迹投影；可人工推进，也可启用可配置的自动推进。
- 非终态运单可进入 `EXCEPTION（异常件）`，自动推进暂停；明确 `RECOVERY（异常恢复）` 后回到异常前状态继续运输。
- 每个轨迹事件与 Callback Outbox 在同一本地事务提交；后台投递支持领取租约、HTTP 超时、失败重试、最大次数和进程重启接管。
- 商城主动签名查询物流平台并补齐永久丢失事件；回调与对账复用事件 ID 和同一投影入口，分别记录 `PROCESSED（回调处理）` 与 `RECONCILED（对账恢复）`。
- 超过最大投递次数的回调进入 `DEAD（停止自动重试）`；管理员可查询、查看审计并填写原因重放，重放使用原始事件 ID 保持幂等。
- 物流回调已暴露积压、投递结果、`DEAD`、人工重放和主动对账 Prometheus 指标。
- 物流状态投影只在严格前进时触发副作用，同级和迟到旧事件仍保留轨迹但不重复调度。Windows 和容器化 Bash 两分钟/12 并发乱序浸泡分别通过 1320/2652 条唯一回调，最终保持 `DELIVERED`、审计精确对齐且过时回调未覆盖承运商或单号。
- 客户可查询本人订单的完整物流轨迹；Angular 订单详情展开时，后续状态始终保留完整物流区域。
- 同值物流重复保存不会无限生成时间线事件。
- 客户可手动确认收货并对订单商品一次性评价；手动收货会取消未执行的 `AUTO_RECEIVE（自动确认收货）` 任务。
- 物流首次进入 `DELIVERED（已送达）` 后创建 `AUTO_RECEIVE`；观察期到期且订单仍为 `SHIPPED（已发货）` 时自动进入 `RECEIVED（已收货）`。
- 手动或自动收货都会幂等创建 `AUTO_COMPLETE（自动完成订单）`；观察期到期后从 `RECEIVED` 进入 `COMPLETED（已完成）`。
- `COMPLETED` 仍保留支付记录、物流轨迹和未评商品的评价入口，同时不再提供取消、确认收货或未发货退款动作。
- 已用两线程同时抢占验证手动收货与 `AUTO_RECEIVE` 只会形成一次有效转换；过期租约可在 backend 重启后恢复，幂等重放不会重复完成订单。
- `AUTO_RECEIVE/AUTO_COMPLETE` 的 RabbitMQ/RocketMQ 故障实验已通过 Windows 与容器化 Bash 实测：Broker 停机时发布尝试和错误可观测，恢复后由原策略快照执行，`TASK_SUCCEEDED` 与订单状态副作用均只有一次。
- 收货/完成竞态浸泡已通过 Windows 120 秒/9 轮/12 并发/864 次操作和容器化 Bash 120 秒/11 轮/12 并发/1056 次操作；交替偏置覆盖手动收货和自动收货两种赢家，每轮只有一次收货转换、一条 `AUTO_COMPLETE` 任务和一次完成事件。

自动轨迹默认关闭，避免干扰手动学习实验；回调 Outbox/重试和物流主动对账默认开启。详见 [独立模拟物流](MOCK-LOGISTICS.md)。

## 6. 基础设施和交付

- MySQL + Flyway、Redis/Spring Session、Kafka/Outbox、RabbitMQ 分桶延迟任务、RocketMQ 5.x 原生延时任务 Profile、ShedLock。
- Angular/Vue 前端、三个 Spring Boot 服务和 Docker Compose。
- `scripts/windows/` 与 `scripts/linux/` 保留同名验收脚本结构。
- `verify-flyway-lifecycle` 使用与后端一致的 Flyway `12.4.0` 和隔离 MySQL 8.4，Windows/容器化 Bash 均已验证空库 V1–V23、V16→V23 升级、V17 历史订单 30 分钟截止时间回填和任务状态 `CHECK` 约束。脚本只删除自己的随机命名容器/网络，不使用或清空开发数据卷。

## 7. 通用定时任务底座（阶段 1）

- 建立 `scheduled_tasks`、`scheduled_task_events`、`scheduled_task_policies` 和策略审计表。
- 内置 10 类任务目录，并允许管理员为新的任务类型保存策略；只有 Java 中注册了 Handler 的类型才能真正创建和执行任务。
- 配置文件提供系统默认与各任务初始策略，数据库覆盖优先；创建任务时固化主触发、对账、宽限期、租约、重试和策略版本快照。
- 已实现 Database、RabbitMQ 与 RocketMQ 三种主触发、独立 Database 对账、原子领取、执行租约、失败重试、过期租约恢复和 ShedLock 多实例互斥。
- RabbitMQ 适配器使用持久化分桶 TTL 队列和 DLX：发布端启用 Confirm/Return，失败保留未发布任务并重试；消费端重读数据库并复用统一执行器，格式错误进入 DLQ，基础设施异常有限退避后进入 DLQ。
- RabbitMQ 消息只携带 `taskId/taskType/dueAt/strategySnapshot`；数据库任务快照仍是唯一执行依据，重复消息和 Database 对账通过同一条件更新竞争，只有一方能够领取。
- RabbitMQ 是可选 Compose Profile，默认 Database 模式不启动 Broker；连接、心跳、发布扫描、拓扑、延迟分桶、Database 扫描、租约恢复和对账周期都支持环境变量覆盖。
- RocketMQ 适配器使用 Java gRPC 客户端和绝对投递时间；发布成功后记录 Broker message ID，消费端重读数据库并复用统一执行器，早到消息会清除发布标记重新投递。
- RocketMQ 也是可选 Compose Profile，提供 NameServer、Broker/Proxy、持久化数据卷和 `message.type=DELAY` Topic 初始化；默认 Database 模式不启动它。
- 管理员 API 支持策略查询/覆盖/恢复、策略审计、任务查询/重试/取消和任务事件查询；客户角色被拒绝访问。
- Windows 专项脚本及后端集成测试已通过；Bash 同语义脚本已提供。

当前已接入 `PAYMENT_TIMEOUT（支付超时）`、`AUTO_RECEIVE（自动确认收货）` 和 `AUTO_COMPLETE（自动完成订单）` 三个业务 Handler；其他任务类型 Handler 仍属于待办。

RabbitMQ 实机验收已覆盖：正常延迟关单、Broker 停机时不误执行、发布失败可观测、恢复后补发，以及开启 Database 对账时由 `DATABASE_RECONCILIATION` 兜底。Windows 脚本已执行通过；Bash 同语义脚本已提供，本机 Git Bash 因缺少 `jq` 未完成实机复验。

RocketMQ 实机验收已通过 Windows 和容器化 Bash 两套脚本：确认 Broker 回执、原生延时投递、策略快照、原子领取、支付超时关单及审计链。Broker 停机专项也已验证发布失败可观测、`DATABASE_RECONCILIATION` 宽限期兜底和恢复 Broker。

MQ 幂等专项已通过 Windows 和容器化 Bash 实机验收：RabbitMQ/RocketMQ 分别重放两条已完成任务的旧消息，ignored 指标按预期增加，而 `TASK_CLAIMED`、`TASK_SUCCEEDED` 和订单取消副作用仍各只有一次；非法 JSON 分别进入 RabbitMQ Dead Queue 与 RocketMQ `%DLQ%commerceflow-scheduled-task-consumer`。RocketMQ 教学 Consumer Group 使用 2 次、1 秒/2 秒的有限重试，避免毒消息无限占用资源。

MQ 未确认重新投递专项已通过 Windows Docker 与容器化 Bash 实机验收：故障注入点在 Broker 已投递、统一执行器尚未领取且消费者未确认时暂停；零秒宽限停止 backend 后，RabbitMQ/RocketMQ 任务 `239/240`、`241/242` 均由原 Broker 消息重新投递，崩溃前后没有新增发布事件，`TASK_CLAIMED`、`TASK_SUCCEEDED` 和订单取消均只有一次。

Angular 管理后台已增加“定时任务”入口，可新增/覆盖/恢复策略，查看 Handler 可用性、策略来源和版本，并按类型/状态查询任务、查看事件、重试失败任务或取消待执行任务。页面维护的是数据策略，不能上传可执行代码。

## 8. 订单取消与 Database 超时首个垂直闭环

- 新订单保存 `payment_due_at`，同时创建带策略快照的 `PAYMENT_TIMEOUT` 任务。
- 客户可通过 `POST /api/purchase-orders/{id}/cancel` 取消自己的待支付订单；重复取消幂等返回 `CANCELLED`。
- 订单使用 `PENDING_PAYMENT -> CANCELLING -> CANCELLED`，并保存取消原因、取消时间、触发来源和关联任务 ID。
- 有待支付交易时先签名调用支付平台关闭渠道交易，再关闭本地支付记录并完成订单取消；进程中断后可从 `CANCELLING` 重试。
- 支付回调与取消并发时，成功支付可从 `CANCELLING/CANCELLED` 恢复 `PAID`；已进入 `CANCELLED` 后才到达的成功回调会在同一事务中创建自动退款单并转入 `REFUNDING`。
- Database 扫描使用同一个 `PaymentTimeoutTaskHandler` 自动关单；客户提前取消会取消尚未执行的超时任务。
- 取消终态和 `ORDER_CANCELLED` Outbox 使用同一本地事务写入；独立 Kafka 消费组会幂等释放预占库存、记录库存流水并生成客户通知。
- 客户订单响应包含后端统一计算的 `availableActions`；Angular 已使用它展示支付、取消、退款、收货和评价操作。
- 客户页面已接入未支付订单取消表单，取消后保留原因、时间和历史支付信息；导航通知中心展示未读数量，并支持单条或全部已读。
- Windows 与容器化 Bash 专项验收覆盖动作契约、无支付取消、重复取消、任务快照/取消和待支付渠道关闭。
- 受控双线程集成测试已固定复现支付回调赢得取消竞态的时序，验证资金事实优先、单一订单事件且不误写取消终态。
- Windows PowerShell 与容器化 Bash 的 `verify-payment-cancellation-race` 使用交替偏置和随机抖动并发调用真实商城/支付平台，已验证 `PAID/SUCCESS` 与 `CANCELLED/CLOSED` 都不会产生矛盾账务状态。
- `verify-payment-cancellation-stress` 在不关闭支付平台每分钟签名请求限流的前提下增加节流与有限退避，并按本轮订单 ID 直接审计 Docker MySQL。Windows 120.6 秒/95 轮（`55 PAID` + `40 CANCELLED`）、容器化 Bash 121 秒/97 轮（`58 PAID` + `39 CANCELLED`）均为 0 矛盾记录。
- `verify-payment-cancellation-lock-timeout` 在真实 MySQL 中分别持有订单行和支付行锁；Windows 与容器化 Bash 均验证首次请求返回 `503/DATABASE_CONCURRENCY_RETRY` 与 `Retry-After`，超时事务无中间状态，释放锁后取消和支付回调都可成功重试。
- `verify-payment-cancellation-crash-recovery` 用默认关闭的定点暂停钩子和 backend SIGKILL 覆盖四个窗口：`CANCELLING` 中间态恢复、渠道已关但本地未完成、支付回调事务回滚/重放、迟到成功回调恢复资金事实；Windows 与容器化 Bash 均已通过。
- 单次渠道交易进入 `EXPIRED` 后不关闭订单；`payment_due_at` 未到时可换渠道发起新支付，到期后创建新支付会被后端拒绝。
- `verify-payment-attempt-deadline` 的 Windows 与容器化 Bash 真实链路均已通过：整单截止时间到达后，Database 任务先关闭当前待支付渠道，再取消订单，且领取/完成事件各一次。
- 全新独立数据卷已在 Windows 与容器化 Bash 跑完完整链路：商城/支付/物流 Flyway 分别达到 V23/V3/V4，从 0 订单开始完成登录、下单、支付、履约、物流、收货、完成、重启恢复和评价。
- V16→V23 隔离旧库升级演练已通过；在不删现有卷的前提下，Windows 和容器化 Bash 均已完成数据层、业务服务和两种 MQ 重启，RabbitMQ/RocketMQ 租约过期任务各恢复一次，终态业务历史指纹不变。

## 9. 库存预占与商家库存中心

- 商品保存可售、预占和已售三个余额；结算原子扣减可售并增加预占，支付成功转为已售，取消事件幂等释放预占。
- 每次预占、转正、释放和人工调整均写库存流水及调整后余额；人工调整不能将可售库存降为负数。
- 商家库存 API 只允许查询和调整自己名下的商品，人工调整必须填写原因。
- Angular 商家端使用 Sim UI 卡片、徽章和按钮展示库存汇总、商品余额、调整表单及最近流水。

## 10. 尚未实现的总边界

以下不得在 README、页面或演示中标记为已完成：

- Prometheus 告警规则、Alertmanager/外部通知接收端。

详细顺序和验收门禁只见 [TODO.md](TODO.md)。

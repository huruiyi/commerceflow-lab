# 已实现能力与验证边界

## 1. 文档规则

本文档是当前源码树的已实现摘要。“已实现”表示已有代码和数据契约；实机或跨平台尚未验收的内容必须单独标注。未实现内容一律放入 [TODO.md](TODO.md)。

## 2. 基础商城

- 用户、角色、Session 登录、CSRF、登录限流和 Remember-Me。
- 商品、购物车、收货地址、下单和订单地址/商品快照。
- 客户、商家和管理员的资源权限边界。
- Angular 运行时中英文切换。
- 客户订单中心使用“状态色轨 + 订单摘要 + 当前主操作 + 可展开详情”；大屏折叠态使用固定列对齐状态、金额和操作，商品数量与收件人收纳到订单基本信息。支付成功/已关闭由订单状态表达，摘要仅在支付处理中、失败或过期等需要关注时显示支付状态；展开态的商品图片提供目录地址修复与失败占位，支付详情采用单层信息布局，空订单历史不再渲染。
- 订单页已提供骨架屏、空状态、错误重试、时间线去噪和响应式布局；本轮生产镜像重建后已在 Edge 完成默认桌面视口与 390×844 窄屏实机验收。
- 全局导航在大屏使用与页面主体一致的 1480 px 居中容器，不再将品牌与账户工具贴到屏幕两端。

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
- 已用两线程同时抢占验证手动收货与 `AUTO_RECEIVE` 只会形成一次有效转换；过期租约可在 commerce-service 重启后恢复，幂等重放不会重复完成订单。
- `AUTO_RECEIVE/AUTO_COMPLETE` 的 RabbitMQ/RocketMQ 故障实验已通过 Windows 与容器化 Bash 实测：Broker 停机时发布尝试和错误可观测，恢复后由原策略快照执行，`TASK_SUCCEEDED` 与订单状态副作用均只有一次。
- 收货/完成竞态浸泡已通过 Windows 120 秒/9 轮/12 并发/864 次操作和容器化 Bash 120 秒/11 轮/12 并发/1056 次操作；交替偏置覆盖手动收货和自动收货两种赢家，每轮只有一次收货转换、一条 `AUTO_COMPLETE` 任务和一次完成事件。

自动轨迹默认关闭，避免干扰手动学习实验；回调 Outbox/重试和物流主动对账默认开启。详见 [独立模拟物流](MOCK-LOGISTICS.md)。

## 6. 基础设施和交付

- MySQL + Flyway、Redis/Spring Session、Kafka/Outbox、RabbitMQ 分桶延迟任务、RocketMQ 5.x 原生延时任务 Profile、ShedLock。
- Angular/Vue 前端、三个 Spring Boot 服务和 Docker Compose。
- `scripts/windows/` 与 `scripts/linux/` 保留同名验收脚本结构。
- `verify-flyway-lifecycle` 使用与后端一致的 Flyway `12.4.0` 和隔离 MySQL 8.4，Windows/容器化 Bash 均验证空库 V1–V34、V16→V34 升级、V17 历史订单截止时间回填、V24 核心注释、V28 catalog/inventory 所有权分离、V29 catalog 版本/Outbox 元数据、V30 inventory 命令 Inbox/跨边界外键清理、V31 catalog-event Inbox/版本化快照、V32 命令投递/结果协议表、V33 业务恢复状态及 V34 人工恢复审计。脚本只删除自己的随机容器/网络，不使用或清空开发数据卷。

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

MQ 未确认重新投递专项已通过 Windows Docker 与容器化 Bash 实机验收：故障注入点在 Broker 已投递、统一执行器尚未领取且消费者未确认时暂停；零秒宽限停止 commerce-service 后，RabbitMQ/RocketMQ 任务 `239/240`、`241/242` 均由原 Broker 消息重新投递，崩溃前后没有新增发布事件，`TASK_CLAIMED`、`TASK_SUCCEEDED` 和订单取消均只有一次。

Angular 管理后台已增加“定时任务”入口，可新增/覆盖/恢复策略，查看 Handler 可用性、策略来源和版本，并按类型/状态查询任务、查看事件、重试失败任务或取消待执行任务。页面维护的是数据策略，不能上传可执行代码。

## 7. 架构命名与代码治理

- 核心商城保持模块化单体，按 `identity/catalog/cart/ordering/inventory/payment/refund/fulfillment/logistics/receipt/review/notification/scheduling/shared` 业务包分界。
- 目录已分为 `services/`、`web/`、`simulators/`和 `infrastructure/`；Angular 商城门户与未来 Vue 3 门户使用明确的并列边界。
- Java/Maven 使用 `vip.fairy.commerceflow`；Compose 使用 `fairy-commerceflow` 项目名和“业务归属 + 角色”服务名，不设置固定 `container_name`。
- 商城、支付模拟器和物流模拟器保留三个独立 MySQL；商城与支付模拟器使用独立 Redis，不共享 Session 或故障边界。
- 12 个 MyBatis-Plus 持久化实体已增加类/字段业务 JavaDoc；简单 CRUD 优先使用 `BaseMapper`/Wrapper，复杂投影、联表、行锁和原子更新放入 Mapper XML。
- 三个服务主源码中已无 MyBatis SQL 注解和 Service 内联业务 SQL；仅 ShedLock 官方 `JdbcTemplateLockProvider` 配置保留 `JdbcTemplate`。
- 三个后端服务的 Java 主源码、Mapper XML 和 Maven POM 已接入 Windows/Bash 只读质量门禁；
  当前覆盖 283 个主源码 Java、21 个 Mapper XML、235 个映射语句和 3 个 POM，并对全部 292 个
  Java 主源码/测试源校验类声明后空行，同时校验格式、内联 SQL/JDBC 边界与 Mapper 契约。
- 物流模拟器根包只保留启动类，业务实现已按 `api/application/domain/infrastructure` 分层；
  基础设施继续按客户端、配置、指标、持久化和安全分目录，Mapper 投影独立放入持久化 `row` 子目录。
- Angular 商城门户和支付模拟器 Web 均已纳入模块本地 Prettier 3.10.1 只读门禁，两个正式 Dockerfile
  都会在生产构建前强制检查。Angular 的 127 个源码/配置文件、29 项单元测试、本地/Docker 生产构建和
  运行态 i18n/权限专项已验证；支付 Web 的 24 个基线文件完成机械格式化，格式检查、无用例 Vitest、
  TypeScript/Vite 生产构建、正式镜像和 `14201/healthz`/Vue 入口运行态已验证。
- 新空卷验收已通过：Flyway 商城/支付/物流为 V24/V4/V5，完成登录→下单→支付→履约→物流→收货→完成→评价；Windows 快速回归同时通过。
- notification-service 与 catalog-service 已完成独立边界切换；inventory、order、payment-orchestration、
  fulfillment 尚未拆分。PostgreSQL 专项必须等 M3 清单中的全部服务完成单一所有权切换并验收后才允许
  启动；后续新增待拆服务也必须先完成。M3.1 初始审计盘点了 37 张表、40 组跨模块依赖方向、
  Outbox/Inbox、消费者契约和追踪缺口，并以 2 项 Maven 基线测试阻止所有权和依赖债务继续增长。
- M3.3 catalog 准入首批已建立 `CatalogProductLookup`、`CatalogCacheInvalidator` 和
  `ProductReviewCatalogQuery` 应用边界；cart/order 不再注入 `ProductMapper`，catalog 不再注入
  `ReviewMapper`。跨模块基线由 41/118/38/21 降至 41/113/37/18，相关 infrastructure 引用上限收紧为 0。
  Java 25 正式镜像 29 项测试和 Windows/Bash 快速回归通过；尚未创建 catalog-service、独立 Schema 或迁移数据。
- M3.3 第二批以 V28 新建 inventory 自有 `product_inventory`，将数量和必要商品快照从 `products` 分离；
  cart/customer order 固化创建时商品快照，库存、购物车、结算和订单读取不再跨边界 join `products`。
  现有库 6 条库存记录及 642/1/65 数量总和保持不变，空库/旧库双平台升级门禁通过；仍未创建 catalog-service、
  inventory-service 或独立数据库，PostgreSQL 继续冻结。
- M3.3 第三批以 V29 和分层 `api/v1`、`api/merchant` 固定列表、详情、批量快照、商家资料/价格/状态/翻译
  契约；expectedVersion 冲突返回稳定错误，写事务同时保存 catalog Outbox 并同步 inventory 展示快照。
  五类事件 V1、fixture、eventId 去重与 businessVersion 乱序/缺口规则已冻结。Java 25 正式镜像 32 项测试、
  双平台生命周期/快速回归和真实 Docker V1 查询通过；创建 API、独立 catalog-service/Schema 尚未启动。
- M3.3 第四批新增独立 catalog-service/catalog-db、HMAC 内部 API、2 秒连接/3 秒读取的 commerce HTTP
  代理和一次性 V29 数据交接。6 条商品与 6 条翻译通过计数/内容指纹后标记 COMPLETE，重复迁移拒绝覆盖；
  commerce 已删除 catalog Mapper/XML/Outbox 写入和过渡投影。catalog 停机返回稳定 503 且 commerce
  健康隔离，commerce→catalog 跨进程 Trace 已验证；inventory-service 与 PostgreSQL 继续冻结。
- M3.4 inventory 准入首批新增分层 `inventory/application/port`：ordering、payment、refund 只依赖
  `InventoryCommandPort`，预占命令使用 inventory 自有最小商品项，商家库存应用层只接收 merchantId。
  ArchUnit 禁止库存外模块重新依赖库存 API、domain、infrastructure 或具体实现。当前依赖为
  39/109/36/18；order 外键、取消通知副作用、独立 Inbox 和 catalog 事件消费仍待下一批治理，未创建
  inventory-service/inventory-db，也未启动 PostgreSQL。Java 25 的 30 项测试、Windows 新运行 JAR 快速
  回归及容器化 Bash 质量/脚本审计已通过。
- M3.4 第二批以 `application/contract/v1` 冻结四类 inventory 命令和 fixture，V30 增加命令 Inbox 并在
  库存本地事务中完成去重、余额变化和成功状态；重复载荷幂等、同 ID 异载荷拒绝。V30 同时移除两条
  inventory→shop_orders 外键，删除 customerId 跨表查询；取消通知由 ordering 事务写 notification Outbox，
  inventory→notification 依赖清零。Java 25 的 32 项测试、双平台 V1→V30/V16→V30 演练及 Windows 快速回归
  通过；默认 Docker 持久库的 17 条真实命令全部 SUCCEEDED，非成功命令和跨边界订单外键均为 0。独立服务、
  独立数据库与 PostgreSQL 仍未启动。
- M3.4 第三批打通 catalog-service Outbox 到 inventory 商品快照的 HMAC HTTP 投递，签名包含请求体
  SHA-256；V31 增加 catalog-event Inbox、生命周期和 businessVersion。重复/异载荷、STALE、版本缺口、
  翻译事件和 INACTIVE 预占门禁均有测试；catalog 最多 5 次后 DEAD。真实 Docker 已验证正常 PUBLISHED，
  以及 commerce 停机 RETRY、恢复后第 4 次尝试成功且 inventory 单次 APPLIED。catalog 5 项、commerce
  35 项测试、双平台 V31 迁移和 Windows 快速回归通过；inventory-service/inventory-db 与 PostgreSQL 仍未启动。
- M3.4 第四批新增统一 `InventoryCommandGateway`、V32 delivery Outbox/结果 Inbox 和
  `InventoryCommandResultV1`。当前本地适配器在原业务事务内调用唯一 Handler 并留下 SUCCEEDED/
  ACKNOWLEDGED 证据；远程切换使用 NEW/IN_FLIGHT/RETRY/RECONCILIATION_REQUIRED 状态机，未知结果不伪造
  拒绝或自动反向补偿。成功/拒绝 fixture、语义指纹、重复命令和超时上限测试已通过，commerce 共 37 项测试；
  双平台 V32 生命周期、Windows 快速回归和双平台质量/脚本审计通过。默认持久库 32 条协议命令全部形成
  SUCCEEDED/ACKNOWLEDGED 配对证据，未决投递为 0；远程传输、独立 inventory-service/inventory-db 和
  PostgreSQL 均未启动。
- M3.4 第五批通过 V33 增加订单与退款自有 inventoryStatus。订单显式经历等待预占、等待提交、已提交、
  退款等待或待对账，发货门禁只接受 COMMITTED；退款财务状态不再替代库存归还状态。超时状态机通过端口
  投影 RECONCILIATION_REQUIRED，ADMIN 恢复入口以原 commandId/原 payload 重试并收口 ACK 与业务状态，
  V34 保留操作者、原因与时间。Java 25 正式镜像 38 项测试、双平台 V34 生命周期、Windows 快速回归和质量
  门禁通过；真实持久库 53 条命令
  全部 ACKNOWLEDGED，订单/退款无等待或待对账残留。独立 inventory-service 与 PostgreSQL 未启动。
- M3.4 第六批完成 inventory-service 最终 CREATE 准入评审。运行手册区分迁入独立库的五张所有权表与留在
  commerce 的生产端 delivery Outbox/结果 Inbox，冻结快照、增量追平、短暂停写、最终指纹、唯一远程
  适配器、跨进程 Trace 和独立新写后的受控回退规则。Windows/Bash 只读审计均通过 V34、七张边界表、
  零跨域外键、余额不变量、命令闭环、零待对账及单一 Gateway；五表基线分别为 6、263、548、87、2 行。
  本批未创建 inventory-service/inventory-db、未迁移或切流；CUTOVER 与 PostgreSQL 继续冻结。
- M3.4 第七批新增分层 inventory-service、独立 MySQL 和默认关闭流量的 `inventory-shadow` Profile。V1
  创建五张所有权表，命令/catalog HTTP 适配器复用冻结的 Inbox、指纹、余额状态机、HMAC 和 Trace 信封；
  未签名请求 401，已签名请求在关闭门禁下稳定 503。可重复迁移连续至少 20 代 READY，快速回归后的五表完整
  字段指纹一致。
  Java 25 的 2 项测试、双平台影子验收、扩展后的质量和 43 组脚本契约门禁通过。commerce/catalog 未改指向，
  独立库未接收业务新写，CUTOVER 与 PostgreSQL 继续冻结。
- notification 写入已从 InventoryMapper 收回：V25 建立模块自有 Inbox，V1 通知命令包含版本、发生时间、
  correlation/causation ID，Handler 在同一事务中完成 Inbox 领取和通知写入；契约 fixture、重复消费及
  Windows/Bash 真实取消通知链路已通过。当前审计基线为 38 张表、41 组方向和 119 个 import。
- V26 增加 notification 自有投递 Outbox；进程内/Kafka 适配器可配置切换，具备持久重试、本地 DEAD/重放、
  Kafka DLT、Broker 恢复和受控单体回退。V1 payload/Header 传播 correlation/causation/W3C traceparent，
  Inbox 保存证据；notification application 查询只接收 customerId，不再传递 `AppUser`。29 项测试及正式镜像、
  真实 Kafka 取消、毒消息 DLT、停机恢复和 FALLBACK 通过。当前基线为 39 张表、41 组方向、118 个 import。
- V27 增加 notification 投影验证窗、窗口级影子投影和核对审计；停止要求最少 5 条匹配、零差异、零阻塞
  Outbox 且连续 3 次通过。真实 Kafka 窗口达到 `READY` 后显式停止，三次审计全部通过。独立 notification
  Schema V1 的三张自有表、零外键、快照和 Inbox 幂等已在 Windows/容器化 Bash 隔离 MySQL 中通过。
  商城 30 项测试、正式镜像和双平台门禁通过；当前为 42 张商城表，依赖基线仍为 41/118/38/21。
  默认已恢复 `IN_PROCESS` 且投影关闭；实际运行仍使用商城 Schema，未创建微服务、独立运行数据库或 PostgreSQL。
- M3.1 最终门禁已让 notification 在 commerce-service 进程内实际使用独立 MySQL：V2/V3 提供回填水位、
  核对审计和孤儿隔离，专用 MyBatis/Flyway/事务边界不替代商城主数据源。Windows/容器化 Bash 均完成
  历史追平、Kafka 实时镜像、连续三次零差异、独立读、已读状态 eventId 镜像、独立库停机和商城回滚。
  当前两库通知均为 167 条；默认恢复 `IN_PROCESS + COMMERCE`。31 项测试、正式镜像和 39 对脚本门禁通过。
  notification-service 与 PostgreSQL 仍未启动，M3.1 完成后仅具备 M3.2 的后续启动条件。
- M3.2 准入首批为三个 Java 服务接入 OpenTelemetry，并增加默认关闭的 OTLP 导出、可选 Tempo 和
  Grafana Trace 数据源。Windows/容器化 Bash 已查询到 commerce-service → logistics-simulator-service
  的同一 HTTP Trace，并验证 Tempo 停机不影响业务健康；Kafka `send/process` Span 及 notification
  来源/传输 traceparent 分离已实测。RabbitMQ 通过 Observation 与显式 W3C 上下文跨越 TTL/DLX，
  RocketMQ 通过消息 properties 传播；Windows 与容器化 Bash 均查询到 publish/process 同 Trace，并验证
  Tempo 停机降级。四种边界的本地追踪准入门禁已补齐，但 notification-service 与 PostgreSQL 仍未启动。
- M3.2 影子首批已新增独立 `notification-service`：分层包、独立 Flyway/MyBatis/MySQL、稳定 Kafka
  consumer group、Inbox/eventId 幂等、有限重试 DLT、健康与影子状态端点均已实现。Windows 与容器化
  Bash 实测实时消费、停机积压恢复、commerce/notification 同 Trace 和切回 `IN_PROCESS`；质量门禁为
  293/22/239/4、全部 303 个 Java 源文件及 42 对脚本。该 Profile 默认关闭，客户 API 仍在单体，
  notification 表和回退路径未删除，PostgreSQL 未启动。
- M3.2 单一所有权批次已将 notification-service 纳入默认 Compose 拓扑，并补齐列表、未读数、eventId
  单条已读和全部已读内部 V1 API。commerce 的公开路径保持不变，但只负责 Session/RBAC、HMAC 签名和
  有超时的 HTTP 转发；独立服务故障返回 `503/NOTIFICATION_SERVICE_UNAVAILABLE`，Angular 显示可重试状态。
  commerce 内通知读写 Mapper、进程内消费者、独立数据源、投影比较、回填核对和旧模式配置已删除，
  只保留订单事务侧 Outbox、V1 生产契约与历史 Flyway。Windows/容器化 Bash 运行专项和快速回归已验证
  Kafka 投递、eventId 已读、服务停机 503、恢复和核心商城健康；当前质量基线为 277/21/198/4、全部 287 个
  Java 源文件与 41 对脚本。Windows 完整验收和 Bash 等价全链路业务阶段通过；Git Bash 告警尾段补验
  3 个采集目标、10 条规则和 Watchdog 真实投递。切换时 commerce 历史通知为 0 行，消费者三分区 lag
  均为 0。M3 清单中的全部服务完成单一所有权切换并验收前继续使用 MySQL，不启动 PostgreSQL 专项或
  数据库方言分支。
- 已建立 [全开发生命周期可复测验证体系](VERIFICATION-SYSTEM.md)，将现有构建、快速/完整回归、空卷/升级恢复、MQ/崩溃专项和浸泡压测组织为 L0–L6 门禁，并固定证据、破坏性操作和基线冻结规则。
- Angular 客户订单页已将退款申请/回执、`RECEIVED/COMPLETED` 履约状态和逐商品评价改为独立响应式面板；评价增加评分、必填内容、防重复提交和成功/失败反馈，未修改后端状态机或接口契约。
- 上述界面已通过 25 项 Angular 单元测试、生产构建、Docker 实际页面与现有 `PAID/REFUNDED/RECEIVED/COMPLETED` 数据验收；浏览器控制台无错误，390 px 窄屏无横向溢出。
- `observability` Profile 已接入 Prometheus、10 条可单测告警规则、Alertmanager、Grafana 的 Prometheus/Tempo
  数据源和只读本地 Webhook 接收端；商城、支付模拟器、物流模拟器三个 Spring Boot Target 均可抓取。
- `verify-alerting` 已在 Windows 与容器化 Bash 通过：Prometheus/Alertmanager 配置有效，服务宕机/退款积压/物流 `DEAD` 规则单测通过，Watchdog 经真实 Alertmanager 路由到 Webhook。
- M2 工程质量总验收已完成：Windows 与容器化 Bash 的默认完整验收、质量审计和 37 对脚本契约审计通过；
  五个正式镜像、默认/全 Profile Compose、默认 11 个核心服务和 8 个主要健康端点已复验。连续执行会在
  全量验收开场通过商户 API 准备测试库存；支付 Web 代理动态解析容器上游；本地单节点 Alertmanager
  使用可重建的 tmpfs 通知日志，监控栈冷启动后 Watchdog 可真实送达 webhook。M2 冻结标签为
  `m2-quality-baseline-2026-11-14`，微服务拆分和 PostgreSQL 迁移尚未启动。

## 8. 订单取消与 Database 超时首个垂直闭环

- 新订单保存 `payment_due_at`，同时创建带策略快照的 `PAYMENT_TIMEOUT` 任务。
- 客户可通过 `POST /api/purchase-orders/{id}/cancel` 取消自己的待支付订单；重复取消幂等返回 `CANCELLED`。
- 订单使用 `PENDING_PAYMENT -> CANCELLING -> CANCELLED`，并保存取消原因、取消时间、触发来源和关联任务 ID。
- 有待支付交易时先签名调用支付平台关闭渠道交易，再关闭本地支付记录并完成订单取消；进程中断后可从 `CANCELLING` 重试。
- 支付回调与取消并发时，成功支付可从 `CANCELLING/CANCELLED` 恢复 `PAID`；已进入 `CANCELLED` 后才到达的成功回调会在同一事务中创建自动退款单并转入 `REFUNDING`。
- Database 扫描使用同一个 `PaymentTimeoutTaskHandler` 自动关单；客户提前取消会取消尚未执行的超时任务。
- 取消终态和 `ORDER_CANCELLED` Outbox 使用同一本地事务写入；独立 Kafka 消费组会幂等释放预占库存、记录库存流水并生成客户通知。
- 客户订单响应包含后端统一计算的 `availableActions`；Angular 已使用它展示支付、取消、退款、收货和评价操作。
- 客户页面已接入未支付订单取消表单，取消后保留原因、时间和历史支付信息；导航通知中心展示未读数量并支持单条或全部已读，使用中性列表、细滚动条和独立正文滚动，面板可通过关闭按钮、页面外点击、`Esc` 或选择通知关闭；语言切换使用紧凑翻译图标并保留无障碍文本。
- Windows 与容器化 Bash 专项验收覆盖动作契约、无支付取消、重复取消、任务快照/取消和待支付渠道关闭。
- 受控双线程集成测试已固定复现支付回调赢得取消竞态的时序，验证资金事实优先、单一订单事件且不误写取消终态。
- Windows PowerShell 与容器化 Bash 的 `verify-payment-cancellation-race` 使用交替偏置和随机抖动并发调用真实商城/支付平台，已验证 `PAID/SUCCESS` 与 `CANCELLED/CLOSED` 都不会产生矛盾账务状态。
- `verify-payment-cancellation-stress` 在不关闭支付平台每分钟签名请求限流的前提下增加节流与有限退避，并按本轮订单 ID 直接审计 Docker MySQL。Windows 120.6 秒/95 轮（`55 PAID` + `40 CANCELLED`）、容器化 Bash 121 秒/97 轮（`58 PAID` + `39 CANCELLED`）均为 0 矛盾记录。
- `verify-payment-cancellation-lock-timeout` 在真实 MySQL 中分别持有订单行和支付行锁；Windows 与容器化 Bash 均验证首次请求返回 `503/DATABASE_CONCURRENCY_RETRY` 与 `Retry-After`，超时事务无中间状态，释放锁后取消和支付回调都可成功重试。
- `verify-payment-cancellation-crash-recovery` 用默认关闭的定点暂停钩子和 commerce-service SIGKILL 覆盖四个窗口：`CANCELLING` 中间态恢复、渠道已关但本地未完成、支付回调事务回滚/重放、迟到成功回调恢复资金事实；Windows 与容器化 Bash 均已通过。
- 单次渠道交易进入 `EXPIRED` 后不关闭订单；`payment_due_at` 未到时可换渠道发起新支付，到期后创建新支付会被后端拒绝。
- `verify-payment-attempt-deadline` 的 Windows 与容器化 Bash 真实链路均已通过：整单截止时间到达后，Database 任务先关闭当前待支付渠道，再取消订单，且领取/完成事件各一次。
- 全新独立数据卷已跑完完整链路：商城/支付/物流 Flyway 分别达到 V24/V4/V5，从 0 订单开始完成登录、下单、支付、履约、物流、收货、完成、重启恢复和评价。
- V16→V24 隔离旧库升级演练已通过；在不删现有卷的前提下，Windows 和容器化 Bash 均已完成数据层、业务服务和两种 MQ 重启，RabbitMQ/RocketMQ 租约过期任务各恢复一次，终态业务历史指纹不变。
- `verify-quick` 已作为快速回归入口在 Windows 和容器化 Bash 通过；M2 冻结前两平台默认完整验收也已通过。
  M1 历史标签为 `m1-baseline-2026-11-13`，M2 质量基线标签为 `m2-quality-baseline-2026-11-14`。

## 9. 库存预占与商家库存中心

- inventory 自有 `product_inventory` 保存可售、预占和已售三个余额；结算原子扣减可售并增加预占，支付成功转为已售，取消事件幂等释放预占。
- 每次预占、转正、释放和人工调整均写库存流水及调整后余额；人工调整不能将可售库存降为负数。
- 商家库存 API 只允许查询和调整自己名下的商品，人工调整必须填写原因。
- Angular 商家端使用 Sim UI 卡片、徽章和按钮展示库存汇总、商品余额、调整表单及最近流水。

## 10. 尚未实现的总边界

以下不得在 README、页面或演示中标记为已完成：

- 真实邮箱、短信、电话或企业值班平台接入，以及 Prometheus/Alertmanager 多副本和通知到人升级流程；当前只验证本地可替换 Webhook 管道。

详细顺序和验收门禁只见 [TODO.md](TODO.md)。

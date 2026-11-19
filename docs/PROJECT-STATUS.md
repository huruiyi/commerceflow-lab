# CommerceFlow Lab 当前项目进度

## 1. 快照与事实来源

| 项目 | 当前值 |
| --- | --- |
| 快照日期 | 2026-11-20 |
| M1 阶段起点基线 | `7ee3854` |
| M1 冻结基线 | Git 标签 `m1-baseline-2026-11-13` |
| M2 冻结基线 | Git 标签 `m2-quality-baseline-2026-11-14` |
| 当前阶段 | M3 六个内部服务均已切流。日常环境已部署库存付款和扣减门禁，订单 695、696 验收通过。 |
| 当前优先级 | 商家入驻与店铺归属已验收并发布，下一章为应结账本与佣金。[后续启动条件](TODO.md#25-缺失能力与服务拆分顺序) |
| 范围边界 | 商城持有身份、购物车、调度、部分投递账本和物流投影。独立履约服务执行，旧源归档和回退包在仓库外保存。数据库继续使用 MySQL。 |

事实优先级为：当前源码/迁移/脚本和实际验收结果 > [IMPLEMENTED.md](IMPLEMENTED.md) > [TODO.md](TODO.md) 勾选状态 > `archive/` 和各专题中标明的历史记录。发现冲突时必须修改文档，不得为了勾选代办而改写实际验收结论。

## 2. 已完成功能域

| 功能域 | 当前状态 | 主要边界 |
| --- | --- | --- |
| 基础商城与权限 | 已实现 | 用户/角色/Session/CSRF、商品、购物车、地址、下单和资源权限 |
| 商家入驻与店铺归属 | 已验收并日常发布 | 一商家一店铺、审核启停、草稿商品、库存归属和存量订单继续履约与售后 |
| 统一结算与运费 | 已验收并日常发布 | 服务端报价、地区运费、订单金额快照、历史迁移与整单退费 |
| 单商家满减券 | 已验收并日常发布 | 活动与券包、领券并发、行分摊、锁券、付款核销、取消返还、中断恢复及优惠订单部分退款。[功能说明](features/CHECKOUT-COUPONS.md) |
| 三渠道模拟支付 | 已实现 | HMAC/RSA、防重放、幂等、异步通知、Outbox/Kafka、对账和安全后台 |
| 订单行售后与部分退款 | 已通过隔离与日常验收 | 未发货部分退款、已收货退货、按行回补与原运费核对 |
| 首版全额退款 | 已实现并双平台验收 | 只支持未发货整单全额退款 |
| 未支付订单取消 | 已实现并验收 | 无支付、有待支付、重复取消、库存释放和通知 |
| 支付尝试/整单截止时间 | 已实现并验收 | 尝试过期可换渠道；整单到期先关渠道再取消 |
| 支付/取消真实竞态 | 已实现并完成浸泡、锁超时和崩溃专项 | 双平台验证锁超时可重试；SIGKILL 覆盖取消中间态、已关渠道窗口、回调事务回滚和迟到成功回调 |
| 通用定时任务 | 已实现核心 | Database/RabbitMQ/RocketMQ、Database 对账、策略快照、租约恢复和管理页 |
| MQ 幂等与重投 | 已实现并验收 | 重复/迟到旧消息、毒消息、DLQ、未 ACK 进程退出与 Broker 重投 |
| 独立模拟物流 | 已实现主链路并完成乱序浸泡 | 双平台 120 秒/12 并发，Windows 1320 条、Bash 2652 条唯一回调；状态不倒退且不变字段未被旧消息覆盖 |
| 退货运单跟踪 | 已隔离验收并日常发布 | 退货绑定、双角色轨迹、回调恢复、实物验收后退款与入库。[验收记录](IMPLEMENTED.md#216-首版退货运单跟踪) |
| 收货、自动收货和自动完成 | M1 可靠性门禁已通过 | 重放、重启/租约恢复、RabbitMQ/RocketMQ 停机恢复和双平台 120 秒/12 并发竞态压测均通过 |
| 库存与商家库存中心 | 已实现 | 可售/预占/已售、转正/释放/退款回补、调整流水 |
| Angular/Vue 界面 | Angular 主流程和订单页视觉收尾已实现 | 订单详情与支付信息已扁平化，商品图有失败回退；通知菜单使用中性列表和细滚动条，支持外部点击、`Esc`、关闭按钮和选择通知后关闭；语言切换使用图标；导航与 1480 px 正文容器对齐，桌面与 390 px 窄屏已验收 |
| Flyway 数据库生命周期 | 空卷和迁移层已验收 | 日常商城 V58、订单 V8、库存 V6、履约 V2、支付编排 V4、目录 V3、通知 V3。 |
| notification 独立服务 | 单一所有权切换已实现 | 默认拓扑启动独立 MySQL/Kafka 服务；commerce 仅签名代理，故障稳定返回 503，无本地数据库回退 |
| catalog 拆分准入 | 单一所有权已切换 | catalog-service/catalog-db 独占资料、翻译、版本和 Outbox；commerce 只保留鉴权、签名代理与 review 组合 |
| inventory 拆分准入 | 完整单一所有权已切换 | 库存服务持有余额、预占、流水、出库单及出库事件；商城持有库存命令投递和结果账本。历史五笔缺证测试订单已归档 |
| 订单、支付编排与履约 | 均已切流 | Order V1 持有正式订单，Payment V1 持有支付与退款，履约服务以 `EXECUTION` 模式持有流程状态。商城旧轻量订单表已归档。 |
| 告警与监控 | P3 日常发布已验收 | Prometheus 加载 39 条规则。商城、库存、订单、目录、通知目标健康；隔离验证了积压、待对账、发布失败、消费滞后和死信的触发与恢复。 |

## 3. M1 冻结结论

M1 按既定 1→9 顺序完成：核心可靠性、空卷/旧库环境生命周期、双平台脚本审计和快速回归入口均有实测证据。基线以注释 Git 标签 `m1-baseline-2026-11-13` 冻结。

## 4. M1 门禁结果

1. 全新空数据卷全链路已通过 Windows 和容器化 Bash 验收。
2. 隔离 V16→V24 旧库升级演练，以及当前持久卷的服务/MQ 重启、未完成任务恢复和历史一致性已通过。
3. Windows/Bash 36 组主要脚本的文件名、参数、语义和默认非破坏性审计已通过。
4. Windows/Bash 快速回归入口已在当前持久卷通过；Windows 默认构建模式也已通过。

## 5. M1 之后的固定顺序

```text
核心可靠性收尾
    -> 完整环境生命周期
    -> 冻结 M1 可复测基线
    -> 编写整个开发生命周期的可复测验证体系文档
    -> 退款区域/已收货/评价表单视觉重做（已完成）
    -> Prometheus Alert Rule、Alertmanager/可替换 Webhook（已完成）
```

M1 期间只维护当前实现证据和专项学习文档，未提前创建或标记“全开发生命周期可复测验证体系”为已开始。

## 6. 当前明确不做或未验收的边界

- 真实支付机构、真实物流渠道、换货和售后工单。
- 多节点 MySQL/Redis/Kafka/MQ 高可用、跨可用区容灾、多日容量压测和生产合规认证。
- Prometheus Rule、Alertmanager 与本地 Webhook 已实现并完成 Windows/容器化 Bash 实时投递；真实通知供应商凭据、通知到人和值班升级流程未配置。
- [全开发生命周期可复测验证体系](VERIFICATION-SYSTEM.md) 已建立；当前为本地单机 Docker 学习环境门禁，不代表多节点容灾、多日容量或生产合规验收。
- Java/Maven 已统一为 `vip.fairy.commerceflow`，Compose 已统一为 `fairy-commerceflow`，目录已按 `services/web/simulators/infrastructure` 责任分组。旧学习卷按用户明确授权精确清理，新空卷 Flyway V24/V4/V5、完整业务链路和快速回归均已通过；M1 标签保留迁移前证据。

## 7. M2 工程质量起点

第 7、8 节按批次保留当时的源码规模、脚本形式和准入判断，当前运行状态见第 1、2 节。

M2 只读基线与分批门禁见 [M2 代码质量与模块化单体治理计划](CODE-QUALITY-PLAN.md)。
当前继续修复数据映射与格式问题，并已建立首批模块边界门禁；微服务拆分和 PostgreSQL 迁移仍是独立架构待办。
首批已修正支付退款实体主键映射，并将支付模拟服务通配符导入与
超过 120 字符的 Java 行均清零。支付服务 9 项测试及 Windows/容器化 Bash 真实 MySQL
退款全链路专项均已复验通过。物流模拟服务也已完成强类型持久化投影与格式治理，
并通过回调恢复、DEAD 人工重投、固定种子乱序并发和容器化 Bash 基础链路验收。
商城服务已建立 4 条 ArchUnit 模块边界门禁，移除用户 API 直接访问 Mapper 的越层依赖，
并完成 `identity` 首批格式治理。第二批完成 `catalog`、`cart` 格式与数据访问治理，
商品翻译改用 MyBatis-Plus 单表查询，并修复旧订单投影遗漏商品 ID；25 项 Maven 测试、
Windows L2 快速回归、容器化 Bash i18n/权限回归及现有 MySQL 卷的订单投影实测均通过。
第三批完成 `customer`、`review`、`receipt` 格式与数据访问治理：地址查询改用 Lambda Wrapper，
评价写入改用带字段注释的实体和 `BaseMapper`，复杂联表、聚合与批量更新仍保留在 Mapper XML。
目标三个包的通配符导入和超过 120 字符的 Java 行均为 0；25 项 Maven 测试、Windows 快速回归、
容器化 Bash 收货/评价专项和 37 对脚本契约审计均通过，未删除或重建数据卷。
第四批完成 `fulfillment` 格式治理，商家归属联表、订单行锁和条件状态更新仍保留在 Mapper XML，
事务分段与远程物流调用顺序未改变。目标包的通配符导入和超过 120 字符的 Java 行均为 0；
正式镜像构建、25 项 Maven 测试、Windows/Bash 履约专项、Windows 快速回归及脚本契约审计均通过，
未删除或重建数据卷。第五批完成 `ordering` 格式、数据库投影注释和 Mapper XML 可读性治理；
购物车行锁、订单/支付幂等锁和条件状态迁移仍保留原事务边界。目标包的通配符导入、超过 120 字符的
Java 行和 Mapper XML 超长行均为 0；正式镜像构建、25 项测试、Windows 快速回归、Bash 订单取消、
支付/取消竞态与商家履约专项以及脚本契约审计均通过，未删除或重建数据卷。下一批从 `payment`
继续按业务包治理商城服务，不做全仓一次性机械重写。第六批已完成 `payment` 格式治理，
目标包的通配符导入和超过 120 字符的 Java 行均为 0，未改动支付 SQL、事务、锁、幂等或签名语义。
正式镜像构建、25 项测试、Windows 快速回归、容器化 Bash 支付安全链路和 37 对脚本契约审计均通过，
未删除或重建数据卷。第七批已完成 `refund` 格式、持久化投影注释和 Mapper XML 可读性治理；
成功支付行锁、退款幂等、条件状态迁移、金额上限与通知去重语义保持不变。目标包的通配符导入、
超过 120 字符的 Java 行和 Mapper XML 超长行均为 0；正式镜像构建、25 项测试、Windows/Bash 退款专项、
Windows 快速回归和 37 对脚本契约审计均通过，未删除或重建数据卷。下一批从 `logistics` 继续。
第八批已完成 `logistics` 格式、持久化投影注释和 Mapper XML 可读性治理；运单 upsert、回调行锁、
事件幂等和乱序状态保护语义保持不变。目标包的通配符导入、超过 120 字符的 Java 行和 Mapper XML 超长行
均为 0；正式镜像构建、25 项测试、Windows 物流主链路/回调恢复/人工重放治理、容器化 Bash 物流主链路、
Windows 快速回归和 37 对脚本契约审计均通过，未删除或重建数据卷。
第九批已完成 `scheduling` 格式与 Mapper XML 可读性治理，任务领取、租约、重试、对账和 MQ 消费语义
保持不变。商城业务源码的通配符导入和超过 120 字符的 Java 行均已清零；正式镜像构建、25 项测试、
Windows 快速回归及 Windows/容器化 Bash 的 RabbitMQ/RocketMQ 幂等专项通过，未删除或重建数据卷。
M2.3 已完成，下一阶段为 M2.4 数据库注释与自动门禁。

M2.4 第一批已新增商城 V24、支付模拟器 V4 和物流模拟器 V5，为 16 张核心业务表及
133 个核心标识、状态、金额和时间列补充数据库注释。现有三库已非破坏性升级到 V24/V4/V5；
Windows/容器化 Bash 隔离 Flyway 演练和 Windows 三库空卷完整业务链路通过。

M2.4 第二批已建立 Windows/Bash 后端只读质量门禁，覆盖 244 个 Java 文件、20 个 Mapper XML、
189 个映射语句和 3 个 Maven POM，并接入 `verify-quick`/`verify-all`。商城 25 项（含 4 条 ArchUnit）、
支付 9 项测试、支付安全专项及 Windows 快速回归通过；容器化 Bash 质量门禁实测通过。M2.4 后端门禁完成。

M2.5 第一批已完成 Angular 商城门户的 Prettier 3.10.1 格式治理，`format:check`、29 项
ChromeHeadless 测试、本地与正式 Docker 生产构建均通过。镜像构建已强制只读格式检查；
运行态 `/healthz`、中英文及三角色权限专项通过，未删除或重建数据卷。

M2.5 第二批已完成支付模拟器 Web 的 Prettier 3.10.1 格式治理。基线 24 个目标文件无制表符和压缩行，
20 条超过 120 字符的行经机械格式化收口；`format:check`、Vitest（当前无测试文件）、本地生产构建、
正式 Docker 镜像构建和 `14201/healthz`/Vue 入口运行态通过。正式镜像已强制只读格式检查，未改支付业务语义，
未删除或重建数据卷。M2.5 完成，下一阶段为 M2 工程质量总验收和基线冻结。

M2 总验收已完成：Windows 默认快速/完整验收、容器化 Bash 默认快速/完整验收、双平台质量与
37 对脚本契约审计均通过；默认及全 Profile Compose、五个正式镜像、11 个默认核心服务和 8 个主要
健康端点已复验。支付 Web 上游动态解析、连续验收库存准备及 Alertmanager 冷启动通知日志边界已收口，
全新重建监控栈后的 Watchdog 在 Windows/Bash 均真实送达本地 webhook。整个验收保留现有数据卷；
微服务与 PostgreSQL 迁移未启动。M2 以注释标签 `m2-quality-baseline-2026-11-14` 冻结。

## 8. M3.1 前置审计当前进展

首批先收紧可审计的代码结构基线：物流模拟器已由根包平铺调整为
`api/application/domain/infrastructure`，基础设施继续按客户端、配置、指标、持久化和安全职责分目录，
Mapper 投影独立放入 `infrastructure/persistence/row`。三个服务全部 256 个 Java 主源码/测试源均要求
每个 `class` 声明后保留空行，Windows/Bash 质量门禁同时约束物流根包只保留启动类。

248 个主源码 Java、20 个 Mapper XML、189 个映射语句和 3 个 POM 的双平台质量审计通过；物流服务
正式镜像完成 Maven 打包，签名建单、重放/伪造签名负例、异常恢复、完整轨迹回调以及
`DEAD -> 管理员重放 -> PUBLISHED` Outbox 治理专项通过。现有数据卷保留，微服务拆分和 PostgreSQL
迁移均未启动；该批没有提前实施架构拆分。

第二批完成了完整审计并形成 [M3.1 微服务拆分前置条件审计](architecture/M3-PRE-SPLIT-AUDIT.md)：
商城当前 37 张表均已声明责任模块；40 组跨模块方向包含 117 个 import 引用，分布在 39 个源码文件，
其中 21 个引用直接指向其他模块的 `infrastructure`。新增 2 项 Maven 架构基线测试，允许依赖债务减少，
但禁止新表无所有者、出现新依赖方向或增加跨模块基础设施引用。

Kafka 已有 5 个 topic、事务 Outbox 和由唯一键保护的 Inbox 等价去重，RabbitMQ/RocketMQ 也具备重复、
迟到、毒消息和重投证据；但事件信封没有 schema version、发生时间、因果/关联 ID 或 trace context，
消费者契约 fixture、每服务独立 Inbox 和真正的分布式追踪仍缺失。因此 M3.2 尚不可启动；下一批先治理
notification 表由 InventoryMapper 直接写入的所有权倒置，不创建新服务或数据库。

第三批完成 notification 写入责任收口：商城 V25 新增 notification 自有 Inbox；
`CustomerNotificationCommandV1` 固定 schema version、事件标识/类型、发生时间、业务字段及
correlation/causation ID。InventoryMapper 已不再写通知表，notification Handler 在原业务事务中先以
`(consumer_name,event_id)` 领取命令，再通过自己的 Mapper 写通知。消费者 fixture、重复消费和
Windows/Bash 真实取消通知链路通过，商城测试增至 28 项。

第三批审计基线曾更新为 38 张表、41 组方向和 119 个跨模块 import；21 个跨模块基础设施引用没有增加。
该批没有创建新服务或数据库，传输仍为单体内应用调用；当时遗留的异步适配器、恢复、身份解耦、
追踪传播和回退开关已由下述第四批完成。

第四批完成 notification 拆分前传输门禁：商城 V26 新增 notification 自有投递 Outbox，并为 Inbox 增加
correlation/causation/traceparent。`CustomerNotificationCommandPublisher` 支持默认进程内与 Kafka 两种适配器；
Kafka 发布失败可持久重试、进入本地 DEAD 后重放，消费毒消息有限重试后进入专用 DLT，达到阈值也可按开关
回退进程内 Handler。Kafka payload/Header 双通道校验 W3C traceparent，Inbox 保留最终证据。

真实 Broker 停机时验证了 `RETRY:1 -> PUBLISHED:2`，恢复后 Inbox/通知各一条；持续故障且回退开启时验证
`FALLBACK:1`。共享调度池扩为 4，Producer 阻塞/请求/交付超时为 5/3/5 秒，避免其他 Outbox 饿死
notification 恢复任务。notification application 已不再接收 `AppUser`，只使用 customerId。当前基线为
39 张表、41 组方向、118 个跨模块 import、38 个涉及文件和 21 个基础设施引用。当时 M3.2 仍被
投影核对窗口、停止条件与独立 Schema 迁移演练阻塞；该批未创建服务或数据库，默认仍为 `IN_PROCESS`。

第五批完成 notification 影子投影验证窗和独立 Schema 迁移演练：商城 V27 增加窗口、影子投影与核对审计，
独立 Kafka consumer group 按窗口起始水位核对主投影。真实窗口共匹配 5 条通知，missing/orphan/mismatch 和
阻塞 Outbox 均为 0，连续 3 次检查通过后才由 `READY` 显式停止为 `STOPPED`。默认配置随后恢复为
`IN_PROCESS` 且投影关闭。

独立 notification Schema V1 只包含通知、Inbox 和投递 Outbox 三张表且无外键；Windows 与容器化 Bash
已在随机网络和 tmpfs MySQL 中验证 Flyway、快照字段和 Inbox 幂等。商城空库 V1→V27 与旧库 V16→V27
隔离升级演练均通过。当前商城所有权基线为 42 张表；依赖基线仍为 41 组方向、118 个 import、38 个文件和
21 个基础设施引用，HTTP 基线为 18 个 Controller、60 个操作。独立 Schema 尚未成为实际运行目标，
notification-service、独立运行数据库和 PostgreSQL 均未启动，M3.2 仍需单独授权。

第六批完成 M3.1 最终门禁：Compose 增加可选 `notification-storage` Profile 的独立 `notification-db`；commerce-service 通过专用连接池、Flyway、
MyBatis Session 与事务管理器访问 notification Schema，商城命令 Outbox 仍由原业务事务持有。V2/V3 增加
历史回填水位、核对审计和孤儿隔离证据，不使用跨 Schema 外键、JOIN 或事务。

真实历史回填从 159 条开始追平。新 consumer group 重放时发现 2 条故障专项旧命令，核对以 orphan=2
阻止切换；完整归档后活动读模型恢复一致。Windows 与容器化 Bash 随后分别完成实时 Kafka 通知、连续三次
零差异、INDEPENDENT 读切换、按 eventId 镜像已读状态、notification-db 停机和 COMMERCE 回滚。
最终商城/独立通知均为 167 条，默认恢复 `IN_PROCESS + COMMERCE`，两个数据库均健康。

当前质量基线为 283 个主源码 Java、21 个 Mapper XML、235 条映射、3 个 POM 和全部 292 个 Java 源文件；
脚本契约为 41 对，HTTP 基线为 19 个 Controller、63 个操作。商城所有权及跨模块依赖基线仍为
42 张表和 41/118/38/21。M3.1 已完成并具备 M3.2 启动条件，但 notification-service 与 PostgreSQL 均未启动。

第七批完成 M3.2 独立进程准入所需的首个跨进程追踪基线。三个 Java 服务接入 OpenTelemetry，统一
W3C Trace Context、日志 `traceId/spanId`、采样和 OTLP 导出开关；Compose 增加可选 Tempo，Grafana 增加
Tempo 数据源。默认关闭导出，因此追踪后端不是默认业务依赖，Prometheus 指标链也不改由 OTLP 承担。

Windows 与容器化 Bash 的配对专项均查询到 commerce-service 与 logistics-simulator-service 的同一 Trace，
并确认 Tempo 停机时业务服务继续健康。Kafka `send/process` Span 已实测；notification V1 同时保留业务来源
traceparent 和传输 Span，上述兼容修复又通过带 tracing 的独立 Schema 实时切换全演练。RabbitMQ 通过
Observation 与显式 W3C 上下文跨越 TTL/DLX，RocketMQ 通过消息 properties 注入/提取上下文。
Windows 任务 410/411 和容器化 Bash 任务 414/415 均在 Tempo 查询到同一 Trace 的 publish/process Span，
两平台也验证 Tempo 停机不影响商城健康。脚本契约现为 41 对。四种边界的本地追踪准入门禁已经补齐，
下一步可单独评审 notification-service 影子进程；本批未创建服务或迁移 PostgreSQL。

第八批在单独授权后实现 `notification-service` 影子进程。服务按
`api/application/domain/infrastructure` 分层并继续拆分 `messaging/persistence`，只消费冻结的 notification
V1 命令，使用稳定消费者组、独立 MySQL Schema、Inbox 和本地事务，不访问商城 Mapper 或 Schema。
Compose 的 `notification-service` Profile 默认关闭，健康与只读影子状态端点使用 `18086`。

Windows 与容器化 Bash 均完成实时写入、进程停机期间消息积压、重启追平、commerce/notification 同一
Trace 和 `IN_PROCESS` 单体回退。当前质量基线为 293/22/239/4、全部 303 个 Java 源文件和 42 对脚本契约。
验收后 notification-service 已关闭，商城恢复默认配置；客户通知 API 尚未切到独立服务，单体表和回退路径
保留，PostgreSQL 未启动。

第九批实施 notification 单一所有权切换：notification-service 补齐四个内部 V1 API、客户隔离和 HMAC
调用方校验并进入默认 Compose 拓扑；commerce 原四个公开接口只做 Session/RBAC、签名与限时 HTTP 转发。
Angular 改用 eventId 已读并在 503 时显示重试。commerce 内本地通知 Mapper、消费者、独立数据源、投影、
回填核对和运行模式均删除，只保留事务 Outbox、V1 生产契约与历史 Flyway。Windows 专项已验证真实 Kafka
通知、已读落库、独立服务停机 503、核心商城健康与恢复；交接时 commerce 历史通知为 0 行，Kafka 三分区
lag 均为 0。Windows/Bash 通知专项、快速回归、质量审计和 41 对脚本契约均已通过；Windows 完整验收通过。
Bash 等价全链路通过全部业务阶段，告警尾段在 Git Bash 排除容器路径的 MSYS 参数转换后补验，结果为 3 个
健康采集目标、10 条规则和 1 次 Watchdog 投递。本批已以 `88d22c6` 独立提交并推送。

第十批开始 M3.3 catalog 拆分准入。首批建立 `CatalogProductLookup`、`CatalogCacheInvalidator` 和
`ProductReviewCatalogQuery` 应用边界，cart/order/catalog/review 不再跨模块注入 ProductMapper/ReviewMapper，
依赖基线由 41/118/38/21 收紧为 41/113/37/18，并以 `587a75d` 独立提交推送。

第二批以商城 Flyway V28 完成数据所有权冻结：catalog 的 `products` 只保存商品资料，inventory 自有
`product_inventory` 独占可售、预占、已售数量并保存履行快照；库存预占/流水外键已改接新表。cart_items、
customer_orders 固化名称、图片和单价，购物车、轻量订单、结算锁定和商家库存不再 join `products`；同时
移除 products→identity 及 cart/order/review→catalog 的跨边界外键。现有库 6 条库存记录和 642/1/65 数量
总和保持不变，Windows/容器化 Bash 的空库 V1→V28、旧库 V16→V28 隔离升级均通过。当前所有权基线为
43 张表，依赖基线保持 41/113/37/18；catalog-service、inventory-service、独立数据库及 PostgreSQL 均未启动。

第三批以商城 Flyway V29 冻结 catalog V1 边界。认证查询提供 ACTIVE 列表、详情和最多 100 个 ID 的批量
快照；商家 API 按 `api/merchant` 子目录分层，支持本人商品资料、价格、状态和翻译维护。每次写入携带
expectedVersion，成功后 businessVersion 加一，并在同一事务写 catalog 自有 Outbox；并发旧版本稳定返回
`409/CATALOG_VERSION_CONFLICT`。profile/price 更新提交前同步 inventory 展示快照，失败则商品与事件一起回滚。

五类事件 V1 已固定 schema/event/业务版本、发生时间、correlation/causation 与商品快照，fixture 和版本门禁
覆盖 APPLY、STALE 与版本缺口重试。商品创建事件已冻结，但创建 API 等 inventory 初始化命令后才开放。
当前商城所有权基线为 44 张表，依赖基线仍为 41/113/37/18；HTTP 为 20 个 Controller、66 个操作。
Java 25 正式镜像 32 项测试、双平台 V1→V29/V16→V29 生命周期及快速回归通过；真实 Docker V1 中文详情、
批量快照和 6 条商家目录已验证。catalog-service、独立数据库、inventory-service 与 PostgreSQL 均未启动。

第四批已创建独立 `catalog-service` 和 `catalog-db`，按 api/application/domain/infrastructure 分层承接商品
资料、翻译、业务版本和 catalog Outbox。一次性迁移以 commerce V29 为源门槛，复制 6 条商品、6 条翻译和
既有 Outbox，计数与内容指纹一致后写入 `catalog-v29-single-owner=COMPLETE`；重复运行只读取 COMPLETE，
不会用陈旧 commerce 表覆盖独立库。首次真实演练的字符集差异被 FAILED 门禁阻止，补齐 utf8mb4 后完成，
未删除命名卷。commerce 已删除 catalog Mapper/XML/本地写路径与 shared 过渡投影，只通过 HMAC HTTP 访问；
catalog 停机稳定返回 503，commerce 核心健康保持 UP，跨进程 Trace 同时出现 commerce-service 与
catalog-service。inventory-service 与 PostgreSQL 均未启动。

第五批进入 M3.4 inventory-service 准入评审。ordering、payment、refund 已改为只依赖
`inventory/application/port/InventoryCommandPort`，预占输入不再泄露 cart DTO，商家库存应用服务不再接收
identity 的 `AppUser`。新增 ArchUnit 门禁阻止外部模块依赖库存 API、domain、infrastructure 或具体实现；
当前跨模块实际依赖收紧为 39 个方向、109 个 import、36 个源码文件和 18 个 infrastructure 引用。评审同时
确认 order 外键、取消通知副作用、本地事务、独立 Inbox 和 catalog 事件消费者仍是拆分阻塞项，因此本批没有
创建 inventory-service/inventory-db、迁移数据或启动 PostgreSQL。Java 25 的 30 项测试、Windows 新运行 JAR
快速回归及容器化 Bash 质量/脚本审计通过；从工具容器调用宿主 Compose 因路径身份不同导致迁移脚本 126，
已用 Windows 原生 Compose 恢复全部健康，未把该次 Bash 全链路记为通过，也未删除命名卷。

第六批完成 M3.4 inventory V1 命令边界。`application/contract/v1` 固定 RESERVE、COMMIT、RELEASE、REFUND
统一信封和四份 fixture；V30 新增 `inventory_command_inbox`，同一事务完成幂等领取、库存变化和 SUCCEEDED，
重复同载荷不重复扣减，同 commandId 异载荷拒绝。两条 inventory→shop_orders 外键和 customerId 跨表查询已
删除，取消通知改由订单取消事务写 notification Outbox，inventory→notification 依赖归零。当前跨模块实际为
39/109/35/18。Java 25 共 32 项测试，Windows 与容器化 Bash 的 V1→V30、V16→V30 隔离升级及 Windows
快速回归均通过；默认 Docker 持久库已升级到 V30，17 条真实 inventory 命令全部 SUCCEEDED，非成功命令和
两条跨边界订单外键均为 0，未删除或重建命名卷。本批仍未创建 inventory-service/inventory-db，也未启动
PostgreSQL。

第七批完成 catalog→inventory 单向事件投影。catalog-service Outbox 通过 HMAC 服务身份和已签名载荷摘要向
inventory 内部端点投递，最多 5 次后进入 DEAD；V31 新增 inventory 自有 catalog-event Inbox 及商品快照
生命周期/businessVersion。重复 eventId 幂等、异载荷拒绝、旧版本 STALE、版本缺口重试，翻译事件不会覆盖
库存主名称，事件不改变任何库存数量。Java 25 的 catalog 5 项、commerce 35 项测试通过；真实 Docker 完成
版本 1→2 投递，以及 commerce 停机 RETRY、恢复后第 4 次尝试 PUBLISHED、inventory 单次 APPLIED 并推进到
版本 3。双平台 V1→V31/V16→V31、Windows 快速回归和双平台质量/脚本审计通过。未删除或重建命名卷，
inventory-service/inventory-db 和 PostgreSQL 仍未启动。

第八批冻结 inventory 命令投递与结果协议。四类命令统一经过 `InventoryCommandGateway`；V32 新增生产端
delivery Outbox 和结果 Inbox，当前本地适配器在同一事务留下 SUCCEEDED/ACKNOWLEDGED 证据并调用唯一
Handler，不维护双实现。`InventoryCommandResultV1`、成功/拒绝 fixture、共享语义指纹和超时状态机已落地；
结果未知达到上限进入 RECONCILIATION_REQUIRED，不伪造拒绝或盲目补偿。commerce Java 25 共 37 项测试
通过；双平台 V1→V32/V16→V32 生命周期、Windows 快速回归和双平台质量/脚本审计均通过。默认持久库已升级
到 V32，32 条新协议命令均为 SUCCEEDED/ACKNOWLEDGED，未决投递为 0。当前尚未启用远程传输；订单等待库存、
履约阻断、退款库存待对账和管理恢复入口仍是独立服务前门禁，inventory-service/inventory-db 与 PostgreSQL
均未启动。

第九批补齐生产侧业务等待态和人工恢复门禁。V33 将订单主状态与 inventoryStatus 分离，checkout、支付提交、
取消释放和退款归还均留下明确状态；只有 COMMITTED 才允许发货。退款财务结果与库存归还结果独立表达，未知
结果进入 RECONCILIATION_REQUIRED。ADMIN 恢复入口只用原 commandId/原 payload 重试，成功后同步恢复
ACKNOWLEDGED 和业务状态；V34 记录操作者、原因和时间。Java 25 正式镜像 38 项测试通过；
inventory-service/inventory-db 与 PostgreSQL 仍未启动。双平台 V34 生命周期和 Windows 快速回归通过；默认
持久库 124 条订单 COMMITTED、118 条 RELEASED、21 条 REFUNDED，21 条退款库存状态均已收口，53 条命令
全部 ACKNOWLEDGED；该项的后续最终准入评审已由第十批完成。

第十批完成 inventory-service 最终创建准入评审。运行手册明确五张 inventory 所有权表迁入独立库，生产端
delivery Outbox 和结果 Inbox 留在 commerce；冻结快照、增量追平、短暂停写、最终指纹核对、整体切换、
跨进程 Trace 与独立库新写后的受控回退规则。Windows/Bash 只读审计均通过 V34、七张边界表、零跨域外键、
余额/预占/已售汇总、Inbox/Outbox 闭环、零业务等待态和唯一 Gateway。当前基线为库存聚合 6 条、预占
263 条、流水 548 条、命令 Inbox 87 条、catalog Inbox 2 条，未决与待对账均为 0。本批仅授予 CREATE
资格；没有创建 inventory-service/inventory-db 或切换流量，CUTOVER 与 PostgreSQL 继续冻结。

第十一批创建分层 inventory-service、独立 MySQL 和 `inventory-shadow` Profile。V1 只持有五张 inventory
所有权表与迁移状态；唯一命令 HTTP 适配器和 catalog 事件适配器复用 V1 Inbox、幂等指纹、余额状态机、
HMAC 与 Trace 信封。默认流量门禁关闭，健康检查为 UP，未签名请求 401、已签名请求 503，commerce/catalog
均未改指向。可重复迁移连续至少 20 代 READY，快速回归后的源/目标完整字段指纹一致。Java 25 的 2 项测试、Windows/Git Bash
影子验收、368 个 Java/30 个 XML/252 个语句/6 个 POM 的质量门禁及 43 组脚本契约通过。未切流、未接收独立
业务新写、未删除 commerce 本地实现；CUTOVER 与 PostgreSQL 继续冻结。

第十二批补齐可配置停写门禁和唯一 HMAC HTTP 远程客户端，但只开放默认关闭的管理员预演入口，不把业务
Gateway 分成 LOCAL/COMPARE 两套。Windows/Git Bash 均完成停写、最终追平、历史 COMMIT 原命令幂等重放、
inventory-service 停机 503 与恢复；预演前后独立库余额/流水/Inbox 未变化。Windows 快速回归通过后，迁移器
generation 23/24 再次得到相同五表指纹：`6|575|1|132|1824707114`、`279|279|2231803732`、
`582|688|2202142859`、`121|2961863654`、`2|2798068318`。Java 25 正式镜像 40 项测试、372 个 Java、
385 个类声明空行和 44 对脚本门禁通过；三个运行开关已恢复 false。真实新写仍走 commerce 本地路径，四类
Tempo Trace、超时/结果丢失及整体 CUTOVER 仍待下一批，PostgreSQL 继续冻结。

第十三批完成库存命令生产切换。commerce 业务事务只写 delivery Outbox，唯一异步派发器远程发送四类命令，
Result V1 确认后再推进订单/退款库存状态；inventory-service/inventory-db 已进入默认拓扑并独占新库存事实写入。
返回结果丢失、服务停机、数据库停机均以原 commandId 恢复且只记一条流水；跨进程 Trace
逐类证明 RESERVE/COMMIT/RELEASE/REFUND 均同时包含 commerce/inventory。完整 Windows 验收后 commerce
源流水仍为 582，独立库增至 627；42 项 Java 25 测试、374/30/253/6 质量门禁、388 个类声明空行及 45 对脚本契约通过。
本批只完成四类命令所有权切换：商家库存查询/调整仍使用 commerce Mapper，所以下一批必须先迁出该管理边界，
再删除本地 Handler/Mapper/XML 和源表权限。PostgreSQL 继续冻结。

第十四批完成 inventory 完整单一所有权。商家库存列表、调账与流水查询迁入 inventory-service，commerce
公开 API 只保留 Session/CSRF/MERCHANT RBAC 和 HMAC 薄代理；独立服务停机稳定返回 503，不回退本地表。
commerce 本地库存 Handler、领域余额对象、四个 Mapper/XML、旧 catalog 内部入口及专用签名代码已删除。
Compose 先以迁移账号执行 Flyway，再为 `commerce_runtime` 逐表授权；该账号可访问 commerce 自有表，但五张
库存源表均被 MySQL 1142 拒绝。Windows 实机完成 `72 -> 73 -> 72` 调账恢复、未签名 401、停机 503 和恢复，
源流水保持 582，目标流水增至 641。Java 25 共 44 项测试，368/27/231/6 质量门禁、383 个 Java 源文件类声明
空行及 46 对脚本契约通过；Bash 对等脚本已通过语法审计。本批未启动 PostgreSQL，下一步是 order-service
边界准入治理，不直接创建服务或数据库。

M3.5 第一批完成 order-service 边界审计与自动门禁。order 最终拥有 `shop_orders`、`order_items`、
`order_shipping_addresses`、`order_events` 四表；当前 4 条出站和 5 条入站跨边界外键、7 个外部
`ShopOrderMapper` 消费者仍阻止 CREATE。当批默认持久卷为 Flyway V34，订单/商品项/地址均为 313 条、事件
1477 条、订单与商品项金额均为 406587.00；重复、孤儿、金额错配、非法状态和待恢复流程均为 0。Windows
与 Git Bash 审计结果一致，Java 25 正式镜像 42 项测试通过。分布式事务固定为本地事务 + Outbox/Inbox +
持久化 Saga/Process Manager + 补偿/对账，不引入 Seata、DTM、XA 或全局事务协调器。本批未创建
order-service/order-db，未并行拆 payment-orchestration/fulfillment，也未启动 PostgreSQL。

M3.5 第二批新增 `ordering/application/port` 和 `ordering/application/contract/v1` 子目录。fulfillment、
logistics、payment、receipt、refund 只通过五类意图端口访问订单，7 个外部 `ShopOrderMapper` 消费者归零；
支付回调的锁定、通知、支付状态和事件 SQL 迁入 payment 自有 Mapper。OrderCommandV1/OrderResultV1 固定稳定
ID、前置状态、失败码、correlation/causation/W3C Trace，以及“超时不是失败”的结果语义，两个兼容 fixture
通过测试。Windows/Git Bash 审计均保持 V34、9 条跨边界外键和数据指纹一致；Java 25 正式镜像 44 项测试、
380/28/232/6 质量门禁与全部 396 个 Java 源文件类声明空行通过。CREATE 仍因跨边界外键、Mapper 剩余
cart/address/payment 表职责及持久化 Saga 尚未完成而冻结；未创建服务/数据库，PostgreSQL 未启动。
本批 Windows 快速回归完整通过后，最新一致性审计为订单/商品项/地址各 321 条、事件 1521 条、金额
416979.00，指纹 `orders|321|416979.00|832328281`；所有错误计数仍为 0。

M3.5 第三批完成最终 CREATE 准入。cart/address/payment SQL 从 `ShopOrderMapper` 迁回各自边界端口和 Mapper，
订单 Mapper 只访问四张核心表；Flyway V35 删除 4 条出站、5 条入站跨边界外键，并新增 order Process Manager、
命令 Outbox 和结果 Inbox 三张表。流程基线覆盖同事务启动、命令/结果 payload 指纹幂等、冲突拒绝、超时未知
进入待对账、拒绝进入待补偿及带操作者/原因/时间的人工恢复。空库 V1→V35、遗留 V16→V35、Java 25 正式
镜像 45 项测试、394/31/243/6 质量门禁、410 个 Java 源文件 class 字段空行、Windows 快速回归和 Windows/
Git Bash 准入审计均通过。最终持久卷为订单/商品项/地址各 329 条、事件 1565 条、金额 427371.00，流程/
命令 Outbox/结果 Inbox 为 0，双向跨边界外键、外部 Mapper 消费者、Mapper 外部表职责和所有一致性错误均为 0。
CREATE 已授权，CUTOVER 仍冻结；该结论是第三批结束时的历史边界，当批未启动 PostgreSQL。

M3.5 第四批创建独立 `order-service/order-db`，但 commerce 不依赖该服务，Order V1 业务流量门固定关闭。
V36 用单例提交顺序时钟和 21 个触发器记录 7 张所有权表增量；order-service 以 REPEATABLE READ 一致性快照、
批次增量、水位、逐字段 SHA-256 审计和孤儿隔离/重放维护影子库。双平台专项复跑后 generation 8、水位 120，
337 笔订单、337 条商品项、337 份地址、1609 条事件、金额 437763.00 与三张空协议表均 MATCH；一次目标父记录
缺失演练已隔离并恢复为
RESOLVED，OPEN 0。order-service 和 order-db 分别停机期间产生的增量均在恢复后追平。当前 407/31/243/7
质量门禁与 424 个 Java 源文件类声明空行通过；PostgreSQL、payment-orchestration/fulfillment 拆分和 CUTOVER
均未启动。

M3.5 第五批把远程 Order V1 从只校验推进为可恢复协议适配器。内部请求使用 HMAC-SHA256、正文 SHA-256、
5 分钟时间窗和持久 requestId 防重放；命令以 messageId/payload 指纹 Inbox 去重，条件状态更新与事件、取消
Process Manager 及两条下游命令 Outbox 在 order-db 本地事务提交。结果 Inbox 拒绝同 ID 异载荷和乱序结果，
丢失结果到期只进入 `WAITING_RECONCILIATION`，人工 RETRY 记录操作者、原因和时间。Windows 合成订单演练在
Tempo 验证真实 trace，覆盖重复、冲突、乱序、超时、丢失、两阶段取消结果和恢复；结束自动清理并恢复默认
503 门。最终快速回归后 generation 20、水位 236，345/345/345/1653、金额 448155.00 及三张空协议表全部
MATCH；临时账号验证
源端权限冻结和恢复、order-service 停机及源端唯一所有者回退。实际 commerce 账号未冻结，未 CUTOVER，
PostgreSQL 与后续两个服务拆分均未启动。

M3.5 第六批在 commerce 停写后完成 generation 21 / 水位 236 的最终快照和 7 表 MATCH，再一次性切换订单
读写。commerce 删除订单 Mapper、本地 Process Manager 与源表 SQL，生产运行只经远程 Order Store V1；
Compose 默认打开业务门、关闭影子复制，order-service 不再持有源库连接。`commerce_runtime` 对库存和订单
源表权限均被撤销。专项验收确认新订单只写 order-db，源端保持 345/448155.00；order-service 停机时返回
503，恢复后健康。完整快速回归后 order-db 为 359/359/359/1710、金额 466341.00，协议表与 OPEN 孤儿均为
0；413/28/204/7 质量门禁、431 个 Java 源文件空行和 50 对脚本契约通过。本批未拆后续服务，未启动 PostgreSQL。

M3.6 第一批把商城支付编排与退款统一评审为一个候选资金边界，冻结 11 张候选所有权表。持久卷为 299 笔支付、
总额 388401.00、累计退款 41568.00，32 笔退款总额同为 41568.00；437 条 Outbox 全部 PUBLISHED，金额、
状态、幂等、渠道证据、退款投影和事件不变量均通过。CREATE 尚未准入：仍有 1 条 refund→identity 外键、
5 个外部 infrastructure 消费者文件和 1 条 OPEN 对账记录。首批只增加源码/数据库只读门禁与双平台审计，
Windows/Linux 审计、45 个 commerce-service 测试、50 对脚本契约与快速回归通过；未创建
payment-orchestration-service/payment-db，未切流、未并行拆 fulfillment，也未启动 PostgreSQL。

M3.6 第二批冻结创建支付、按订单查询支付/退款、关闭待支付记录和支付/退款结果的 Payment V1 契约。ordering
与 fulfillment 对 payment/refund 的 application、domain、infrastructure 和 Mapper 直接导入归零，只保留稳定
V1 数据结构及订单侧端口；当前由唯一 `LocalPaymentOrchestrationAdapter` 承接原实现，不存在 LOCAL/COMPARE。
创建/关闭命令、关闭结果和支付成功结果共 4 个 JSON 兼容样例覆盖版本、幂等键、关联/因果 ID、W3C Trace、
payload 指纹、“超时结果未知”和支付成功优先语义。CREATE 仍因 refund→identity 外键、1 条 OPEN 对账记录和
尚未收口的 payment-backed 订单事件 Outbox/Inbox 适配器、Process Manager/结果 Inbox/人工恢复门禁而冻结。
回归同时修复迟到 RESERVE 结果把已 COMMITTED 库存倒退为 RESERVED 的竞态，人工核对后的恢复重试仍可推进终态。最终持久卷为 324 笔支付、36 笔退款，外部
内部实现导入与数据不变量错误均为 0；48 项测试、431/28/204/7 质量门禁、451 个 Java 源文件类声明空行、
Windows/Linux 审计、50 对脚本契约和完整快速回归通过。本批没有创建服务/数据库或启动 PostgreSQL。

M3.6 第三批删除 refund→identity 外键，并把唯一 OPEN 超时对账依据已为 `CLOSED` 的支付事实审计式关闭。
179 条历史订单副作用 Outbox/Inbox 已迁入 ordering 自有表，payment 表不再承载 `order-events`。新增支付/退款
Process Manager、命令 Outbox、结果 Inbox，以及同 ID 异 payload 冲突、未知结果、超时转人工核对和带操作人、
原因、时间的人工重试门禁。持久卷 Flyway V37 审计为 14 张 payment/refund 所有权表、双向跨边界外键 0、
OPEN 对账 0、payment 中订单事件 0，179/179 条订单 Outbox/Inbox 完整保留；55 项测试通过。CREATE 已准入，
CUTOVER 仍冻结；本批没有创建独立服务/数据库、没有并行拆 fulfillment，也没有启动 PostgreSQL。

M3.6 第四批创建分层 `payment-orchestration-service` 和独立 MySQL `payment-db`，但 commerce 仍只使用唯一
`LocalPaymentOrchestrationAdapter`，独立服务的 `PAYMENT_ACCEPTING_TRAFFIC=false`，只开放影子管理端点。
Flyway V38 以 42 个触发器和提交顺序水位捕获 14 张所有权表变更；独立服务完成 REPEATABLE READ 可重复快照、
增量追平、逐字段 SHA-256 核对和外键孤儿隔离/重放。Windows 与 Ubuntu 26.04 WSL 专项及最终快速回归后
generation 3、水位 83，支付 353、商户 3、通知 248、支付事件 706、幂等锁 353、对账 2、
Outbox/Kafka Inbox 各 326、退款 40、退款事件/通知各 80，三张恢复协议表为空，14 张表全部 MATCH；两次孤儿演练均 RESOLVED，OPEN 0。
本批没有业务流量、CUTOVER、LOCAL/COMPARE、fulfillment 拆分或 PostgreSQL。

M3.6 第五批在独立服务实现受流量门保护的 Payment V1 创建、查询、关闭、结果和恢复入口，以及支付/退款渠道
回调；创建与关闭会用原签名协议真实调用 payment-simulator-service。Flyway V2 持久化命令 Inbox 和内部请求防重放；HMAC、正文摘要、时间窗、requestId、payload 指纹与 W3C
Trace 均有专项门禁。同命令重放返回原结果，同 ID 异正文拒绝；支付成功先到或关闭后迟到成功都保持资金成功优先。
结果丢失会进入人工核对，恢复重试保存操作人、原因和时间。Windows/Ubuntu 26.04 WSL 均实跑服务/数据库停机、
退款、重复通知、临时源权限冻结拒绝和整体回退，结束后删除合成数据并恢复关闭流量。

完整快速回归并最终追平后 payment-db Flyway V2 无失败，generation 13、水位 160；支付/商户/通知/事件/幂等锁/
对账/Outbox/Kafka Inbox/退款/退款事件/退款通知为 360/3/253/720/360/2/333/333/41/82/82，三张恢复协议表为空，
14 张表全部 MATCH，OPEN 孤儿和待恢复流程均为 0。payment 服务 2 项 Java 25 测试、461/30/220/8 质量门禁、
484 个 Java 源文件空行和 53 对脚本契约通过。commerce 仍只有本地正式实现，未 CUTOVER、未拆 fulfillment，
PostgreSQL 容器为 0。

M3.6 第六批完成一次性 CUTOVER 评审，结论为不通过。独立服务默认流量门仍关闭且未配置 PostgreSQL，但尚无
远程 Order V1/Inventory 命令客户端、支付 Outbox 投递器和退款发起入口；支付/退款回调仍指向 commerce，
commerce 本地实现与源表权限仍是唯一正式路径。新增 Windows 与 WSL/Linux 对等审计，成功结果表示这些安全门禁
和阻塞基线未漂移，最终结论固定为 `REJECTED`。本批未切流、未删除本地路径、未撤销支付源表权限、未拆
fulfillment，也未启动 PostgreSQL。第七批随后只在关闭流量门下补齐跨边界副作用与退款恢复闭环。

M3.6 第七批在独立服务补齐远程 Order V1、Inventory COMMIT/REFUND、可靠 payment Outbox 投递器、退款发起和
退款渠道命令闭环。Flyway V3 在真实 payment-db 增加 Outbox 失败证据；稳定命令 ID、条件状态推进、租约、退避
重试和退款持久 Inbox/Outbox 防止重复副作用。正式副作用受流量门与副作用门共同保护，且影子复制开启时强制
拒绝；默认仍为流量关、副作用关、影子开。Windows 与 Ubuntu 26.04 WSL 缺失订单演练均得到可恢复 RETRY、
库存零写入，随后精确清理并恢复默认门禁。commerce 本地路径、渠道回调和源表权限未切换，CUTOVER 审计继续
`REJECTED`。payment 服务 6 项 Java 25 测试、468/30/220/8 质量门禁、493 个 Java 源文件空行、54 对脚本契约
和完整快速回归通过；未维护 LOCAL/COMPARE、未拆 fulfillment、未启动 PostgreSQL。

M3.6 第八批把客户退款 POST 收口到 `RefundCreateCommandV1 -> PaymentOrchestrationV1`，但
`LocalPaymentOrchestrationAdapter` 仍是唯一 Bean，继续复用原退款事务，因此没有远程切流或双实现。commerce
56 项测试和真实全额退款、重放、冲突、签名拒绝专项通过。评审更正 `RefundController` 为切流后应保留的身份薄
入口，把本地 `RefundService/RefundMapper` 明确为删除对象；一次性运行手册冻结停写、最终追平、唯一远程适配器、
回调切换、源表撤权和开门顺序。由于本地实现、旧回调及源权限仍在，CUTOVER 继续 `REJECTED`。

本批最终完整快速回归发现支付成功与库存预留结果并发到达时，两次远程条件更新之间可能发生状态漂移。现已用
Order V1 原子 `START_INVENTORY_COMMIT` 同时接受 `RESERVED/WAITING_RESERVATION`，并允许同一成功回调重放修复
`PAID` 但库存尚未提交的部分结果。两笔历史测试样本均恢复为 `PAID/COMMITTED`；order 2 项、commerce 56 项、
payment 6 项 Java 25 测试，以及 469/30/220/8 质量门禁、495 个 Java 源文件空行、54 对脚本契约和完整快速回归通过。

M3.6 第九批新增默认关闭的 `PAYMENT_WRITE_FROZEN` 和稳定 `PaymentWriteAdmissionV1`，在创建支付、订单取消进入
`CANCELLING`、发起退款之前统一拒绝新资金写，签名回调继续排空。Windows 真实演练确认三类写均返回 503、拒绝
不改变业务事实、已有支付仍由回调收敛到 `PAID/SUCCESS`，退出后恢复 false。Ubuntu 26.04 WSL 补齐 jq 并修复
Windows Docker CLI 的 Compose 路径/环境透传后完成同等运行态验收。commerce 58 项测试、55 对脚本契约通过。

M3.6 第十批完成最终 GO/NO-GO：generation 15、水位 603、14 表 MATCH，OPEN 孤儿、待投递 Payment/Command
Outbox、未完成 Process Manager 均为 0；权限冻结恢复和目标停机恢复通过。由于本地实现、旧回调、源权限和唯一
远程适配器原子替换仍待同一窗口完成，结论为 NO-GO，CUTOVER 审计继续 `REJECTED`。

M3.6 第十一批在明确授权的受控窗口完成一次性 CUTOVER。商城停止后完成 generation 16、水位 610 的最终快照，
14 表全部 MATCH，OPEN 孤儿、Payment/Command Outbox 和 Process Manager 未决项均为 0。commerce 现在只有
`RemotePaymentOrchestrationAdapter`，本地支付/退款 Service、Mapper/XML、渠道 Gateway、旧回调和对账路径已删除；
支付平台回调只指向 payment-orchestration-service，影子默认关闭且流量/副作用默认开启，`commerce_runtime` 对
14 张旧源表授权为 0。受控订单 486 的支付、退款及两类幂等重放通过，最终为
`REFUNDED/SUCCEEDED/REFUNDED`，独立库未决项归零，旧源库没有该订单支付记录。未拆 fulfillment，未启动 PostgreSQL。

M3.6 第十二批补齐切流后运行观察。payment-orchestration-service 新增支付/命令 Outbox、Process Manager、人工恢复、
OPEN 孤儿、处理中退款、指标刷新和 `true/true/false` 切流模式共 10 个 Prometheus 指标；监控增加独立采集目标，
14 条告警规则通过 promtool。Windows 订单 517/520、WSL 订单 527/530 在 payment 服务、payment-db 和支付模拟器
重启前后均完成支付/退款，待支付取消同时完成 Process Manager 与 Command Outbox。两端最终源水位仍为 610，
14 张源表授权、Payment/Command Outbox、未完成 Process Manager 和 OPEN 孤儿均为 0。下一步只评审 fulfillment
CREATE 准入；本批未创建 fulfillment-service，未启动 PostgreSQL。

M3.7 第一批决定让履约拥有执行流程与恢复记录。订单、资金和物流投影继续由原边界负责。移除履约对物流 Mapper
和身份领域对象的直接依赖，完成契约草案。Windows 与 WSL 只读审计记录 3 张物流表、103 条运单、0 条未绑定运单、
0 条跨边界外键与 0 条通知断链。

M3.7 第二批在 commerce 内实现发货编排、发货与退款原子互斥、发货人工恢复与改运单版本保护。商城 V42 将
接单和改运单也纳入持久化执行，受理后可查询原命令，后台从渠道或订单检查点继续。真实 MySQL 样本 601
在渠道先提交、商城后重启的场景使用原命令完成，三处运单版本一致，订单接单和改运单事件各 1 条。
商城 48 项测试、Angular 构建、MySQL 空库与旧库升级、Windows 快速回归以及 Windows/WSL 审计通过。

商城 V43 补齐接单和改运单的管理员恢复与审计。订单 609 模拟 Order V1 已提交、商城尚未确认，两路认领只有
一路成功，接单租约过期后自动完成，改运单经管理员恢复完成，两个 Inbox 成功结果与运单版本保持一致。接单、
改运单事件及恢复审计各 1 条。商城 49 项测试、MySQL 空库与旧库升级、商家履约专项和 Windows/WSL 准入审计通过。

V43 后完成 CREATE 复核，结论仍为 `REJECTED`。MySQL 样本 611 复现接单、发货或改运单已在下游提交、商城任务
仍待核对时，新履约操作可进入。Windows/WSL 复测记录 33 条任务，未决任务、到期租约、重叠订单与发货意图
均为 0。已修订 Fulfillment V1 草案。

V44 在商城内落地同订单事务准入和 Fulfillment V1 提交、结果查询。真实 MySQL 样本 612 的三个跨操作场景均受
待核对任务阻塞，管理员恢复原命令后继续。商城 51 项测试、V44 空库安装及旧库升级验证通过。独立履约服务仍处于
CREATE 复核阶段。

已完成 V44 拆分前评审。MySQL 一致性快照中的六张履约表在隔离库还原两次，行数、摘要、表定义和自增游标一致，
17 组订单与商家引用均匹配。样本 614 证实渠道重放返回当前资源或版本冲突。下一步在商城内落实物流命令回执端口，
并为无副作用的已知拒绝保留历史、允许重新准入。当时结论为 `REJECTED`。

V45 已通过独立履约服务 CREATE 准入，范围为关闭业务流量的影子运行。订单 615 验证发货已知拒绝后只允许一个
新命令执行，已有同参数发货意图仍被 Order V1 拒绝。运单升到版本 2 后，原改运单命令仍能恢复版本 1 的回执，
订单事件无重复。商城 53 项测试、订单 2 项测试、MySQL 空库与旧库升级、六表两次还原及 Windows 快速回归通过。
Windows/WSL 最终审计记录 59 条任务、9 条物流回执，未决任务、待完成回执、回执不一致及未清发货意图均为 0。

V46 已启动独立 `fulfillment-service` 和 MySQL `fulfillment-db` 影子环境，商城继续承担履约业务。真实 MySQL
验收通过重复快照、回滚、增量写入与删除、孤儿隔离、重启续跑和签名读写门禁。最终源与目标水位均为 52，六表
摘要匹配，67 条任务的命令登记一致，待隔离记录为 0。新服务 2 项、商城 53 项测试，真实 MySQL 空库与旧库升级、
完整 Windows 快速回归及 58 对 Windows/WSL 脚本契约通过。

V47 增加商城履约新命令冻结，完成影子进程断线、源只读授权恢复、商城冻结排空和撤回演练。六次冻结写请求
均为 503，已受理改运单完成，解除冻结后三个原命令 ID 各成功受理一次。目标写入提交失败时，行和水位原子回滚。
最终源目标水位 153，六表摘要一致，隔离 0，冻结已解除。商城 54 项测试、V47 迁移与完整快速回归通过。
当时切流准入为 `REJECTED`。

履约库 V2 增加数据库模式绑定。独立服务具备默认关闭的签名写执行，订单侧增加受限履约身份，商城侧增加默认关闭
的签名物流桥。隔离真实 Order V1 提交后超时、物流夹具回执保存后断连、六表转移与回退均通过，最终运单版本 2。
默认商城继续承接业务，影子库为 `SHADOW`，源目标水位均为 201，隔离为 0。商城 55 项、订单 2 项、履约 2 项
测试及完整 Windows 快速回归通过。切流准入仍为 `REJECTED`，下一批验证真实物流桥和独立人工恢复。

独立履约已实现默认关闭的管理员签名查询和恢复。隔离订单 101 使用真实商城物流桥和物流模拟器，回执提交后
模拟断连，确认原命令进入人工核对。签名恢复与审计在同一事务提交，原命令完成后没有重复建单。隔离源冻结水位
为 5，六表逐行一致，旧写授权降为 0 后仍返回冻结错误。回退后原商家路由沿用旧回执，运单推进到版本 2，
管理员审计保留。默认部署仍由商城承接商家履约。下一批处理商家和管理员远程路由与现有影子库所有权转换，
切流准入仍为 `REJECTED`。

M3.7 已接通商城商家与管理员的签名远程路由。隔离演练在 generation 1、水位 5 原地转换影子库，撤销旧六表
写权限后验证历史命令、兼容接单发货和改运单、管理员恢复及停机 503。回传六表后恢复原商城路由，运单推进到
版本 2，管理员审计保留。默认环境保持 `SHADOW`，源目标水位 297，隔离与未决任务为 0。后续需将六表权限
交接和回退配置固化到部署脚本并通过重启验收。

V48 与独立执行覆盖配置已完成权限交接的重启验收。两套隔离 MySQL 和应用重启、授权任务重跑后，旧账号对
六表的 18 次增删改均被拒绝。回退恢复原商城权限，退役目标拒绝旧执行配置重启。默认环境仍为
`COMMERCE`/`SHADOW`，源目标水位 345、隔离与未决任务为 0。正式履约切流待明确授权和窗口内最终核对。

2026-11-18 履约正式切流并通过端到端验收。最终交接点为 generation 41、水位 393，六表一致。独立履约库
为 `EXECUTION`，商城仅代理履约请求，源六表授权为 0，源水位仍为 393。验收订单 675 的运单版本为 2，
历史命令和管理员恢复重放通过。首次验收脚本列表超时后的回退记录见演进计划第 17 节。

独立履约已纳入 Prometheus 指标与告警。订单 676 的发货任务在 `PENDING/RESERVE` 停机后沿用原命令完成，
发货和改运单各产生一次事件。商城源水位保持 393，执行与路由指标均为 1，待处理与人工核对均为 0。

2026-11-18 初次复核时，M3 六个服务已切至独立库。当时库存迁移为 `READY`，发现 35 条库存投递待对账、
1 个缺少投递记录的等待预占订单、4 条支付对账 OPEN，以及目录、通知及已停用影子表的遗留权限。
收尾优先顺序与验收标准见 [M3 单一所有权复核](architecture/M3-SINGLE-OWNERSHIP-REVIEW.md)。

库存旧迁移入口已通过隔离空库导入、已切流和非空库拒绝、并发新增保护及指纹不符回滚验证。现有库存库登记
`CUTOVER_COMPLETE`（generation 25），五表 3012 行及商城库存投递 Outbox 的登记前后摘要一致。

2026-11-19 复核结果为库存历史 35 条中 30 条业务结案、5 条测试数据归档，订单 485 已取消，支付库 4 条历史对账已结案。旧账号撤权和迁移工具隔离均已验收。日常环境本次升级保留 9 个数据库原容器与卷，历史订单、库存流水、支付记录和运单摘要一致。[日常库存验收](architecture/INVENTORY-RECONCILIATION-REVIEW.md#2026-11-19-日常环境库存门禁验收)

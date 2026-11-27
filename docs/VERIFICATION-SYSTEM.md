# CommerceFlow Lab 全开发生命周期可复测验证体系

## 1. 目标与适用范围

本文档把现有测试、Docker 验收、并发/故障实验和数据生命周期脚本组织成一套统一门禁。它解决三个问题：

1. 某类改动至少要跑哪些验证。
2. 如何保存足够证据，使失败可重现、结果可对比。
3. 哪些入口会改写学习数据，哪些操作需要显式的破坏性参数。

当前验证对象是本地单机 Docker Compose 学习环境。它不等价于多节点容灾、真实支付/物流渠道、多日容量压测或生产合规认证。

## 2. 事实和证据优先级

验收结论按以下顺序判定：

```text
当前源码/迁移/脚本
    -> 本次命令的退出码和实际输出
    -> 数据库不变量、历史指纹和健康端点
    -> IMPLEMENTED.md 与各架构专题的验收证据
    -> TODO.md 当前快照与历史实施记录
```

只有脚本返回成功、关键不变量成立且清理/恢复步骤完成，才能记录为“已验收”。仅有代码、仅构建成功或仅容器启动都不等于业务验收通过。

## 3. 分层验证入口

| 级别 | 目的 | 主入口 | 何时执行 |
| --- | --- | --- | --- |
| L0 预检 | 确认分支、变更边界、后端代码质量、Compose 和脚本契约 | `git status`、`audit-code-quality`、`docker compose config --quiet`、`audit-script-contracts` | 每次修改前后 |
| L1 构建/测试 | 编译、单元/集成测试、前端生产构建 | `docker compose build <service>` | Java/POM/前端代码改动 |
| L2 快速回归 | 数分钟内覆盖核心端到端不变量 | `verify-quick` | 普通功能提交、跨模块重构 |
| L3 完整验收 | 串联主要功能域与最终健康检查 | `verify-all` | 里程碑、合并前、基线冻结前 |
| L4 数据生命周期 | 验证空库、旧库升级、重启恢复和历史一致性 | `verify-flyway-lifecycle`、`verify-fresh-volume-lifecycle`、`verify-upgrade-restart-recovery` | Flyway、持久模型、Compose/中间件改动 |
| L5 可靠性专项 | 真实锁超时、进程崩溃、MQ 故障、重投与并发竞态 | 对应 `verify-*` 专项 | 事务、锁、幂等、任务或 MQ 改动 |
| L6 浸泡/压测 | 在可重放随机种子下验证长时间不变量 | 支付取消、物流乱序、收货/完成 stress 入口 | 并发语义或性能相关改动、里程碑冻结前 |

各平台统一执行同一份 PowerShell 7 脚本。机器可读契约见 `scripts/script-contracts.tsv`。

日常核对一笔订单时，传入该单的数字 ID。例如，下面两条只读命令分别检查订单 750 的结算凭证、库存命令，以及整个目录与通知链路的积压。

输出中的 `Commerce ledger` 来自当前商城应用的 JDBC 连接。日常切换后应指向 `commerce-db-postgresql/commerceflow`。`Historical order source` 指向保留的只读 原数据库，供核对拆分前的订单历史。订单状态、库存流水和退款记录仍分别由订单、库存和支付服务持有。

## 4. 日常开发流程

### 4.1 修改前：固定基线

```powershell
git status --short
git branch --show-current
git log -5 --oneline --decorate
docker compose ps
```

- 若工作区不干净，先区分用户改动与本轮改动，不覆盖无关文件。
- 记录基线 commit、当前 Compose Profile 和是否使用现有卷。
- 涉及已发布 Flyway 文件时，必须先确认它们不可修改；变更只能通过新版本迁移追加。

### 4.2 编码循环：最小反馈

1. 先运行被改模块的构建/测试。
2. 修改 Java/Mapper XML/POM 后先运行 `audit-code-quality`；修改 Mapper XML 还要运行对应 Spring 测试和真实 PostgreSQL 专项，不只依赖 H2。
3. 修改页面后完成生产构建，并运行相关 API/权限/国际化脚本。
4. 在提交前运行 L0，普通功能改动再运行 L2。

### 4.3 提交/基线门禁

```powershell
pwsh -File ./scripts/verify-quick.ps1 -CleanupOnSuccess
pwsh -File ./scripts/verify-all.ps1 -CleanupOnSuccess
```

`verify-quick` 和 `verify-all` 始终运行后端质量与脚本契约门禁。两者均创建独立 Compose 项目，
在独立项目中用新卷初始化业务 PostgreSQL 和 Kafka。业务请求、数据库核对、故障恢复及健康检查
均使用该项目，完整入口还启用隔离观测 Profile。默认结束时停止隔离容器并保留卷。默认构建模式还会通过 Angular Dockerfile 执行
`npm run format:check` 和生产构建。`-SkipBuild` 只能在已确认镜像来自当前源码时使用，
并且不会重新执行 Maven/ArchUnit 测试或 Angular 格式检查。冻结基线或修改 Dockerfile/POM/前端依赖时
必须使用默认构建模式。

验收结果只需保留日志和账本摘要时，在快速或完整入口传入 `-CleanupOnSuccess` 并将命令输出保存到验收目录。
通过最后一轮健康检查后，脚本删除本次隔离项目的容器、网络、数据卷及本次构建的项目专用镜像标签。
搭配 `-SkipBuild` 使用时保留预先提供的验收镜像。验收失败时停止项目并保留现场，下次复测前先保存日志、
故障证据及卷清单。需要保留现场的升级或回退演练，在待办中登记对应项目、复验条件和清理时点。

新卷初始化使用履约空库覆盖文件。演示库存同时写入商品总账和默认仓，保证报价与实际仓库预占使用一致的库存。

GitHub Actions 的 `PostgreSQL isolated acceptance` 作业在推送 `main` 或手动触发检查时执行同一完整入口。作业在 Ubuntu runner 构建镜像并使用独立项目、新 PostgreSQL 卷和动态回环端口。成功或失败时均归档 `postgresql-acceptance.log`，保留 14 天。[工作流](../.github/workflows/checks.yml)

### 4.4 商家运营组合回归

在仓库根目录用 PowerShell 7 启动隔离项目。组合章节绑定检查门户、支付、物流、仓库和消息模拟器的端口及 Compose 标签。它会创建测试店铺、触发受控故障和重启隔离服务。

```powershell
. ./scripts/lib/release-acceptance.ps1
Invoke-IsolatedAcceptance -SimulatorAdmin -MessagingFaults -CleanupOnSuccess -Verify {
  param($Target)
  $endpoints = $Target.Endpoints
  $arguments = @{
    Chapter = 'Operations'
    ProjectName = $Target.Project
    BaseUrl = $endpoints['commerce-portal-angular'].Url
    PaymentBaseUrl = $endpoints['payment-simulator-web'].Url
    LogisticsUrl = $endpoints['logistics-simulator-service'].Url
    WarehouseBaseUrl = $endpoints['warehouse-simulator-web'].Url
    MessagingBaseUrl = $endpoints['messaging-simulator-web'].Url
    NotificationDatabase = 'PostgreSQL'
  }
  & ./scripts/commerce/verify-merchant-operations.ps1 @arguments
}
```

将上例的 `Chapter` 设为 `StatementCorrections` 可单独复验冻结账单差异。脚本在隔离店铺触发部分退款和三次模拟红冲失败，验证发票复核、回执关联和结案重放。库存场景核对指定出库行的数量差异、已完成预占订单的 `MANUAL` 路由、盘点资格与结案拦截。管理员和本店待办须关联同一原订单及差异复核，显示待凭证。另一家店的待办须为空。验收后恢复故障注入行的原值。输出包含订单、账单、红蓝票、复核及结案回执。

输出包含正常出库和短发、补充质检、退款及红票、冻结账单、打款回执和异常结案的关联号。重点核对同一退货的实发、实收、合格件数、退款金额和红票引用，以及恢复后的单笔库存入账和成功打款。通知验收包含渠道接收后超时、查询恢复与重放。新卷报告初始归档为空。复用历史副本时核对记录中的五笔历史归档。

同卷回退先锁定商城和门户的原镜像与候选、卷名、Flyway 版本、处置行和渠道回执，验证冷备可恢复后先把门户切回原镜像，再切商城。以原路径读取冻结账单和复核历史，然后先切回候选商城，再切候选门户。两次切换均核对原数据库容器及卷身份，恢复后确认处置回执、结案及待办可读。验收证据记录镜像 ID、备份校验和、全表基线及清理清单。

## 5. 改动类型与必跑门禁

| 改动范围 | 最低门禁 | 附加门禁 |
| --- | --- | --- |
| 纯文档，不改命令/契约 | L0，链接和命令人工复核 | 无 |
| Java 业务代码、Mapper、POM | L1 + L2 | 对应功能专项；跨模块时加 L3 |
| Angular/Vue/Nginx | 模块格式检查 + 单元测试 + 前端生产镜像构建 + L2 | 角色/国际化/相关业务专项 |
| Flyway/表结构/状态约束 | L1 + L2 + L4 | 相关业务不变量与升级后历史指纹 |
| Compose、服务名、Profile、脚本 | L0 + 五镜像构建 + L2 | 涉及卷/重启时加 L4；涉及 MQ 时加对应 L5 |
| Prometheus、Alertmanager、告警规则 | L0 + `verify-alerting` | 规则变更必须通过 `promtool` 单测和 Watchdog 实时投递 |
| OpenTelemetry、Tempo、Trace 传播 | L0 + 三服务构建 + `verify-distributed-tracing` | 涉及消息中间件时追加对应 Kafka/RabbitMQ/RocketMQ 真实传播专项 |
| 事务、锁定顺序、幂等、Outbox | L1 + L2 | 锁超时、崩溃恢复、竞态和历史一致性专项 |
| RabbitMQ/RocketMQ/定时任务 | L1 + L2 | 幂等、未 ACK 重投、Broker 停机恢复和 Profile 对应专项 |
| 并发状态机 | L1 + L2 | 固定种子短竞态 + L6 浸泡，并记录数据库矛盾计数 |
| 发布、标签或里程碑冻结 | L0–L4 | 与本里程碑相关的 L5/L6 |

## 6. 数据库与环境生命周期

验收与运维的 SQL 连接使用[按用途授权的核验账号](architecture/M3-SINGLE-OWNERSHIP-REVIEW.md#8-验收与运维账号撤权)。
运行依赖 SQL 的独立专项前，先为当前项目启用所需读者或演练账号。

### 6.1 空卷与 Flyway

快速回归和完整验收在隔离项目使用新卷，按各服务的 PostgreSQL 迁移文件初始化业务库和演示数据。

### 6.2 旧卷备份恢复

以独立 PostgreSQL 恢复副本核对每个库的表摘要、序列、约束和授权。部署参数、校验和及业务对账要求见[数据库运维](architecture/DATABASE-EVOLUTION-PLAN.md)。

## 7. 可靠性和并发专项

| 风险 | 必跑入口 | 关键证据 |
| --- | --- | --- |
| 支付/取消行锁 | `verify-payment-cancellation-lock-timeout` | `503/DATABASE_CONCURRENCY_RETRY`、超时事务无中间态、重试成功 |
| 支付/取消进程退出 | `verify-payment-cancellation-crash-recovery` | 四个精确事务窗口、回滚/重放、单一业务事实 |
| 切流后取消订单跨库进程退出 | `verify-order-cancellation-cutover-recovery` | 订单终态已提交、商城 Outbox 尚未提交时 SIGKILL，重启自动恢复，库存释放与可见通知各一次 |
| 支付/取消竞态 | `verify-payment-cancellation-race` / `stress` | 种子、轮数、合法终态分布、矛盾记录为 0 |
| 物流乱序 | `verify-logistics-ordering-stress` | 时长、并发、唯一回调数、状态不倒退、不变字段未覆盖 |
| MQ 重复/毒消息 | `verify-mq-scheduling-idempotency` | ignored 指标、DLQ、领取/成功/副作用各一次 |
| MQ 未 ACK 退出 | `verify-mq-scheduling-redelivery` | Broker 重投，不额外发布，业务副作用一次 |
| 自动收货/完成 MQ 故障 | `verify-auto-lifecycle-mq-failures` | 发布失败可观测、恢复后沿原快照执行、终态一次 |
| 收货/完成长竞态 | `verify-receipt-lifecycle-stress` | 手动/自动两种赢家、收货转换/任务/完成事件各一次 |
| 告警链断路或规则误判 | `verify-alerting` | 三个 Target、10 条规则、PromQL 单测、Alertmanager 配置和 Watchdog Webhook |
| 通知死信告警无人确认或无法定位事件 | `verify-notification-alert-workflow` | 真实 Kafka 死信触发告警，主值班确认、超时升级、备值班确认和恢复通知，按死信偏移读回原始事件 ID |

浸泡输出必须记录随机种子、时长、并发数、节流参数、实际操作数和最终数据库审计值。没有这些值的“压测通过”不具备可复测性。

## 8. 安全和破坏性边界

- 默认入口不执行 `down --volumes`、`docker volume rm` 或 `docker volume prune`。
- 当前默认卷属于 `fairy-commerceflow` Compose 项目；隔离专项不得使用该项目名。
- 破坏性重建必须由用户明确授权，先列出精确容器/卷，再使用显式参数；不使用全局 prune。
- 故障脚本会停止、SIGKILL 或重建特定服务，必须在 `finally` / `trap` 中恢复故障开关、环境变量和默认服务。
- 验收脚本会写入可识别的测试订单、任务和审计数据；“不删卷”不等于“不改数据”。

## 9. 可复测证据记录

每次里程碑或故障实验至少记录：

```text
基线：commit / tag / 工作区是否干净
环境：OS、Docker/Compose、Java/Node、Compose Profile
入口：完整命令与所有非默认参数
输入：随机种子、时长、并发、轮数、临时项目名
结果：退出码、通过/失败项、测试数、健康状态
数据证据：Flyway 版本、矛盾计数、事件/副作用计数、历史指纹
清理：临时容器/卷是否删除，默认环境是否恢复
边界：本次未验证的平台、时长、多节点或外部系统
```

长日志不应全部复制到 `IMPLEMENTED.md`。该文档只记录可复核结论、关键计数和边界；原始日志保留在当次任务/构建记录中。

## 10. 失败处理顺序

1. 保留失败现场，不立即删除临时卷或重跑覆盖证据。
2. 记录失败命令、退出码、随机种子、对象 ID 和容器状态。
3. 收集最小相关服务日志、对应表行和事件轨迹，不用全量日志淹没问题。
4. 先使用原参数重现，再将场景缩小到单一专项。
5. 修复后运行失败专项 + L2；若涉及共享基础设施或事务语义，追加 L3/L4。

## 11. 基线冻结检查表

- [ ] 工作区干净，本地分支与远端一致。
- [ ] L0 脚本契约、Compose 配置与默认非破坏性审计通过。
- [ ] 当前源码镜像在不跳过构建的情况下通过 L2/L3。
- [ ] Flyway 空库、旧库升级和持久卷重启恢复通过。
- [ ] 本里程碑改动触发的所有 L5/L6 专项通过。
- [ ] 涉及过渡兼容的事项已通过[兼容退出验收](#12-临时兼容退出验收)。
- [ ] 验收后默认服务健康，故障开关、Profile 和临时资源已恢复/清理。
- [ ] 更新所属待办的完成状态，并链接 `IMPLEMENTED.md` 或架构专题中已有的实测证据。
- [ ] 提交、推送后再创建注释标签，不移动已发布基线标签。

M1 冻结证据保留在 `m1-baseline-2026-11-13`。后续基线应使用新标签，不改写 M1 历史。
M2 已按本清单完成质量总验收，并以注释标签 `m2-quality-baseline-2026-11-14` 冻结；
详细证据见 [M2 工程质量总验收记录](archive/PROJECT-HISTORY.md#133-m2-工程质量总验收与基线冻结)。

## 12. 临时兼容退出验收

凡在迁移期间保留兼容的功能，须逐项核对以下条件。任一项未通过，保持过渡状态，最终验收不能通过。

- [ ] 对照过渡记录核实调用方和数据已迁移。现行入口、运行权限和唯一写入方与目标设计一致。
- [ ] 检查源码、配置、部署入口和运行授权。过渡接口、双读双写、旧源回退和临时适配器均已移除。
- [ ] 使用同一个最终候选版本，在隔离环境启动空库和从升级前版本迁移的存量库。
  在两种环境通过正式入口执行关键业务，核对历史业务事实保真以及旧入口和运行依赖已退出。
- [ ] 记录迁移结果、移除的路径及验证证据。归档或回退工件如需保留，核对其与正式运行隔离。

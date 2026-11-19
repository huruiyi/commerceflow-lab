# CommerceFlow Lab / CommerceFlow 电商全链路实验室

这是一个可独立运行的前后端分离教学项目，已将原 Angular Demo 中的登录、商品、订单和用户功能改为本地 Spring Boot + MySQL 实现，不再依赖外部 JsonBulut API。

## 项目标识 / Project identity

| 用途 | 标识 |
|---|---|
| 英文标题 | `CommerceFlow Lab` |
| 中文标题 | `CommerceFlow 电商全链路实验室` |
| GitHub 仓库 | `commerceflow-lab` |
| Java/Maven 命名空间 | `vip.fairy.commerceflow` |
| Docker Compose 项目名 | `fairy-commerceflow` |
| M1 历史基线 | `m1-baseline-2026-11-13`（保留旧命名与验收证据） |
| M2 质量基线 | `m2-quality-baseline-2026-11-14` |
| 商城服务 Maven artifactId | `commerce-service` |
| 通知服务 Maven artifactId | `notification-service`（默认启动、唯一通知读写实现） |
| 商品服务 Maven artifactId | `catalog-service`（默认启动、唯一商品资料读写实现） |
| 库存服务 Maven artifactId | `inventory-service`（默认启动，库存命令和商家库存管理的唯一实现） |
| 支付编排服务 Maven artifactId | `payment-orchestration-service`（远程协议已验收，普通业务流量门关闭） |
| 命名迁移策略 | 不混用两套名称；按 [命名规范](docs/NAMING-CONVENTIONS.md) 独立迁移 |

## 技术架构

```text
浏览器
  └─ http://localhost:14200
       └─ Nginx
            ├─ Angular 22.2.6 + SimUI 1.8.0 / Spartan UI 静态页面
            └─ /api/* → commerce-service → MySQL 8.4 / Redis 7.4
                         └─ 通知身份薄代理 → notification-service → 独立 MySQL 8.4
                         ├─ 商品身份薄代理 → catalog-service → 独立 MySQL 8.4
                         └─ 库存 Outbox / 身份薄代理 → inventory-service → 独立 MySQL 8.4

支付平台浏览器 http://localhost:14201
  └─ Vue 3.5.45 + Ant Design Vue Next + VXE-Table + Nginx
                  → 独立 Spring Boot 支付服务 → 独立 MySQL
                                      └────────────→ 独立 Redis Session 命名空间
```

- `web/commerce-portal/angular/`：Angular 22.2.6、SimUI 1.8.0 / Spartan UI 1.8.0、Tailwind CSS 4.3.5、Bootstrap 5.3.9、Nginx 配置和前端 Dockerfile。
- `services/commerce-service/`：Java 25 LTS、Spring Boot 4.1.2、Spring Security 7、MyBatis-Plus、Flyway 和后端 Dockerfile。
- `services/notification-service/`：独立 MySQL/Kafka 通知服务，是通知查询、未读统计和已读状态的唯一实现；
  commerce 只保留 Session/RBAC/HMAC 薄代理与订单事务侧通知 Outbox。
- `services/catalog-service/`：独立 MySQL 商品资料服务，commerce 只保留鉴权、签名代理与评价组合。
- `services/inventory-service/`：分层库存服务，独立 MySQL 保存五张所有权表；默认接收 RESERVE、COMMIT、
  RELEASE、REFUND，并独占商家库存查询、调账和流水。commerce 只保留生产端 Outbox/结果 Inbox 与
  Session/RBAC/HMAC 薄代理，其运行数据库账号无五张库存源表权限。
- `services/payment-orchestration-service/`：独立 MySQL 支付/退款边界；已完成 14 张所有权表的影子迁移、远程
  Payment V1、跨边界订单/库存副作用、可靠 Outbox、退款命令和故障/回退演练，普通业务流量门与副作用门关闭，
  尚未 CUTOVER。
- `simulators/payment-platform/service/`：独立支付数据库、后台安全、商户凭据和三渠道交易服务。
- `simulators/logistics-platform/service/`：独立物流数据库、运单推进、异常与可靠回调模拟服务。
- `simulators/payment-platform/web/`：Vue 3.5.45、Ant Design Vue Next 和 VXE-Table 支付管理后台与模拟收银台。
- `compose.yaml`：一键启动 MySQL、Redis、后端和前端。

## 学习路线与代码阅读顺序

建议按“界面调用 → 安全边界 → 业务事务 → 数据结构 → 异步可靠性”的顺序阅读：

1. 从 `web/commerce-portal/angular/src/app` 看登录、商品、购物车、地址、订单和商家页面如何调用 API。
2. 阅读 `services/commerce-service/.../identity/infrastructure/SecurityConfiguration.java` 和认证 Controller，理解 Session、CSRF、Remember-Me 与角色授权。
3. 阅读购物车、下单、支付、履约、收货评价对应的 Controller、Service、Mapper。
4. 按版本查看 `services/commerce-service/src/main/resources/db/migration`，把业务模型与数据库约束对应起来。
5. 阅读支付 Outbox、Kafka 发布/消费和补偿任务，再进入独立的 `simulators/payment-platform/service/`。
6. 最后使用 `scripts/` 中的验收脚本做成功、失败、幂等、越权和故障实验。

文档统一从 [docs/README.md](docs/README.md) 进入：[当前项目进度](docs/PROJECT-STATUS.md) 给出快照和下一里程碑，[已实现现状](docs/IMPLEMENTED.md) 只记录可验证事实，[统一进度与代办](docs/TODO.md) 是勾选状态和未实现顺序的唯一清单，接口索引见 [docs/API.md](docs/API.md)。分章历史见 [IMPLEMENTATION-PLAN.md](IMPLEMENTATION-PLAN.md)。

## 一键启动

在已完成履约交接或初始化的环境中，从本目录执行：

```powershell
docker compose up --build -d --wait
docker compose ps
```

商城默认使用独立履约服务。首次使用全新卷时，通过空库配置显式建立履约所有权，后续启动使用上面的默认命令。

```powershell
docker compose -f compose.yaml -f compose.fulfillment-fresh.yaml up --build -d --wait
```

已有商城本地履约数据的环境先完成所有权切换。使用独立签名密钥的环境可叠加 `compose.fulfillment-execution.yaml`，
回退时使用已验收的版本化回退包。见[履约演进计划](docs/architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md)。

已有目录库可直接启动。全新环境会先建表。体验加购、下单和支付时，启动后显式初始化演示商品、库存及支付渠道。

```powershell
docker compose --profile demo-data run --rm catalog-demo-init
docker compose --profile demo-data run --rm payment-demo-init
```

从旧商城库导入目录数据时使用[手工迁移入口](docs/architecture/M3-SINGLE-OWNERSHIP-REVIEW.md#5-目录启动与初始化验收)。

默认配置可直接用于本地学习。需要改端口以外的数据库密码、回调密钥或 Remember-Me 密钥时，复制 `.env.example` 为 `.env` 后修改；不要把生产密钥提交到仓库。

访问地址：

- 前端：`http://localhost:14200`
- 支付平台：`http://localhost:14201`
- 前端健康检查：`http://localhost:14200/healthz`
- 后端健康检查：`http://localhost:18081/actuator/health`
- 支付服务健康检查：`http://localhost:18082/actuator/health`
- 模拟物流健康检查：`http://localhost:18083/actuator/health`
- 通知服务健康检查：`http://localhost:18086/actuator/health`
- 商品服务健康检查：`http://localhost:18087/actuator/health`
- 库存服务健康检查：`http://localhost:18088/actuator/health`
- 订单服务健康检查：`http://localhost:18089/actuator/health`
- 支付编排影子服务健康检查：`http://localhost:18090/actuator/health`
- 物流 MySQL：`localhost:13308`
- Kafka UI：`http://localhost:14202`

`notification-db` 与 `notification-service` 属于默认拓扑。浏览器继续访问原 `/api/notifications`，commerce
从 Session 提取 customerId 并签名转发；它不访问通知表，也不在独立服务故障时回退到本地数据。

`inventory-db` 与 `inventory-service` 属于默认拓扑。commerce 在业务事务中只写库存命令 Outbox，由唯一
异步派发器向独立服务发送，未知结果使用原 commandId 有限重试，不回退本地 Handler。历史数据复跑使用
`inventory-migration` Profile；它不是长期运行路径。

可选告警与监控栈使用 `docker compose --profile observability up -d --wait` 启动：

- Prometheus：`http://localhost:19090`
- Alertmanager：`http://localhost:19093`
- Grafana：`http://localhost:13000`
- 本地告警 Webhook：`http://localhost:18085/api/alerts`

告警目录、阈值、验证方式与真实外部接收端边界见 [告警管道](docs/ALERTING.md)。

教学账号（密码均为 `demo-password`）：

| 账号 | 角色 | 可体验功能 |
| --- | --- | --- |
| `demo@example.com` | CUSTOMER | 购物车、地址、下单、支付、收货、评价 |
| `merchant@example.com` | MERCHANT | 查看所属订单、确认订单、录入物流并发货 |
| `admin@example.com` | ADMIN | 用户与角色数据查看 |

支付平台账号（密码同为 `demo-password`）：

| 账号 | 角色 | 可体验功能 |
| --- | --- | --- |
| `payment-admin` | PAYMENT_ADMIN | 用户、商户、凭据、安全设置与全量后台能力 |
| `payment-operator` | PAYMENT_OPERATOR | 商户和渠道交易运营 |
| `payment-auditor` | PAYMENT_AUDITOR | 只读审计与交易检查 |

### 如何登录

1. 确认 `docker compose ps` 中所有长期运行服务均为 `running`，带健康检查的服务均为 `healthy`。
2. 浏览器打开 `http://localhost:14200`。
3. 输入 `demo@example.com` 和 `demo-password`。
4. 点击登录后进入商品页，可继续查看订单和用户列表。

支付平台使用方法：

1. 打开 `http://localhost:14201`，用 `payment-admin` / `demo-password` 登录。
2. 先进入“安全中心”绑定 TOTP，并离线保存只显示一次的恢复码。
3. 在“商户与凭据”创建 HMAC 或 RSA 商户，立即保存只显示一次的 App Secret。
4. 在“渠道交易”使用 Web Crypto 签名，依次体验支付宝跳转、微信二维码和银联网关。
5. 密钥轮换、商户启停、创建后台用户和权限变更前，需在安全中心生成一次性 Step-up 令牌。

## 验收脚本

Windows、Linux 和 WSL 共用 `scripts/` 目录下的 PowerShell 7 脚本。运行前准备 `pwsh`、Docker 和 Compose v2；部分静态审计还使用 `rg`。以下命令以仓库根目录为工作目录。

单独检查脚本参数和临时卷清理规则：

```powershell
pwsh -File ./scripts/audit-script-contracts.ps1
```

契约清单与删卷边界见 [验收脚本审计](docs/SCRIPT-AUDIT.md)；改动类型对应的必跑门禁、证据格式和基线冻结检查见 [全开发生命周期可复测验证体系](docs/VERIFICATION-SYSTEM.md)。

四个 Java 服务的只读代码质量与 Mapper 契约门禁：

```powershell
pwsh -File ./scripts/audit-code-quality.ps1
```

该入口不会格式化或改写文件；它检查后端 Java、Mapper XML 和 Maven POM 的客观格式规则，
并校验 Mapper namespace、SQL statement id、Java 方法、内联 SQL 和直接 JDBC 边界。

order-service 的只读 CREATE 准入审计可单独复跑；它检查 7 张 order 所有权表、跨边界外键归零、状态与关联不变量、
外部 Mapper 消费者保持为 0，以及全局事务协调器缺席门禁，不创建服务、不迁移数据、不清空卷：

```powershell
pwsh -File ./scripts/audit-order-service-admission.ps1
```

当前结论和后续收口顺序见
[order-service 准入与单一所有权演进计划](docs/architecture/ORDER-SERVICE-EVOLUTION-PLAN.md)。

order-service 已完成一次性 CUTOVER 并独占订单运行时读写。以下入口验证新订单只写 order-db、源端计数不变、
commerce 运行账号无源表权限，以及 order-service 停机时明确返回 503、恢复后重新健康：

```powershell
pwsh -File ./scripts/verify-order-runtime-cutover.ps1
```

远程 Order V1 故障语义使用合成订单在独立库内演练；脚本临时打开协议门，验证 HMAC/摘要/防重放、Tempo
Trace、重复/冲突/乱序、命令 Outbox/结果 Inbox、超时未知结果和人工恢复，随后清理合成数据并恢复 503：

```powershell
pwsh -File ./scripts/verify-order-remote-protocol.ps1
pwsh -File ./scripts/verify-order-precutover-rehearsal.ps1
```

影子与预切换脚本保留为历史取证入口；默认运行时关闭影子复制且不再配置源库，不应重新开放源端权限。

payment-orchestration 准入审计检查 14 张 payment/refund 所有权表、跨边界外键、外部内部实现消费者、
金额/状态/幂等、独立 Outbox/Inbox 和恢复不变量；当前 CREATE 已准入，独立影子服务与数据库已经创建，
但没有业务流量，也未 CUTOVER：

```powershell
pwsh -File ./scripts/audit-payment-orchestration-admission.ps1
```

当前结论和后续顺序见
[payment-orchestration-service 准入与单一所有权演进计划](docs/architecture/PAYMENT-ORCHESTRATION-SERVICE-EVOLUTION-PLAN.md)。
受控停写、最终追平、唯一远程适配器、回调切换和源表撤权的原子顺序见
[payment-orchestration 一次性切换运行手册](docs/architecture/PAYMENT-ORCHESTRATION-CUTOVER-RUNBOOK.md)。
当前最终结论和剩余阻塞见
[payment-orchestration 最终切换 GO/NO-GO 评审](docs/architecture/PAYMENT-CUTOVER-GO-NO-GO-REVIEW.md)。
切换窗口使用 `PAYMENT_WRITE_FROZEN=true` 暂停创建支付、关闭支付和发起退款，同时保留签名回调排空；默认必须为
`false`，该开关本身不表示已经切流。
创建、查询、关闭和结果的稳定字段与恢复语义见
[支付第一版远程契约](docs/architecture/PAYMENT-V1-CONTRACT.md)。当前仍由唯一的本地适配器实现；CREATE
准入之后已完成无业务流量影子服务、远程协议和切流前故障/回退演练；commerce 仍只有本地正式路径，这仍不能
描述成已经切换独立支付服务。

一次性 CUTOVER 评审当前仍为 `REJECTED`：订单/库存副作用、支付 Outbox 投递和退款发起已在关闭流量门下补齐，
但 commerce 本地运行路径、渠道回调与源表权限尚未切换。以下审计通过表示剩余拒绝原因和安全门禁没有漂移，
不表示允许切流：

```powershell
pwsh -File ./scripts/audit-payment-cutover-readiness.ps1
```

支付副作用演练只插入一条精确合成 Outbox，验证远程失败进入可恢复 RETRY 且不产生库存写入；结束时删除该事件并
恢复流量关、副作用关、影子开：

```powershell
pwsh -File ./scripts/verify-payment-effects-rehearsal.ps1
```

支付影子运行专项会执行可重复快照、增量水位、14 表指纹核对和可恢复孤儿演练。支付副作用、影子运行、远程协议
及预切换演练脚本均使用显式隔离配置，启动方法见[旧迁移工具与权限验收](docs/architecture/M3-SINGLE-OWNERSHIP-REVIEW.md#7-旧迁移工具与权限验收)。

```powershell
pwsh -File ./scripts/verify-payment-shadow-runtime.ps1
```

远程协议专项会临时开启独立服务流量门，使用合成数据验证签名、防重放、支付/关闭竞态、退款、结果丢失、
服务/数据库停机与整体回退；脚本结束后自动清理合成数据并恢复关闭流量：

```powershell
pwsh -File ./scripts/verify-payment-remote-protocol.ps1
pwsh -File ./scripts/verify-payment-precutover-rehearsal.ps1
```

Angular 商城门户和支付模拟器 Web 都使用模块本地 Prettier 只读门禁：

```powershell
cd web/commerce-portal/angular
npm run format:check
npm test -- --watch=false --browsers=ChromeHeadless
```

```powershell
cd simulators/payment-platform/web
npm run format:check
npm test
```

两个正式前端 Dockerfile 都会在生产构建前自动执行 `format:check`。`format:write` 是人工明确执行的
机械格式化入口，不会由验收脚本自动改写源码。

### 快速回归

默认构建当前源码镜像并运行约数分钟的核心回归：

```powershell
pwsh -File ./scripts/verify-quick.ps1
```

已有当前镜像时可使用 `-SkipBuild`。快速入口始终执行后端代码质量与脚本契约门禁，
默认构建还会通过两个前端 Dockerfile 执行格式检查。回归覆盖权限/i18n、取消、支付竞态、退款、物流、
收货/完成/评价、commerce-service 租约恢复、Redis 降级和健康检查。跳过构建时不会重新执行镜像内
Maven/ArchUnit 测试或前端格式检查。它不代替两分钟浸泡、MQ 停机、空卷或旧卷重启专项。

### 完整自动验收

```powershell
pwsh -File ./scripts/verify-all.ps1
```

完整验收会先构建镜像并运行镜像内测试，再启动完整环境，最后验证认证、CSRF、权限边界、国际化、支付/取消竞态、支付结果、Kafka、签名物流和回调轨迹、收货评价、Redis 缓存、MySQL 降级与健康状态。已有最新镜像时可跳过构建：

```powershell
pwsh -File ./scripts/verify-all.ps1 -SkipBuild
```

可用 `-BaseUrl http://host:port` 指定商城地址。

### 单项脚本及作用

| 验收脚本 | 作用 | 默认地址 |
| --- | --- | --- |
| `verify-alerting` | 校验 Prometheus/Alertmanager 配置和规则单测，确认三个服务 Target 与 Watchdog 实时 Webhook 投递 | Prometheus `19090`、Alertmanager `19093`、Webhook `18085` |
| `verify-all` | 先执行后端代码质量门禁，再构建、启动并串联主要验收，包含告警链和最终健康检查 | 商城 `14200`、监控 `19090/19093/18085` |
| `verify-quick` | 始终执行后端代码质量和脚本契约门禁；默认构建并覆盖核心业务、重启恢复、Redis 降级与健康 | 商城 `14200`、支付 `14201/18082`、物流 `18083` |
| `verify-authorization` | 匿名访问、登录、禁用账号、CSRF 和三种角色权限矩阵 | 商城 `14200` |
| `verify-i18n` | 中英文商品、按语言隔离的 Redis 缓存、稳定错误码和前端入口 | 商城 `14200` |
| `verify-flyway-lifecycle` | 在隔离的临时 MySQL 中验证空库 V1–V24、V16 旧库升级、核心业务注释与历史订单截止时间回填，并确认非法任务状态被约束拒绝 | Docker Engine |
| `verify-fresh-volume-lifecycle` | 使用独立 Compose 项目和全新卷验证三库 Flyway、空订单种子与登录到评价全链路；默认保留临时卷，仅显式 `-RemoveTemporaryVolumes` 删除临时项目卷 | Docker Compose；不触碰默认 `fairy-commerceflow_*` 卷 |
| `verify-upgrade-restart-recovery` | 先做隔离 V16→V24 升级演练，再对当前持久卷重启三库/Redis/Kafka/业务服务/RabbitMQ/RocketMQ，验证两种 MQ 未完成任务恢复一次且历史指纹不变 | Docker Compose 全栈；只重启容器，不删卷 |
| `verify-payment-notifications` | 创建订单、发起待支付交易、模拟签名回调并拒绝伪造回调 | 商城 `14200` |
| `verify-payment-attempt-deadline` | 验证单次渠道支付过期不关闭整单、到期前可换渠道重试，以及整单截止时先关闭待支付交易再取消订单 | 商城 `14200`、支付平台 `14201` |
| `verify-payment-cancellation-race` | 交替偏置并加入随机抖动，并发支付完成与客户取消，校验订单/支付/退款一致性 | 商城 `14200`、支付平台 `14201` |
| `verify-payment-cancellation-stress` | 可配置时长、最少轮数、随机种子和节流的竞态浸泡测试；自动准备库存并对本轮订单做 MySQL 矛盾记录审计 | 商城 `14200`、支付平台 `14201`、Docker MySQL |
| `verify-payment-cancellation-lock-timeout` | 真实持有订单/支付行锁，验证锁等待超时可识别、事务完整回滚且释放锁后可重试 | commerce-service `18081`、Docker MySQL；只重建 commerce-service 容器，不删卷 |
| `verify-payment-cancellation-crash-recovery` | 定点 SIGKILL 验证取消中间态、渠道已关窗口、回调事务回滚与迟到回调 | commerce-service `18081`、商城/支付 MySQL；钩子默认关闭，不删卷 |
| `verify-kafka-payment` | 支付回调、Kafka Topic、Outbox 发布和消费记录 | 商城 `14200` |
| `verify-notification-runtime-cutover` | notification 单一所有权、签名代理、eventId 已读、停机 503 与核心商城隔离 | 商城 `18081`、通知服务 `18086`、通知 MySQL |
| `verify-inventory-single-owner` | 商家库存签名代理、调账恢复、五张源表权限拒绝、未签名 401、停机 503 与恢复 | 商城 `18081`、库存服务 `18088`、商城/库存 MySQL |
| `verify-payment-outcomes` | 失败、余额不足、取消和超时四类支付结果 | 商城 `14200` |
| `verify-refunds` | 全额退款、资金账一致性、请求/通知幂等、重复申请和伪造通知拒绝 | 商城 `14200` |
| `verify-merchant-fulfillment` | 已支付订单的商家确认、发货及客户越权拒绝 | 商城 `14200` |
| `verify-logistics` | 独立物流建单、签名/防重放、`EXCEPTION（异常件） -> RECOVERY（异常恢复）`、六段轨迹和回调投影 | 商城 `14200`、物流 `18083` |
| `verify-logistics-automation` | 临时启用秒级自动轨迹，验证自动送达与 `AUTO_RECEIVE`，结束后恢复默认关闭 | 商城 `14200`、物流 `18083` |
| `verify-logistics-reliability` | 验证 commerce-service 停机后的 `RETRY -> PUBLISHED`，以及永久丢失回调由主动对账恢复为 `RECONCILED` | 商城 `14200`、物流 `18083` |
| `verify-logistics-ordering-stress` | 可配置时长/并发/最少回调数的乱序浸泡，审计状态不倒退、副作用有界和不变字段 | 商城 `14200`、commerce-service `18081`、Docker MySQL |
| `verify-logistics-callback-governance` | 制造 `DEAD（停止自动重试）`，验证管理员人工重放、审计、权限、幂等和 Prometheus 指标 | 商城 `14200`、物流 `18083` |
| `verify-auto-lifecycle` | 手动收货取消自动任务、`DELIVERED（已送达） -> RECEIVED（已收货） -> COMPLETED（已完成）`，以及 commerce-service 重启后租约恢复/幂等重放 | 商城 `14200`、物流 `18083` |
| `verify-auto-lifecycle-mq-failures` | 分别停止 RabbitMQ/RocketMQ，验证 `AUTO_RECEIVE/AUTO_COMPLETE` 发布失败可观测、Broker 恢复后按策略快照执行且副作用仅一次 | 商城 `14200`、Docker Compose、RabbitMQ、RocketMQ；不删卷 |
| `verify-receipt-lifecycle-stress` | 长时间并发客户收货与 RabbitMQ 任务重投，交替覆盖手动/自动收货赢家，审计收货和完成副作用各一次 | 默认 120 秒/12 并发；保留数据卷 |
| `verify-rabbitmq-scheduling` | RabbitMQ 延迟任务的确认发布、策略快照、原子领取、审计事件和未支付订单自动关闭；需先启用 RabbitMQ Profile 并使用秒级测试期限 | 商城 `14200`、RabbitMQ `15672` |
| `verify-rocketmq-scheduling` | RocketMQ 5.x 原生延时消息的 Broker 确认、策略快照、原子领取、审计事件和未支付订单自动关闭；需启用 RocketMQ Profile 和秒级测试期限 | 商城 `14200`、RocketMQ Proxy `18084` |
| `verify-rocketmq-reconciliation` | 停止 RocketMQ Broker，验证发布失败可观测、Database 对账兜底、策略快照不变，并在结束时恢复 Broker | 商城 `14200`、Docker Compose |
| `verify-mq-scheduling-idempotency` | 向 RabbitMQ/RocketMQ 重放已完成任务的旧消息并注入非法 JSON，验证只有一次领取/完成及两种 DLQ；需同时启用两个 MQ Profile | 商城 `14200`、后端指标 `18081`、RabbitMQ 管理端 `15672`、RocketMQ |
| `verify-mq-scheduling-redelivery` | 在消费者已取到消息但未确认时强制终止 commerce-service，验证 RabbitMQ/RocketMQ 重新投递且业务只执行一次 | 商城 `14200`、Docker Compose、RabbitMQ、RocketMQ |
| `verify-receipt-reviews` | 收货、一次性评价、评分校验及商品评分聚合 | 商城 `14200` |
| `verify-redis-business` | 商品缓存 TTL、支付幂等键、Redis 停机时 MySQL 降级及恢复 | 商城 `14200` |
| `verify-payment-security` | HMAC/RSA 三渠道签名、Nonce/篡改/过期拒绝、TOTP、Step-up 和审计哈希链 | 支付平台 `14201` |
| `verify-payment` | `verify-payment-security` 的兼容入口 | 支付平台 `14201` |

单项脚本通过 `-BaseUrl` 改地址：

```powershell
pwsh -File ./scripts/verify-authorization.ps1 -BaseUrl http://localhost:14200
pwsh -File ./scripts/verify-flyway-lifecycle.ps1
pwsh -File ./scripts/verify-payment-attempt-deadline.ps1 -BaseUrl http://localhost:14200 -PaymentUrl http://localhost:14201
pwsh -File ./scripts/verify-payment-cancellation-stress.ps1 -DurationSeconds 300 -MinimumIterations 30
pwsh -File ./scripts/verify-payment.ps1 -BaseUrl http://localhost:14201
```

RabbitMQ 调度专项验收需要显式启用可选适配器；默认 `docker compose up` 仍只使用 Database，不依赖 RabbitMQ：

```powershell
$env:SCHEDULING_RABBITMQ_ENABLED='true'
$env:ORDER_PAYMENT_TIMEOUT='5s'
docker compose --profile rabbitmq up -d --build --wait rabbitmq-broker commerce-service commerce-portal-angular
pwsh -File ./scripts/verify-rabbitmq-scheduling.ps1 -MaxWaitSeconds 30
```

RocketMQ 调度使用 5.x gRPC 客户端和原生绝对投递时间；Profile 会启动 NameServer、Broker/Proxy，并创建 `message.type=DELAY` Topic：

```powershell
$env:SCHEDULING_ROCKETMQ_ENABLED='true'
$env:ORDER_PAYMENT_TIMEOUT='5s'
docker compose --profile rocketmq up -d --build --wait rocketmq-topic-init commerce-service commerce-portal-angular
pwsh -File ./scripts/verify-rocketmq-scheduling.ps1 -MaxWaitSeconds 30
pwsh -File ./scripts/verify-rocketmq-reconciliation.ps1 -MaxWaitSeconds 45
```

故障脚本会短暂停止并恢复 RocketMQ Broker；建议同时把 `SCHEDULING_RECONCILIATION_INITIAL_DELAY`、`SCHEDULING_RECONCILIATION_FIXED_DELAY` 和 `SCHEDULING_ROCKETMQ_REQUEST_TIMEOUT` 调成秒级，避免等待默认生产型周期。

重复、迟到旧消息和毒消息专项会同时启动两种 MQ，但每个任务仍只有一个主策略：

```powershell
$env:SCHEDULING_RABBITMQ_ENABLED='true'
$env:SCHEDULING_ROCKETMQ_ENABLED='true'
$env:ORDER_PAYMENT_TIMEOUT='6s'
docker compose --profile rabbitmq --profile rocketmq up -d --build --wait rabbitmq-broker rocketmq-topic-init commerce-service commerce-portal-angular
pwsh -File ./scripts/verify-mq-scheduling-idempotency.ps1 -MaxWaitSeconds 60
```

专项脚本要求 RabbitMQ Dead Queue 在开始前为空；验证后会取走本次 RabbitMQ 毒消息。RocketMQ 毒消息使用唯一 Key 查询对应 `%DLQ%ConsumerGroup`，不会清空或覆盖已有消息。

未确认重新投递专项会自动启动两种 Broker、两次以零秒宽限停止 commerce-service，并在结束时恢复策略与环境变量：

```powershell
pwsh -File ./scripts/verify-mq-scheduling-redelivery.ps1 -MaxWaitSeconds 120
```

详细确认边界、实验证据和官方学习资料见 [MQ 未确认消息与重新投递](docs/learning/CHAPTER-06A-MQ-UNACKED-REDELIVERY.md)。

这些脚本不是纯只读检查：支付、履约、评价脚本会创建演示订单、交易、商户或评价；`verify-redis-business` 会短暂停止再恢复 Redis；完整验收会构建镜像并启动 Compose 服务。建议只在本地学习环境运行。

本项目**没有 Spring Boot 启动时随机生成的默认密码**。三个演示账号由 Flyway 创建，密码以 BCrypt 哈希保存。Spring Security 的 `User` 是认证时从数据库记录转换出来的运行时对象，不是另一个内置账号。

商城 Flyway 创建演示账号和禁用的用户列表资料，演示账号的 BCrypt 哈希随迁移脚本提交。独立目录和库存的演示商品由 `catalog-demo-init` 初始化。如需从零重建本地演示数据，可执行：

```powershell
docker compose down --volumes
docker compose up --build -d --wait
docker compose --profile demo-data run --rm catalog-demo-init
```

`--volumes` 会同时删除已有订单和所有 MySQL 演示数据，使用前请确认数据可以丢弃。

停止服务：

```powershell
docker compose down
```

需要同时删除本地演示数据时使用 `docker compose down --volumes`。也可在上一级 `legacy-reference` 目录通过聚合 Compose 与其他 Demo 一起启动。

## 已实现功能

完整功能、操作路径、金额规则和状态请查阅 [功能文档](docs/features/README.md)。

| 功能模块 | 已交付能力 |
| --- | --- |
| [账号、商品与购物车](docs/features/ACCOUNT-CATALOG.md) | 邮箱登录与授权、商品查询和评价、收货地址、购物车、再次购买 |
| [结算、运费与优惠券](docs/features/CHECKOUT-COUPONS.md) | 勾选结算、统一计价、商家运费模板、单商家满减、订单行分摊、并发锁券、付款核销、取消返还和恢复 |
| [订单、取消与履约](docs/features/ORDERS-FULFILLMENT.md) | 订单历史、通知跳转、取消补偿、独立履约、正向与退货物流、收货和自动完成 |
| [支付、退款与售后](docs/features/PAYMENTS-AFTER-SALES.md) | 三渠道模拟支付、原路全退、按订单行售后与部分退款、退货验收和库存回补 |
| [库存与商家库存中心](docs/features/INVENTORY.md) | 预占、扣减、释放、回补、库存管理和命令对账 |
| [外部系统模拟器](docs/features/SIMULATORS.md) | 独立支付与物流模拟器、渠道回调和管理入口 |
| [部署、监控与验收](docs/features/OPERATIONS.md) | Docker、Flyway、定时任务、分服务告警和隔离验收 |

[文档中心](docs/README.md) 提供架构、API、验收记录和项目计划的入口。

## 安全和生产边界

Compose 中的数据库密码、商户号、App ID/Secret、RSA/HMAC 密钥和教学账号只适用于本地 Demo。界面和协议刻意模拟真实支付，但不会连接支付机构或产生真实资金交易；应用级幂等、签名、Kafka 和多实例实验也不等同于金融机构级容灾与合规。真实部署必须使用密钥管理与定期轮换、HTTPS、Secure Cookie、网络隔离、真实渠道证书、PCI/等保等合规控制，并移除对外暴露的调试端口。Flyway 迁移已启用；已经发布的迁移脚本不应直接修改，而应新增后续版本。

## 常见故障

- 端口被占用：检查 `14200`、`14201`、`14202`、`18081`、`18082`、`18083`、`18086`、`13307`、`13308`，或修改 Compose 端口映射。
- 服务一直 `starting`：运行 `docker compose ps` 和 `docker compose logs <service>`，优先查看 MySQL、Kafka 和后端依赖健康状态。
- 修改源码未生效：执行 `docker compose up -d --build --wait` 重新构建镜像。
- 演示数据需要重置：确认数据可删除后执行 `docker compose down --volumes`，再启动并显式运行 `catalog-demo-init`。
- Redis 暂停时登录 Session 会受影响，但公开商品会降级查询 MySQL；恢复后等待健康检查通过。
- Windows 脚本执行策略阻止运行时，可使用 `pwsh -ExecutionPolicy Bypass -File ./scripts/verify-all.ps1`。

详见 [前端说明](web/commerce-portal/angular/README.md) 和 [后端说明](services/commerce-service/README.md)。

高级三渠道模拟支付、Kafka、幂等、安全和高可用实验的设计与分章计划见
[高级模拟支付架构与实现记录](docs/reference/ADVANCED-PAYMENT.md)。

05A-5～05A-8 的 Kafka、三渠道收银台、HA、监控和故障实验见
[可靠支付交付指南](docs/learning/CHAPTER-05A-5-TO-8.md)。

# M2 代码质量与模块化单体治理计划

## 1. 定位与边界

本计划是 M1 之后的工程质量待办，不新增商城业务能力，也不启动微服务拆分或 PostgreSQL 迁移。
微服务和数据库演进继续分别维护在
[微服务演进计划](architecture/MICROSERVICE-EVOLUTION-PLAN.md) 与
[数据库演进计划](architecture/DATABASE-EVOLUTION-PLAN.md)，不得在本计划中标记为已实现。

事实优先级仍为当前源码、迁移、脚本和实际验证结果高于文档。数据访问重构不得改变锁顺序、
事务边界、幂等键、条件更新或 Outbox/任务租约语义。

## 2. 2026-11-13 只读审计基线

| 模块 | Java 文件 | Mapper XML | 迁移文件 | 通配符导入 | 超过 120 字符的行 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `commerce-service` | 162 | 16 | 38 | 36 | 129 |
| `payment-simulator-service` | 65 | 4 | 5 | 11 | 32 |
| `logistics-simulator-service` | 15 | 1 | 4 | 1 | 4 |

审计结论：

- 12 个 MyBatis-Plus 持久化实体已有类和字段 JavaDoc，仍需持续检查注释与真实列语义一致。
- 未发现 Controller、Service、Job 或 Dispatcher 内联业务 SQL；物流复杂 SQL已集中到
  `LogisticsDataMapper.xml`，商城和支付复杂 SQL 也已放在 Mapper XML。
- `SchedulerConfiguration` 中的 `JdbcTemplate` 只用于 ShedLock 官方适配器配置，不属于业务 SQL。
- 未发现多个字段或方法压缩在同一行的现存 Java 声明。
- 基线时尚未配置自动格式检查；ArchUnit 模块依赖边界测试已在 M2.3 首批治理中建立。
- `ChannelRefund` 错把外部退款编号标为 `@TableId(AUTO)`，与数据库 `id BIGINT AUTO_INCREMENT`
  不一致；该项作为第一批功能性修复处理。

## 3. 分批实施顺序

### M2.1 支付退款映射与格式基线

- [x] 修正 `ChannelRefund.id` 的 MyBatis-Plus 自增主键映射并增加防回归测试。
- [x] 整理支付模块通配符导入、过长构造器和 Wrapper 链，不改变退款事务和通知补偿语义。
- [x] 使用真实 MySQL 重新执行模拟支付测试和退款专项。

首批验证证据：支付服务 9 项 Maven 测试通过；Windows 与容器化 Bash 真实退款专项均完成
签名支付、全额退款、账本核对、重复请求幂等、重复退款 `409`、伪造回调 `401` 和通知重投幂等。
验收过程中修正了 `verify-refunds` 向下游支付通知脚本遗漏支付平台 URL 的双平台参数透传。
正式 Dockerfile
首次重建受 Docker Hub 获取 `eclipse-temurin:25-jre-alpine` 元数据时连续 `EOF` 阻断；本轮使用
已缓存的同版本 Maven/JDK 25 Alpine 镜像构建测试运行镜像，不修改仓库 Dockerfile。

格式收口验收：支付模拟服务的通配符导入从审计基线 11 个降为 0，
超过 120 字符的 Java 行从 32 行降为 0。支付服务 9 项 Maven 测试再次通过，
Windows 与容器化 Bash 真实退款专项再次通过，相关容器重建后均为 `healthy`。
Windows `verify-quick -SkipBuild` 也已使用本次构建的支付服务镜像通过完整 L2 快速回归。

### M2.2 物流模拟服务类型与格式治理

- [x] 清理剩余通配符导入和过长 Java 行。
- [x] 将运单、轨迹和 Outbox 的 `Map<String, Object>` 查询结果替换为含字段语义的持久化投影；行锁、条件更新、
  批量领取和 Outbox SQL 继续保留在 Mapper XML。
- [x] 运行物流回调、乱序和 Outbox 恢复专项，证明当前投影和幂等语义不变。

验收证据：物流服务构建通过，通配符导入与超过 120 字符的 Java 行均归零；
Windows 回调可靠性专项通过 `RETRY -> PUBLISHED` 和丢失回调对账恢复，回调治理专项通过
`DEAD -> 人工重投 -> PUBLISHED`、RBAC、审计、幂等和指标验证。固定种子 `20261113`
的 10 秒乱序并发产生 322 个回调，最终状态为 `DELIVERED`、有界字段更新计数为 1、
不可变身份字段保持不变。容器化 Bash 基础物流链路也通过签名创建、重放 `409`、
伪造回调 `401`、异常恢复、`CREATED -> DELIVERED` 和商城投影验证。

### M2.3 商城服务分包治理

- [x] 分业务包清理通配符导入和过长行，不做全仓一次性机械重写；`identity`、`catalog`、`cart`、
  `customer`、`review`、`receipt`、`fulfillment`、`ordering`、`payment`、`refund`、`logistics`、`scheduling` 已完成。
- 简单单表 CRUD 继续使用 `BaseMapper` 和 Lambda Wrapper；复杂 SQL 保留在 Mapper XML。
- [x] 建立 ArchUnit 测试，阻止 API 访问基础设施、领域层反向依赖外层，并约束
  `@RestController` 与 Mapper 接口的包位置。

首批治理移除了 `UserController -> AppUserMapper` 的越层访问，查询改由
`UserQueryService` 使用 Lambda Wrapper 完成；`identity` 与 `shared` 包的通配符导入和
超过 120 字符的 Java 行均为 0。商城服务 24 项 Maven 测试通过，其中包含 4 条 ArchUnit
门禁；使用重建后的镜像执行 Windows L2 快速回归和容器化 Bash 权限回归均通过，现有数据卷未删除。
其余商城业务包的格式治理仍按包分批进行，不能据此标记为全服务完成。

第二批治理将 `catalog` 与 `cart` 的通配符导入和超过 120 字符的 Java 行均清零。
商品翻译单表查询由手写 `SELECT *` 改为 `BaseMapper + LambdaQueryWrapper`，复合业务键字段补齐语义注释；
购物车 upsert、联表投影和按用户归属条件更新仍保留在 Mapper XML。审计同时发现旧订单投影遗漏
`product_id`，修复后补充接口级回归测试，并将测试 Schema 与 V1 真实迁移对齐。商城服务 25 项测试、
Windows L2 快速回归、容器化 Bash i18n 与权限回归均通过。Bash i18n 脚本还修复了
`pipefail` 下 `curl | grep -q` 提前断管导致的页面可用性误判。最终镜像还在现有 MySQL 卷完成
旧订单创建与数量更新，响应保留正确的 `productId`。本批未删除或重建数据卷。

第三批治理将 `customer`、`review` 与 `receipt` 的通配符导入和超过 120 字符的 Java 行均清零。
收货地址单表查询改用 Lambda Wrapper，并通过 `@TableField("is_default")` 显式映射默认地址字段；
评价写入改用带完整字段注释的 `ProductReview` 实体和 `BaseMapper`。评价联表查询、聚合统计以及地址默认项
批量切换等复杂 SQL 继续保留在 Mapper XML。商城服务 25 项测试、Windows 快速回归和容器化 Bash
收货/评价专项均通过；快速回归还覆盖服务重启后的租约恢复和 Redis 故障降级。脚本契约审计确认
37 对 Windows/Bash 验证脚本参数有序对应，默认路径不会删除命名卷。本批未删除或重建数据卷。

第四批治理将 `fulfillment` 的 5 个通配符导入和 7 条超过 120 字符的 Java 行均清零。
商家订单归属联表、订单行锁以及确认、发货和物流信息更正的条件更新仍保留在 Mapper XML，
原有事务分段、远程物流调用顺序和状态条件未改变。商城服务正式 Dockerfile 构建及 25 项测试通过，
Windows/Bash 履约专项均通过支付回调、商家确认、发货、物流信息更正、时间线和客户越权 `403`；
Windows 快速回归继续通过取消竞态、退款、物流、收货/完成/评价、服务重启租约恢复和 Redis 降级。
37 对脚本契约审计再次通过，现有数据卷未删除或重建。商城服务剩余 19 个通配符导入和
104 条超过 120 字符的 Java 行，M2.3 仍需按包继续治理。

第五批治理将 `ordering` 的 4 个通配符导入、19 条超过 120 字符的 Java 行以及
`ShopOrderMapper.xml` 的 23 条超长 SQL 行均清零。订单、商品明细、事件和收货地址四个数据库投影
补齐类与字段业务语义注释；购物车行锁、订单/支付幂等锁、条件状态迁移、事件写入和支付通知写入
继续保留在 Mapper XML，原有锁顺序和事务边界未改变。商城服务正式 Dockerfile 构建及 25 项测试通过；
Windows 快速回归覆盖取消竞态、退款、履约、物流、收货/完成/评价、服务重启租约恢复和 Redis 降级，
Bash 订单取消、支付/取消竞态与商家履约专项也通过。37 对脚本契约审计再次通过，现有数据卷未删除
或重建。商城服务剩余 15 个通配符导入和 85 条超过 120 字符的 Java 行，M2.3 仍未完成。

第六批治理将 `payment` 的 9 个通配符导入和 12 条超过 120 字符的 Java 行均清零。
本批仅整理导入、参数与表达式换行，未改动支付 SQL、事务边界、锁顺序、幂等或签名语义。
商城服务正式 Dockerfile 构建及 25 项测试通过；Windows 快速回归覆盖取消竞态、退款、履约、
物流、收货/完成/评价、服务重启租约恢复和 Redis 降级；容器化 Bash 支付安全链路通过 HMAC/RSA、
签名负例、MFA、一次性提权令牌和审计链验证。37 对脚本契约审计再次通过，未删除或重建数据卷。
商城服务剩余 6 个通配符导入和 73 条超过 120 字符的 Java 行，下一批从 `refund` 继续。

第七批治理将 `refund` 的 4 个通配符导入、27 条超过 120 字符的 Java 行和
`RefundMapper.xml` 的 11 条超长 SQL 行均清零。`RefundView` 与 `RefundEventView` 补齐类和字段业务
语义注释；成功支付行锁、退款幂等查询、状态条件更新、金额上限保护和通知去重 SQL 继续保留在
Mapper XML，未改动事务、锁顺序、幂等、库存回补或通知语义。商城服务正式 Dockerfile 构建及 25 项测试通过；
Windows 与容器化 Bash 退款专项均通过签名支付、全额退款、双端账本一致、幂等重放、重复退款 `409`、
伪造回调 `401` 和通知重投；Windows 快速回归与 37 对脚本契约审计同时通过。本批未删除或重建数据卷。
商城服务当前剩余 2 个通配符导入和 47 条超过 120 字符的 Java 行，下一批从 `logistics` 继续。

第八批治理将 `logistics` 的 2 个通配符导入、38 条超过 120 字符的 Java 行和
`ShipmentMapper.xml` 的 8 条超长 SQL 行均清零。`ShipmentRecordView` 与 `ShipmentEventView` 补齐类和字段业务
语义注释；运单 upsert、回调行锁、事件幂等、状态投影和对账候选 SQL 继续保留在 Mapper XML，未改动
签名、事务、乱序保护、送达时间或自动收货任务语义。商城服务正式 Dockerfile 构建及 25 项测试通过；
Windows 物流主链路、回调丢失恢复与 `DEAD -> 管理员重放 -> PUBLISHED` 治理专项通过，容器化 Bash 主链路通过
签名创建、重放 `409`、伪造签名 `401`、异常恢复和完整轨迹投影验证。Windows 快速回归与 37 对脚本契约审计
同时通过，未删除或重建数据卷。商城服务通配符导入已全部清零，剩余 9 条 Java 超长行均位于 `scheduling`；
下一批完成 `scheduling` 后再判定 M2.3 整体门禁。

第九批治理将 `scheduling` 的 9 条超过 120 字符的 Java 行和
`ScheduledTaskMapper.xml` 的 1 条超长 SQL 行清零，并拆分了多计数器单行声明。过期租约查询仍保留在
Mapper XML，未改动任务领取、租约、重试、对账或 MQ 消费语义。商城服务正式 Dockerfile 构建及
25 项测试通过；Windows 快速回归覆盖数据库调度、服务重启与过期租约恢复，Windows 和容器化 Bash
MQ 幂等专项均通过 RabbitMQ/RocketMQ 重复与迟到消息单次执行及毒消息 DLQ 验证；37 对脚本契约
审计通过，未删除或重建数据卷。商城业务源码通配符导入和超过 120 字符的 Java 行均为 0，
M2.3 整体门禁完成；下一阶段为 M2.4 数据库注释与自动门禁。

### M2.4 数据库注释与自动门禁

- [x] 通过新增 Flyway 迁移补充关键表和列注释，不修改已经执行的历史迁移。
- [x] 为三个后端服务接入与 IntelliJ IDEA 规则兼容的只检查门禁，不自动改写源码。
- [x] 将后端格式、Mapper 契约、模块边界和相关测试加入快速/完整验证矩阵。

第一批新增商城 V24、支付模拟器 V4 和物流模拟器 V5，为 7/5/4 张核心业务表与
67/39/27 个核心标识、状态、金额和时间列补充业务语义注释。本批只修改数据字典元数据，
未改表结构、约束、默认值或业务数据。三服务正式镜像构建通过，商城 25 项和支付 9 项测试通过；
Windows 与容器化 Bash 隔离演练均通过空库 V1→V24 及旧库 V16→V24，Windows 独立临时卷还完成
V24/V4/V5 三库空卷到评价的完整业务链路。验收后仅删除明确命名的临时卷，现有数据卷保留并升级成功。
Windows 快速回归与 37 对 Windows/Bash 脚本契约审计也已通过。第一批完成后进入只检查的
自动格式与 Mapper 契约门禁建设。

第二批新增 Windows/Bash `audit-code-quality`，全量检查 244 个主源码 Java 文件、20 个 Mapper XML、
189 个映射语句和 3 个 Maven POM。门禁覆盖文件末尾换行、制表符/行尾空格、Java/Mapper XML
120 字符、通配符导入、单行多成员、MyBatis 内联 SQL、直接 Spring JDBC、Mapper namespace、
重复 statement id 和接口方法对应关系；只报告问题，不自动格式化文件。整理 3 个 Mapper XML 的
8 条历史超长 SQL 行后，Windows 与容器化 Bash 门禁均通过。三个服务正式镜像构建通过，商城
25 项（含 4 条 ArchUnit）和支付 9 项测试通过；Windows `verify-quick -SkipBuild` 完整通过且
始终执行新质量门禁；支付安全专项也通过 HMAC/RSA、签名负例、MFA、一次性提权、角色/商户授权
和审计链验证。现有数据卷未删除或重建。

M2.4 后端门禁完成。

### M2.5 前端格式门禁

#### M2.5.1 Angular 商城门户（已完成）

- [x] 分模块治理 Angular 商城门户的 TypeScript、HTML、CSS 和配置文件。
- [x] 使用项目本地 Prettier 3.10.1 提供 `format:check` 只读检查和显式 `format:write` 写入入口。
- [x] Angular 正式 Dockerfile 在生产构建前执行格式检查。

Angular 批次纳入 127 个源码/配置文件（包含新增的两个 Prettier 配置文件），已通过
`format:check`、29 项 ChromeHeadless 单元测试、本地生产构建和正式 Docker 镜像构建。重建且仅重建
Angular 容器后，`/healthz`、中英文与三角色权限专项通过，未删除或重建数据卷。
`verify-quick`/`verify-all` 默认构建会通过 Angular Dockerfile 执行该门禁；显式跳过构建时不会重新检查。
支付模拟器 Web 由下一独立批次治理。

#### M2.5.2 支付模拟器 Web（已完成）

- [x] 以 `simulators/payment-platform/web/` 当前源码、`package-lock.json`、Dockerfile 和现有测试为基线。
- [x] 接入模块本地 Prettier 及只读/写入分离的 npm 命令，不顺带重做 UI 或支付流程。
- [x] 格式化后通过 Vitest、TypeScript/Vite 生产构建和核心差异审查。
- [x] 将只读门禁接入正式 Dockerfile，验证镜像、`14201/healthz` 和支付 Web 运行态。
- [x] 同步状态文档、独立提交并推送，不删除或重建数据卷。

本批基线包含 24 个 Vue/TypeScript/CSS/JSON/HTML 文件，无制表符或超过 200 字符的压缩行，
共有 20 条超过 120 字符的行。固定 Prettier 3.10.1 后只对本模块执行机械格式化；依赖锁仅增加
Prettier 包，支付签名、收银台和管理视图没有业务重构。`format:check`、Vitest（当前无测试文件，
按既有 `--passWithNoTests` 契约通过）、TypeScript/Vite 生产构建和正式 Docker 镜像构建均通过；
镜像构建已强制格式检查，`14201/healthz`、Vue 入口和正式服务名运行态验证通过。未删除或重建数据卷。

M2.5 前端格式门禁完成；总验收清单见 [TODO.md 第 13.3 节](TODO.md#133-m2-工程质量总验收与基线冻结)。

### M2.6 工程质量总验收与冻结（已完成）

- [x] 三个 Java 服务的格式、内联 SQL/JDBC 边界、Mapper 契约和商城 ArchUnit 模块边界门禁通过。
- [x] Angular 与支付模拟器 Web 的项目本地 Prettier、生产构建和正式镜像门禁通过。
- [x] Windows 与容器化 Bash 的质量审计、37 对脚本契约、快速/完整验收通过。
- [x] Compose 默认/全 Profile、五个正式镜像、运行态服务名和主要健康端点通过复验。
- [x] 文档、最终提交、远端 `main` 与注释标签 `m2-quality-baseline-2026-11-14` 完成收口。

最终验收保持现有数据卷，连续运行所需库存通过商户 API 准备。支付 Web 使用 Docker DNS 动态解析
支付后端；本地单节点 Alertmanager 关闭集群监听并使用 tmpfs 通知日志，Watchdog 具有稳定分组标签，
监控栈全新重建后的 Windows/Bash webhook 投递均通过。容器化 Bash 是因为当前主机没有可用的原生
Bash/WSL，使用 Docker CLI 容器、host 网络和 Docker Desktop 可见路径执行同一原生脚本。
M2 结论不包含微服务拆分、PostgreSQL 迁移、多节点高可用、多日容量或生产通知验收。

M2 标签冻结后的 M3.1 首批治理扩展了当前门禁，但不改写历史标签结论：物流模拟器根包只允许保留
启动类，业务代码必须存在 `api/application/domain/infrastructure` 四层；三个服务全部 256 个 Java
主源码/测试源要求每个 `class` 声明后保留空行。当前双平台门禁覆盖 248 个主源码 Java、20 个 Mapper XML、
189 个映射语句和 3 个 POM，并已通过物流正式镜像打包、主链路和 Outbox 管理专项。

M3.1 第二批新增 2 项商城 Maven 架构基线测试：37 张 Flyway 表必须声明当前责任模块；40 组既有跨模块
依赖允许减少，但不允许未经评审新增方向、增加 117 个 import 基线或增加其中 21 个跨模块基础设施引用。
新增后商城 27 项测试和正式镜像构建通过，质量门禁的类声明空行范围同步扩大到 257 个 Java 源文件。
该基线记录债务而不豁免债务，完整拆分阻塞项见
[M3.1 微服务拆分前置条件审计](architecture/M3-PRE-SPLIT-AUDIT.md)。

## 4. 每批完成门禁

1. 修改前后 `git diff --check` 通过，格式化变更与业务语义变更可以清晰审查。
2. 相关 Maven 测试和 Docker 镜像构建通过。
3. 涉及数据访问时执行真实 MySQL 专项；涉及并发 SQL 时执行对应竞态或恢复脚本。
4. Windows/Bash 脚本契约保持一致，默认不删除现有数据卷。
5. 每批独立提交并推送，验证结果同步到本计划和 `PROJECT-STATUS.md`。

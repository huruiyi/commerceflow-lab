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
- 尚未配置自动格式检查；ArchUnit 模块依赖边界测试已在 M2.3 首批治理中建立。
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

- [ ] 分业务包清理通配符导入和过长行，不做全仓一次性机械重写；`identity` 首批已完成。
- 简单单表 CRUD 继续使用 `BaseMapper` 和 Lambda Wrapper；复杂 SQL 保留在 Mapper XML。
- [x] 建立 ArchUnit 测试，阻止 API 访问基础设施、领域层反向依赖外层，并约束
  `@RestController` 与 Mapper 接口的包位置。

首批治理移除了 `UserController -> AppUserMapper` 的越层访问，查询改由
`UserQueryService` 使用 Lambda Wrapper 完成；`identity` 与 `shared` 包的通配符导入和
超过 120 字符的 Java 行均为 0。商城服务 24 项 Maven 测试通过，其中包含 4 条 ArchUnit
门禁；使用重建后的镜像执行 Windows L2 快速回归和容器化 Bash 权限回归均通过，现有数据卷未删除。
其余商城业务包的格式治理仍按包分批进行，不能据此标记为全服务完成。

### M2.4 数据库注释与自动门禁

- 通过新增 Flyway 迁移补充关键表和列注释，不修改已经执行的历史迁移。
- 选择与 IntelliJ IDEA 格式结果兼容的自动检查方式，先以只检查模式接入，再逐模块修复。
- 将格式、Mapper 契约、模块边界和相关测试加入快速验证矩阵。

## 4. 每批完成门禁

1. 修改前后 `git diff --check` 通过，格式化变更与业务语义变更可以清晰审查。
2. 相关 Maven 测试和 Docker 镜像构建通过。
3. 涉及数据访问时执行真实 MySQL 专项；涉及并发 SQL 时执行对应竞态或恢复脚本。
4. Windows/Bash 脚本契约保持一致，默认不删除现有数据卷。
5. 每批独立提交并推送，验证结果同步到本计划和 `PROJECT-STATUS.md`。

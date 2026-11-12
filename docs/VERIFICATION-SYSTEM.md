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
    -> IMPLEMENTED.md / PROJECT-STATUS.md
    -> TODO.md 勾选和历史实施记录
```

只有脚本返回成功、关键不变量成立且清理/恢复步骤完成，才能记录为“已验收”。仅有代码、仅构建成功或仅容器启动都不等于业务验收通过。

## 3. 分层验证入口

| 级别 | 目的 | 主入口 | 何时执行 |
| --- | --- | --- | --- |
| L0 预检 | 确认分支、变更边界、Compose 和脚本契约 | `git status`、`docker compose config --quiet`、`audit-script-contracts` | 每次修改前后 |
| L1 构建/测试 | 编译、单元/集成测试、前端生产构建 | `docker compose build <service>` | Java/POM/前端代码改动 |
| L2 快速回归 | 数分钟内覆盖核心端到端不变量 | `verify-quick` | 普通功能提交、跨模块重构 |
| L3 完整验收 | 串联主要功能域与最终健康检查 | `verify-all` | 里程碑、合并前、基线冻结前 |
| L4 数据生命周期 | 验证空库、旧库升级、重启恢复和历史一致性 | `verify-flyway-lifecycle`、`verify-fresh-volume-lifecycle`、`verify-upgrade-restart-recovery` | Flyway、持久模型、Compose/中间件改动 |
| L5 可靠性专项 | 真实锁超时、进程崩溃、MQ 故障、重投与并发竞态 | 对应 `verify-*` 专项 | 事务、锁、幂等、任务或 MQ 改动 |
| L6 浸泡/压测 | 在可重放随机种子下验证长时间不变量 | 支付取消、物流乱序、收货/完成 stress 入口 | 并发语义或性能相关改动、里程碑冻结前 |

Windows 和 Bash 的主要脚本必须保持同名、同参数顺序和同验收语义。机器可读契约见 `scripts/script-contracts.tsv`。

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
2. 修改 Mapper XML 后运行对应 Spring 测试和真实 MySQL 专项，不只依赖 H2。
3. 修改页面后完成生产构建，并运行相关 API/权限/国际化脚本。
4. 在提交前运行 L0，普通功能改动再运行 L2。

### 4.3 提交/基线门禁

Windows：

```powershell
./scripts/windows/verify-quick.ps1
./scripts/windows/verify-all.ps1
```

Bash：

```bash
bash ./scripts/linux/verify-quick.sh
bash ./scripts/linux/verify-all.sh
```

`-SkipBuild` / `--skip-build` 只能在已确认镜像来自当前源码时使用。冻结基线或修改 Dockerfile/POM/前端依赖时必须使用默认构建模式。

## 5. 改动类型与必跑门禁

| 改动范围 | 最低门禁 | 附加门禁 |
| --- | --- | --- |
| 纯文档，不改命令/契约 | L0，链接和命令人工复核 | 无 |
| Java 业务代码、Mapper、POM | L1 + L2 | 对应功能专项；跨模块时加 L3 |
| Angular/Vue/Nginx | 前端生产镜像构建 + L2 | 角色/国际化/相关业务专项 |
| Flyway/表结构/状态约束 | L1 + L2 + L4 | 相关业务不变量与升级后历史指纹 |
| Compose、服务名、Profile、脚本 | L0 + 五镜像构建 + L2 | 涉及卷/重启时加 L4；涉及 MQ 时加对应 L5 |
| 事务、锁定顺序、幂等、Outbox | L1 + L2 | 锁超时、崩溃恢复、竞态和历史一致性专项 |
| RabbitMQ/RocketMQ/定时任务 | L1 + L2 | 幂等、未 ACK 重投、Broker 停机恢复和 Profile 对应专项 |
| 并发状态机 | L1 + L2 | 固定种子短竞态 + L6 浸泡，并记录数据库矛盾计数 |
| 发布、标签或里程碑冻结 | L0–L4 | 与本里程碑相关的 L5/L6 |

## 6. 数据库与环境生命周期

### 6.1 Flyway 隔离演练

```powershell
./scripts/windows/verify-flyway-lifecycle.ps1
```

它使用随机容器/网络和 tmpfs，验证空库及 V16→V23 升级，不连接默认开发卷。

### 6.2 全新卷全链路

```powershell
./scripts/windows/verify-fresh-volume-lifecycle.ps1 -TemporaryProjectName commerceflow-fresh-<run-id>
```

默认保留该临时项目卷用于失败排查。只有显式传入 `-RemoveTemporaryVolumes` / `--remove-temporary-volumes` 才会删除本次随机临时项目卷。

### 6.3 旧卷升级和重启恢复

```powershell
./scripts/windows/verify-upgrade-restart-recovery.ps1
```

该入口会重启三库、Redis、Kafka、RabbitMQ、RocketMQ 和业务服务，并写入可识别的恢复夹具任务；它不删除卷。运行前确认当前学习环境允许短暂中断。

## 7. 可靠性和并发专项

| 风险 | 必跑入口 | 关键证据 |
| --- | --- | --- |
| 支付/取消行锁 | `verify-payment-cancellation-lock-timeout` | `503/DATABASE_CONCURRENCY_RETRY`、超时事务无中间态、重试成功 |
| 支付/取消进程退出 | `verify-payment-cancellation-crash-recovery` | 四个精确事务窗口、回滚/重放、单一业务事实 |
| 支付/取消竞态 | `verify-payment-cancellation-race` / `stress` | 种子、轮数、合法终态分布、矛盾记录为 0 |
| 物流乱序 | `verify-logistics-ordering-stress` | 时长、并发、唯一回调数、状态不倒退、不变字段未覆盖 |
| MQ 重复/毒消息 | `verify-mq-scheduling-idempotency` | ignored 指标、DLQ、领取/成功/副作用各一次 |
| MQ 未 ACK 退出 | `verify-mq-scheduling-redelivery` | Broker 重投，不额外发布，业务副作用一次 |
| 自动收货/完成 MQ 故障 | `verify-auto-lifecycle-mq-failures` | 发布失败可观测、恢复后沿原快照执行、终态一次 |
| 收货/完成长竞态 | `verify-receipt-lifecycle-stress` | 手动/自动两种赢家、收货转换/任务/完成事件各一次 |

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
- [ ] 验收后默认服务健康，故障开关、Profile 和临时资源已恢复/清理。
- [ ] `PROJECT-STATUS.md`、`IMPLEMENTED.md`、`TODO.md` 只写已有实测证据的结论。
- [ ] 提交、推送后再创建注释标签，不移动已发布基线标签。

M1 冻结证据保留在 `m1-baseline-2026-11-13`。后续基线应使用新标签，不改写 M1 历史。

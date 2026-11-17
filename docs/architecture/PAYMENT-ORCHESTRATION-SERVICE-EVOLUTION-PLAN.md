# payment-orchestration-service 准入与单一所有权演进计划

## 1. 首批结论

M3.6 第一批只完成边界审计和自动门禁，不创建服务或数据库，不迁移数据、不切流。候选服务统一拥有商城侧
支付编排与退款事实，外部 `payment-simulator-service` 仍只是渠道模拟平台，不属于本服务的数据所有权。

当前数据不变量通过，第二批已把 payment/refund 内部类型的外部消费者归零并冻结
[支付第一版远程契约](PAYMENT-V1-CONTRACT.md)。第三批已删除 refund→identity 外键，审计式关闭唯一已确认终态的
OPEN 对账，订单事件迁入 ordering 自有 Outbox/Inbox，并建立 Process Manager、命令 Outbox、结果 Inbox 与
人工恢复门禁，CREATE 已准入。不得把 CREATE 准入描述为独立服务已创建或已经 CUTOVER；也不得用共享 commerce
Schema、远程行锁、LOCAL/COMPARE 或全局事务协调器绕过后续迁移门禁。

## 2. 唯一所有权候选

| 表 | 唯一事实 |
| --- | --- |
| `payment_records` | 支付尝试、渠道结果、金额、退款累计及乐观版本 |
| `payment_merchant_accounts` | 商城到渠道模拟平台的商户配置 |
| `payment_notifications` | 支付回调 Inbox、摘要和处理结果 |
| `payment_events` | 支付状态转换审计 |
| `payment_idempotency_locks` | 支付请求幂等占位 |
| `payment_reconciliation_issue` | 支付未知结果与人工对账状态 |
| `payment_outbox` | 支付/退款事务事件投递 |
| `kafka_consumed_event` | 当前支付相关消费者 Inbox；后续须按消费者所有权收口 |
| `refund_records` | 全额退款流程、库存投影和渠道结果 |
| `refund_events` | 退款状态转换审计 |
| `refund_notifications` | 退款回调 Inbox、摘要和处理结果 |

order、inventory、identity 与 fulfillment 只保留稳定 ID、版本化命令/结果和必要投影，不跨库 join，也不建立
跨边界外键。退款属于支付编排的同一资金边界，不单独创建 refund-service。

## 3. CREATE 阻塞处置

1. **已完成**：删除 `refund_records.customer_id` 对 identity 的数据库外键，保留稳定 customerId。
2. **已完成**：ordering/fulfillment 不再直接注入支付 application/infrastructure、支付领域对象或退款 Mapper；
   创建、查询、关闭支付与回调结果 V1 契约及兼容样例已冻结。
3. **已完成**：fulfillment 不再直接读取 `RefundMapper`，改用稳定支付/退款投影契约。
4. **已完成**：原 1 条 OPEN `PAYMENT_TIMEOUT` 对应支付已为 `CLOSED`，记录解析码、执行者和终态详情后关闭。
5. **已完成**：179 条订单事件和消费记录迁入 ordering 自有表，payment Outbox/Inbox 中订单主题归零。
6. **已完成**：支付/退款 Process Manager、命令 Outbox、结果 Inbox、未知结果和人工恢复门禁已建立。

## 4. 分布式事务规则

- payment-orchestration 只在自己的 MySQL 中提交支付、退款、Inbox/Outbox 与对账事实。
- order 状态只通过远程 Order V1 条件转换推进；支付成功事实优先，迟到成功不得被取消结果覆盖。
- inventory 退款命令复用稳定命令 ID、结果 Inbox、未知结果和人工恢复语义。
- 渠道请求与回调使用现有 HMAC/RSA、幂等键、摘要、防重放和主动对账，不把 HTTP 超时解释为失败。
- 不引入 Seata、DTM、XA 或 TCC；不长期维护本地/远程双实现。

## 5. 后续批次顺序

1. **已完成**：11 张候选所有权表、外键、外部消费者、数据不变量与恢复基线审计。
2. **已完成**：收口 ordering/fulfillment 对 payment/refund 内部实现的依赖，冻结 Payment V1 契约。
3. **已完成**：删除跨 identity 外键，处置 OPEN 对账记录，收口订单事件 Outbox/Inbox 临时适配器，建立支付/退款
   Process Manager、命令 Outbox、结果 Inbox 与人工恢复门禁，取得 CREATE 准入。
4. **已完成**：创建分层独立服务和 MySQL `payment-db`，仅无业务流量影子迁移与核对。
5. **已完成**：远程 Payment V1、回调、支付/取消竞态、退款、结果丢失、服务/数据库停机和整体回退演练；
   普通业务流量仍未切换，CUTOVER 必须单独评审。
6. **已完成，评审不通过**：一次性 CUTOVER 评审确认订单/库存副作用、支付 Outbox 投递、退款发起、回调归属、
   commerce 本地运行路径和源表权限尚未闭环，普通流量继续留在 commerce。
7. **已完成**：只在关闭流量门下补齐独立服务的远程 Order V1、Inventory 命令、可靠 Outbox/Inbox、退款发起与恢复闭环；
   不增加 LOCAL/REMOTE/COMPARE，也不提前切换回调。
8. 阻塞归零后重新评审；通过后在同一个受控窗口完成停写、最终追平核对、Payment V1/渠道回调切换、本地路径
   删除和源表权限撤销，不保留长期双实现或数据库回退。
9. payment-orchestration 切换验收及稳定窗完成后，才进入 fulfillment-service。

## 6. 首批证据

2026-11-17 持久卷基线：299 笔支付，总额 388401.00、累计退款 41568.00；3 个商户配置、209 条支付通知、
598 条支付事件、299 个幂等锁、437 条已发布 Outbox、601 条 Kafka Inbox；32 笔退款，总额 41568.00、
64 条退款事件、64 条退款通知。重复键、非法金额/状态、成功支付渠道证据、退款/支付投影、缺失退款事件、
未发布 Outbox 和待恢复退款均为 0；OPEN 支付对账为 1。

```powershell
.\scripts\windows\audit-payment-orchestration-admission.ps1
```

```bash
./scripts/linux/audit-payment-orchestration-admission.sh
```

## 7. 第二批证据

ordering/fulfillment 对 payment/refund 的 application、domain、infrastructure 和 Mapper 外部导入已归零，只有
`payment.application.contract.v1` 稳定契约可跨边界使用。创建/关闭命令、关闭结果和支付成功结果 4 个 JSON
样例完成反序列化、字段校验和 SHA-256 指纹兼容测试。回归发现并修复迟到库存 RESERVE 结果把 COMMITTED
状态倒退为 RESERVED 的竞态，恢复重试语义保持通过。

最终审计持久卷为 324 笔支付、总额 420876.00、累计退款 46764.00；36 笔退款总额 46764.00，477 条 Outbox
全部发布、656 条 Kafka Inbox。所有金额、状态、幂等、渠道证据、退款投影、事件和待恢复不变量为 0；仍有
1 条跨 identity 外键和 1 条 OPEN 对账记录。48 项测试、431/28/204/7 质量门禁、451 个 Java 源文件类声明
空行、Windows/Linux 审计、50 对脚本契约及完整快速回归通过。

## 8. 第三批证据

真实 MySQL 持久卷已升级到 Flyway V37：14 张 payment/refund 所有权表，内部外键 7，双向跨边界外键均为 0；
唯一对账记录保留且 OPEN 为 0。payment Outbox 与 Kafka Inbox 中订单事件均为 0，ordering 自有 Outbox/Inbox
各保留 179 条，未发布为 0。支付进程、命令和结果协议表当前为空，恢复状态不一致为 0。

新增 6 项恢复协议测试固定命令/结果同 ID 重放、异 payload 冲突、超时转人工核对和带操作人/原因的人工重试；
commerce-service 共 55 项测试通过。CREATE 已准入，CUTOVER 仍冻结。下一批只创建独立服务与 `payment-db`
进行无业务流量影子快照、增量追平、水位核对和孤儿隔离。

## 9. 第四批影子运行证据

已创建按 `api/application/domain/infrastructure` 分层的 `payment-orchestration-service` 和独立 MySQL
`payment-db`。commerce 的支付/退款业务流量仍由唯一 `LocalPaymentOrchestrationAdapter` 承担；独立服务只开放
影子管理端点，`PAYMENT_ACCEPTING_TRAFFIC=false`，没有远程业务入口、LOCAL/COMPARE 或数据库回退。

Flyway V38 使用单例提交顺序水位和 42 个触发器捕获 14 张 payment/refund 所有权表变更。独立服务在
REPEATABLE READ 一致性视图中完成可重复快照，按水位增量追平，并对固定列序列执行逐字段 SHA-256 核对；
目标端外键缺失会进入 `payment_shadow_orphan_quarantine`，父记录恢复后使用原源记录重放并留下解析时间。

Windows 与 Ubuntu 26.04 WSL 对等专项均通过。最终快速回归并追平后 generation 3、水位 83；支付 353、
商户 3、通知 248、支付事件 706、幂等锁 353、对账 2、Outbox 326、Kafka Inbox 326、退款 40、退款事件/通知各 80，三张恢复
协议表为空，14 张表计数和指纹全部 MATCH。两次孤儿演练均为 RESOLVED，OPEN 为 0。V38 真实 MySQL、
commerce-service 55 项与 payment-orchestration-service 1 项 Java 25 测试、442/30/220/8 质量门禁、
464 个 Java 源文件空行、51 对脚本契约、完整快速回归和
关闭流量门均已验证；本批未 CUTOVER、未拆 fulfillment、未启动 PostgreSQL。

## 10. 第五批远程协议与回退证据

独立服务新增 Flyway V2 持久命令 Inbox 和内部 requestId 防重放表，并实现 Payment V1 创建、查询、关闭、结果、
恢复入口及支付/退款渠道回调。支付创建和关闭使用原渠道 HMAC/Nonce/请求 ID 签名真实调用
`payment-simulator-service`，不再生成虚构收银台地址。内部调用使用 HMAC-SHA256、正文 SHA-256、5 分钟时间窗和持久 requestId；业务
命令/结果使用稳定 ID 与 payload 指纹去重，同 ID 异正文返回冲突。渠道回调继续验证原 HMAC 签名，支付成功事实
可覆盖先到的关闭结果，已成功支付不会被迟到关闭覆盖。

Windows 与 Ubuntu 26.04 WSL 对等专项均通过：相同创建命令重放、冲突正文、伪造签名、HTTP 重放、支付成功先到、
关闭先到后迟到成功、全额退款及重复通知、结果丢失转 `RECONCILIATION_REQUIRED`、带操作人/原因的人工重试和
最终结果去重。payment-orchestration-service 与 payment-db 分别停机后均恢复原事实，Trace 可在 Tempo 查询。
专项只临时开启独立服务流量门并使用合成订单，结束时删除合成事实、恢复关闭流量和影子追平；commerce 没有新增
远程生产适配器或 LOCAL/REMOTE/COMPARE 选择器。

预切换演练使用临时数据库账号验证源支付表权限冻结会被拒绝，再恢复源端访问并在独立服务停机时确认源事实不变。
最终快速回归后 generation 13、水位 160；支付 360、商户 3、通知 253、支付事件 720、幂等锁 360、对账 2、
Outbox/Kafka Inbox 各 333、退款 41、退款事件/通知各 82，三张恢复协议表为空，14 张表计数和指纹全部 MATCH，
OPEN 孤儿和待恢复流程均为 0。payment-db Flyway V2 无失败；payment 服务 2 项 Java 25 测试、461/30/220/8
质量门禁、484 个 Java 源文件空行、53 对脚本契约和完整快速回归通过；PostgreSQL 容器为 0。

本批没有 CUTOVER。下一步只能先评审一次性切换方案；评审通过前 commerce 的唯一
`LocalPaymentOrchestrationAdapter`、源表权限和普通业务流量路径保持不变，也不开始 fulfillment-service。

## 11. 第六批 CUTOVER 评审结论

2026-11-18 的一次性切换评审结论为 **不通过**。安全门禁仍成立：独立服务默认
`PAYMENT_ACCEPTING_TRAFFIC=false`，Compose 没有 PostgreSQL。第六批评审识别出以下阻塞；第七批只关闭其中
第 3 至 5 项，当前仍不得承接真实流量：

1. commerce 仍拥有唯一 `LocalPaymentOrchestrationAdapter`、本地 `RefundService/RefundMapper` 和支付/退款回调
   Controller；`RefundController` 已收口为 Payment V1 薄入口，切流后保留。
2. 支付模拟平台的支付、退款回调地址仍指向 commerce。
3. **第七批已关闭**：独立服务已有远程 Order V1 与 Inventory 命令客户端，跨边界副作用使用稳定命令 ID。
4. **第七批已关闭**：`payment_outbox` 已有带租约、有限退避和失败证据的独立投递器。
5. **第七批已关闭**：独立服务已有受双门禁保护的退款创建入口和持久退款命令 Outbox。
6. `commerce_runtime` 权限冻结脚本尚未排除 14 张支付/退款源表。

Windows 与 WSL/Linux 对等审计入口为：

```powershell
.\scripts\windows\audit-payment-cutover-readiness.ps1
```

```bash
./scripts/linux/audit-payment-cutover-readiness.sh
```

审计成功表示“阻塞识别与安全门禁没有漂移”，最终输出必须是 `REJECTED`，不表示已经具备切换资格。第七批只在
关闭流量门下补齐第 3 至 5 项；剩余阻塞归零后必须替换这份拒绝基线并重新评审，不能通过删除审计脚本绕过门禁。

## 12. 第七批跨边界副作用闭环证据

payment-orchestration-service 新增远程 Order V1 条件状态/库存状态/事件适配器和 Inventory COMMIT/REFUND
适配器。支付成功、取消竞态、迟到成功、退款发起与退款结果均先写本库事实和 Outbox，再由独立投递器以稳定命令
ID 执行；失败记录 `last_error` 并按退避进入 RETRY。Flyway V3 已在真实 `payment-db` 执行。退款创建入口继续
复用 HMAC、正文摘要、requestId 防重放和业务 payload 指纹，渠道退款通过持久命令 Outbox 投递。

正式副作用必须同时满足流量门打开、副作用门打开、影子复制关闭；默认值保持
`PAYMENT_ACCEPTING_TRAFFIC=false`、`PAYMENT_EFFECTS_ENABLED=false`、`PAYMENT_SHADOW_ENABLED=true`。
Windows 与 Ubuntu 26.04 WSL 均用单条精确合成 Outbox 验证缺失订单：远程 Order V1 鉴权成功后进入 RETRY，
`last_error` 留证，订单和库存均无业务写；脚本只删除本次事件并恢复三门默认值。

第七批没有改动 commerce 的唯一正式支付/退款路径、支付平台回调地址或源表权限，因此第 1、2、6 项仍是
CUTOVER 阻塞，审计结论继续为 `REJECTED`。未增加 LOCAL/REMOTE/COMPARE，未拆 fulfillment，未启动 PostgreSQL。
payment 服务 6 项 Java 25 测试、468/30/220/8 质量门禁、493 个 Java 源文件空行、54 对脚本契约和完整快速
回归通过；真实 payment-db Flyway 最新版本为 V3 且成功，合成副作用残留为 0。

## 13. 第八批退款入口收口与再次评审

commerce 的客户退款 POST 已改为组装 `RefundCreateCommandV1` 并调用唯一 `PaymentOrchestrationV1`。当前
`LocalPaymentOrchestrationAdapter` 仍是唯一 Bean 和唯一正式路径，内部继续复用原 `RefundService`，所以这不是
远程切流，也没有 LOCAL/REMOTE/COMPARE。commerce 56 项 Java 25 测试及真实全额退款、重复通知、幂等冲突和
签名拒绝专项通过。

最终完整快速回归还暴露了支付成功回调与库存预留结果并发到达的窗口：旧实现先尝试
`RESERVED -> WAITING_COMMIT`，再尝试 `WAITING_RESERVATION -> WAITING_COMMIT`，状态可能在两次远程条件更新之间变化。
Order V1 因此增加原子 `START_INVENTORY_COMMIT`，由单条条件更新同时接受两个前置状态；回调重放也能对
`PAID + RESERVED/WAITING_RESERVATION` 的跨服务部分结果补发幂等 COMMIT。两笔回归遗留样本恢复到
`PAID/COMMITTED` 后，最终完整快速回归通过；order 2 项、commerce 56 项、payment 6 项 Java 25 测试通过，
469/30/220/8 质量门禁、495 个 Java 源文件空行和 54 对脚本契约通过。

再次评审同时更正了阻塞定义：`RefundController` 应在切流后作为 Session/RBAC 薄入口保留；真正需要在原子窗口
删除的是本地 `RefundService/RefundMapper`、本地支付适配器、两类旧回调与资金对账运行路径。切换步骤已冻结在
[一次性切换运行手册](PAYMENT-ORCHESTRATION-CUTOVER-RUNBOOK.md)。由于唯一远程适配器尚未替换、本地实现和旧回调
仍在、源表权限尚未撤销，当前结论继续为 `REJECTED`，本批没有 CUTOVER。

# order-service 准入与单一所有权演进计划

## 1. 当前结论

M3.5 第六批已完成一次性 CUTOVER：分层的独立 `order-service` 和 MySQL `order-db` 是 7 张订单表的唯一
运行时所有者；commerce 只通过远程 Order Store V1 读写，不再包含订单 Mapper 或源库回退路径。

CUTOVER 前已完成可重复快照、增量追平、跨进程 Trace、重复/乱序/超时/结果丢失、服务与数据库停机恢复、
人工恢复和整体回退演练；停写后的 generation 21 / 水位 236 最终审计为 7 表 MATCH。当前仍使用 MySQL 8.4；
后续服务须逐一完成单一所有权切换并验收，之后才可另立 PostgreSQL 专项。

## 2. 唯一所有权

| 所有权表 | 唯一事实 |
| --- | --- |
| `shop_orders` | 订单身份、客户稳定 ID、总额、状态和下游结果投影 |
| `order_items` | 下单时商品、商家、单价和数量快照 |
| `order_shipping_addresses` | 下单时收货信息快照 |
| `order_events` | 状态转换、触发来源和因果审计 |
| `order_process_managers` | 跨边界流程、等待结果、恢复和版本状态 |
| `order_command_outbox` | 远端命令投递、截止时间、关联与追踪上下文 |
| `order_result_inbox` | 跨进程结果去重、冲突、应用或忽略证据 |

其他边界仍拥有各自权威事实。order 只保存稳定 ID、下单快照和必要结果投影，不跨库 join，也不建立跨边界外键。

## 3. CREATE 准入已完成

- fulfillment、logistics、payment、receipt、refund 通过五类意图型 application port 访问订单；外部
  `ShopOrderMapper` 消费者为 0，并有 ArchUnit 与源码审计禁止回流。
- `ShopOrderMapper` 只访问四张订单核心表；cart/address/payment SQL 已迁回各自边界 Mapper/端口。
- Flyway V35 删除 4 条 order 出站和 5 条外部入站跨边界外键；稳定 ID、Inbox、对账和条件转换继续承担完整性校验。
- `OrderCommandV1` / `OrderResultV1` 冻结稳定 ID、前置状态、失败码、correlation/causation ID 和 W3C Trace。
- 持久化 Process Manager、命令 Outbox 和结果 Inbox 已实现同事务建流程、payload 指纹去重、未知结果截止时间、
  冲突拒绝、待补偿/待对账和带操作者/原因/时间的人工恢复。
- 项目不引入 Seata、DTM、XA、TCC 或全局事务协调器。

`PurchaseOrderService` 仍在 commerce 内编排购物车、支付等边界，但订单持久化只经远程 `OrderStorePort`；
commerce 生产代码不保留本地订单适配器、订单 Mapper 或源表 SQL。

## 4. 分布式事务与恢复规则

1. 每个服务只在自己的数据库内使用本地事务。
2. 业务事实与 Transactional Outbox 在同一本地事务提交。
3. 消费方以 Inbox、稳定 `commandId/resultId`、payload 指纹和条件状态更新保证幂等。
4. order 持久化 Saga/Process Manager，不使用内存等待串联跨进程步骤。
5. 超时只代表“结果未知”，进入等待或待对账；禁止立即推断失败或静默回退本地实现。
6. 补偿是新的可审计业务动作，不回滚已被外部系统确认的支付、退款或库存事实。

| 流程 | order 本地提交 | 远端结果与恢复 |
| --- | --- | --- |
| 下单 | 订单、流程与库存预占命令 Outbox | 原 commandId 返回结果；未知结果等待对账 |
| 支付 | 支付编排引用和流程状态 | 回调与主动查询收口；成功资金事实优先 |
| 取消 | `CANCELLING` 与补偿意图 | 关闭支付、释放库存确认后进入 `CANCELLED` |
| 退款 | 退款流程引用和等待状态 | 保留原支付事实；失败恢复或进入人工对账 |
| 履约 | 条件推进允许的订单状态 | 重复/乱序结果不倒退，缺失结果主动对账 |

## 5. 后续批次顺序

1. **已完成**：创建分层 `order-service` 与独立 MySQL `order-db`，仅影子运行，不接业务流量。
2. **已完成**：实现一致性可重复全量快照、提交顺序增量追平、水位、逐字段核对审计和孤儿隔离/重放。
3. **已完成**：命令 Inbox/Outbox、结果 Inbox、跨进程 Trace、重复、冲突、乱序、超时、结果丢失、人工恢复
   和服务停机恢复已通过；不维护 LOCAL/COMPARE 双实现。
4. **已完成**：停写后执行 generation 21 最终快照、增量追平及 7 表零差异审计。
5. **已完成**：一次性切换订单读写；故障返回稳定 503，不允许数据库或本地代码静默回退。
6. **已完成**：删除 commerce 的 order 运行路径，并撤销 `commerce_runtime` 对订单源表的权限。
7. **待另批评审**：进入 `payment-orchestration-service`；不得与 `fulfillment-service` 并行拆分。

## 6. CREATE 验收证据与复跑

2026-11-17 最终快速回归后的默认 MySQL 持久卷为 Flyway V35；7 张 order 所有权表、出站/入站跨边界外键、
外部 `ShopOrderMapper` 消费者和 Mapper 外部表职责均满足目标值。329 笔订单、329 条商品项、329 份地址快照、
1565 条事件通过金额、状态、关联和孤儿审计；需要恢复的订单/流程均为 0：

```text
orders|329|427371.00|867786460
items|329|329|427371.00
addresses|329
events|1565
processes|0
command-outbox|0
result-inbox|0
```

Java 25 正式镜像共 45 项测试通过；质量门禁为 394 个主源码 Java、31 个 Mapper XML、243 条映射、6 个
Maven POM，并检查全部 410 个 Java 源文件的 class 字段空行。Windows 快速回归、Windows/Git Bash 准入
审计、空库 V1→V35 和遗留 V16→V35 隔离 MySQL 生命周期均通过。

```powershell
.\scripts\windows\audit-order-service-admission.ps1
```

```bash
./scripts/linux/audit-order-service-admission.sh
```

脚本只启动并读取现有 `commerce-db`，不清空数据卷。CREATE 已授权；CUTOVER、源实现删除、后续服务拆分和
PostgreSQL 仍受各自门禁约束。

## 7. 第四批影子运行证据

- commerce Flyway V36 使用单例时钟和 21 个触发器记录 7 张所有权表的提交顺序变更；并发事务不会因
  AUTO_INCREMENT 先分配后提交而跳过低水位。
- order-service 在 REPEATABLE READ 事务中先读取源水位再取快照，目标事务原子替换 7 张表；重复快照从
  generation 1 推进到 8。增量使用有上界批次追平，服务停机和 order-db 停机期间产生的水位均在恢复后追平。
- 快速回归后实际持久卷核对为订单/商品项/地址各 337、事件 1609、金额 437763.00，流程/命令 Outbox/
  结果 Inbox 均为 0；7 张表逐字段 SHA-256 全部 `MATCH`。人为删除影子父订单后，商品项变更进入隔离并在
  父记录恢复后重放为 `RESOLVED`，OPEN 0。
- `/internal/v1/orders/commands` 与 `/results` 复用冻结的 Order V1 校验和 payload 指纹；默认流量门返回 503，
  commerce 没有 order-service 依赖或 LOCAL/COMPARE 选择器，因而当前不是 CUTOVER。

```powershell
.\scripts\windows\verify-order-shadow-runtime.ps1
```

```bash
./scripts/linux/verify-order-shadow-runtime.sh
```

## 8. 第五批远程协议与预切换演练证据

- `/commands`、`/results` 和人工恢复端点要求 commerce HMAC-SHA256 签名、正文 SHA-256、5 分钟时间窗和持久
  requestId 防重放；同 messageId/resultId 同指纹返回幂等结果，异指纹返回 409。
- 取消命令以条件状态更新创建 Process Manager 和 payment/inventory 两条 Outbox；结果 Inbox 可任意顺序接收
  两个成功结果且不允许状态倒退。缺失结果到期进入 `WAITING_RECONCILIATION`，人工 RETRY 保存操作者、原因、
  时间并重新安排 Outbox，不把超时推断为业务失败。
- Windows 合成订单演练验证真实 Tempo trace、HTTP 重放、命令重复/冲突/乱序、结果重复、结果丢失、超时和
  人工恢复；结束清理订单、事件和协议记录，并把 `ORDER_ACCEPTING_TRAFFIC` 恢复为 `false`。
- 预切换演练以临时数据库账号证明源端 order 权限可冻结且整体回退可恢复源端唯一所有者；order-service 停机
  后源数据仍完整，重启后 7 表再次 MATCH。最终快速回归后 generation 20、水位 236，345/345/345/1653、
  金额 448155.00 与三张空协议表全部 MATCH。实际 commerce 账号未冻结，因此这不是 CUTOVER。

```powershell
.\scripts\windows\verify-order-remote-protocol.ps1
.\scripts\windows\verify-order-precutover-rehearsal.ps1
```

```bash
./scripts/linux/verify-order-remote-protocol.sh
./scripts/linux/verify-order-precutover-rehearsal.sh
```

## 9. 第六批 CUTOVER 证据

- 停止 commerce 新写后执行 generation 21 / 水位 236 的最终快照与追平；源端 345 笔订单、345 条商品项、
  345 份地址和 1653 条事件在 7 张表审计中全部 `MATCH`，OPEN 孤儿为 0。
- commerce 删除订单 Mapper、Process Manager 本地实现及其 XML；生产 Mapper 对 7 张订单源表引用为 0，
  `RemoteOrderStoreAdapter` 是唯一订单存储适配器。
- Compose 默认 `ORDER_ACCEPTING_TRAFFIC=true`、`ORDER_SHADOW_ENABLED=false`；order-service 不再配置源库，
  commerce 依赖其健康状态。逐表授权脚本撤销 `commerce_runtime` 对库存和订单源表的全部权限。
- 切换验收创建的新订单只增加 order-db，源端保持 345/448155.00；完整快速回归后 order-db 为
  359/359/359/1710、金额 466341.00，协议表与 OPEN 孤儿均为 0。order-service 停机期间查询返回 503，
  恢复后健康，不存在 LOCAL/COMPARE 或源库回退。

```powershell
.\scripts\windows\verify-order-runtime-cutover.ps1
```

```bash
./scripts/linux/verify-order-runtime-cutover.sh
```

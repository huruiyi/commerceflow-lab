# order-service 准入与单一所有权演进计划

## 1. 当前结论

M3.5 第五批已经在分层的独立 `order-service` 和独立 MySQL `order-db` 上完成远程协议及预切换演练，当前仍
只作为无业务流量的影子进程：
不得切换生产读写、不得删除 commerce 本地路径、不得并行拆 `payment-orchestration-service` 或
`fulfillment-service`。

CREATE 不等于 CUTOVER。只有影子库完成可重复快照、增量追平、跨进程 Trace、重复/乱序/超时/结果丢失、
服务与数据库停机恢复、人工恢复和整体回退演练后，才可单独评审 CUTOVER。当前仍使用 MySQL 8.4；M3 清单中的
所有服务以及以后新增的待拆服务完成单一所有权切换并逐一验收后，才可另立 PostgreSQL 专项。

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

当前 `PurchaseOrderService` 仍在模块化单体内组合多个 application port；这些本地适配器不是远程调用已完成。
下一批必须复用稳定契约，不得把通用 Mapper、当前大事务或本地数据库回退复制到跨进程边界。

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
4. **已完成演练，未实际冻结**：临时账号验证源端权限冻结/拒绝、最终增量追平、零差异审计及整体回退。
   实际 commerce 运行账号只有在单独批准 CUTOVER 后才可冻结。
5. 一次性切换订单读写；失败只允许通过预演的整体回退恢复，不允许数据库或本地代码静默回退。
6. 稳定窗通过后删除 commerce 的 order 运行路径和源表权限，冻结单一所有权。
7. order 完成后再进入 `payment-orchestration-service`、`fulfillment-service` 及新增候选边界。

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

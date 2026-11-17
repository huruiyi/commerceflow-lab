# inventory-service 创建准入与单一所有权切换运行手册

## 1. 评审结论与两道门

本手册把“可以创建服务”和“可以切换生产写入”分成两道不可混淆的门：

- **CREATE 准入**：允许下一批创建分层 `inventory-service` 与独立 MySQL `inventory-db`，实现唯一远程适配器和迁移工具；
- **CUTOVER 准入**：只有独立进程完成数据追平、跨进程 Trace、并发/重复/乱序/超时/停机恢复和回退演练后，才允许整体切换调用方。

CREATE、生产命令切换和最终单一所有权门均已完成：独立服务/Schema 进入默认拓扑，commerce 生产 Gateway
只写 Outbox，catalog、四类库存命令与商家库存管理均指向 inventory-service。真实新写、结果丢失、服务/数据库
停机恢复、跨进程 Trace、商家调账恢复与停机 503 已通过；commerce 本地库存 Mapper/XML 和运行源表权限已
删除。PostgreSQL 继续冻结，直到 M3 清单中的所有服务独立验收完成。

## 2. 数据搬迁清单

### 2.1 必须迁入 inventory-db 的五张所有权表

| 表 | 迁移校验 |
| --- | --- |
| `product_inventory` | 行数、可售/预占/已售总和、逐行 CRC 聚合指纹 |
| `inventory_reservations` | 行数、数量总和、状态分布、逐行 CRC 聚合指纹 |
| `inventory_movements` | 行数、数量总和、eventId 唯一性、逐行 CRC 聚合指纹 |
| `inventory_command_inbox` | 行数、全部 SUCCEEDED、commandId/载荷指纹 |
| `inventory_catalog_event_inbox` | 行数、无 PROCESSING、eventId/版本/载荷指纹 |

这五张表之间可以保留内部外键，但不得引用 ordering、catalog、identity 或其他 Schema。稳定的 `orderId`、
`productId`、`merchantId` 只是跨边界标识，不建立跨库外键。

### 2.2 必须留在 commerce-db 的两张生产侧协议表

`inventory_command_delivery_outbox` 和 `inventory_command_result_inbox` 不搬入 inventory-db。前者属于命令生产者
事务，后者属于生产侧结果确认与订单/退款状态投影。拆分后 inventory-service 自己只保留命令 Inbox、库存事实，
并通过冻结的 Result V1 把结果返回生产侧。不得把七张表机械地全部搬走。

## 3. 创建阶段约束

下一批可以创建独立服务，但必须同时满足：

1. 包结构至少分为 `api`、`application/port`、`application/service`、`domain`、`infrastructure/persistence`、
   `infrastructure/transport`，类声明与首个字段之间保留空行；
2. 独立库继续使用 MySQL 8.4，只实现一套运行路径；禁止 `LOCAL/COMPARE`、双写、影子写和数据库回退；
3. commerce 只有一个 `InventoryCommandPort` 远程适配器，四类命令仍使用原 commandId；
4. catalog V1 只投递到独立服务，inventory 不写回 catalog；
5. 服务先以未接流量状态启动，建表和迁移验证不得改变现有 commerce 写入口。

## 4. 迁移、停写与增量追平顺序

1. 在现有 commerce-db 执行双平台 `audit-inventory-service-admission`，保存五张表基线；任何未决命令、待对账
   订单/退款或余额不一致都中止迁移。
2. 对五张所有权表做一致性快照并导入 inventory-db；保留主键、时间精度、唯一键和内部外键。
3. 迁移期间 commerce 仍是唯一写者。按主键/`updated_at` 与命令、事件 Inbox 增量补齐，重复导入必须幂等。
4. 进入短暂停写窗口：阻止结算、支付库存提交、取消释放、退款库存归还和商家调库存；读取可继续。
5. 等当前事务结束，要求 delivery 全部 ACKNOWLEDGED、两个 Inbox 无 PROCESSING、订单/退款无等待或待对账。
6. 最后一次增量追平，在源库和目标库分别复算五张表行数、数量总和和内容指纹，必须完全相等。
7. 原子部署唯一远程适配器并整体重启相关调用方；不得按租户、命令类型或请求比例保留本地分支。
8. 完成烟雾验证后解除停写。源表立即转只读保留，不删除，作为本次切换的数据证据。

## 5. 跨进程 Trace 门禁

四类命令和 catalog 事件必须保留合法 W3C `traceparent`，并以 `commandId/eventId`、`correlationId`、
`causationId` 关联生产 Outbox、HTTP/消息传输、inventory Inbox、库存事务、Result V1 和生产侧结果 Inbox。
CUTOVER 前至少对 RESERVE、COMMIT、RELEASE、REFUND 各执行一次真实链路，在 Tempo 中证明同一 trace 横跨
commerce-service 与 inventory-service；重试可以产生新 span，但不得更换业务幂等键。

## 6. 故障与回退规则

- 独立服务不可达或超时：调用方保留等待状态，Outbox 有限重试；达到上限进入
  RECONCILIATION_REQUIRED，不得改调本地 Handler。
- 返回结果丢失：用原 commandId 查询/重投，inventory Inbox 返回原结果，不重复改变余额。
- inventory-db 已接收新写入后：禁止把流量直接切回旧库。先停写、导出独立库新增事实并完成正向或反向对账，
  由人工决定继续修复还是执行受控反迁移。
- 只有在解除停写前、目标库尚未接受任何独立新写入时，才可以用原 Git 提交和原 Compose 配置直接撤销部署。
- 回退不删除任一命名卷；保留源/目标基线、迁移日志、失败命令和 Trace 证据。

## 7. CUTOVER 验收矩阵

| 门禁 | 通过标准 |
| --- | --- |
| 数据 | 五张表基线、最终追平和目标库完全一致 |
| 单一所有权 | commerce 无库存 Mapper/XML、无源表运行权限；inventory-db 是唯一库存写库 |
| 传输 | 四类命令重复、乱序、超时、拒绝、结果丢失均符合 V1 协议 |
| 业务等待态 | COMMIT 未确认禁止履约；退款成功但库存未知保持独立待对账状态 |
| 恢复 | 停服务、停数据库、重启和原 commandId 人工恢复均不重复改变余额 |
| Trace | 四类命令均有跨两个进程的完整 Trace 与业务关联键 |
| 回退 | 解除停写前回退和接收新写后的受控停止方案均实际演练 |
| 全量回归 | Java、Flyway、快速回归、Windows/Bash 脚本与代码质量门禁全部通过 |

命令 CUTOVER 和商家库存管理迁出均已通过；commerce 的库存 Mapper/XML 已删除，受限运行账号只获非库存表
逐表授权，对五张源表访问均被拒绝。源表仅作为历史迁移证据保留，不是运行回退路径。
即使 inventory-service 已独立，PostgreSQL 仍要等待 notification、catalog、inventory、order、
payment-orchestration、fulfillment 以及 M3 清单中所有待拆服务全部完成独立验收。

## 8. 本批可复跑命令

```powershell
.\scripts\windows\audit-inventory-service-admission.ps1
.\scripts\windows\verify-inventory-remote-rehearsal.ps1
.\scripts\windows\verify-inventory-runtime-cutover.ps1
.\scripts\windows\verify-inventory-single-owner.ps1
```

```bash
./scripts/linux/audit-inventory-service-admission.sh
./scripts/linux/verify-inventory-remote-rehearsal.sh
./scripts/linux/verify-inventory-runtime-cutover.sh
./scripts/linux/verify-inventory-single-owner.sh
```

`audit-*` 只启动已有 `commerce-db`（如尚未运行）并执行只读查询。`verify-inventory-remote-rehearsal` 保留为历史
迁移/预演复测；`verify-inventory-runtime-cutover` 验证当前唯一生产路径、四类命令、跨进程 Trace、结果丢失及
服务/数据库停机恢复，退出时恢复默认运行配置。`verify-inventory-single-owner` 进一步验证商家薄代理、调账
恢复、五张源表权限拒绝、未签名 401 和停机 503。所有脚本都保留命名卷。

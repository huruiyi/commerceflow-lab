# inventory-service 准入与单一所有权演进计划

## 1. 本批结论

`inventory-service` 已完成前三批准入治理，但**尚未获准创建独立进程或独立 Schema**。
当前只在 `commerce-service` 内保留一套库存实现，并完成以下收敛：

- ordering、payment、refund 只能依赖 `inventory/application/port`，不能注入 `InventoryService`、库存领域对象或 Mapper；
- 四类写入统一为 `InventoryCommandV1`，预占输入使用库存自有 `Item`，不再接收 cart DTO；
- 商家库存应用服务只接收 `merchantId`，不再接收 identity 的 `AppUser`；
- V30 新增 inventory 自有 `inventory_command_inbox`，同一本地事务完成命令领取、余额变更和处理状态；
- V30 移除 reservation/movement 指向 `shop_orders` 的外键，InventoryMapper 不再查询 ordering 表；
- V31 新增 inventory 自有 `inventory_catalog_event_inbox`，按 eventId 与载荷指纹幂等消费 catalog V1 事件；
- `product_inventory` 以 lifecycle/businessVersion 维护商品履行快照，catalog 事件不修改库存数量；
- catalog-service Outbox 通过 HMAC 和载荷摘要投递，失败有限重试并在第 5 次进入 DEAD；
- 取消通知由订单取消事务写入 notification Outbox，库存释放不再承担客户身份查询或通知副作用；
- ArchUnit 门禁禁止库存以外的模块重新依赖库存 API、领域、基础设施或具体应用实现；
- 跨模块现状为 39 个方向、109 个 import、35 个源码文件，跨模块 infrastructure 引用仍为 18。

这表示本地 V1 命令、幂等边界和 catalog 单向投影已稳定，不表示四类库存命令的远程结果、事务补偿或
数据交接已经完成。

## 2. 唯一数据所有权

| 表/事实 | 唯一所有者 | 说明 |
| --- | --- | --- |
| `product_inventory` | inventory | productId、merchantId、履行展示快照及可售/预占/已售余额 |
| `inventory_reservations` | inventory | 每个订单商品的预占状态与版本 |
| `inventory_movements` | inventory | 余额变化后的不可变流水，eventId 唯一 |
| `inventory_command_inbox` | inventory | commandId 去重、语义载荷指纹和本地处理结果 |
| `inventory_catalog_event_inbox` | inventory | catalog eventId 去重、载荷指纹、APPLIED/STALE 处理结果 |
| `products`、`product_translations` | catalog-service | 商品资料、翻译、生命周期和业务版本；不得回流库存 |
| `shop_orders`、`order_items` | ordering | 订单及下单快照；inventory 只能保存稳定 orderId/productId |

V30 已移除 `inventory_reservations.order_id`、`inventory_movements.order_id` 指向 `shop_orders` 的跨边界
外键，并删除 `InventoryMapper.customerId(orderId)`。orderId 现在只是稳定业务标识，不依赖 ordering 表存在；
取消通知由订单取消事务使用同一 order eventId 作为 causationId 写入 notification Outbox，库存模块不再依赖
notification。独立 Schema 后同样不得跨库模拟这些外键。

## 3. 当前应用端口

`InventoryCommandPort` 是单体内唯一允许的跨模块写入口：

| 操作 | 当前调用方 | 业务语义 |
| --- | --- | --- |
| `handle(RESERVE)` | ordering checkout | 原子扣减可售、增加预占并写 reservation/movement |
| `handle(COMMIT)` | payment callback | `RESERVED → COMMITTED`；迟到支付允许从 RELEASED 重新占用可售 |
| `handle(RELEASE)` | order event consumer | 仅释放 RESERVED，重复 commandId 不重复改变余额 |
| `handle(REFUND)` | refund callback | `COMMITTED → REFUNDED`，已售回到可售 |

端口参数不泄露 cart、ordering、payment、refund 的实体。当前传输仍是 Java 本地调用；独立服务前不能直接
机械改成同步 REST，否则 checkout/payment/refund 的本地事务会变成不可恢复的跨服务部分提交。

## 4. 已冻结的 V1 命令与失败语义

`RESERVE/COMMIT/RELEASE/REFUND` 使用统一信封：

- `schemaVersion`、稳定 `commandId`、`commandType`、`occurredAt`、`producer`；
- `orderId`、商品项或事件所需最小字段；
- `correlationId`、`causationId` 和合法 W3C `traceparent`；
- commandId 是幂等键；相同 commandId、相同语义载荷返回已处理，不重复改变余额；
- 相同 commandId 复用为不同类型、orderId、reason 或商品项时拒绝，防止错误重放被静默吞掉；
- `RESERVE` 必须有正数商品项，其他命令禁止携带商品项；未知 schema、非法 traceparent 和空必填字段拒绝；
- 业务处理失败时命令领取与库存变化一起回滚，调用方必须使用原 commandId 重试。

四份 fixture 已固定字段与类型；集成测试覆盖解析/校验、重复不重复扣减和同 ID 异载荷拒绝。乱序/迟到仍由
reservation 状态机处理；跨进程超时、Broker 重投和并发消费要在真正引入传输适配器后专项验证。

## 5. catalog 事件入口

catalog-service 已是商品资料唯一所有者。inventory 只能消费 catalog V1 事件维护必要履行快照：

- catalog 当前不开放商品创建；未来 `PRODUCT_CREATED` 必须由明确的库存初始化命令提供初始数量；
- profile、price、status 事件按 `businessVersion` 顺序更新 productName、thumbnailUrl、unitPrice 和生命周期；
- translation 事件只推进版本，不用本地化名称覆盖 inventory 主快照；
- eventId 重复返回已处理，同 ID 异载荷拒绝，旧版本记为 STALE，版本缺口回滚并进入有限重试/DEAD；
- catalog 停机不影响既有商品的库存扣减，inventory 停机也不得把数量写回 catalog。

该入口现已通过 V31 Inbox 和 HMAC HTTP 投递落地。签名覆盖时间窗、requestId 和请求体 SHA-256；真实 Docker
已验证正常 PUBLISHED，以及 inventory 停机时 RETRY、恢复后单次 APPLIED。当前未把该链路描述为完整
跨进程 Trace 验收，后续独立 inventory-service 切换时仍要与四类命令一起验证 Trace。

## 6. 切换顺序与回退

后续严格一次只推进一个门禁：

1. [已完成] 冻结并实现 catalog 事件消费 fixture、businessVersion 门禁、独立 Inbox 和有限重试/DEAD；
2. 明确 checkout/payment/refund 的跨进程结果与补偿协议，再评审独立服务启动；
3. 在独立 MySQL Schema 创建分层 `inventory-service`，完成一次性计数、余额总和和内容指纹迁移；
4. 停写窗口内追平增量，整体切换调用方，验证并发、重复、乱序、超时、停机恢复和跨进程 Trace；
5. 删除 commerce 的库存 Mapper/XML/运行表访问，只保留必要的公开身份/RBAC 薄代理，不保留 LOCAL/COMPARE、
   双写或数据库回退；
6. 以 Git 提交和可恢复数据备份作为回退边界，失败时停止切换，不覆盖已接收新写入的独立库。

## 7. 明确不在本批实施

- 不创建或启动 `inventory-service`、`inventory-db`；
- 不迁移库存表、不创建独立数据库、不改 Docker 默认拓扑；
- 不同时启动 order/payment-orchestration/fulfillment 拆分；
- 不引入 PostgreSQL 或 MySQL/PostgreSQL 双方言。只有 M3 清单中的 notification、catalog、inventory、order、
  payment-orchestration、fulfillment 全部完成单一所有权切换并逐一验收后，才允许单独启动 PostgreSQL 专项。

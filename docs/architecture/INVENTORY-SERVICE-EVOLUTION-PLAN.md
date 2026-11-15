# inventory-service 准入与单一所有权演进计划

## 1. 本批结论

`inventory-service` 已完成首批准入评审和模块内边界治理，但**尚未获准创建独立进程或独立 Schema**。
本批只在 `commerce-service` 内保留一套库存实现，并完成以下收敛：

- ordering、payment、refund 只能依赖 `inventory/application/port`，不能注入 `InventoryService`、库存领域对象或 Mapper；
- 预占输入改为库存自有的 `InventoryCommandPort.ReservationItem`，库存不再接收 cart 的 `CheckoutCartItem`；
- 商家库存应用服务只接收 `merchantId`，不再接收 identity 的 `AppUser`；
- ArchUnit 门禁禁止库存以外的模块重新依赖库存 API、领域、基础设施或具体应用实现；
- 跨模块现状收紧为 39 个方向、109 个 import、36 个源码文件，跨模块 infrastructure 引用仍为 18。

这表示本地调用入口已稳定，不表示远程命令、事务补偿、独立 Inbox 或数据交接已经完成。

## 2. 唯一数据所有权

| 表/事实 | 唯一所有者 | 说明 |
| --- | --- | --- |
| `product_inventory` | inventory | productId、merchantId、履行展示快照及可售/预占/已售余额 |
| `inventory_reservations` | inventory | 每个订单商品的预占状态与版本 |
| `inventory_movements` | inventory | 余额变化后的不可变流水，eventId 唯一 |
| `products`、`product_translations` | catalog-service | 商品资料、翻译、生命周期和业务版本；不得回流库存 |
| `shop_orders`、`order_items` | ordering | 订单及下单快照；inventory 只能保存稳定 orderId/productId |

商城历史 Flyway 中 `inventory_reservations.order_id`、`inventory_movements.order_id` 仍有指向
`shop_orders` 的跨边界外键。这在同库单体中有效，但独立 Schema 前必须通过新的非破坏迁移移除；不得在拆分后
跨库模拟外键或读取 ordering 表。`InventoryMapper.customerId(orderId)` 及取消通知副作用也仍跨越 ordering、
notification 边界，必须在下一批改为各消费者独立处理同一订单事件。

## 3. 当前应用端口

`InventoryCommandPort` 是单体内唯一允许的跨模块写入口：

| 操作 | 当前调用方 | 业务语义 |
| --- | --- | --- |
| `reserve(orderId, items)` | ordering checkout | 原子扣减可售、增加预占并写 reservation/movement |
| `commit(orderId, eventId)` | payment callback | `RESERVED → COMMITTED`；迟到支付允许从 RELEASED 重新占用可售 |
| `releaseCancelledOrder(orderId, eventId, reason)` | order event consumer | 仅释放 RESERVED，重复事件不重复改变余额 |
| `refund(orderId, eventId)` | refund callback | `COMMITTED → REFUNDED`，已售回到可售 |

端口参数不泄露 cart、ordering、payment、refund 的实体。它仍是 Java 本地接口，不作为 HTTP/消息 V1 契约；
独立服务前不能直接把这些方法机械改成同步 REST，否则 checkout/payment/refund 的本地事务会变成不可恢复的
跨服务部分提交。

## 4. 独立服务前必须冻结的 V1 命令

下一批需要为 `RESERVE/COMMIT/RELEASE/REFUND` 定义统一信封，至少包含：

- `schemaVersion`、稳定 `commandId`、`commandType`、`occurredAt`、`producer`；
- `orderId`、商品项或事件所需最小字段；
- `correlationId`、`causationId` 和合法 W3C `traceparent`；
- 每类命令的幂等键、允许的来源状态、重复结果和不可重试错误；
- 库存不足、版本缺口、非法状态、服务不可用和超时后的稳定错误/补偿语义。

每个命令必须由 inventory 自有 Inbox 去重，并在同一本地事务中完成余额、reservation、movement 与处理结果。
调用方超时后只能用同一 commandId 重试，不能生成新命令。消费者契约测试必须覆盖当前版本 fixture、重复、乱序、
迟到、并发及未知版本拒绝。

## 5. catalog 事件入口

catalog-service 已是商品资料唯一所有者。inventory 只能消费 catalog V1 事件维护必要履行快照：

- `PRODUCT_CREATED` 初始化唯一库存聚合，初始数量由明确的初始化命令提供；初始化完成前 catalog 不开放商品创建；
- profile、price、status 事件按 `businessVersion` 顺序更新 productName、thumbnailUrl、unitPrice 等快照；
- eventId 重复返回已处理，旧版本记为 STALE，版本缺口进入有限重试/DEAD，不覆盖数量字段；
- catalog 停机不影响既有商品的库存扣减，inventory 停机也不得把数量写回 catalog。

该入口需要 inventory 自有 catalog-event Inbox、失败恢复和跨进程 Trace。本批未创建这些表和消费者。

## 6. 切换顺序与回退

后续严格一次只推进一个门禁：

1. 冻结库存命令 V1、catalog 事件消费 fixture、稳定失败语义和事务/补偿边界；
2. 移除 inventory→ordering/notification 运行依赖及跨边界数据库外键，增加 inventory 自有 Inbox/Outbox；
3. 在独立 MySQL Schema 创建分层 `inventory-service`，完成一次性计数、余额总和和内容指纹迁移；
4. 停写窗口内追平增量，整体切换调用方，验证并发、重复、乱序、超时、停机恢复和跨进程 Trace；
5. 删除 commerce 的库存 Mapper/XML/运行表访问，只保留必要的公开身份/RBAC 薄代理，不保留 LOCAL/COMPARE、
   双写或数据库回退；
6. 以 Git 提交和可恢复数据备份作为回退边界，失败时停止切换，不覆盖已接收新写入的独立库。

## 7. 明确不在本批实施

- 不创建或启动 `inventory-service`、`inventory-db`；
- 不迁移库存表、不移除现有外键、不改 Docker 默认拓扑；
- 不同时启动 order/payment-orchestration/fulfillment 拆分；
- 不引入 PostgreSQL 或 MySQL/PostgreSQL 双方言。只有 M3 清单中的 notification、catalog、inventory、order、
  payment-orchestration、fulfillment 全部完成单一所有权切换并逐一验收后，才允许单独启动 PostgreSQL 专项。

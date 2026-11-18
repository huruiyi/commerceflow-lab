# 库存预占对账复核

复核范围为商城投递 Outbox 中 35 条历史 `RESERVE` 和未生成预占的订单 485。历史时间统一用 UTC。订单、库存、支付与退款、商城投递分别取自 `order-db`、`inventory-db`、`payment-db`、`commerce-db`，以订单 ID、原命令 ID 及商品数量交叉核对。

## 原因

- 35 条预占全部指向商品 1，共 36 件，每条都试了 5 次。库存流水显示 20:18:43.679383 可售数量变为 0，到 21:42:01.767851 商家增加 100 件才恢复。前几笔超时与正常预占短暂交错，最后一批超时后，订单 502 从 21:42:47.494347 起恢复预占。
- 原商城把库存 HTTP 409 归类为远程结果未知。空库实测在购物车已有商品、提交前可售库存降到 0 时，收到同样的 5 次超时，商城没有结果 Inbox，库存命令事务回滚，无预占和流水。后续 `RELEASE`、`COMMIT`、`REFUND` 对空预占返回成功，产生了回执，该订单的库存余额没有改变。
- 订单 485 只有 `ORDER_CREATED`，商城未留下预占、结果和付款超时任务，库存无命令、预占和流水，支付无支付、退款和流程记录。空库停止支付服务后，原版结账已创建远程订单，响应组装查询支付失败返回 503，商城事务回滚，复现了相同形态。历史商城容器已重建，无法查到 485 当次请求的异常日志。

## 逐笔结论

每行的原命令 ID 为 `order:<订单 ID>:reserve`。这批原命令的商城结果 Inbox、库存原命令 Inbox、库存预占和库存流水匹配数均为 0。表中库存状态为处置前的订单投影。后续命令为库存 Inbox 中留下的成功回执。原命令日期为 2026-11-17 UTC。

| 订单 | 原命令时间 | 数量 | 订单 / 库存 | 支付 / 退款 | 后续库存回执 | 处置 |
| --- | --- | ---: | --- | --- | --- | --- |
| 462 | 20:18:30 | 1 | CANCELLED / RECONCILIATION_REQUIRED | CLOSED | RELEASE | 库存净影响为 0，已结案 |
| 465 | 20:19:25 | 1 | COMPLETED / RECONCILIATION_REQUIRED | SUCCESS | COMMIT | 保留待核，缺少实物库存依据 |
| 466 | 20:21:29 | 1 | PAID / RECONCILIATION_REQUIRED | SUCCESS | COMMIT | 保留待核，缺少实物库存依据 |
| 467 | 20:21:41 | 1 | CANCELLED / RELEASED | 无 | RELEASE | 库存净影响为 0，已结案 |
| 468 | 20:35:44 | 1 | CANCELLED / RELEASED | CLOSED | RELEASE | 库存净影响为 0，已结案 |
| 469 | 20:37:56 | 1 | CANCELLED / RELEASED | CLOSED | RELEASE | 库存净影响为 0，已结案 |
| 470 | 20:39:31 | 1 | CANCELLED / RELEASED | CLOSED | RELEASE | 库存净影响为 0，已结案 |
| 473 | 20:46:00 | 1 | PAID / RECONCILIATION_REQUIRED | SUCCESS | COMMIT | 保留待核，缺少实物库存依据 |
| 474 | 20:49:10 | 1 | CANCELLED / RECONCILIATION_REQUIRED | 无 | RELEASE | 库存净影响为 0，已结案 |
| 475 | 20:49:12 | 1 | CANCELLED / RECONCILIATION_REQUIRED | CLOSED | RELEASE | 库存净影响为 0，已结案 |
| 476 | 20:49:13 | 1 | PAID / RECONCILIATION_REQUIRED | SUCCESS | COMMIT | 保留待核，缺少实物库存依据 |
| 477 | 20:49:14 | 1 | CANCELLED / RECONCILIATION_REQUIRED | CLOSED | RELEASE | 库存净影响为 0，已结案 |
| 478 | 20:49:16 | 1 | REFUNDED / RECONCILIATION_REQUIRED | SUCCESS / SUCCEEDED | COMMIT / REFUND | 资金已退，库存净影响为 0，已结案 |
| 479 | 20:49:30 | 1 | RECEIVED / RECONCILIATION_REQUIRED | SUCCESS | COMMIT | 保留待核，缺少实物库存依据 |
| 480 | 20:50:15 | 1 | COMPLETED / RECONCILIATION_REQUIRED | SUCCESS | COMMIT | 保留待核，缺少实物库存依据 |
| 481 | 20:52:25 | 1 | PAID / RECONCILIATION_REQUIRED | SUCCESS | COMMIT | 保留待核，缺少实物库存依据 |
| 482 | 20:52:36 | 1 | CANCELLED / RELEASED | 无 | RELEASE | 库存净影响为 0，已结案 |
| 483 | 20:58:21 | 1 | CANCELLED / RELEASED | CLOSED | RELEASE | 库存净影响为 0，已结案 |
| 484 | 20:59:36 | 1 | PAID / RECONCILIATION_REQUIRED | SUCCESS | COMMIT | 保留待核，缺少实物库存依据 |
| 485 | 无 | 1 | PENDING_PAYMENT / WAITING_RESERVATION | 无 | 无 | 经订单取消接口取消，复核为 CANCELLED / RELEASED |
| 486 | 21:24:15 | 2 | REFUNDED / RECONCILIATION_REQUIRED | SUCCESS / SUCCEEDED | COMMIT / REFUND | 资金已退，库存净影响为 0，已结案 |
| 487 | 21:31:49 | 1 | REFUNDED / RECONCILIATION_REQUIRED | SUCCESS / SUCCEEDED | COMMIT / REFUND | 资金已退，库存净影响为 0，已结案 |
| 488 | 21:32:00 | 1 | REFUNDED / RECONCILIATION_REQUIRED | SUCCESS / SUCCEEDED | COMMIT / REFUND | 资金已退，库存净影响为 0，已结案 |
| 489 | 21:33:04 | 1 | CANCELLED / RECONCILIATION_REQUIRED | 无 | RELEASE | 库存净影响为 0，已结案 |
| 490 | 21:33:06 | 1 | CANCELLED / RECONCILIATION_REQUIRED | CLOSED | RELEASE | 库存净影响为 0，已结案 |
| 491 | 21:33:07 | 1 | REFUNDED / RECONCILIATION_REQUIRED | SUCCESS / SUCCEEDED | COMMIT / REFUND | 资金已退，库存净影响为 0，已结案 |
| 492 | 21:33:11 | 1 | PAID / RECONCILIATION_REQUIRED | SUCCESS | COMMIT | 保留待核，缺少实物库存依据 |
| 493 | 21:34:33 | 1 | CANCELLED / RECONCILIATION_REQUIRED | 无 | RELEASE | 库存净影响为 0，已结案 |
| 494 | 21:34:34 | 1 | CANCELLED / RECONCILIATION_REQUIRED | CLOSED | RELEASE | 库存净影响为 0，已结案 |
| 495 | 21:34:35 | 1 | REFUNDED / RECONCILIATION_REQUIRED | SUCCESS / SUCCEEDED | COMMIT / REFUND | 资金已退，库存净影响为 0，已结案 |
| 496 | 21:34:39 | 1 | PAID / RECONCILIATION_REQUIRED | SUCCESS | COMMIT | 保留待核，缺少实物库存依据 |
| 497 | 21:35:05 | 1 | RECEIVED / RECONCILIATION_REQUIRED | SUCCESS | COMMIT | 保留待核，缺少实物库存依据 |
| 498 | 21:36:08 | 1 | COMPLETED / RECONCILIATION_REQUIRED | SUCCESS | COMMIT | 保留待核，缺少实物库存依据 |
| 499 | 21:39:44 | 1 | REFUNDED / RECONCILIATION_REQUIRED | SUCCESS / SUCCEEDED | COMMIT / REFUND | 资金已退，库存净影响为 0，已结案 |
| 500 | 21:39:59 | 1 | CANCELLED / RELEASED | 无 | RELEASE | 库存净影响为 0，已结案 |
| 501 | 21:40:29 | 1 | REFUNDED / RECONCILIATION_REQUIRED | SUCCESS / SUCCEEDED | COMMIT / REFUND | 资金已退，库存净影响为 0，已结案 |

23 笔原投递记录已在商城库标记为 `CLOSED`，其中 15 笔已取消、8 笔已退款。23 条均保留原超时原因及核对人、结案原因、时间。库存预占、库存流水、原命令结果 Inbox 均未补造。订单 485 通过取消流程写了订单事件和释放回执，复核库存预占与流水均为 0。

剩余 12 笔支付成功且未退款。订单 466、473、476、481、484、492、496 无发货记录。订单 465、479、480、497、498 在物流模拟器中均为 `DELIVERED`，但缺少正式库存出库流水。前组需要决定履约或退款，后组需要核对出库差异。原命令保持 `RECONCILIATION_REQUIRED`，商城后台拒绝重试已超期或已脱离待付款状态的预占。

## 空库复测

使用隔离 Compose 项目和全新 MySQL 卷，导入目录、库存及支付商户演示配置。结账前订单、商城库存投递和结果、库存预占及订单流水均为 0。测试使用远程订单、库存与支付服务，履约使用空库默认归属。

| 场景 | 结果 |
| --- | --- |
| 正常结账 | 商品 1 可售数量 100 → 99，订单达到 `RESERVED`，原投递尝试 1 次，生成 1 条预占流水。 |
| 多商品结账且商品 1 缺货 | 库存 Inbox 持久化 `REJECTED`，商城收到 `STOCK_UNAVAILABLE` 并一次确认，订单为 `RESERVATION_REJECTED`。商品 2 没有部分扣减，订单无预占和流水，创建支付返回 409。补货后重放原命令仍返回相同拒绝，库存余额不变，商城结果仍只有 1 条。 |
| 结账时停止支付编排 | 结账成功，远程订单保留 1 条预占流水、1 条已确认的商城命令和 1 条支付超时任务。 |
| 订单取消、支付和退款 | 取消通知链路通过，支付和退款的幂等重放均匹配，退款后库存投影正确收口。 |

商城和库存服务合计 61 项测试通过。真实 MySQL 验证商城 V1–V49 空库迁移、V16 到 V49 升级，以及库存 V2 空库迁移和本地存量库升级通过。结账路径已移除创建订单时的支付读取，远程订单创建和商城落账仍跨库，进程在两者之间崩溃属于单独的恢复场景。

## 复核查询

以下查询分别在所属库执行。

- 商城库查 `inventory_command_delivery_outbox` 的 `order_id`、`command_id`、`status`、`attempts`、`payload`、`recovered_by`、`recovery_reason`，按 `command_id` 左连接 `inventory_command_result_inbox`。再用 `scheduled_tasks.aggregate_type='ORDER'` 和 `aggregate_id` 核对超时任务。
- 订单库按 `shop_orders.id` 查 `status`、`inventory_status`、`payment_due_at`，再按 `order_id` 查 `order_items` 和 `order_events`。
- 库存库按 `order_id` 查 `inventory_reservations`、`inventory_movements` 和 `inventory_command_inbox`。查商品 1 在原命令创建窗口内的 `available_after`，定位库存归零和补货。按具体命令 ID 区分原预占和后续成功回执。
- 支付库按 `order_id` 查 `payment_records`、`refund_records` 和 `payment_process_managers`。

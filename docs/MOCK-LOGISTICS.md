# 独立模拟物流实现说明

## 1. 本阶段实现边界

首版已建立一条可运行、可验签、可回放的物流闭环：

```text
商家发货
  -> 商城准备 REQUESTED 投影
  -> HMAC 签名调用模拟物流建单
  -> 物流平台在独立 MySQL 保存运单和轨迹
  -> 同一事务写入 Callback Outbox（回调事务发件箱）
  -> 后台领取、HMAC 签名回调商城并失败重试
  -> 商城幂等投影轨迹
  -> Database Reconciliation（数据库主动对账）补偿永久丢失
  -> 客户查询本人订单物流
```

已实现状态是 `CREATED（已创建） -> PICKED_UP（已揽收） -> IN_TRANSIT（运输中） -> AT_STATION（已到站） -> OUT_FOR_DELIVERY（派送中） -> DELIVERED（已送达）`。轨迹既可通过签名的 `advance` 接口人工推进，也可由默认关闭的配置化定时器自动推进。非终态可进入 `EXCEPTION（异常件）`，经 `RECOVERY（异常恢复）` 返回原状态后继续。回调 Outbox、失败重试、处理租约、最大次数和商城主动对账均已实现。

## 2. 服务和数据边界

| 组件 | 端口/存储 | 职责 |
| --- | --- | --- |
| `mock-logistics-service` | `18083` | 验签、防重放、运单幂等、轨迹状态机、回调 Outbox、重试、人工重放与指标 |
| `logistics-mysql` | 宿主机 `13308` | 物流平台运单、轨迹、已用 Nonce 和 `callback_outbox` |
| `backend` | `18081` | 商城运单投影、回调去重、主动对账、归属校验和客户查询 |
| 商城 MySQL | Compose 内部 | `shipment_records/events/notifications` 读模型 |

模拟物流不连接商城数据库。两边不使用跨库事务：商城先持久化可恢复的 `REQUESTED` 投影，远程建单成功后才将订单改为 `SHIPPED`。

## 3. 接口与安全协议

| 方向 | 接口 | 作用 |
| --- | --- | --- |
| 商城 -> 物流 | `POST /api/channel/shipments` | 幂等建单，`merchantShipmentNo` 是商城幂等键 |
| 商城 -> 物流 | `GET /api/channel/shipments?merchantShipmentNo=...` | 按商城运单号查询 |
| 商城 -> 物流 | `PUT /api/channel/shipments/{merchantShipmentNo}` | 更正承运商和运单号 |
| 实验端 -> 物流 | `POST /api/channel/shipments/{merchantShipmentNo}/advance` | 推进一个轨迹节点 |
| 实验端 -> 物流 | `POST /api/channel/shipments/{merchantShipmentNo}/exception` | 记录异常件并暂停自动推进 |
| 实验端 -> 物流 | `POST /api/channel/shipments/{merchantShipmentNo}/recover` | 恢复至异常前状态 |
| 物流 -> 商城 | `POST /api/logistics-notifications/callback` | 签名状态回调 |
| 客户 -> 商城 | `GET /api/purchase-orders/{orderId}/logistics` | 仅订单所属客户可查 |
| 管理员 -> 商城 | `GET /api/admin/logistics-callbacks` | 按状态查询回调 Outbox |
| 管理员 -> 商城 | `GET /api/admin/logistics-callbacks/{id}/events` | 查询投递与人工操作审计 |
| 管理员 -> 商城 | `POST /api/admin/logistics-callbacks/{id}/replay` | 填写原因后重放 `DEAD（停止自动重试）` 回调 |

请求签名使用以下规范化字符串：

```text
appId + "\n" + epochSeconds + "\n" + nonce + "\n" + sha256Hex(rawBody)
```

客户端传递 `X-App-Id`、`X-Timestamp`、`X-Nonce` 和 `X-Signature`，签名为 HMAC-SHA256 Base64。时间超窗口、签名错误返回 `401`；同一 App ID + Nonce 重放返回 `409`。

回调对 Outbox 保存的不可变原始 JSON 使用独立 Callback Secret 计算 HMAC-SHA256，通过 `X-Callback-Signature` 传递。商城以稳定 `notificationId/eventId` 去重，并用事件类型和状态序号防止旧回调覆盖新状态。承运商和快递单号允许更正，因此迟到事件不能用这两个可变快照判定伪造；平台运单号才是不变身份。

## 4. 数据一致性和限制

- 物流平台对 `merchantShipmentNo` 唯一，同一建单请求可安全重试。
- 承运商 + 运单号在物流平台唯一，不同订单不能共用同一运单。
- 每个轨迹节点使用稳定事件 ID；重复回调不会重复插入。
- 轨迹事件和 `callback_outbox` 在物流平台同一本地事务提交；接口成功不依赖商城当时在线。
- Outbox 状态为 `NEW（新建） -> PROCESSING（处理中） -> PUBLISHED（已发布）`；暂时失败进入 `RETRY（等待重试）`，超过最大次数进入 `DEAD（停止自动重试）`。
- `DEAD` 不会被后台投递器继续执行。只有 `ADMIN（管理员）` 可填写原因将它原子地改回 `RETRY`；非 `DEAD` 重放返回 `409 Conflict（状态冲突）`。
- 人工重放不会重新生成物流轨迹，而是再次投递原始不可变 JSON；商城仍使用稳定事件 ID 去重。
- `callback_outbox_events` 记录创建、领取、失败、发布和人工重放；人工操作必须保留管理员账号与原因。
- 投递器使用领取租约和执行者 ID，进程在发送前后崩溃时允许重新领取；可能产生的重复 HTTP 由商城事件 ID 幂等吸收。
- HTTP 连接和读取均有明确超时，避免任务无限卡在 `PROCESSING`。
- 商城定期签名查询物流平台权威运单。回调永久丢失时，以相同事件 ID 补齐轨迹并将通知审计标记为 `RECONCILED（已对账恢复）`。
- 事件类型分为 `PROGRESS（轨迹推进）`、`EXCEPTION（异常件）` 和 `RECOVERY（异常恢复）`。商城只有收到明确 `RECOVERY` 才会离开异常状态，迟到的 `PROGRESS` 不会误恢复。
- 手动推进、自动推进、进入异常和恢复都先锁定运单并条件更新，防止并发生成重复节点。
- `DELIVERED（已送达）` 只代表物流平台的送达事实，不等于客户已收货。回调写入 `logistics_delivered_at` 并创建 `AUTO_RECEIVE（自动确认收货）`；客户手动收货会取消该任务。
- 自动收货成功后创建 `AUTO_COMPLETE（自动完成订单）`，最终将 `RECEIVED（已收货）` 推进为 `COMPLETED（已完成）`。
- Outbox 是正常投递主路径，主动对账是永久丢失兜底；两条路径共用同一投影入口和事件 ID，不会形成两套业务状态机。

## 5. 自动轨迹配置

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `LOGISTICS_AUTO_PROGRESS_ENABLED` | `false` | 是否启用自动轨迹；默认关闭以便手动学习 |
| `LOGISTICS_AUTO_PROGRESS_INITIAL_DELAY` | `10s` | 服务启动后首次扫描延迟 |
| `LOGISTICS_AUTO_PROGRESS_SCAN_INTERVAL` | `5s` | 自动扫描周期 |
| `LOGISTICS_AUTO_PROGRESS_STEP_INTERVAL` | `5m` | 同一运单两个正常节点之间的最短时间 |
| `LOGISTICS_AUTO_PROGRESS_BATCH_SIZE` | `100` | 每批最多推进的运单数 |

`EXCEPTION`、`DELIVERED` 不在自动候选集合中。这保证异常件必须先被明确处理，已送达运单也不会继续生成轨迹。

## 6. 回调投递与主动对账配置

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `LOGISTICS_CALLBACK_DELIVERY_ENABLED` | `true` | 是否启动 Outbox 投递器；关闭仅用于丢失回调实验 |
| `LOGISTICS_CALLBACK_DELIVERY_SCAN_INTERVAL` | `1s` | 扫描 `NEW/RETRY` 和过期 `PROCESSING` 的周期 |
| `LOGISTICS_CALLBACK_DELIVERY_RETRY_DELAY` | `2s` | 失败后的下次尝试间隔 |
| `LOGISTICS_CALLBACK_DELIVERY_CLAIM_LEASE` | `30s` | 一次领取的处理租约 |
| `LOGISTICS_CALLBACK_DELIVERY_CONNECT_TIMEOUT` | `2s` | 回调连接超时 |
| `LOGISTICS_CALLBACK_DELIVERY_READ_TIMEOUT` | `3s` | 回调读取超时 |
| `LOGISTICS_CALLBACK_DELIVERY_BATCH_SIZE` | `50` | 单轮最多领取数量 |
| `LOGISTICS_CALLBACK_DELIVERY_MAX_ATTEMPTS` | `10` | 超过后进入 `DEAD` 的最大尝试次数 |
| `LOGISTICS_RECONCILIATION_ENABLED` | `true` | 是否启用商城数据库主动对账 |
| `LOGISTICS_RECONCILIATION_SCAN_INTERVAL` | `1m` | 商城扫描疑似落后投影的周期 |
| `LOGISTICS_RECONCILIATION_STALE_AFTER` | `5m` | 多久未更新才查询权威运单 |
| `LOGISTICS_RECONCILIATION_BATCH_SIZE` | `100` | 每轮最多对账运单数 |

生产环境应保持两者开启。专项实验可以分别关闭，以观察“只有回调”和“只有对账”时的行为。

## 7. 监控与人工处理

管理员页面入口为 `/admin/logistics-callbacks`。页面可按 `NEW（新建）` / `PROCESSING（处理中）` / `RETRY（等待重试）` / `PUBLISHED（已发布）` / `DEAD（停止自动重试）` 筛选，查看完整审计轨迹，并对 `DEAD` 填写原因后重放。

`GET http://localhost:18083/actuator/prometheus` 暴露：

- `commerceflow_logistics_callback_outbox`：按状态统计当前积压；
- `commerceflow_logistics_callback_delivery_total`：已发布、失败和进入 `DEAD` 的投递计数；
- `commerceflow_logistics_callback_replays_total`：管理员人工重放计数；
- `commerceflow_logistics_reconciliation_total`：商城主动对账的检查、恢复和失败计数。

当前已实现指标与可审计人工处理；Prometheus Alert Rule、Alertmanager 和外部通知接收端属于后续运维集成，没有在本阶段伪造为“已告警”。

## 8. 验收

Windows：

```powershell
./scripts/windows/verify-logistics.ps1
```

Bash（需 `curl`、`jq`、`openssl`）：

```bash
bash ./scripts/linux/verify-logistics.sh
```

脚本会新建已支付订单，完成商家确认/发货，验证签名、重放拒绝、伪造签名拒绝、`EXCEPTION -> RECOVERY`、六个正常轨迹节点、回调投影、客户查询和送达时间。

自动轨迹专项：

```powershell
./scripts/windows/verify-logistics-automation.ps1
```

```bash
bash ./scripts/linux/verify-logistics-automation.sh
```

专项脚本会临时启用自动推进并将步长压缩为 1 秒，验证送达回调与 `AUTO_RECEIVE`，然后重建物流服务恢复默认关闭。

可靠投递与主动对账专项：

```powershell
./scripts/windows/verify-logistics-reliability.ps1
```

```bash
bash ./scripts/linux/verify-logistics-reliability.sh
```

脚本先停止商城，验证回调 `RETRY -> PUBLISHED`；随后关闭回调投递制造永久丢失，验证商城通过主动对账补齐并记录 `RECONCILED`。脚本结束会恢复原环境变量并重建服务。

`DEAD` 治理专项：

```powershell
./scripts/windows/verify-logistics-callback-governance.ps1
```

```bash
bash ./scripts/linux/verify-logistics-callback-governance.sh
```

该脚本将最大尝试次数临时设为 1，真实制造 `DEAD`，验证客户 `403`、管理员查询、必填原因、`DEAD -> RETRY -> PUBLISHED`、操作人/原因审计、重复重放 `409`、商城事件幂等和 Prometheus 指标。

自动生命周期专项：

```powershell
./scripts/windows/verify-auto-lifecycle.ps1
```

```bash
bash ./scripts/linux/verify-auto-lifecycle.sh
```

该脚本额外验证手动收货取消 `AUTO_RECEIVE`，以及 `DELIVERED -> AUTO_RECEIVE -> RECEIVED -> AUTO_COMPLETE -> COMPLETED` 的真实 Docker 链路，并确认支付、物流和评价能力在订单完成后仍保留。脚本还会伪造“Handler 已提交但任务确认前崩溃”，重启 backend 后验证 `LEASE_RECOVERY（租约恢复）` 与幂等重放。

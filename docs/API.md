# API 学习索引

本项目使用浏览器 Session 认证。除公开商品和支付回调外，先请求 `GET /api/auth/csrf`，登录后在写请求中携带返回的 CSRF Header。前端已经自动完成该流程。

## 商城 API

| 领域 | 主要接口 | 权限 |
| --- | --- | --- |
| 认证 | `GET /api/auth/csrf`、`POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/logout` | 公开/已登录 |
| 商品 | `GET /api/products?lang=en|zh-CN` | 公开 |
| 购物车 | `GET /api/cart`、`POST /api/cart/items`、`PUT/DELETE /api/cart/items/{id}`、`DELETE /api/cart` | CUSTOMER |
| 地址 | `GET/POST /api/addresses`、`PUT/DELETE /api/addresses/{id}`、`PUT /api/addresses/{id}/default|select` | CUSTOMER |
| 订单 | `POST /api/purchase-orders/checkout`、`GET /api/purchase-orders`、`GET /api/purchase-orders/{id}`、`POST /api/purchase-orders/{id}/cancel` | CUSTOMER |
| 物流 | `GET /api/purchase-orders/{id}/logistics` | CUSTOMER，仅本人订单 |
| 支付 | `POST /api/purchase-orders/{id}/payments` | CUSTOMER |
| 退款 | `POST/GET /api/purchase-orders/{id}/refunds`、`GET /api/refunds/{refundNumber}` | CUSTOMER，仅本人订单 |
| 收货评价 | `POST /api/purchase-orders/{id}/receive`、`POST /api/order-items/{id}/reviews` | CUSTOMER |
| 商家履约 | `GET /api/merchant/orders`、`POST /api/merchant/orders/{id}/confirm|ship` | MERCHANT |
| 用户管理 | `GET /api/users` | ADMIN |
| 定时任务治理 | `GET/PUT/DELETE /api/admin/scheduling/policies...`、`GET/POST /api/admin/scheduling/tasks...` | ADMIN |

## 通用定时任务管理 API

管理员可维护任务类型策略，但不能从页面上传或执行代码。任务类型只有同时注册了受信任的 Java `ScheduledTaskHandler`，才允许创建实际任务。

| 操作 | 接口 | 说明 |
| --- | --- | --- |
| 策略目录 | `GET /api/admin/scheduling/policies` | 合并内置目录、YAML、数据库覆盖和已注册 Handler |
| 保存覆盖 | `PUT /api/admin/scheduling/policies/{taskType}` | 写数据库覆盖并递增配置版本，只影响后续新任务 |
| 恢复配置 | `DELETE /api/admin/scheduling/policies/{taskType}` | 删除数据库覆盖，恢复 YAML 任务策略或默认策略 |
| 策略审计 | `GET /api/admin/scheduling/policies/{taskType}/events` | 查看新增、修改和恢复配置的操作者与完整参数 |
| 任务查询 | `GET /api/admin/scheduling/tasks?taskType=&status=&limit=` | 按类型/状态查询，最大 500 条 |
| 任务事件 | `GET /api/admin/scheduling/tasks/{id}/events` | 查看创建、领取、成功、失败、重试和取消轨迹 |
| 人工重试 | `POST /api/admin/scheduling/tasks/{id}/retry` | 仅允许重试 `FAILED` 任务，次数从零重新开始 |
| 人工取消 | `POST /api/admin/scheduling/tasks/{id}/cancel` | 仅允许取消尚未完成的待执行/失败任务 |

保存策略请求体：

```json
{
  "enabled": false,
  "primaryTrigger": "DATABASE",
  "reconciliationEnabled": true,
  "gracePeriodSeconds": 120,
  "claimLeaseSeconds": 120,
  "maxAttempts": 5,
  "retryDelaySeconds": 30
}
```

配置覆盖不会修改已经创建任务的策略快照。`RABBITMQ` 和 `ROCKETMQ` 枚举已为后续适配器保留，但适配器完成前不得在实际业务任务上启用。

Angular 管理员可从导航栏“定时任务”进入 `/admin/scheduling` 操作上述接口。新增任务类型时，页面默认以禁用状态保存；只有后端已有对应 Handler 时才可安全启用并创建任务。

## 订单取消 API

`POST /api/purchase-orders/{id}/cancel` 仅允许订单所属客户操作。请求体可省略：

```json
{
  "reason": "客户改变购买计划"
}
```

后端不会直接把订单从 `PENDING_PAYMENT` 改成 `CANCELLED`。它先进入 `CANCELLING`，逐个签名关闭仍为 `PENDING` 的渠道交易，关闭成功后再写本地支付记录和订单终态。重复请求幂等；若支付成功先发生，则支付事实优先并保留异常事件供审计。

取消成功后同一本地事务会写入 `ORDER_CANCELLED` Outbox。订单查询响应包含 `availableActions`，供页面决定显示支付、取消、退款、收货和评价等操作；该字段不代替服务端授权校验。

## 客户通知 API

| 能力 | 方法与路径 | 说明 |
|---|---|---|
| 最近通知 | `GET /api/notifications` | 返回当前客户最近 20 条通知 |
| 未读数量 | `GET /api/notifications/unread-count` | 返回当前客户未读数 |
| 单条已读 | `PATCH /api/notifications/{id}/read` | 只能更新当前客户自己的通知 |
| 全部已读 | `PATCH /api/notifications/read-all` | 将当前客户的未读通知批量标记为已读 |

当前通知由 `ORDER_CANCELLED` Kafka 消费者创建。消费记录、库存流水事件 ID 和通知事件 ID 均有唯一约束，重复消息不会重复释放库存或重复通知。

## 商家库存 API

| 能力 | 方法与路径 | 说明 |
|---|---|---|
| 库存列表 | `GET /api/merchant/inventory` | 返回当前商家商品的可售、预占和已售余额 |
| 库存流水 | `GET /api/merchant/inventory/{productId}/movements` | 返回当前商家指定商品最近 100 条流水 |
| 人工调整 | `POST /api/merchant/inventory/{productId}/adjustments` | 请求体为 `delta` 与必填 `reason`，只调整可售库存且不能减为负数 |

库存接口只允许 `MERCHANT` 角色访问，并以商品的 `merchant_id` 再做资源归属校验。人工调整和订单库存变化使用同一流水模型。

## 模拟物流 API

| 接口 | 认证 | 说明 |
| --- | --- | --- |
| `POST /api/channel/shipments` | App ID + 时间戳 + Nonce + HMAC | 幂等创建运单 |
| `GET /api/channel/shipments?merchantShipmentNo=...` | 同上 | 查询物流平台权威运单 |
| `PUT /api/channel/shipments/{merchantShipmentNo}` | 同上 | 修改承运商和运单号 |
| `POST /api/channel/shipments/{merchantShipmentNo}/advance` | 同上 | 学习环境手工推进一个轨迹节点 |
| `POST /api/channel/shipments/{merchantShipmentNo}/exception` | 同上 | 运单进入 `EXCEPTION（异常件）`，请求体可携带 `location/description` |
| `POST /api/channel/shipments/{merchantShipmentNo}/recover` | 同上 | 通过 `RECOVERY（异常恢复）` 返回异常前状态 |
| `POST /api/logistics-notifications/callback` | 回调 HMAC | 物流平台推送状态和轨迹 |
| `GET /api/admin/logistics-callbacks?status=DEAD` | ADMIN | 查询 Callback Outbox，可按状态筛选 |
| `GET /api/admin/logistics-callbacks/{id}/events` | ADMIN | 查询回调投递和人工操作审计 |
| `POST /api/admin/logistics-callbacks/{id}/replay` | ADMIN + CSRF | 填写 `reason` 重放 `DEAD（停止自动重试）` 回调 |

商城订单列表和详情中的 `logistics` 字段会返回当前状态、承运商、运单号、发货/送达时间和完整轨迹。完整协议和一致性边界见 [独立模拟物流](MOCK-LOGISTICS.md)。

物流回调由平台 Callback Outbox 异步投递，接口返回成功不代表商城已在同一毫秒完成投影。客户端应读取商城订单/物流接口观察最终一致结果。商城同时运行主动对账，回调永久丢失时会用权威运单事件补齐；稳定 `eventId` 保证回调与对账并发时不重复入库。

## 退款 API

已支付订单退款使用独立退款单、渠道退款、签名异步通知、幂等、Outbox 和对账补偿。原支付记录继续保持 `SUCCESS`，成功退款金额写入 `refundedAmount`。

| 领域 | 接口 | 权限 |
| --- | --- | --- |
| 客户退款 | `POST /api/purchase-orders/{id}/refunds`、`GET /api/purchase-orders/{id}/refunds` | CUSTOMER，仅本人订单 |
| 退款查询 | `GET /api/refunds/{refundNumber}` | CUSTOMER，仅本人退款 |
| 商家查看 | `GET /api/merchant/orders/{id}/refunds` | MERCHANT，只读且限所属商品 |
| 渠道退款 | `POST /api/channel/refunds`、`GET /api/channel/refunds?merchantRefundNo=...`、`POST /api/channel/refunds/{merchantRefundNo}/notify` | 商户签名请求 |
| 支付后台 | `GET /api/admin/refunds`、`POST /api/admin/refunds/{channelRefundNo}/notify` | `REFUND_READ` / `REFUND_OPERATE` |

首版仅支持 `PAID` 或 `CONFIRMED` 且尚未发货订单的原路全额退款。请求体包含 `idempotencyKey`、`reasonCode`（`CUSTOMER_CANCELLED`、`ORDER_ERROR` 或 `OTHER`）和可选 `reasonDetail`；同一请求安全重放返回原退款，不同的第二次申请返回冲突。详细设计见 [退款实现说明](reference/REFUND.md)。

登录请求示例：

```json
{
  "email": "demo@example.com",
  "password": "demo-password",
  "remember": true
}
```

支付请求示例：

```json
{
  "idempotencyKey": "每次新支付使用一个 UUID；安全重试复用原值",
  "channel": "ALIPAY",
  "simulateFailure": false
}
```

`channel` 可取 `ALIPAY`、`WECHAT_PAY`、`UNION_PAY`。支付最终状态以支付服务签名异步通知为准，浏览器页面不能直接修改订单状态。

## 支付平台 API

| 领域 | 主要接口 | 权限/认证 |
| --- | --- | --- |
| 后台认证 | `GET /api/auth/csrf`、`POST /api/auth/login`、`GET /api/auth/session`、`POST /api/auth/logout` | 公开/已登录 |
| MFA 与提权 | `POST /api/auth/mfa/enroll|confirm`、`POST /api/auth/step-up` | 后台用户 |
| 商户 | `GET/POST /api/admin/merchants`、轮换密钥、启停、下载平台公钥 | PAYMENT_ADMIN/OPERATOR |
| 后台用户 | `GET/POST /api/admin/users`、分配商户 | PAYMENT_ADMIN |
| 审计 | `GET /api/admin/audit-logs` | PAYMENT_ADMIN/AUDITOR |
| 渠道交易 | `POST/GET /api/channel/transactions`、关闭交易 | 商户签名请求 |
| 收银台 | `GET /api/checkout/{token}`、`POST /api/checkout/{token}/complete` | 模拟收银台 |

商城后端调用渠道交易接口时使用 App ID、时间戳、Nonce、请求体摘要和 RSA/HMAC 签名。完整协议、安全实验和生产边界见 [高级模拟支付](reference/ADVANCED-PAYMENT.md)。

## 错误响应

商城 API 返回稳定的 `code`，前端按语言映射文案。常见代码包括：

- `AUTH_INVALID`：账号或密码错误。
- `AUTH_RATE_LIMITED`：登录尝试过于频繁。
- `VALIDATION_ERROR`：字段校验失败。
- `BUSINESS_RULE`：状态机或业务条件不允许。
- `NOT_FOUND`：资源不存在或当前用户不可见。

安全原则是后端强制鉴权和资源归属校验；前端隐藏菜单只改善体验，不构成访问控制。

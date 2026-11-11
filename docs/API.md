# API 学习索引

本项目使用浏览器 Session 认证。除公开商品和支付回调外，先请求 `GET /api/auth/csrf`，登录后在写请求中携带返回的 CSRF Header。前端已经自动完成该流程。

## 商城 API

| 领域 | 主要接口 | 权限 |
| --- | --- | --- |
| 认证 | `GET /api/auth/csrf`、`POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/logout` | 公开/已登录 |
| 商品 | `GET /api/products?lang=en|zh-CN` | 公开 |
| 购物车 | `GET /api/cart`、`POST /api/cart/items`、`PUT/DELETE /api/cart/items/{id}`、`DELETE /api/cart` | CUSTOMER |
| 地址 | `GET/POST /api/addresses`、`PUT/DELETE /api/addresses/{id}`、`PUT /api/addresses/{id}/default|select` | CUSTOMER |
| 订单 | `POST /api/purchase-orders/checkout`、`GET /api/purchase-orders`、`GET /api/purchase-orders/{id}` | CUSTOMER |
| 支付 | `POST /api/purchase-orders/{id}/payments` | CUSTOMER |
| 退款 | `POST/GET /api/purchase-orders/{id}/refunds`、`GET /api/refunds/{refundNumber}` | CUSTOMER，仅本人订单 |
| 收货评价 | `POST /api/purchase-orders/{id}/receive`、`POST /api/order-items/{id}/reviews` | CUSTOMER |
| 商家履约 | `GET /api/merchant/orders`、`POST /api/merchant/orders/{id}/confirm|ship` | MERCHANT |
| 用户管理 | `GET /api/users` | ADMIN |

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

# mock-payment-service

05A-2/05A-3 的独立 Spring Boot 3.5.16 模拟支付平台。它使用自己的 MySQL schema、后台账号与 Redis Session，不读写商城数据库。

核心边界：

- `PaymentAdminUser` 是 MyBatis-Plus 持久化实体，不实现 `UserDetails`。
- `PaymentAdminPrincipal` 是认证投影，实现 `UserDetails` 和 `CredentialsContainer`。
- `PaymentAdminUserDetailsService` 只负责登录加载；`PaymentAdminAccountService` 保存邮箱、MFA 策略、角色、创建人和商户范围。
- App Secret 使用 AES-256-GCM 加密，只在签发和轮换时显示一次；平台 RSA 私钥也加密保存。
- 05A-3 渠道 API 已改为 HMAC-SHA256/RSA-SHA256 签名，并使用时间戳、Nonce、请求体摘要、Redis 与 MySQL 双层防重放。
- 支付后台已实现 TOTP、一次性恢复码、Step-up 令牌和哈希链审计。

演示后台账号密码均为 `demo-password`：

| 用户名 | 角色 | 能力 |
| --- | --- | --- |
| `payment-admin` | `PAYMENT_ADMIN` | 用户、商户、凭据、交易和审计 |
| `payment-operator` | `PAYMENT_OPERATOR` | 被授权商户的交易查询和关闭 |
| `payment-auditor` | `PAYMENT_AUDITOR` | 被授权商户的交易和审计只读 |

推荐从根目录执行 `docker compose up -d --build --wait`，管理 UI 位于 `http://localhost:14201`，健康检查位于 `http://localhost:18082/actuator/health`。签名协议与验收方法见 [`docs/CHAPTER-05A-3.md`](../docs/CHAPTER-05A-3.md)。

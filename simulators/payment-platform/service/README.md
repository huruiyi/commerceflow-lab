# payment-simulator-service

05A-2/05A-3 的独立 Java 25 / Spring Boot 4.1.2 模拟支付平台。它使用自己的 MySQL schema、后台账号与 Redis Session，不读写商城数据库。

核心边界：

- `PaymentAdminUser` 是 MyBatis-Plus 持久化实体，不实现 `UserDetails`。
- `PaymentAdminPrincipal` 是认证投影，实现 `UserDetails` 和 `CredentialsContainer`。
- `PaymentAdminUserDetailsService` 在登录时加载有效权限。`PaymentAdminAccountService` 保存账号资料与初始平台角色；已有账号的角色授权由角色绑定接口管理。
- App Secret 使用 AES-256-GCM 加密，只在签发和轮换时显示一次；平台 RSA 私钥也加密保存。
- 05A-3 渠道 API 已改为 HMAC-SHA256/RSA-SHA256 签名，并使用时间戳、Nonce、请求体摘要、Redis 与 MySQL 双层防重放。
- 支付后台已实现 TOTP、一次性恢复码、Step-up 令牌和哈希链审计。

演示后台账号密码均为 `demo-password`：

| 用户名 | 角色 | 能力 |
| --- | --- | --- |
| `payment-admin` | `PAYMENT_ADMIN` | 账号、角色、商户凭据、全量账本和平台审计 |
| `payment-operator` | `PAYMENT_OPERATOR` | 支付后台访问，商户操作权限来自对应商户的角色 |
| `payment-auditor` | `PAYMENT_AUDITOR` | 平台审计，商户账本可见范围来自对应商户的角色 |

后台「角色管理」定义平台角色和商户角色的权限项。「后台账号」中的平台角色页签管理账号与角色的绑定，商户授权页签按账号、商户和角色授权。启用状态和权限配置变化会使受影响的旧会话失效。

内置角色支持编辑且不可删除。支付平台管理员角色可编辑名称和说明，权限与启用状态受保护。角色编码和授权层级在创建后固定。角色列表的禁用操作会暂停该角色的所有授权，重新启用后恢复仍处于启用状态的绑定。

V6 将旧商户范围迁移为内置商户角色。`OPERATE` 提供关单，`REFUND` 提供重发退款通知，`ADMIN` 包含两种操作，`READ_ONLY` 和 `AUDIT` 仅提供商户账本查询。交易和退款的写操作根据记录归属的商户实时校验。

推荐从根目录执行 `docker compose up -d --build --wait`，管理 UI 位于 `http://localhost:14201`，健康检查位于 `http://localhost:18082/actuator/health`。签名协议与验收方法见 [`docs/learning/CHAPTER-05A-3.md`](../../../docs/learning/CHAPTER-05A-3.md)。

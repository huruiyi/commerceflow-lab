# 05A-2：独立多渠道模拟支付平台

## 学习目标

本章把渠道侧能力从商城进程中拆出，建立独立的模拟支付平台。它不连接支付宝、微信支付或银联真实系统，也不会产生真实资金交易；重点是学习服务边界、后台身份、商户凭据、渠道适配器和收银台交互。

```text
Angular 商城（已有）                  Vue 3 支付平台（本章）
        |                                      |
        |                              Redis Session + CSRF
        |                                      |
        +---------- 后续签名请求 ----------> payment-simulator-service
                                               |
                          +--------------------+--------------------+
                          |                    |                    |
                    AlipayAdapter       WechatPayAdapter      UnionPayAdapter
                          |                    |                    |
                          +---------- 独立 payment MySQL -----------+
```

商城数据库和支付平台数据库由两个 MySQL 容器分别持有。支付服务没有商城数据源，也不能直接修改商城订单。

## 为什么不直接使用 `UserDetailsManager#createUser`

支付后台用户除了用户名和密码，还包含邮箱、手机号、MFA 策略、锁定状态、创建人、平台角色和商户数据范围。因此：

- `PaymentAdminUser` 是 MyBatis-Plus 实体，不实现 `UserDetails`；
- `PaymentAdminPrincipal` 是登录期认证投影，实现 `UserDetails`；
- `PaymentAdminUserDetailsService` 只负责按用户名加载认证信息；
- `PaymentAdminAccountService` 在事务中保存完整账户资料、角色、创建人和商户授权；
- `createdBy` 从当前 Spring Security 身份取得，不接受浏览器伪造。

主要关系：

```text
payment_admin_user --< payment_admin_user_role >-- payment_admin_role
        |
        +--< payment_admin_user_merchant >-- payment_merchant
        |
        +--< payment_admin_mfa_credential
```

## 后台安全

- 独立 `PAYMENT_ADMIN_SESSION` HttpOnly Cookie，不复用商城 Cookie；
- Session 保存到 Redis 的 `payment-platform:session` 命名空间；
- 修改请求要求 `X-PAYMENT-XSRF-TOKEN`；
- BCrypt 验证密码，登录后更换 Session ID；
- Redis 按 IP 和用户名限制短期登录次数；
- 连续五次认证失败后账号锁定十五分钟；
- App Secret 和平台 RSA 私钥使用 AES-256-GCM 加密；
- App Secret 只在首次签发或轮换响应中显示一次；
- 轮换和禁用商户要求重新输入当前后台密码；
- 登录、注销、用户、商户、凭据和交易敏感操作写入独立审计表。

`mfa_required` 与 `mfa_enabled` 分开保存。本章实现策略、数据库和 UI 状态；TOTP 绑定、恢复码和敏感操作二次验证在 05A-3 实现，UI 会明确提示该章节边界。

## 角色与数据范围

| 角色 | 平台权限 |
| --- | --- |
| `PAYMENT_ADMIN` | 用户、商户、凭据、全部交易和审计 |
| `PAYMENT_OPERATOR` | 被授权商户的交易查询与关闭 |
| `PAYMENT_AUDITOR` | 被授权商户的交易只读及审计日志 |

平台权限控制“能做什么”，`payment_admin_user_merchant.permission_scope` 控制“能操作哪个商户”。Vue 菜单隐藏只是体验优化，后端的 `@PreAuthorize` 和商户过滤才是安全边界。

## 商户凭据

商户创建响应返回：

```json
{
  "merchant": {
    "merchantNo": "EDU000000000001",
    "appId": "edu_xxx",
    "credentialVersion": 1,
    "secretMasked": "****abcd"
  },
  "appSecret": "仅本次显示",
  "warning": "之后只能轮换"
}
```

数据库只保存 `secret_cipher` 和末四位，不提供明文查询接口。平台启动时生成 RSA 密钥对，私钥加密保存，管理后台只允许下载公钥。商户公钥登记入口已经保留，05A-3 将使用它完成 RSA-SHA256 验签。

## 三渠道差异

| 渠道 | Adapter 返回动作 | Vue 体验 |
| --- | --- | --- |
| `ALIPAY` | `REDIRECT` | 跳转支付宝风格收银台 |
| `WECHAT_PAY` | `QR_CODE` | 生成真实可扫描的教学二维码 |
| `UNION_PAY` | `GATEWAY` | 跳转银联网关风格页面 |

渠道交易支持 `PENDING → SUCCESS / FAILED / CLOSED / EXPIRED`。成功交易不能关闭；相同商户请求号和相同参数返回原交易，不同参数返回 409。

本章的服务 API 使用 App ID + Demo App Secret 完成独立测试。它不是最终生产协议，05A-3 会增加 RSA/HMAC 签名、请求体摘要、时间戳、Nonce、防重放与回调白名单。

## 启动与验收

```powershell
docker compose up --build -d --wait
docker compose ps
./scripts/windows/verify-payment.ps1
```

访问：

- 商城：`http://localhost:14200`
- 支付平台与收银台：`http://localhost:14201`
- 支付服务健康检查：`http://localhost:18082/actuator/health`
- 支付 MySQL 本机端口：`13307`

演示密码均为 `demo-password`：

- `payment-admin`
- `payment-operator`
- `payment-auditor`

`scripts/windows/verify-payment.ps1`（或 Bash 版 `scripts/linux/verify-payment.sh`）会新建隔离商户并执行当前安全协议的三渠道与安全验收。脚本不依赖固定交易号。

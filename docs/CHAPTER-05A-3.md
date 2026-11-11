# 第 05A-3 章：签名、验签与防重放

本章把 05A-2 的“直接发送 App Secret”升级为接近真实支付网关的签名协议，并为支付管理后台补齐 TOTP、恢复码和高危操作二次认证。所有功能均为本地教学实现，不连接真实支付机构。

## 1. 本章完成内容

- 渠道 API 支持 `HMAC_SHA256` 和 `RSA_SHA256`。
- 每次请求必须携带时间戳、Nonce、请求 ID、请求体 SHA-256 和 Base64 签名。
- Redis 使用 `SET NX + TTL` 保存短期 Nonce，同时执行商户级分钟限流。
- MySQL 对 `(merchant_id, nonce)` 和 `(merchant_id, request_id)` 建立唯一约束，作为最终防重放边界。
- 回调地址从支付平台商户配置读取并写入白名单表，渠道请求不能临时指定任意回调地址。
- 支付后台支持 RFC 6238 TOTP、8 个一次性恢复码、5 分钟一次性 Step-up 令牌。
- 密钥轮换、商户启停、创建后台账号和分配商户权限必须消耗 Step-up 令牌。
- 审计日志通过 `previous_hash + event_hash` 串成 SHA-256 哈希链，数据库触发器禁止更新和删除。
- Vue 3 后台新增安全中心和 Web Crypto 签名实验台。

## 2. 请求签名协议

### 2.1 必填 Header

| Header | 含义 |
| --- | --- |
| `X-App-Id` | 支付平台签发的应用编号 |
| `X-Signature-Algorithm` | `HMAC_SHA256` 或 `RSA_SHA256` |
| `X-Timestamp` | Unix 秒时间戳，默认允许正负 5 分钟 |
| `X-Nonce` | 16～100 位、每次请求唯一的随机字符串 |
| `X-Request-Id` | 8～80 位请求追踪号，同一商户内唯一 |
| `X-Body-SHA256` | 原始 HTTP Body 的小写十六进制 SHA-256 |
| `X-Signature` | 签名结果的标准 Base64 |

签名原文严格按以下格式构造，字段之间是一个换行符，末尾没有额外换行：

```text
HTTP_METHOD
REQUEST_URI_WITH_QUERY
TIMESTAMP
NONCE
REQUEST_ID
BODY_SHA256
```

例如：

```text
POST
/api/channel/transactions
1794307200
052a6299cb054858b85ea61629a26e6c
754e66cc19234c6a95bd3fe841d348ee
7bc2b14cfbe568e2e088fad42fe7c02a1b3ce923266f03a24c58d1415a1f3202
```

HMAC 商户使用 App Secret 计算 `HMAC-SHA256`。RSA 商户在商城侧保存 PKCS#8 私钥，支付平台只登记 X.509 公钥并使用 `SHA256withRSA` 验签；私钥不能上传到支付平台。

## 3. 服务端验签顺序

```text
格式与时间窗口
  -> 请求体摘要
  -> 商户状态与算法
  -> HMAC/RSA 签名
  -> Redis 分钟限流
  -> Redis Nonce 原子占位
  -> MySQL 防重放记录
  -> 支付业务事务
```

签名失败时不会占用 Nonce，避免攻击者用伪造签名污染合法请求。Redis 异常时请求失败关闭，不会绕过安全控制。Redis 负责短期快速判断，MySQL 唯一约束负责最终一致的安全边界。

业务幂等和协议防重放是两件不同的事：相同 `merchantRequestNo` 可以用新的签名请求安全查询/重试并返回原交易；完全相同的 Nonce 或请求 ID 则会返回 HTTP 409。

## 4. MFA 和高危操作

用 `payment-admin / demo-password` 登录 `http://localhost:14201`，进入“安全中心”：

1. 输入当前密码，生成 TOTP 密钥。
2. 用 Google Authenticator、Microsoft Authenticator 等应用扫描二维码。
3. 输入 6 位验证码确认绑定。
4. 立即离线保存页面仅显示一次的 8 个恢复码。
5. 执行高危操作前，再输入密码和 TOTP（或一个未使用的恢复码）获取 Step-up 令牌。
6. 在 5 分钟内进入商户或用户页面执行一次操作。令牌使用后立即从 Redis 删除。

恢复码在数据库中只保存 BCrypt 哈希。重新绑定 TOTP 会让旧密钥失效；确认绑定时也会废弃旧恢复码并生成新的一组。

## 5. UI 实验

- “商户与凭据”可选择 HMAC 或 RSA 算法，RSA 必须登记公钥。
- “渠道交易”使用浏览器 Web Crypto 对精确 JSON 正文签名，密钥只留在当前页面内存，不写入 localStorage。
- “安全中心”展示 TOTP 二维码、恢复码和 Step-up 令牌状态。
- “审计日志”可以查看登录、MFA、Step-up、密钥轮换和权限变更记录。

浏览器签名只是为了观察协议。真实商城必须在可信后端或 KMS/HSM 中签名，绝不能把生产 App Secret 或私钥交给浏览器。

## 6. Docker 启动和自动验收

```powershell
docker compose up --build -d --wait
./scripts/windows/verify-payment-security.ps1
```

脚本真实验证：

- 支付宝、微信、银联三种渠道均能通过 HMAC 签名创建交易。
- 错误签名、过期时间戳、重复 Nonce、金额篡改和未知商户均被拒绝。
- TOTP 可以完成绑定并生成 8 个恢复码。
- Step-up 令牌只能使用一次，重放会被拒绝。
- 审计记录包含哈希链字段。

`scripts/windows/verify-payment.ps1` 和 `scripts/linux/verify-payment.sh` 现在转发到各自平台的同一套 05A-3 验收，因为旧的明文 Secret 协议已被安全协议替代。

## 7. 教学环境与生产差距

本章已经实现协议和数据层安全边界，但不宣称达到金融生产系统标准。生产环境还需要 HTTPS/mTLS、KMS 或 HSM、密钥双人控制和定期轮换、可信时间源、WAF/DDoS 防护、Redis/MySQL 高可用、集中不可变日志、风控、告警和正式安全审计。演示主密钥和账号可提交仅因为本项目明确限定为本地学习环境。

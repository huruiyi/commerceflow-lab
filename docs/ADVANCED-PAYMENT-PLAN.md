# 高级模拟支付实现方案

## 1. 目标和边界

本方案将当前同步成功/失败按钮升级为支付宝、微信支付和银联三渠道的教学型支付系统。实现重点是支付单、商户、签名、异步通知、幂等、状态机、Kafka 事件、补偿和故障恢复，不连接真实支付机构，也不会产生真实资金交易。

演示商户号、测试密钥和测试卡可以随公开仓库提交，但必须仅用于本地环境。真实部署必须更换商户资料，使用 HTTPS、KMS/Vault、网络访问控制、生产证书和正式支付机构 SDK。

## 2. 服务与数据边界

```text
Angular 商城
    |
    | 创建支付单、查询状态
    v
商城 backend ------------------------+
    |                                |
    | 签名支付请求                    | MySQL 业务事务
    v                                | payment/order/event/outbox
mock-payment-service                 |
    |                                |
    | 模拟收银台                      +----> Outbox Publisher
    | 支付宝 / 微信 / 银联                         |
    |                                             v
    +---- 签名异步通知 ----> 商城 backend -----> Kafka
                                                   |
                              +--------------------+------------------+
                              v                    v                  v
                           订单处理             商家通知           审计/对账
```

- 商城后端拥有商城订单、支付单、通知处理结果和 Outbox。
- 模拟支付服务拥有独立的渠道交易数据，不能直接修改商城数据库。
- MySQL 是支付和订单状态的事实来源。
- Redis 只保存 Session、Nonce、限流和可重建缓存。
- Kafka 传递已经持久化的业务事件，不承担数据库事实存储职责。

## 3. 模拟商户

| 渠道 | 商户号 | 应用编号 | 教学签名算法 |
| --- | --- | --- | --- |
| 支付宝 | `ALIPAY_MERCHANT_10001` | `2026111000000001` | RSA-SHA256 |
| 微信支付 | `1900001001` | `wx202611100001` | HMAC-SHA256 |
| 银联支付 | `898310000000001` | `UP202611100001` | RSA-SHA256 |

商户配置包含渠道、商户号、App ID、显示名称、签名算法、商户公钥、渠道公钥、API 密钥、通知地址、启用状态和密钥版本。业务日志不得输出私钥、API 密钥或完整敏感请求。

### 3.1 支付后台身份和访问安全

模拟支付平台拥有独立于商城的后台账号、Session 和权限体系，不能复用商城用户 Cookie。Vue 3 管理 UI 通过 Spring Security 登录，使用 HttpOnly Redis Session、CSRF Token、BCrypt、连续失败限流和账号临时锁定。

| 角色 | 最小权限 |
| --- | --- |
| `PAYMENT_ADMIN` | 创建商户、签发或轮换凭据、管理后台账号和权限 |
| `PAYMENT_OPERATOR` | 查询交易、执行允许的关闭和通知重试操作 |
| `PAYMENT_AUDITOR` | 只读查看交易、通知、验签结果和审计日志 |

App Secret 或 HMAC 密钥只在首次签发时显示一次，之后只能轮换，不能再次读取明文。商户 RSA 私钥由商城后端本地生成，支付平台只登记商户公钥；平台私钥永不发送到 Vue 或商城。查看一次性 Secret、轮换密钥、禁用商户和修改权限需要重新输入密码，进阶章节增加 TOTP 和恢复码。登录、登出、失败锁定、商户变更、凭据签发、轮换、禁用和权限变更都必须进入后台审计日志。

## 4. 支付状态机

```text
CREATED -> PENDING -> SUCCESS
    |         |
    +---------+----> FAILED
    +---------+----> CLOSED
    +---------+----> EXPIRED
```

- `SUCCESS` 为不可逆终态。
- 商城订单只能由经过验签和金额校验的成功通知从 `PENDING_PAYMENT` 更新为 `PAID`。
- 浏览器收银台结果只提供用户反馈，不能直接修改商城订单。
- 已关闭或过期支付单收到成功通知时进入异常和对账流程，不直接覆盖状态。
- 支付失败后允许使用新的幂等键创建新支付单。

## 5. 幂等设计

### 5.1 创建支付单

客户端必须发送 `Idempotency-Key`。商城保存以下请求指纹：

```text
SHA-256(customerId + orderId + channel + amount + currency)
```

数据库使用 `(merchant_account_id, idempotency_key)` 唯一约束：

- 幂等键和请求指纹一致时返回原支付单。
- 幂等键相同但请求指纹不同时返回 HTTP 409。
- Redis 可以快速返回缓存结果，但不能取代数据库约束。

### 5.2 渠道交易和通知

- 模拟渠道使用 `(merchant_no, merchant_request_no)` 唯一约束。
- 商城通知使用 `(channel, notification_id)` 唯一约束。
- 渠道交易号使用 `(channel, channel_transaction_no)` 唯一约束。
- 状态变更使用条件更新或行锁，重复通知返回成功确认但不重复执行业务。

### 5.3 Kafka 消费

消费者以 `(consumer_group, event_id)` 唯一约束登记处理记录。只有业务事务提交后才提交 Kafka Offset；重复投递不会重复更新订单、发送物流或生成通知。

## 6. 签名和防重放

服务间请求至少包含：

```text
merchantId, appId, timestamp, nonce, requestId, bodyHash, signature
```

验签顺序：

1. 检查商户存在并启用。
2. 检查时间戳位于允许窗口。
3. 检查 Nonce 未被使用。
4. 重新计算请求体 SHA-256。
5. 按固定字段顺序构造签名原文。
6. 使用对应商户密钥验签。
7. 校验订单、金额、币种和商户归属。
8. 验证通过后才进入业务事务。

回调地址必须来自服务端商户配置，不能接受浏览器传入的任意地址。回调接口不依赖用户 Session 或 CSRF，但必须完成渠道签名认证、限流和重放保护。

## 7. Kafka 与 Transactional Outbox

项目直接使用 Kafka KRaft，不经过 RabbitMQ。主要 Topic：

```text
payment-events
order-events
payment-callback-events
payment-reconciliation-events
merchant-notification-events
payment-events-retry-1m
payment-events-retry-5m
payment-events-retry-30m
payment-events-dlq
```

消息 Key 分别使用支付单号、订单 ID 或渠道交易号，以保证同一业务聚合内的消息顺序。生产者启用 `acks=all` 和幂等生产。

支付事务不能直接依赖一次 Kafka 发送。商城在同一 MySQL 事务中更新支付、订单、事件和 Outbox，提交后由 Outbox Publisher 重试发布。第一版使用带行锁的定时轮询；后续可以增加 Debezium 和 Kafka Connect 的 CDC 对照实现。

## 8. 高可用和补偿

默认 Docker 模式提供单节点 Kafka 和 Kafka UI，适合普通电脑学习，但不宣称高可用。高可用实验 Profile 提供：

- 三节点 Kafka KRaft，副本数 3，`min.insync.replicas=2`。
- 两个商城后端实例和两个模拟支付服务实例。
- Nginx 负载均衡。
- Resilience4j 超时、有限重试、熔断和隔离。
- ShedLock 保证超时关闭和对账任务仅由一个实例执行。
- Prometheus 指标和 Grafana 教学面板。

定时对账扫描长时间处于 `PENDING` 的支付单，主动查询模拟渠道并补偿通知丢失。单节点 MySQL、Redis 仍是实验环境单点；生产级高可用还需要数据库集群、Redis Sentinel/Cluster、跨主机 Kafka 和完善的灾备方案。

## 9. 验收矩阵

每个实现章节均需执行后端测试、前端测试、生产构建、Docker 健康检查和真实 HTTP 流程。最终至少覆盖：

- 三渠道创建、查询、成功、失败、取消、关闭和过期。
- 创建请求、渠道请求、异步通知和 Kafka 消费四层幂等。
- 错误签名、过期时间戳、重复 Nonce、金额篡改和未知商户。
- 重复、延迟、乱序和错误金额通知。
- Kafka 暂停、应用重启、通知端点暂时不可用和 Outbox 重发。
- 停止单个应用实例或 Kafka Broker 后的连续服务和最终恢复。
- 支付密钥、卡信息和敏感 Payload 不进入业务日志。

## 10. 实施顺序

严格按 `05A-0` 到 `05A-8` 实现。每一章完成验证并形成独立 Git 提交后，才开始下一章。高级支付全部验收后，再继续商家确认与发货章节。

当前进度：`05A-0`、`05A-1` 和 `05A-2` 已完成。商城已具备高级支付领域模型和数据库幂等；独立支付平台已具备独立 MySQL、Spring Security 后台、Redis Session、加密商户凭据、Vue 管理 UI、三渠道收银台和可重复 HTTP 验收脚本。请求签名、防重放和强制 TOTP 从 `05A-3` 开始实现。

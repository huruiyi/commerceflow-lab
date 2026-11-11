# 已实现能力与验证边界

## 1. 文档规则

本文档是当前源码树的已实现摘要。“已实现”表示已有代码和数据契约；实机或跨平台尚未验收的内容必须单独标注。未实现内容一律放入 [TODO.md](TODO.md)。

## 2. 基础商城

- 用户、角色、Session 登录、CSRF、登录限流和 Remember-Me。
- 商品、购物车、收货地址、下单和订单地址/商品快照。
- 客户、商家和管理员的资源权限边界。
- Angular 运行时中英文切换。

## 3. 支付

- 独立模拟支付服务与支付数据库。
- 支付宝、微信、银联三种模拟渠道与 Vue 收银台。
- 支付单、渠道交易、请求幂等、金额/币种校验。
- HMAC/RSA 签名、防重放、回调白名单、TOTP/恢复码、Step-up 与审计链。
- 异步通知、Transactional Outbox、Kafka 幂等消费、定时补偿和对账。

详见 [高级模拟支付](reference/ADVANCED-PAYMENT.md)。

## 4. 退款首版

- 只支持 `PAID/CONFIRMED` 且未发货订单的原路整单全额退款。
- 订单使用 `REFUNDING/REFUNDED`，原 `payment_record=SUCCESS` 事实不改写。
- 商城和支付平台分别建模退款单、事件、通知和对账。
- 客户申请、商家只读摘要、支付后台台账/通知补发与双前端国际化已实现。
- Windows Docker 专项验收已记录通过；Linux/WSL 实机复验、部分退款、退货/换货不在首版。
- 当前退款变更尚未提交 Git，不应与已推送版本混淆。

详见 [退款实现说明](reference/REFUND.md)。

## 5. 商家履约、收货与评价

- 商家可查看自己的订单、确认订单、录入承运商/物流单号并发货。
- 同值物流重复保存不会无限生成时间线事件。
- 客户可手动确认收货并对订单商品一次性评价。

当前“物流”仅是商城内手工记录，尚无独立物流服务、轨迹回调、自动收货或 `COMPLETED`。

## 6. 基础设施和交付

- MySQL + Flyway、Redis/Spring Session、Kafka/Outbox、ShedLock。
- Angular/Vue 前端、两个 Spring Boot 服务和 Docker Compose。
- `scripts/windows/` 与 `scripts/linux/` 保留同名验收脚本结构。

## 7. 尚未实现的总边界

以下不得在 README、页面或演示中标记为已完成：

- 客户取消未支付订单和整单超时关闭。
- Database/RabbitMQ/RocketMQ 可插拔触发和六种组合。
- 独立模拟物流、物流轨迹与签名回调。
- 物流签收后自动收货和 `RECEIVED -> COMPLETED`。
- 订单页面的摘要/折叠视觉重构。

详细顺序和验收门禁只见 [TODO.md](TODO.md)。

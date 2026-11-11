# 已支付订单取消与退款：实现说明

## 1. 文档状态

| 项目 | 内容 |
| --- | --- |
| 章节编号 | `05B` |
| 当前状态 | 首版已实现并通过 Windows Docker 自动验收 |
| 首版范围 | 未发货订单、原路全额退款、异步退款结果 |
| 不在首版 | 部分退款、按订单项退款、退货退款、换货、拒付、真实支付机构接入 |
| 最后更新 | 2026-11-12 |

本文是退款功能的设计基线、实施清单和过程记录。实现过程中若改变状态机、表结构、接口或安全边界，必须先更新本文，再修改代码。

## 2. 当前基线与问题

实施前系统已经实现支付单、渠道交易、签名异步支付通知、订单事件、Transactional Outbox 和对账，但尚未实现退款领域。以下内容作为本章的历史基线保留：

- 支付后台出现的 `REFUND` 目前只是权限名称，不代表退款能力已经存在。
- 商城和模拟支付平台都没有退款单、退款事件、退款通知或退款接口。
- 收银台的 `CANCELLED` 只关闭尚未成功的渠道交易，不是已支付订单退款。
- `payment_records.status=SUCCESS` 表示原支付成功事实，退款后也不能把它改成失败或关闭。
- 不能只把 `shop_orders.status` 改为 `CANCELLED` 或 `REFUNDED`，否则订单状态与资金状态不一致。

首版现已补齐独立退款链路；前端按钮调用退款接口，仍不得直接修改订单状态。

## 3. 首版业务规则

### 3.1 可退款条件

首版只支持原支付单的全额原路退款，并同时满足：

1. 当前用户是订单所属客户。
2. 订单状态为 `PAID` 或 `CONFIRMED`。
3. 订单尚未进入 `SHIPPED`、`RECEIVED` 或 `COMPLETED`。
4. 存在状态为 `SUCCESS` 的支付单。
5. 该支付单没有成功退款，也没有正在处理的退款。
6. 退款金额必须等于支付成功金额，币种必须一致。

`SHIPPED` 之后属于售后、退货和逆向物流范围，不能复用首版“取消并退款”接口。

### 3.2 权限边界

- `CUSTOMER`：为自己的未发货订单申请退款、查看退款进度。
- `MERCHANT`：只读查看自己商品相关订单的退款状态；首版不允许单个商家对可能包含多商家商品的整单发起退款。
- `PAYMENT_OPERATOR`：查询退款和处理允许的人工重试，不得绕过商城退款单直接修改商城订单。
- `PAYMENT_ADMIN`：配置权限和查看审计；敏感退款操作需要 MFA/Step-up。
- `PAYMENT_AUDITOR`：只读查看退款、通知和审计记录。

所有权限必须在后端校验，不能依赖前端隐藏按钮。

## 4. 状态机

### 4.1 退款单状态

```text
REQUESTED -> PROCESSING -> SUCCEEDED
    |            |
    +------------+----> FAILED
    +-----------------> CANCELLED
```

- `REQUESTED`：商城已经受理退款请求并完成幂等校验。
- `PROCESSING`：模拟支付平台已经创建渠道退款，等待异步结果。
- `SUCCEEDED`：收到验签、金额和关联关系校验均通过的成功通知。
- `FAILED`：渠道明确拒绝或达到不可自动恢复的失败条件。
- `CANCELLED`：仅允许在尚未提交渠道前关闭退款请求；不能用于撤销已经成功的退款。

`SUCCEEDED`、`FAILED` 和 `CANCELLED` 是终态。成功通知重复到达时返回成功确认，但不得重复累计退款金额。

### 4.2 订单状态

```text
PAID -------> REFUNDING -------> REFUNDED
  \               |
   \              +--退款失败--> PAID

CONFIRMED --> REFUNDING -------> REFUNDED
                    |
                    +--退款失败--> CONFIRMED
```

退款单保存 `order_status_before_refund`。进入 `REFUNDING` 后，确认、发货和重复退款必须被条件更新或行锁阻止；退款失败时恢复原状态，退款成功时进入 `REFUNDED`。

### 4.3 支付状态

原支付记录继续保持 `SUCCESS`，因为“曾经支付成功”是不可改写的历史事实。退款事实由独立退款单和聚合字段表达：

```text
payment_record.status = SUCCESS
payment_record.refunded_amount = 成功退款累计金额
refund_record.status = SUCCEEDED
```

首版全额退款完成后，`refunded_amount == payment_record.amount`。后续支持部分退款时仍沿用此模型。

## 5. 数据模型

商城后端新增 Flyway 迁移，建议从当前下一个版本开始创建：

### 5.1 `refund_records`

| 字段 | 说明 |
| --- | --- |
| `id` | 主键 |
| `refund_number` | 商城退款单号，全局唯一 |
| `order_id`、`payment_id` | 订单和原支付单 |
| `customer_id` | 发起客户，用于归属审计 |
| `idempotency_key` | 客户端幂等键 |
| `request_fingerprint` | 订单、支付、金额、币种和原因的摘要 |
| `amount`、`currency` | 退款金额和币种 |
| `reason_code`、`reason_detail` | 结构化原因和补充说明 |
| `status` | 退款状态 |
| `order_status_before_refund` | 失败时恢复订单状态 |
| `channel_refund_no` | 渠道退款号，成功创建渠道退款后写入 |
| `failure_reason` | 稳定失败原因，不保存敏感响应全文 |
| `requested_at`、`succeeded_at`、`failed_at` | 关键时间 |
| `created_at`、`updated_at`、`version` | 审计和乐观并发字段 |

关键约束：

- `UNIQUE(refund_number)`。
- `UNIQUE(payment_id, idempotency_key)`。
- `UNIQUE(channel_refund_no)`，允许提交渠道前为空。
- 金额大于零，币种与原支付单一致。
- 首版通过业务校验和条件更新保证每个支付单最多一个活动退款。

### 5.2 `refund_events` 与 `refund_notifications`

- `refund_events` 记录操作者、事件类型、旧状态、新状态、摘要和时间。
- `refund_notifications` 记录渠道通知 ID、签名校验结果、载荷摘要、处理状态和失败原因。
- `(channel, notification_id)` 必须唯一，保证通知幂等。
- 原始敏感 Payload 不进入普通业务日志；如教学实验需要保留，只保存脱敏值或摘要。

### 5.3 Outbox 与模拟支付平台

- 商城沿用 `payment_outbox`，新增 `REFUND_REQUESTED`、`REFUND_SUCCEEDED`、`REFUND_FAILED` 和 `ORDER_REFUNDED` 事件。
- 模拟支付平台新增独立 `channel_refunds` 和退款通知记录，不能直接访问商城数据库。
- 渠道退款以 `(merchant_no, merchant_refund_no)` 唯一，重复请求返回原退款结果。

已经发布的 Flyway 文件不得修改，必须新增迁移文件。

## 6. 接口设计

以下接口已在首版实现。

### 6.1 商城客户接口

```http
POST /api/purchase-orders/{orderId}/refunds
Content-Type: application/json

{
  "idempotencyKey": "<UUID>",
  "reasonCode": "CUSTOMER_CANCELLED",
  "reasonDetail": "下单信息有误"
}
```

成功受理返回 `202 Accepted` 和退款单，不直接宣称退款成功。

```http
GET /api/purchase-orders/{orderId}/refunds
GET /api/refunds/{refundNumber}
```

客户只能读取自己的退款。前端轮询或刷新查询最终结果，浏览器不能直接提交“退款成功”。

### 6.2 商家与支付后台接口

```http
GET /api/merchant/orders/{orderId}/refunds
GET /api/admin/refunds
POST /api/admin/refunds/{channelRefundNo}/notify
```

后台操作只补发已存在退款的结果通知，复用同一渠道退款号并写入审计日志；不得创建第二笔资金退款。

### 6.3 模拟支付平台接口

```http
POST /api/channel/refunds
GET /api/channel/refunds?merchantRefundNo=...
POST /api/channel/refunds/{merchantRefundNo}/notify
```

商城服务使用 App ID、时间戳、Nonce、请求摘要和 RSA/HMAC 签名调用。退款结果通过支付平台签名的服务端异步通知进入商城；查询接口用于通知丢失后的对账补偿。

## 7. 事务、幂等与并发标准

1. 接收退款请求时锁定订单和成功支付单。
2. 使用条件更新把订单从 `PAID/CONFIRMED` 改为 `REFUNDING`。
3. 在同一商城事务中写退款单、退款事件和 Outbox。
4. 提交事务后再调用模拟支付平台；网络超时不能直接判定失败。
5. 同一幂等键与相同请求指纹返回原退款单；参数不同返回 HTTP 409。
6. 渠道创建、异步通知、Outbox 发布和 Kafka 消费分别具有独立幂等约束。
7. 成功通知事务同时更新退款单、支付累计退款金额、订单状态、事件和 Outbox。
8. 任何层级重试都不能造成重复退款、重复金额累计或重复业务事件。

## 8. 异常、补偿与对账

- 渠道请求超时：保持 `PROCESSING`，通过渠道查询确认，不立即重发新的退款号。
- 通知丢失：定时任务查询长时间 `PROCESSING` 的渠道退款并补偿状态。
- 重复或乱序通知：依据唯一通知 ID、退款终态和版本字段幂等处理。
- 金额、币种、支付单或商户不匹配：拒绝更新并创建对账异常。
- 渠道成功但商城事务暂时失败：通知重试或主动对账最终收敛。
- 退款失败：记录稳定原因，并在同一事务将订单恢复为退款前状态。

Prometheus 至少记录退款请求数、成功数、失败数、处理中数量、通知验签失败数、对账修复数和处理时延。

## 9. 前端与国际化标准

- `PAID`、`CONFIRMED` 状态显示“申请取消并退款”，点击后先展示影响和原因表单。
- `REFUNDING` 只允许查看进度，禁用确认订单、发货和再次退款。
- `REFUNDED` 显示退款金额、退款单号、渠道退款号和完成时间。
- 商家页面展示只读退款状态和时间线，不能对整笔多商家订单越权退款。
- Angular 商城和 Vue 支付后台都必须提供中英文文案；状态值不得直接把英文枚举暴露给中文界面。
- 焦点、错误、加载和禁用状态遵循现有设计系统，不使用浏览器默认突兀描边。

## 10. 分阶段实施计划

### 05B-0：方案与基线（本文，已完成）

- [x] 核实当前没有退款领域实现。
- [x] 定义首版范围、状态机、数据边界和验收标准。
- [x] 明确不能通过单独修改订单状态伪造退款。

### 05B-1：商城退款领域模型

- [x] 新增退款表、事件表、通知表和必要索引。
- [x] 扩展订单状态及支付累计退款金额。
- [x] 实现退款 Mapper、Service、状态机和客户归属校验。
- [x] 覆盖幂等键冲突、重复请求和数据库唯一约束并发保护。

### 05B-2：模拟支付平台退款协议

- [x] 新增渠道退款表、签名接口和查询接口。
- [x] 支持成功、失败、通知丢失和重复通知实验；延迟故障仍属于后续扩展。
- [x] 将 `REFUND_READ` / `REFUND_OPERATE` 权限绑定到退款查询和通知补发接口。
- [x] 通知补发写入审计日志；现有后台会话与权限校验生效，独立退款 Step-up 留待后续增强。

### 05B-3：异步通知、Outbox 与对账

- [x] 验签并幂等处理退款通知。
- [x] 在事务中同步退款、支付、订单、事件和 Outbox。
- [x] 退款事件复用现有 Outbox 发布管道；退款专用下游消费者属于后续业务扩展。
- [x] 增加处理中退款的定时对账和签名通知补发。

### 05B-4：双前端与国际化

- [x] Angular 增加退款申请、进度和结果展示。
- [x] Vue 增加退款台账查询和受权限控制的通知补发。
- [x] 中英文覆盖按钮、状态、原因、错误和时间线。
- [x] 双前端生产构建通过；窄屏与辅助技术仍建议人工复验。

### 05B-5：验收与交付

- [x] 增加同名 Windows PowerShell 与 Bash 验收脚本。
- [x] 自动验证成功、请求重放、通知重放、重复申请、伪造通知和账务一致性；失败、乱序和并发压力仍可继续扩展。
- [x] Docker 镜像重建、服务健康检查和 Windows 自动验收通过；Bash 因当前 Windows 主机没有可用 WSL 发行版，待 Linux/WSL 复验。
- [x] 更新 API、README、架构和生产边界文档。

## 11. 完成定义

只有同时满足以下条件，才可以把退款功能标记为已完成：

- 商城、模拟支付平台和数据库都存在可追踪的同一笔退款。
- 原支付成功事实不被覆盖，成功退款金额与订单退款状态一致。
- 任何重复请求、通知或消息都不会重复退款。
- 退款处理中无法发货，已发货订单无法使用首版取消退款。
- 客户、商家和支付后台均通过后端资源归属与角色校验。
- 中英文页面不暴露未翻译的状态枚举。
- Windows 与 Bash 脚本覆盖主流程和关键失败场景。
- 后端测试、双前端生产构建、Docker 健康检查和空卷验收全部通过。
- 文档中的实施记录、迁移版本、接口和最终提交号已经补齐。

## 12. 实施记录

实施记录只写已经发生并且可以验证的事实，不把计划项写成完成项。每次实现按以下格式追加：

```text
日期：YYYY-MM-DD
阶段：05B-x
变更：涉及的表、接口、状态机、前端或脚本
验证：执行的测试/脚本及结果
遗留：未完成项、风险或人工验收
提交：Git 提交号；未提交时明确写“未提交”
```

### 2026-11-12：05B-0 退款基线与方案

- 变更：新增本实现计划；在总实施计划、README 和 API 索引建立退款章节入口。
- 核查：代码搜索确认商城及模拟支付平台没有退款表、退款接口、退款服务或退款通知；现有 `REFUND` 仅为后台权限值，`CANCELLED` 仅为付款前渠道交易结果。
- 决策：首版只支持 `PAID/CONFIRMED` 未发货订单的原路全额退款；支付成功记录保持不可变，退款单独建模。
- 验证：文档交叉链接与 Markdown 内容检查。
- 遗留：`05B-1` 至 `05B-5` 的业务代码和自动验收均未实现。
- 提交：未提交。

### 2026-11-12：05B-1～05B-5 首版退款链路

- 变更：商城新增 Flyway `V14__refund_workflow.sql`、退款单/事件/通知、支付累计退款金额、客户申请与查询、商家只读查询、HMAC 回调验签、Outbox 和定时对账补偿；订单执行 `PAID/CONFIRMED -> REFUNDING -> REFUNDED`，原支付状态始终保留 `SUCCESS`。
- 变更：模拟支付平台新增 `V3__channel_refunds.sql`、签名渠道退款创建/查询/通知补发接口、后台退款台账、`REFUND_READ` / `REFUND_OPERATE` 资源权限和补发审计；回调暂时不可达不会回滚已经落库的渠道退款。
- 变更：Angular 增加退款原因、申请、状态与时间线，商家页增加只读退款摘要；Vue 增加退款台账和通知补发操作；两端均补齐中英文文案。
- 变更：新增 `scripts/windows/verify-refunds.ps1` 与 `scripts/linux/verify-refunds.sh`，并接入两套 `verify-all`。
- 验证：商城 Maven 测试、支付服务 Maven 测试、Angular/Vue 生产构建、Docker 镜像重建和服务健康检查通过；Windows 退款专项验证成功退款、支付状态不改写、累计退款金额、订单状态、请求重放、第二笔冲突、伪造通知拒绝及后台补发幂等均通过。
- 遗留：当前只支持未发货整单全额退款；部分退款、退货/换货、真实支付机构、退款专用 Kafka 下游消费者、独立退款 Step-up、延迟/乱序/并发压力专项和 Linux/WSL 实机复验不在本次完成范围。
- 提交：未提交。

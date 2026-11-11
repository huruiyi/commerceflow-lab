> 归档说明：本文是准备改代码前形成的业务草案，已完整合并到 [`../TODO.md`](../TODO.md)。本文只保留原始问题与推导过程，不再维护勾选状态。

这次改造以业务闭环为主，页面继续采用当前“所有信息向下展开”的结构，不做摘要、折叠、抽屉和整体美化。

需要先明确一个关键区别：支付尝试超时，不等于订单立刻超时。客户可能第一次支付失败后继续换渠道支付，所以应分别管理“支付单有效期”和“订单支付截止时间”。

## 一、目标订单状态机

```text
PENDING_PAYMENT（待支付）
    ├─ 支付成功 ───────────────> PAID（已支付）
    ├─ 客户取消/订单超时
    │       ↓
    │  CANCELLING（取消中）
    │       ↓
    └──────────────────────────> CANCELLED（已取消）

PAID（已支付）
    ├─ 客户退款 ─> REFUNDING ─> REFUNDED
    └─ 商家确认 ───────────────> CONFIRMED（已确认）
                                      ↓
                                 SHIPPED（已发货）
                                      ↓
                                 RECEIVED（已收货）
                                      ↓
                                 COMPLETED（已完成）
```

退款失败时仍恢复到退款前的 `PAID` 或 `CONFIRMED`。

## 二、模拟物流方案

建议增加独立的 `mock-logistics-service`，但第一版不做物流管理 UI，只提供接口、自动状态推进和签名回调。

这样才算真正“接入模拟物流”，而不是在商城数据库里写几条假记录。

### 物流状态

```text
CREATED          已创建运单
PICKED_UP        已揽收
IN_TRANSIT       运输中
AT_STATION       到达配送站
OUT_FOR_DELIVERY 派送中
DELIVERED        已签收
EXCEPTION        运输异常
```

### 数据边界

模拟物流平台保存：

- 渠道运单号
- 商城运单请求号
- 承运商
- 物流单号
- 当前物流状态
- 轨迹节点
- 创建、揽收、配送、签收时间
- 回调记录和幂等信息

商城保存物流投影：

- `shipment_records`
- `shipment_events`
- 当前承运商和物流单号
- 最新物流状态
- 发货时间、物流签收时间
- 通知 ID 和回调摘要

两边通过签名 API 与签名回调通信，不能让模拟物流服务直接修改商城数据库。

### 主要接口

商城调用模拟物流：

```http
POST /api/channel/shipments
GET  /api/channel/shipments?merchantShipmentNo=...
POST /api/channel/shipments/{merchantShipmentNo}/advance
```

模拟物流回调商城：

```http
POST /api/logistics-notifications/callback
```

客户查询：

```http
GET /api/purchase-orders/{orderId}/logistics
```

### 客户页面展示

订单为 `SHIPPED`、`RECEIVED` 或 `COMPLETED` 时，在收货地址下面增加始终展开的物流区域：

```text
物流信息

京东快递
JD154524434342457668    复制单号

2026-11-12 00:44  商家已发货
2026-11-12 00:46  快递已揽收
2026-11-12 08:20  运输中
2026-11-13 09:10  派送中
2026-11-13 13:40  已签收
```

物流签收只说明快递渠道认为包裹已经送达，不应直接等同于客户确认收货。

## 三、未支付订单主动关闭

新增客户接口：

```http
POST /api/purchase-orders/{orderId}/cancel
```

只允许订单处于 `PENDING_PAYMENT` 时调用。

### 关闭流程

1. 锁定订单并检查客户归属。
2. 把订单从 `PENDING_PAYMENT` 改为 `CANCELLING`。
3. 查询该订单全部仍为 `PENDING` 的支付尝试。
4. 使用原渠道和渠道交易号调用支付平台关闭接口。
5. 所有待处理交易均关闭后，把订单改为 `CANCELLED`。
6. 写入订单事件和 Outbox。
7. 重复请求返回原取消结果，不重复关闭渠道交易。

如果订单没有支付记录，或者所有支付记录都已是 `FAILED/CLOSED/EXPIRED`，可以直接进入 `CANCELLED`。

如果已经存在成功支付，拒绝普通取消，提示客户使用退款流程。

### 取消中的并发规则

取消与支付成功可能同时发生，因此引入短暂的 `CANCELLING` 是必要的：

- 渠道先关闭成功：订单进入 `CANCELLED`。
- 支付成功通知先到：支付优先，订单进入 `PAID`，取消操作失败。
- 已取消订单意外收到成功通知：不得忽略，应创建资金对账异常。第一版建议恢复为 `PAID` 并记录异常，后续可以升级为自动原路退款。

## 四、超时未支付订单

### 增加订单级支付截止时间

在订单表增加：

```text
payment_due_at
cancelled_at
cancel_reason
```

创建订单时设置：

```text
payment_due_at = created_at + 30 分钟
```

时长通过配置控制：

```yaml
commerceflow:
  order:
    payment-timeout: 30m
```

### 支付尝试超时与订单超时的区别

- 单次支付记录 `expires_at` 到期：该次支付变为 `EXPIRED`。
- 只要订单的 `payment_due_at` 未到，客户仍可发起新的支付尝试。
- 订单支付截止时间到达后，才自动关闭整张订单。

当前系统只会检测过期支付并创建对账问题，不会真正关闭订单，这部分需要补齐。

### 自动关单任务

新增 `UnpaidOrderExpirationJob`：

- 每分钟运行一次。
- 使用 ShedLock 防止多实例重复处理。
- 分批查找已经超过 `payment_due_at` 的 `PENDING_PAYMENT` 订单。
- 执行与客户主动取消相同的渠道关闭流程。
- 最终写入：

```text
PENDING_PAYMENT -> CANCELLING -> CANCELLED
eventType = ORDER_PAYMENT_EXPIRED
cancelReason = PAYMENT_TIMEOUT
```

不能在数据库里直接批量执行一条 `UPDATE ... SET status='CANCELLED'`，否则支付平台仍可能存在可成功支付的交易。

## 五、`COMPLETED` 如何引入

`RECEIVED` 和 `COMPLETED` 必须有不同业务含义，否则没有必要增加 `COMPLETED`。

建议定义：

- `RECEIVED`：客户已确认收货，或者物流签收后系统自动确认；评价和观察期仍开放。
- `COMPLETED`：收货后的观察期结束，订单正式成为终态，只保留查询、评价和历史信息。

数据库增加：

```text
received_at
completed_at
logistics_delivered_at
```

### 转换规则

客户主动确认：

```text
SHIPPED -> RECEIVED
received_at = 当前时间
```

物流已经 `DELIVERED`，但客户一直没有确认：

```text
DELIVERED + 7 天 -> 自动 RECEIVED
```

订单收货后：

```text
RECEIVED + 7 天 -> COMPLETED
```

时间通过配置控制：

```yaml
commerceflow:
  order:
    auto-receive-after-delivery: 7d
    auto-complete-after-receipt: 7d
```

为了教学验收，可以在测试环境设置成几秒或直接调整数据库时间。

### `COMPLETED` 后允许的操作

- 查看订单
- 查看支付记录
- 查看物流轨迹
- 查看退款历史
- 对未评价商品进行评价

第一版不允许：

- 取消订单
- 修改物流
- 确认收货
- 使用“未发货退款”接口

`COMPLETED` 不应依赖“所有商品都已经评价”。评价是可选行为，不应阻止订单完结。

需要说明：当前系统还没有退货、换货和售后期限，因此 `COMPLETED` 第一版主要承担“稳定终态和历史归档”的作用。以后实现售后时，可以把 `RECEIVED -> COMPLETED` 的观察期正式定义为售后申请期。

## 六、页面改造范围

按你的要求，暂时保持所有信息始终展开。

推荐顺序：

```text
订单头部
商品列表
收货地址
当前可用操作
物流信息
支付记录
退款信息
订单时间线
商品评价
```

各状态显示内容如下：

| 状态 | 页面操作与信息 |
| --- | --- |
| `PENDING_PAYMENT` | 支付、刷新支付状态、取消订单、支付尝试记录 |
| `CANCELLING` | 显示正在关闭支付交易，禁用支付与重复取消 |
| `CANCELLED` | 显示取消原因、取消时间、历史支付尝试 |
| `PAID` | 支付记录、申请退款 |
| `CONFIRMED` | 支付记录、申请退款、商家确认信息 |
| `SHIPPED` | 支付记录、物流轨迹、复制单号、确认收货 |
| `RECEIVED` | 支付记录、完整物流轨迹、收货时间、评价 |
| `COMPLETED` | 支付记录、完整物流轨迹、完成时间、评价 |
| `REFUNDING` | 原支付记录、退款进度 |
| `REFUNDED` | 原支付记录、退款记录、退款金额 |

这一阶段不处理退款区域美化、评价表单美化、订单摘要或折叠。

## 七、后端动作能力

建议订单响应增加 `availableActions`，避免 Angular 自己到处判断状态：

```json
{
  "status": "SHIPPED",
  "availableActions": [
    "VIEW_PAYMENT",
    "VIEW_LOGISTICS",
    "COPY_TRACKING_NUMBER",
    "CONFIRM_RECEIPT"
  ]
}
```

后端仍然必须重新校验状态和权限，`availableActions` 只是告诉页面应该展示什么。

## 八、文档计划

新增两份核心文档：

- `docs/ORDER-LIFECYCLE.md`
  - 所有订单状态
  - 客户、商家、系统允许的动作
  - 状态转换条件
  - 主动取消、超时关闭、自动收货和自动完成
  - 支付、订单、退款、物流四套状态的区别

- `docs/MOCK-LOGISTICS.md`
  - 模拟物流架构
  - 签名协议
  - 物流状态机
  - 轨迹自动推进
  - 回调幂等、丢失补偿和对账
  - Docker 启动及验收方法

并同步更新：

- README
- API 索引
- IMPLEMENTATION-PLAN
- Windows/Bash 脚本说明

## 九、建议实施阶段

### 阶段 1：订单生命周期基础

- 新迁移增加支付截止、取消、收货和完成时间。
- 增加 `CANCELLING`、`CANCELLED`、`COMPLETED`。
- 后端统一计算 `availableActions`。
- 新增订单生命周期文档。

### 阶段 2：未支付订单关闭

- 客户主动取消。
- 支付平台关闭全部待处理交易。
- 超时订单定时关闭。
- 处理取消与成功支付并发。
- 增加取消、超时、重复请求验收。

### 阶段 3：模拟物流

- 新建独立 `mock-logistics-service` 和独立数据库。
- 签名创建运单、查询运单和自动推进状态。
- 商城物流记录、事件、签名通知和对账。
- 客户订单接口返回物流信息。

### 阶段 4：收货与完成

- 客户确认收货记录 `received_at`。
- 物流签收后自动确认收货。
- `RECEIVED` 定时进入 `COMPLETED`。
- `COMPLETED` 继续允许一次性评价。

### 阶段 5：客户页面功能

- 增加取消未支付订单按钮。
- 增加始终展开的物流信息和轨迹。
- 所有后续状态继续显示支付记录。
- 显示取消原因、收货时间、完成时间。
- 暂不改整体视觉结构。

### 阶段 6：验收与文档

覆盖：

- 主动取消无支付订单
- 主动取消有待支付交易的订单
- 重复取消幂等
- 支付成功与取消并发
- 单次支付过期但订单仍可重试
- 整单支付超时自动关闭
- 物流轨迹自动推进
- 客户越权查询物流
- 手动与自动确认收货
- 自动进入 `COMPLETED`
- 完成后仍可查看支付、物流并评价
- Windows、Bash 和空数据卷 Docker 重建

这套方案先把订单、支付、物流和履约生命周期补完整，现有退款和评价 UI 暂时不美化。等这些基础状态和数据都稳定后，再统一做订单卡片摘要、折叠详情和视觉整理。

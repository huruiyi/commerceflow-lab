# 支付第一版远程契约

## 1. 目的与当前边界

Payment V1 是商城主服务与未来支付编排服务之间的第一版稳定交互格式。它覆盖创建支付、按订单查询支付/退款、
关闭待支付记录，以及支付或退款回调处理结果。当前仍由 commerce 内唯一的本地适配器实现，尚未创建
payment-orchestration-service、payment-db 或远程 HTTP 入口；以后创建独立服务时整体替换该适配器，不保留
LOCAL/COMPARE 双实现。

退款属于同一个资金边界，不另建 refund-service。ordering 与 fulfillment 只能依赖本文件描述的 V1 数据结构，
不得导入 payment/refund 的 application、domain、infrastructure 或 Mapper。

## 2. 创建支付命令

`PaymentCreateCommandV1` 包含版本、命令编号、发生时间、生产者、客户/订单稳定 ID、订单号、渠道、金额、币种、
支付截止时间、业务幂等键、故障实验标志、关联/因果编号和 W3C `traceparent`。

- `schemaVersion` 固定为 `1`；不兼容变更必须新增版本。
- `commandId` 标识本次传输，`idempotencyKey` 标识客户业务意图；相同幂等键和相同请求指纹返回原支付。
- 相同幂等键但订单、渠道、金额、币种或故障标志不同必须拒绝。
- 金额必须大于零，订单和客户使用稳定 ID，不传递数据库实体或 Mapper 类型。
- HTTP 超时或连接中断表示结果未知，不得直接创建第二笔支付；调用方应按幂等键或订单查询。

## 3. 查询与只读快照

`PaymentQueryV1` 以 `schemaVersion + orderId` 查询。结果使用 `PaymentSnapshotV1` 和 `RefundSnapshotV1`，只暴露调用方
需要的稳定字段；内部主键、请求指纹、乐观版本和 Mapper 对象不进入契约。查询不到返回空结果，不把空结果解释为
支付失败。

## 4. 关闭待支付命令

`PaymentCloseCommandV1` 包含稳定命令编号、订单 ID、关闭原因及追踪信封。处理顺序为先请求渠道关闭，再条件关闭
本地待支付记录。结果 `PaymentCloseResultV1` 只有两种业务结论：

| 结果 | 中文含义 | 订单侧动作 |
| --- | --- | --- |
| `CLOSED` | 待支付记录已关闭或本来就没有待关闭记录 | 可继续完成订单取消 |
| `PAYMENT_ALREADY_SUCCEEDED` | 关闭过程中已经观察到成功支付 | 支付成功优先，订单恢复为已支付 |

命令重复执行必须收敛到相同业务事实。网络超时不是 `CLOSED`，必须进入重试、查询或人工恢复。

## 5. 支付与退款结果

`PaymentResultV1` 用于未来跨进程结果投递，结果类型覆盖支付创建/成功/失败/关闭和退款成功/失败；结果结论为
`SUCCEEDED`、`REJECTED` 或 `RECONCILIATION_REQUIRED`。每条结果必须携带稳定 `resultId`、支付单号、来源状态、
关联/因果编号和 Trace；退款结果还必须携带退款单号。非成功结论必须有稳定错误码。

结果消费者必须以 `resultId + payload fingerprint` 实现幂等：完全重复可返回原处理结果，同一 resultId 不同载荷
必须拒绝并告警。迟到成功不能被订单取消结果覆盖。

## 6. 兼容性证据

兼容样例位于 `services/commerce-service/src/test/resources/contracts/payment/`：

- `payment-create-command-v1.json`
- `payment-close-command-v1.json`
- `payment-close-result-v1.json`
- `payment-success-result-v1.json`

集成测试会反序列化、校验必填字段并计算稳定 SHA-256 指纹。当前本地适配器只是一条迁移接缝，不代表已完成远程
调用、影子迁移或正式业务切换。

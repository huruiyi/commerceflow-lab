# payment-orchestration 最终切换 GO/NO-GO 评审

## 1. 评审结论

截至 M3.6 第十批，结论为 **NO-GO**，不得执行 CUTOVER。

数据一致性、停写和恢复演练已经满足切换前要求，但运行拓扑仍保留 commerce 本地支付/退款实现、旧支付与退款
回调以及支付源表权限。它们只能与唯一远程适配器替换在同一个受控窗口内原子变更，不能通过并存实现或分批切换
提前消除。

## 2. 已满足的门禁

| 门禁 | 当前证据 |
| --- | --- |
| 停写与排空 | Windows、Ubuntu 26.04 WSL 均验证创建支付、关闭支付、发起退款返回 503，签名回调继续收敛 |
| 最终快照 | generation 15，源水位 603，14 张表全部 `MATCH` |
| 孤儿隔离 | `OPEN=0`，历史演练孤儿均为 `RESOLVED` |
| 支付 Outbox | NEW/RETRY/PROCESSING 为 0 |
| 命令 Outbox | NEW/RETRY/IN_FLIGHT 为 0 |
| Process Manager | 未完成记录为 0 |
| 权限演练 | 合成账号冻结拒绝、源所有者权限恢复通过 |
| 故障恢复 | payment-orchestration-service 停机、启动及核对恢复通过 |
| 安全默认值 | `PAYMENT_WRITE_FROZEN=false`，独立服务为 `false/false/true`，PostgreSQL 为 0 |
| 回归 | commerce 58 项测试、55 对脚本契约、完整快速回归通过 |

## 3. 仍阻塞 GO 的事项

1. commerce 仍包含唯一 `LocalPaymentOrchestrationAdapter` 和本地支付/退款 Service、Mapper、渠道 Gateway、对账路径。
2. 支付与退款回调仍指向 commerce，旧回调 Controller 仍是正式所有者。
3. `commerce_runtime` 仍拥有 14 张 payment/refund 源表的运行权限。
4. 唯一远程 `PaymentOrchestrationV1` 尚未在删除本地实现的同一提交中替换。

以上任一项存在时，自动 CUTOVER 审计都必须输出 `REJECTED`。不得通过删除审计、临时放宽权限或增加
LOCAL/REMOTE/COMPARE 绕过。

## 4. 允许进入 GO 的条件

只有获得明确的 CUTOVER 授权和受控变更窗口后，才允许按
[一次性切换运行手册](PAYMENT-ORCHESTRATION-CUTOVER-RUNBOOK.md)执行：停写、最终追平、停止 commerce、唯一远程
适配器替换与本地路径删除、回调改址、源表撤权、关闭影子并开门、真实支付/退款烟雾验收。步骤必须整体成功；
独立库接收新写后不得直接回退旧库。

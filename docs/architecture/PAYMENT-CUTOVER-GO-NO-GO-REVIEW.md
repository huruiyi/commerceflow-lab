# payment-orchestration 最终切换 GO/NO-GO 评审

## 1. 评审结论

M3.6 第十批的历史结论为 **NO-GO**。2026-11-18 用户明确授权后，第十一批按受控窗口完成全部阻塞项，
当前结论更新为 **GO / CUTOVER 已完成**。

切换窗口已原子替换为唯一远程适配器，删除 commerce 本地支付/退款实现和旧回调，并撤销支付源表权限；没有经历
LOCAL/REMOTE/COMPARE 并存阶段。

## 2. 已满足的门禁

| 门禁 | 当前证据 |
| --- | --- |
| 停写与排空 | Windows、Ubuntu 26.04 WSL 均验证创建支付、关闭支付、发起退款返回 503，签名回调继续收敛 |
| 最终快照 | generation 16，源水位 610，14 张表全部 `MATCH` |
| 孤儿隔离 | `OPEN=0`，历史演练孤儿均为 `RESOLVED` |
| 支付 Outbox | NEW/RETRY/PROCESSING 为 0 |
| 命令 Outbox | NEW/RETRY/IN_FLIGHT 为 0 |
| Process Manager | 未完成记录为 0 |
| 权限演练 | 合成账号冻结拒绝、源所有者权限恢复通过 |
| 故障恢复 | payment-orchestration-service 停机、启动及核对恢复通过 |
| 安全默认值 | `PAYMENT_WRITE_FROZEN=false`，独立服务为 `true/true/false`，PostgreSQL 为 0 |
| 回归 | commerce 48 项、payment 6 项、payment simulator 9 项测试；56 对脚本契约；Windows/WSL 快速回归通过 |

## 3. 已关闭的 GO 阻塞

1. 本地支付/退款 Service、Mapper、渠道 Gateway、对账路径及旧适配器已删除。
2. 支付与退款回调已同时改投独立服务，旧回调 Controller 已删除。
3. `commerce_runtime` 对 14 张 payment/refund 源表权限已归零并实测拒绝。
4. commerce 已由唯一 `RemotePaymentOrchestrationAdapter` 调用 Payment V1。

自动 CUTOVER 审计现输出 `APPROVED`，并继续禁止临时放宽权限或增加 LOCAL/REMOTE/COMPARE。

## 4. GO 的执行结果

本次已按[一次性切换运行手册](PAYMENT-ORCHESTRATION-CUTOVER-RUNBOOK.md)完成停写、最终追平、停止 commerce、
唯一远程适配器替换与本地路径删除、回调改址、源表撤权、关闭影子并开门、真实支付/退款烟雾验收。独立库已
接收新写，因此不得直接回退旧库。

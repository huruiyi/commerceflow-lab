# payment-orchestration 一次性切换运行手册

## 1. 当前评审结论

截至 M3.6 第八批，CUTOVER 仍为 **REJECTED**，普通支付/退款流量继续由 commerce 的唯一
`LocalPaymentOrchestrationAdapter` 处理。远程 Payment V1、退款发起、Order V1/Inventory 副作用和可靠
Outbox 已具备；commerce 的退款 POST 已先收口到同一个 `PaymentOrchestrationV1` 契约，但没有增加远程实现、
选择器或双写。

真正切换必须在一个受控窗口内原子完成，不能拆成“先开回调”“先开远程写”或“保留本地兜底”几个长期阶段。

## 2. 切换前必须为零

1. payment-db 的 14 张所有权表完成最终增量追平，逐表行数和指纹全部 `MATCH`。
2. `payment_outbox` 无 NEW/RETRY/PROCESSING，`payment_command_outbox` 无 NEW/RETRY/IN_FLIGHT，Process Manager
   无超时或待人工恢复，孤儿隔离 OPEN 为 0。
3. commerce 源端没有正在提交的支付创建、关闭、回调、退款和退款回调事务。
4. Payment V1、渠道回调、Order V1、Inventory COMMIT/REFUND 使用的独立服务身份和密钥均已验证。
5. Windows、Ubuntu WSL 的预切换、远程协议、副作用重试、服务/数据库停机恢复和快速回归全部通过。

## 3. 单窗口原子顺序

1. 进入停写：以 `PAYMENT_WRITE_FROZEN=true` 重启 commerce，阻止新支付、订单取消中的支付关闭和退款发起；
   等待当前资金事务结束。支付/退款签名回调不经过该闸门，必须继续排空。
2. 保持 payment 的流量门和副作用门关闭，执行最后一次快照/增量追平和 14 表水位、指纹、孤儿核对。
3. 停止 commerce-service，确保旧回调 Controller 和本地调度任务不再消费新请求。
4. 部署只含唯一远程 `PaymentOrchestrationV1` 适配器的 commerce 镜像；同时删除本地支付/退款 Service、Mapper、
   回调 Controller、对账任务和渠道 Gateway 运行路径，不保留 LOCAL/REMOTE/COMPARE。
5. 把支付平台的支付与退款回调一次性改到 payment-orchestration-service。
6. 在 `commerce_runtime` 授权清单中排除 14 张 payment/refund 源表，并验证 SELECT/INSERT/UPDATE/DELETE 均拒绝。
7. 关闭影子复制，打开 payment 流量门和副作用门，再启动唯一远程 commerce 调用方。
8. 用一笔受控支付和一笔全额退款验证：Payment V1、签名回调、Order V1、Inventory、Trace 和幂等重放。
9. 验收通过后解除停写；源表只保留为历史证据，不是运行回退路径。

步骤 3 至 7 必须使用同一提交和同一变更窗口。任一步失败且 payment-db 尚未接收新业务写时，可恢复原提交、
原回调和原授权；一旦独立库接收新写，禁止直接切回旧库。

## 4. 必须删除和必须保留

必须删除的是 commerce 的本地支付/退款业务实现、Mapper/XML、支付与退款回调、渠道 Gateway、资金对账任务以及
对 14 张源表的运行权限。历史 Flyway 不改写，源表不删除。

必须保留的是 commerce 的客户/商家 HTTP 薄入口、Session/RBAC/CSRF、稳定 Payment V1 契约、订单侧调用端口和
跨服务签名客户端。`RefundController` 是身份薄入口，不再被视为本地资金所有者；它只能调用
`PaymentOrchestrationV1`。

## 5. 验收与停止规则

| 门禁 | 通过标准 |
| --- | --- |
| 数据 | 14 表最终水位、行数、指纹一致，OPEN 孤儿/恢复为 0 |
| 单一实现 | commerce 只有一个远程适配器，无本地/比较/数据库回退 |
| 回调 | 支付与退款回调只指向独立服务，旧 Controller 不存在 |
| 权限 | commerce 运行账号对 14 张源表无任何表权限 |
| 可靠性 | 重复、超时、结果丢失、服务/数据库重启均以原业务 ID 收敛 |
| 副作用 | 支付、取消竞态、退款的订单和库存状态正确且可恢复 |
| 回归 | Java、Flyway、Windows/Bash、快速回归和代码质量全部通过 |

停写闸门必须先于订单 `CANCELLING` 状态变更生效。使用
`verify-payment-write-freeze.ps1/.sh` 验证创建、关闭、退款均返回 503、业务事实不变且旧回调可继续收敛；脚本退出时
必须恢复 `PAYMENT_WRITE_FROZEN=false`。

任一门禁失败都停止切换。不得用打开本地兜底、恢复源表权限或同时运行两套回调来绕过失败。

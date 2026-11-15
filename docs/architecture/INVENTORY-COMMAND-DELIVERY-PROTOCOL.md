# inventory V1 命令投递、结果与补偿协议

## 1. 当前实施状态

四类命令统一经过 `InventoryCommandGateway`。Gateway 在调用方业务事务内写入
`inventory_command_delivery_outbox`，通过当前唯一的本地 Handler 执行库存事务，记录
`inventory_command_result_inbox`，最后把投递状态标记为 `ACKNOWLEDGED`。

当前没有远程 inventory-service，也没有第二套库存实现；Outbox 与结果 Inbox 用于冻结将来的跨进程协议和
保留真实运行证据。切换远程适配器前，不能把当前状态描述为异步投递或独立服务验收完成。

V33 已补齐生产侧业务等待态：订单和退款各自持有 inventoryStatus，传输状态仍只属于 delivery Outbox。
达到重试上限时业务状态投影为 RECONCILIATION_REQUIRED，管理员只能使用原 commandId 和原 payload 恢复。
V34 要求恢复请求携带原因，并在 delivery 记录保存操作者、原因和时间。

## 2. 稳定标识与幂等

- `commandId` 是生产端 Outbox、inventory 消费 Inbox 和结果 Inbox 的共同幂等键；
- 语义指纹只覆盖命令类型、producer、orderId、reason 和商品项，不把重试时可能变化的 occurredAt、
  traceparent 等传输元数据误判成业务载荷冲突；
- 同 commandId、同语义载荷返回既有结果，不重复修改库存；同 commandId 异语义载荷拒绝；
- `InventoryCommandResultV1` 固定 resultId、commandId、commandType、SUCCEEDED/REJECTED、errorCode、
  correlation/causation 和 traceparent；超时不是 REJECTED 结果。

## 3. 投递状态机

| 状态 | 含义 | 允许后继 |
| --- | --- | --- |
| `NEW` | 命令已与生产者业务事实一同持久化，尚未开始远程投递 | `IN_FLIGHT`、当前本地适配器直接 `ACKNOWLEDGED` |
| `IN_FLIGHT` | 已发送，等待同 commandId 的明确结果；结果未知时不得重建新 ID | `ACKNOWLEDGED`、`RETRY`、`RECONCILIATION_REQUIRED` |
| `RETRY` | 可使用原 commandId 重投 | `IN_FLIGHT`、`ACKNOWLEDGED` |
| `ACKNOWLEDGED` | 已收到并持久化 SUCCEEDED 或 REJECTED 结果 | 终态 |
| `RECONCILIATION_REQUIRED` | 达到尝试上限仍无明确结果，必须按 commandId 查询/人工处理 | 对账后受控恢复，不自动假定失败 |

`DEAD` 不用于“结果未知”的业务命令。基础设施无法解析的毒消息可以进入传输层 DLT，但生产端业务状态仍必须
保留为需要对账，防止消费者其实已经提交而生产者再次执行相反补偿。

## 4. 四类命令的结果与补偿

| 命令 | SUCCEEDED | REJECTED | 超时或未知结果 |
| --- | --- | --- | --- |
| `RESERVE` | 订单可进入待支付 | 库存不足时取消本次下单事务或把未来异步订单转为库存拒绝终态 | 先按 commandId 查询；不得重新生成预占命令，也不得盲目 RELEASE |
| `COMMIT` | 支付库存由预占转已售 | 进入支付/库存不一致对账，禁止发货 | 订单支付事实不回滚；阻止履约并以原 commandId 重试/查询 |
| `RELEASE` | 取消订单的预占已释放 | 状态冲突进入对账，不恢复已取消订单 | 原 commandId 安全重试；不得因为未知结果重新开放订单 |
| `REFUND` | 已售库存回到可售 | 财务退款事实保留，库存进入对账 | 不自动撤销已成功退款；以原 commandId 重试直到明确结果或人工处理 |

## 5. 当前事务边界与远程切换门禁

当前本地适配器在一个数据库事务中完成生产端 Outbox、inventory Inbox、余额/流水、结果 Inbox 和 ACK，任何
业务异常整体回滚，保持现有 checkout、支付、取消和退款语义。

V33 已完成订单等待库存结果、COMMIT 履约阻断、退款库存待对账和管理恢复入口。远程切换前仍须完成：

1. 将各生产者 Outbox 放回其未来所属服务事务，不能让多个独立服务共享 commerce 表；
2. 实测重复、乱序、消费者提交后响应丢失、生产者重启、消费者停机、DLT 和对账恢复；
3. 完成跨进程 Trace 和指标；
4. 以上门禁通过后才允许创建独立 inventory-service/inventory-db，仍使用 MySQL。

PostgreSQL 继续受全服务单一所有权完成后的总门禁约束。

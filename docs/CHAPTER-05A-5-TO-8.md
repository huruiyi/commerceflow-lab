# 05A-5～05A-8：可靠支付事件、收银台、高可用与验收

## 学习路线

1. 从 `PaymentNotificationService` 阅读验签、金额校验和支付状态机。
2. 阅读 `payment_outbox` 与 `PaymentEventPipeline`，理解“业务事务先落库、Kafka 最终投递”。
3. 从商城订单页创建三种渠道支付，在 Vue 收银台完成支付，再刷新订单状态。
4. 阅读 `PaymentGatewayClient` 的 Retry、CircuitBreaker、Bulkhead，以及 `PaymentReconciliationJob` 的 ShedLock。
5. 在 Kafka UI、Prometheus 和 Grafana 中观察事件与指标，并执行故障实验。

## 默认单节点实验

```powershell
docker compose up --build -d --wait
docker compose ps
./verify-payment-notifications.ps1
./verify-kafka-payment.ps1
./verify-payment-outcomes.ps1
```

入口：商城 `http://localhost:14200`，支付平台 `http://localhost:14201`，Kafka UI `http://localhost:14202`。商城客户账号为 `demo@example.com` / `demo-password`。

带监控启动：

```powershell
docker compose --profile observability up --build -d --wait
```

Prometheus 为 `http://localhost:19090`，Grafana 为 `http://localhost:13000`，Grafana 演示账号 `admin` / `commerceflow-grafana`。

## 三节点 Kafka 与双后端实验

仅建议从干净的教学数据卷启动 HA 拓扑，以免单节点 Kafka 元数据与固定集群 ID 冲突：

```powershell
docker compose down --volumes
docker compose -f compose.yaml -f compose.ha.yaml --profile observability up --build -d --wait
```

停止 Kafka 单节点或一个后端容器，确认其余实例可服务；恢复后检查 Outbox 从 `RETRY` 变为 `PUBLISHED`，且 `kafka_consumed_event` 对同一 `(consumer_group,event_id)` 只有一行。

## 三渠道测试

- 支付宝：创建支付后打开跳转收银台，确认成功回调。
- 微信支付：订单页展示二维码 payload，打开收银台模拟扫码完成。
- 银联：打开网关页面使用页面标注的模拟测试卡；不连接真实银行。
- 异常：可测试用户取消、失败、超时、重复回调和错误签名。浏览器结果不会直接改订单，只有服务端验签回调可以使订单进入 `PAID`。

## 可观测与故障处理

- `/actuator/prometheus` 提供 JVM、HTTP、Resilience4j 和对账指标。
- `commerceflow_payment_reconciliation_detected_total` 记录发现的超时异常，`commerceflow_payment_reconciliation_open` 表示未处理数量。
- 支付平台暂时不可用时，有限重试后熔断，调用者得到明确错误；不要无限重试同一非幂等请求。
- Kafka 不可用时，回调事务仍保存 Outbox；发布器定时重试，数据库仍是事实来源。
- 定时对账由数据库 ShedLock 互斥，多后端实例不会重复执行同一轮任务。

## 生产边界

这是模拟支付与应用层高可用教学实验，不是生产支付系统。演示商户号、Secret、测试卡和 Grafana 密码可以提交，仅能用于本地。生产环境还需要真实机构 SDK、HTTPS/mTLS、KMS/HSM、密钥轮换、PCI/等保审计、跨可用区数据库与 Kafka、灾备演练、告警值班和正式清结算流程。

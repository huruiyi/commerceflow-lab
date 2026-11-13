# CommerceFlow 告警管道

## 1. 实现边界

本地 `observability` Profile 已形成可运行的告警链：

```text
commerce-service / payment-simulator-service / logistics-simulator-service
    -> Prometheus 抓取与规则计算
    -> Alertmanager 分组、抑制和重复间隔
    -> alert-webhook 接收通知
    -> Grafana 查询 Prometheus 与 Alertmanager
```

`alert-webhook` 是学习环境中的外部通知接收端模拟器，保存最近 100 次通知于内存，容器重建后清空。它证明 Webhook 协议和路由链真实可用，但不等同于已经接入企业邮箱、短信、电话、Slack、Teams 或值班平台。生产部署应把 [alertmanager.yml](../infrastructure/monitoring/alertmanager.yml) 中的接收地址替换为受 TLS、鉴权和密钥管理保护的真实接收端。

## 2. 启动与入口

默认业务栈不自动启动监控组件，也不增加日常资源占用。显式启用：

```powershell
docker compose --profile observability up -d --wait
```

| 组件 | 地址 | 作用 |
| --- | --- | --- |
| Prometheus | `http://localhost:19090` | 指标、Targets、Rules 和 Alerts |
| Alertmanager | `http://localhost:19093` | 告警分组、抑制、静默和通知状态 |
| Grafana | `http://localhost:13000` | 已预置 Prometheus 与 Alertmanager 数据源 |
| 本地 Webhook | `http://localhost:18085/api/alerts` | 查看最近收到的 Alertmanager 通知 |

Grafana 教学账号为 `admin` / `commerceflow-grafana`。这些端口和演示凭据只适用于本地学习环境。

## 3. 告警规则

规则位于 [commerceflow-alerts.yml](../infrastructure/monitoring/rules/commerceflow-alerts.yml)。

| 告警 | 条件 | 等待 | 级别 |
| --- | --- | --- | --- |
| `CommerceFlowWatchdog` | 固定心跳 | 20 秒 | `none` |
| `CommerceFlowServiceDown` | 三个 Spring Boot 服务任一抓取失败 | 1 分钟 | `critical` |
| `CommerceFlowHighServerErrorRate` | HTTP 5xx 比例超过 5% | 5 分钟 | `warning` |
| `CommerceFlowJvmHeapPressure` | JVM Heap 超过 90% | 10 分钟 | `warning` |
| `CommerceFlowScheduledTaskFailures` | 5 分钟内出现任务执行失败 | 立即 | `warning` |
| `CommerceFlowMqPublishFailures` | 5 分钟内 RabbitMQ/RocketMQ 发布失败 | 立即 | `warning` |
| `CommerceFlowRefundBacklog` | 存在处理中的退款 | 10 分钟 | `warning` |
| `CommerceFlowPaymentReconciliationBacklog` | 存在未关闭支付对账问题 | 10 分钟 | `warning` |
| `CommerceFlowLogisticsReconciliationFailure` | 5 分钟内物流对账失败 | 立即 | `warning` |
| `CommerceFlowLogisticsCallbackDeadLetter` | 物流回调 Outbox 存在 `DEAD` | 5 分钟 | `critical` |

`CommerceFlowWatchdog` 是故意保持 firing 的“死信号开关”，用于发现 Prometheus、Alertmanager 或接收端本身断链，不代表业务故障。Alertmanager 对同一告警的 `critical` 抑制 `warning`，普通重复间隔为 4 小时，严重告警为 15 分钟。

## 4. 验收

Windows：

```powershell
./scripts/windows/verify-alerting.ps1
```

Bash：

```bash
bash ./scripts/linux/verify-alerting.sh
```

验收入口默认启动 `observability` Profile，但不停止业务服务、不删除容器或数据卷。它执行：

1. `promtool check config` 与 10 条规则加载检查；
2. `promtool test rules`，离线验证服务宕机、退款积压和物流回调 `DEAD`；
3. `amtool check-config`；
4. 三个 Spring Boot Target 均为 `up`；
5. Watchdog 经 Alertmanager 真实到达 Webhook。

已有监控容器时可传 `-SkipStart`；Bash 的第五个位置参数使用 `true`。脚本只验证告警链，不通过停止业务服务制造故障。

## 5. 生产部署前仍需完成

- Prometheus、Alertmanager、Grafana 和 Actuator 不应直接暴露到公网；应放入受控网络并启用 TLS、身份认证和访问审计。
- 接入真实通知供应商，使用环境注入或 Secret 管理保存令牌，禁止提交凭据。
- 根据真实流量、SLO、值班轮次和容量基线调整阈值、分组、静默和升级策略。
- 为 Prometheus/Alertmanager 配置持久存储、高可用副本和异地保留；当前单机 Compose 不代表生产高可用。
- 通过真实故障演练验证通知到人、确认、升级和复盘流程；本项目当前只证明本地 Webhook 端到端投递。

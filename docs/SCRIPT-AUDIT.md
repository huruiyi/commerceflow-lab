# 验收脚本契约

Windows、Linux 和 WSL 使用 PowerShell 7，从仓库根目录调用同一个入口。快速回归需要 Docker Compose v2 和可用的 Docker 引擎。常规验收会向当前实验环境写入测试订单。

```sh
pwsh -File ./scripts/audit-script-contracts.ps1
pwsh -File ./scripts/verify-quick.ps1 -SkipBuild
```

## 脚本覆盖

[参数清单](../scripts/script-contracts.tsv) 登记所有 `verify-*` 入口相对 `scripts/` 的路径及有序参数。
新增验证时在对应分类添加一份 PowerShell 7 脚本并更新清单。审计递归检查语法、路径和参数，以及是否存在同名入口。

| 目录 | 内容 |
| --- | --- |
| `scripts/` | 快速回归、完整验收、脚本契约审计与参数清单 |
| `scripts/audit/` | 代码质量和日常 PostgreSQL 拓扑审计 |
| `scripts/commerce/` | 鉴权、国际化、目录、结算计价、优惠、售后、商家操作与 Redis |
| `scripts/orders/` | 订单切换、取消、自动生命周期、收货和评价 |
| `scripts/payment/` | 支付、退款、支付切换、回调及竞态 |
| `scripts/fulfillment/` | 履约、物流、发货及回调 |
| `scripts/inventory/` | 库存预占与独立库存服务 |
| `scripts/messaging/` | 通知服务和短信邮件模拟器 |
| `scripts/scheduling/` | 定时调度、RabbitMQ、RocketMQ 和消息追踪 |
| `scripts/operations/` | 监控告警、运行控制台和分布式追踪 |
| `scripts/lib/` | 公用的验证函数 |
| `scripts/tools/` | 教学工具，视频生成器使用 Windows 图形与语音组件 |

满减券使用 [verify-coupons.ps1](../scripts/commerce/verify-coupons.ps1)。日常环境走创建测试活动、领券、下单、取消、支付与退款。并发、超时与跨库中断注入由脚本识别专用隔离项目后执行。

## 数据卷

快速回归和完整验收使用隔离 PostgreSQL 卷，并默认保留。显式传入 `-CleanupOnSuccess` 时，仅在验收成功后清理该隔离项目的临时卷。

脚本契约审计检查临时删卷开关、全局 Docker 卷删除命令和宽范围的递归删除。按代码变动选择验收层级，见[开发生命周期验证体系](VERIFICATION-SYSTEM.md)。

## 合并验收

2026-11-19，Windows 和原生 Linux 容器均通过 58 项参数契约及代码质量审计。Linux 容器从同一工作树运行中英文接口专项，另用临时副本确认重复入口、参数换序、移除删卷开关都会触发失败。

Windows 使用现有镜像运行 `verify-quick.ps1 -SkipBuild` 通过，覆盖取消、支付退款、发货、自动收货、重启恢复与健康检查。本轮未执行完整验收及全部故障专项。

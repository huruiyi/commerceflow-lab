# 验收脚本契约

Windows、Linux 和 WSL 使用 PowerShell 7，从仓库根目录调用同一个入口。快速回归需要 Docker Compose v2 和可用的 Docker 引擎。常规验收会向当前实验环境写入测试订单。

```sh
pwsh -File ./scripts/audit-script-contracts.ps1
pwsh -File ./scripts/verify-quick.ps1 -SkipBuild
```

## 脚本覆盖

[参数清单](../scripts/script-contracts.tsv) 按脚本名登记所有 `verify-*` 入口及有序参数。新增验证时在 `scripts/` 中添加一个 `.ps1` 并更新清单。审计会核对名称和参数，检查 PowerShell 语法，并报告重复的平台入口。两份履约审计 SQL 由对应脚本加载。教学视频生成器使用 Windows 图形与语音组件。

满减券使用 [verify-coupons.ps1](../scripts/verify-coupons.ps1)。日常环境走创建测试活动、领券、下单、取消、支付与退款。并发、超时与跨库中断注入由脚本识别专用隔离项目后执行。

## 数据卷

快速回归和完整验收默认保留持久卷。全新卷演练使用独立项目，只有显式传入 `-RemoveTemporaryVolumes` 才清理本次临时项目卷。Flyway 空库和升级演练使用临时容器、网络与 tmpfs。

脚本契约审计检查临时删卷开关、全局 Docker 卷删除命令和宽范围的递归删除。按代码变动选择验收层级，见[开发生命周期验证体系](VERIFICATION-SYSTEM.md)。

## 合并验收

2026-11-19，Windows 和原生 Linux 容器均通过 58 项参数契约及代码质量审计。Linux 容器从同一工作树运行中英文接口专项，另用临时副本确认重复入口、参数换序、移除删卷开关都会触发失败。

Windows 使用现有镜像运行 `verify-quick.ps1 -SkipBuild` 通过，覆盖取消、支付退款、发货、自动收货、重启恢复与健康检查。本轮未执行完整验收及全部故障专项。

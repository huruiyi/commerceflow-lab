# PostgreSQL 运维与数据库演进

CommerceFlow Lab 的日常环境和隔离验收均使用 PostgreSQL 17.11。每个业务服务连接独立数据库，数据库端口留在 Compose 网络内。

| 服务 | 数据库 |
| --- | --- |
| 核心商城 | `commerceflow` |
| 商品目录 | `catalog` |
| 库存 | `inventory` |
| 订单 | `orders` |
| 履约 | `fulfillment` |
| 支付编排 | `payments` |
| 支付渠道模拟 | `payment_platform` |
| 发票模拟 | `invoice_platform` |
| 物流模拟 | `logistics_platform` |
| 仓库模拟 | `warehouse_platform` |
| 站外消息模拟 | `messaging_platform` |
| 通知 | `notification` |
| 行政区划基础数据 | `reference_data` |

## 日常恢复启动

根目录 `.env` 加载日常覆盖文件及目录 Elasticsearch 覆盖，运行项目为 `fairy-commerceflow`。数据库覆盖使用现有的 13 个外部命名卷。重建前按 `.env.example` 从本机部署配置加载现役凭据和发票签名密钥，核对应用镜像、Kafka 数据目录、搜索索引和所有现役挂载。日常使用已发布的镜像和原卷重建。只恢复已停止容器时，`docker compose start --wait` 沿用创建配置。

日常应用使用已有运行账号。升级数据库时按对应服务的 PostgreSQL 迁移配置使用迁移账号。通知应用重建前确认 Kafka 原卷、维护卷和消费组已提交位置。容器内通知地址为 `notification-service:8080`，宿主机端口为 `18086`。

## 空卷验收

```powershell
./scripts/audit/audit-daily-postgresql-compose.ps1
./scripts/verify-quick.ps1
./scripts/verify-all.ps1
```

快速回归和完整验收创建独立项目和新 PostgreSQL 卷。Flyway 建立业务表后，隔离入口初始化演示账号、商品、库存及支付商户。默认在结束时停止隔离容器并保留卷。

## 备份和恢复

逐库用 `pg_dump -Fc` 保存业务数据库，保存对应的全局角色定义并记录 SHA-256。使用同版本 PostgreSQL 在隔离实例恢复，按库核对对象所有者、表摘要、序列、约束及角色授权。跨库交易应用订单号、支付号、退款号和事件号核对完整性。日常恢复前先冻结写入入口，并记录 Kafka 消费组和待投递事件的状态。[全量备份与隔离恢复记录](../IMPLEMENTED.md#2133-2026-11-27-日常-postgresql-全量备份与隔离恢复)

日常 Windows 主机通过任务计划程序在北京时间每天 21:00 备份，并在每周六 21:30 选取最新完整备份执行隔离恢复。维护任务使用当前 Windows 用户和 Docker Desktop。用户须保持登录，Docker Desktop 和备份盘须可用。变更前仍按需另做备份。

备份目录须在仓库之外，根路径应限制访问。生成的备份包含数据库角色和现役容器配置，按部署凭据管理。计划任务不自动清理备份和数据卷。人工整理时保留至少最近 7 份每日备份、4 份周度演练对应的备份，以及仍关联发布回退的备份。

在 Windows PowerShell 7 中执行以下命令可注册任务。首次部署先触发备份任务并核对结果，再注册恢复和告警任务。

```powershell
./scripts/operations/install-postgresql-maintenance-task.ps1 -Task Backup -BackupRoot 'G:/CommerceFlow-backups'
Start-ScheduledTask -TaskName 'CommerceFlow PostgreSQL Daily Backup'
./scripts/operations/install-postgresql-maintenance-task.ps1 -Task Restore -BackupRoot 'G:/CommerceFlow-backups'
Start-ScheduledTask -TaskName 'CommerceFlow PostgreSQL Weekly Restore'
./scripts/operations/install-postgresql-maintenance-task.ps1 -Task Monitor
```

任务脚本在 `%LOCALAPPDATA%/CommerceFlow/postgresql-maintenance/` 保留 `backup.json`、`restore.json` 和每次执行的日志。恢复核验报告写入对应的备份目录。[任务告警与排查](../ALERTING.md#postgresql-备份和隔离恢复告警) · [首次运行证据](../IMPLEMENTED.md#2140-2026-11-27-postgresql-定期备份恢复与告警)

可手动创建变更前备份，或从指定备份演练恢复。

```powershell
./scripts/operations/backup-daily-postgresql.ps1 -BackupRoot 'G:/CommerceFlow-backups'
./scripts/operations/verify-postgresql-backup.ps1 -BackupDirectory '<备份命令生成的目录>'
```

以 `SHA256SUMS.txt` 为备份完成标记。清单记录逐库快照时间、Flyway 历史、运行镜像、Docker 挂载、已提交的 Kafka 消费位置和分区起止位置。Kafka 数据由下列独立任务归档。按两个任务各自的采集窗口和跨库业务证据选取恢复副本。

验证入口先核对所有文件摘要，再逐库在同镜像的无网络临时实例恢复并比对 Flyway 历史，输出逐库恢复耗时。整栈演练使用独立项目和独立卷启动 Compose，继续验证下单、支付、出库、退款及通知，记录到业务就绪的时间。可恢复到的业务时间以归档窗口和跨库订单核验为准。

2026 年 11 月 27 日的整栈演练另取 Kafka 数据卷归档，先恢复 PostgreSQL 和 Kafka，核对消息与提交位置，再启动应用。两个原消费组实际接续并通过历史消息重投去重验证，恢复后的新订单通过出库、退款、开票及通知验收。[恢复结果与耗时](../IMPLEMENTED.md#2141-2026-11-27-postgresql-与-kafka-整栈隔离恢复)

### Kafka 定期归档与恢复

`CommerceFlow Kafka Daily Backup` 在北京时间每天 21:15 归档单节点 KRaft broker 的实际数据卷。这个时段与 PostgreSQL 21:00 备份错开。任务依赖当前用户登录、Docker Desktop 的 WSL 运行环境和可用的备份盘。它核对现役 Kafka 进程及实际挂载，在独立挂载命名空间以只读方式取样，比较采集前后的业务分区边界、消费位点和主题标识。文件列表及内容须吻合，允许 KRaft 日志在已校验前缀之后追加。采集期间发生变化时最多重试三次。任务保持 broker 在线，并以 SHA-256 清单和目录原子更名标记完成，`.incomplete` 目录仅用于排查。

```powershell
$state = Join-Path $env:LOCALAPPDATA 'CommerceFlow/kafka-maintenance'
./scripts/operations/install-postgresql-maintenance-task.ps1 -Task KafkaBackup -BackupRoot 'G:/CommerceFlow-backups' -StateDirectory $state
Start-ScheduledTask -TaskName 'CommerceFlow Kafka Daily Backup'
./scripts/operations/install-postgresql-maintenance-task.ps1 -Task KafkaMonitor -StateDirectory $state
```

从 `kafka-backup.json` 查找归档和执行日志。归档含 Kafka 数据文件、主题、分区边界、消费组提交位点及历史消息摘要。按日备份的目标间隔为 24 小时，实际可恢复点取决于最近一次成功采集。人工整理时至少保留最近 7 份完整 Kafka 每日归档及仍关联恢复或回退的归档。计划任务保留原卷和既有备份。备份盘访问权限按业务数据管理。

隔离恢复先校验归档，将数据解包到新卷并校验逐文件摘要，使用归档记录的 Kafka 镜像启动。核对集群与目录标识、主题 ID、分区边界、历史消息摘要和消费位点，再启动对应的应用与 PostgreSQL 恢复副本，确认消费组实际从原位点接续。重复投递选定的历史消息后，核对提交位置、业务账本和死信位点。记录从完整校验到回执核对结束的耗时。跨主机恢复单独验收。

## 已应用版本的注释清理

当前源码调整了以下 Flyway 文件的首行说明，可执行 SQL 逐行一致。现役迁移记录仍保留原校验值。后续在现有卷执行 Flyway 发布前，先在备份恢复副本检查 `validate` 的差异仅限下表，使用该库原有迁移参数执行 `repair`，再运行 `validate`。日常应用启动时保持 Flyway 关闭。

| 数据库及版本 | 已应用校验值 | 当前源码校验值 |
| --- | ---: | ---: |
| `commerceflow` V74 | -1911609292 | 2107552281 |
| `inventory` V1 | 1126934548 | 1905933795 |
| `payments` V1 | -711889659 | 713327911 |
| `payment_platform` V1 | 706323955 | 755381305 |

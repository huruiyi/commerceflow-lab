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

根目录 `.env` 加载日常覆盖文件。运行项目为 `fairy-commerceflow`。覆盖文件使用现有的 13 个外部命名卷。重建前按 `.env.example` 从本机部署配置加载现役凭据和发票签名密钥，确认目标卷已存在。用已发布的 Compose 参数启动已有容器时，`docker compose start --wait` 沿用创建配置。

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

## 已应用版本的注释清理

当前源码调整了以下 Flyway 文件的首行说明，可执行 SQL 逐行一致。现役迁移记录仍保留原校验值。后续在现有卷执行 Flyway 发布前，先在备份恢复副本检查 `validate` 的差异仅限下表，使用该库原有迁移参数执行 `repair`，再运行 `validate`。日常应用启动时保持 Flyway 关闭。

| 数据库及版本 | 已应用校验值 | 当前源码校验值 |
| --- | ---: | ---: |
| `commerceflow` V74 | -1911609292 | 2107552281 |
| `inventory` V1 | 1126934548 | 1905933795 |
| `payments` V1 | -711889659 | 713327911 |
| `payment_platform` V1 | 706323955 | 755381305 |

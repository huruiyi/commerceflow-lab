# 数据库演进计划（架构待办）

## 当前决策

日常通知服务使用 PostgreSQL 17.11（`notification-db-postgresql` / `notification`）。原通知 MySQL
数据库 `notification-db` 及其应用容器保留作回退，反向迁移前以 PostgreSQL 为唯一写入源。
行政区划基础数据（`reference-data-db` / `reference_data`）、核心商城、商品目录、履约、库存以及站外消息、仓库、物流和发票模拟服务也已使用 PostgreSQL 17.11。2026-11-26 库存切换后，以下 3 个业务库以 MySQL 8.4 为写入方。

- `payment-simulator-db` / `payment_platform`
- `order-db` / `orders`（已完成单一所有权 CUTOVER）
- `payment-db` / `payments`（支付编排）

### 下一库的隔离候选

站外消息模拟库 `messaging_platform` 有两张业务表，保存短信、邮件的模拟供应商回执及按请求设置的模拟故障。请求 ID 和供应商 ID 分别受主键及唯一约束保护。通知服务独立保存对应的请求号、供应商编号和送达状态。

2026-11-26 已从十个日常 MySQL 实例核对表数、外键数，并检查各服务的迁移 SQL 与 Mapper。下表为隔离演练建议顺序。表数包含 Flyway 历史表。回退一列列出反向迁移后需要从外部系统同时对上的记录；每次演练完成后再决定对应日常窗口。

| 顺序与写入服务 | 表 / 外键 | SQL 适配点 | 回退时需核对的跨服务记录 |
| --- | ---: | --- | --- |
| 1 站外消息模拟服务 | 3 / 0 | 冲突更新、时间加法和请求行锁 | 通知服务与模拟供应商使用同一请求和回执；重放后只有一条投递。 |
| 2 仓库模拟服务 | 3 / 0 | 冲突更新、标识符排序规则 | 履约出库单、退货签收与库存回补的数量、仓库及来源单一致。 |
| 3 物流模拟服务 | 6 / 4 | 自增键、行锁 | 发货与退货运单的事件顺序、回调及重试位置一致。 |
| 4 发票模拟服务 | 4 / 3 | 枚举、时间算术和冲突更新 | 蓝票、红票和商城已确认的回执能按原请求号重放。 |
| 5 商品目录服务 | 9 / 3 | JSON 提取、多语言资料冲突更新 | 库存商品快照与搜索索引能从原目录版本追平。 |
| 6 履约服务 | 10 / 1 | 冲突更新、租约时间算术 | 已接收的发货命令与订单、仓库和物流的执行结果一致。 |
| 7 库存服务 | 18 / 16 | 忽略重复插入、行锁 | 预占、提交、释放与退货回补逐笔对应订单行，库存余额对平。 |
| 8 订单服务 | 17 / 10 | 忽略重复插入、时间差计算 | 订单状态与取消命令、支付和库存的确认结果一致。 |
| 9 支付渠道模拟服务 | 18 / 16 | 冲突更新、回调重试时间算术 | 支付与退款的渠道流水号、金额及回调状态与支付编排一致。 |
| 10 支付编排服务 | 21 / 7 | 忽略重复插入、冲突更新、行锁 | 订单、渠道、退款、结算和待处理命令逐笔对账，回退后不能重复入账。 |

### 库存服务 PostgreSQL 隔离演练

从日常 MySQL V10 的单事务原生备份恢复库存账本。PostgreSQL 17.11 V1 建立 17 张业务表、16 个外键及两个自增序列。V2 初始化空库的两个默认仓，保留迁入的仓库资料。迁移时保留商品库存和分仓库存、预占状态和版本、命令防重记录、出库单与出库事件、退货回补和原迁移水位。正反向均核对逐表全字段摘要和下一自增值。

在仓库根目录使用 PowerShell 7.4+、Node.js 24+ 及本机 Docker。`$release` 指向包含 `inventory-mysql.sql` 的备份目录。演练的源库、目标库、回退库和 Kafka 使用独立数据卷，服务仅在隔离网络内相互访问。

```powershell
docker build -t commerceflow-inventory-pg:rehearsal services/inventory-service
$legacy = docker inspect fairy-commerceflow-inventory-service --format '{{.Config.Image}}'
pwsh scripts/inventory/verify-inventory-postgresql.ps1 -BackupPath "$release/inventory-mysql.sql" -CandidateImage commerceflow-inventory-pg:rehearsal -BaselineImage $legacy -EvidenceDirectory $release
```

正向迁移前，旧版在隔离 MySQL 预占 2 件，PostgreSQL 接续释放。再通过签名接口预占和提交 3 件，按订单行从仓库 1 部分出库 2 件，沿用原出库单退货回补 1 件。复验重复预占、释放、提交、出库和回补，以及缺货、错误出库单和超额回补。发布准备演练在两个方向各留一条待投递出库事件，记录原事件号、原报文和下次重试时间。恢复隔离 Kafka 后，接管应用各补投原事件一次。回迁到独立 MySQL V10 后，旧版释放 PostgreSQL 创建的预占并读回出库与回补凭证。[库存隔离验收记录](../IMPLEMENTED.md#2123-2026-11-26-库存服务-postgresql-隔离验收) · [发布准备验收](../IMPLEMENTED.md#2124-2026-11-26-库存服务日常发布准备)

### 库存服务日常发布窗口

发布目录为 `%LOCALAPPDATA%/CommerceFlow/releases/inventory-pg-ready-1795694316/`。日常库存现役库为 PostgreSQL 17.11 V2，使用 `fairy-commerceflow_inventory-postgresql-data`。`inventory_migrator` 持有结构，应用以 `inventory_runtime` 连接并处理库存和事件投递。原 MySQL 卷 `fairy-commerceflow_inventory-db-data` 持久保持只读，独立回退卷 `fairy-commerceflow_inventory-rollback-data` 保留 V10 结构和十条原 Flyway 记录，回退实例暂停。日常 Kafka 沿用原实例和主题。[日常库存切换验收](../IMPLEMENTED.md#2125-2026-11-26-日常库存切换-postgresql)

从仓库根目录加载已核对的 Compose 参数。日常重启现役库存应用使用 `$pg`。下列正向步骤保留切换前的原应用及候选镜像 ID、备份摘要和 PostgreSQL 权限检查。备份及发布凭据保存在仓库外。

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$release = Join-Path $env:LOCALAPPDATA 'CommerceFlow/releases/inventory-pg-ready-1795694316'
$binding = Get-Content "$release/release-binding.json" -Raw | ConvertFrom-Json
$old = Get-Content "$release/original-args.json" -Raw | ConvertFrom-Json
$pg = Get-Content "$release/postgresql-args.json" -Raw | ConvertFrom-Json
$back = Get-Content "$release/rollback-args.json" -Raw | ConvertFrom-Json
$source = 'fairy-commerceflow-inventory-db'
$target = 'fairy-commerceflow-inventory-db-postgresql'
if ((docker inspect fairy-commerceflow-inventory-service --format '{{.Image}}') -cne $binding.legacyId -or
    (docker image inspect $binding.legacyTag --format '{{.Id}}') -cne $binding.legacyId -or
    (docker image inspect $binding.candidateTag --format '{{.Id}}') -cne $binding.candidateId -or
    (Get-FileHash "$release/inventory-mysql.sql").Hash -cne (Get-Content "$release/backup.sha256")) {
    throw '库存镜像或备份与发布准备记录不一致'
}
if ((docker exec $target psql -U postgres -d inventory -XAtq -v ON_ERROR_STOP=1 -c (Get-Content infrastructure/inventory/verify-runtime.sql -Raw)) -cne 't') {
    throw '库存账号权限已变化'
}
```

正式切换时只停库存应用并持久冻结原 MySQL。记录冻结点的活动预占、分仓余额和未投递出库事件。复制十七张业务表和两个自增序列后再迁移 V2，以沿用迁入的仓库资料和待投递事件。保留现有 Kafka 主题和下游消费位置。

```powershell
$stamp = Get-Date -Format yyyyMMdd-HHmmss
docker @old stop inventory-service
docker exec $source sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -e "SET PERSIST super_read_only=ON"'
docker exec $source sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-lock-tables --hex-blob inventory' > "$release/frozen-$stamp.sql"
node scripts/inventory/inventory-postgresql-ledger.mjs fairy-commerceflow ToPostgreSQL "$release/forward-$stamp.json"
docker @pg run --rm --no-deps inventory-postgresql-migrator
docker exec $target psql -U postgres -d inventory -v ON_ERROR_STOP=1 -c 'ALTER ROLE inventory_runtime LOGIN'
docker @pg up -d --no-deps --no-build --wait inventory-service
```

从 PostgreSQL 确认应用使用 `inventory_runtime`，核对冻结的活动预占和分仓余额，以原事件号检查待投递出库回执，再做一笔日常新交易。若应用启动前中止且确认目标无新增业务写入，禁用 PostgreSQL 运行账号，解除原 MySQL 的 `super_read_only` 和 `read_only`，用 `$old` 恢复库存应用。

新应用已受理业务时，先停库存应用，禁用 PostgreSQL 运行账号并结束余留连接。归档最新 PostgreSQL 后反向复制到独立 MySQL 回退卷，原 MySQL 保持只读。已经写入的目标只允许同方向重试校验。回退后的应用继续使用现有 Kafka，按原事件号核对待投递记录。

```powershell
$stamp = Get-Date -Format yyyyMMdd-HHmmss
docker @pg stop inventory-service
docker exec $target psql -U postgres -d inventory -v ON_ERROR_STOP=1 -c 'ALTER ROLE inventory_runtime NOLOGIN'
docker exec $target psql -U postgres -d inventory -v ON_ERROR_STOP=1 -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='inventory_runtime'"
docker exec $target pg_dump -U postgres -d inventory -Fc > "$release/postgresql-$stamp.dump"
docker @back start --wait inventory-db-rollback
node scripts/inventory/inventory-postgresql-ledger.mjs fairy-commerceflow ToMySQL "$release/reverse-$stamp.json"
docker @back up -d --no-deps --no-build --wait inventory-service
```

### 履约服务 PostgreSQL 隔离演练

从日常 MySQL V4 的单事务备份恢复九张业务表，另存取自日常订单 751、752、753 的履约、仓库、商城物流桥和物流模拟器回执。正向复制到 PostgreSQL 17.11 V1，按表核对全字段摘要和两个下一自增值，启动候选服务后应用 V2。V2 初始化空库运行模式和水位，迁入的现有配置及水位保留原值。PostgreSQL `postgresql` 配置用于 `EXECUTION` 模式，旧库的 `SHADOW` 模式继续在 MySQL 上运行。

在仓库根目录使用 PowerShell 7.4+、Node.js 24+ 及本机 Docker。`$release` 指向备份与证据目录，其中 `fulfillment-mysql.sql` 为 MySQL 原生备份，同一目录保存 `daily-jobs.jsonl`、`daily-warehouse.jsonl`、`daily-commerce.jsonl` 和 `daily-logistics.jsonl`。源、目标、回退库各用一个独立卷，数据库端口仅在隔离网络内可达。

```powershell
docker build -t commerceflow-fulfillment-pg:rehearsal services/fulfillment-service
$legacy = docker inspect fairy-commerceflow-fulfillment-service --format '{{.Config.Image}}'
pwsh scripts/fulfillment/verify-fulfillment-postgresql.ps1 -BackupPath "$release/fulfillment-mysql.sql" -CandidateImage commerceflow-fulfillment-pg:rehearsal -BaselineImage $legacy -ReceiptDirectory $release -EvidenceDirectory $release
```

脚本在旧服务认领发货、签名下游收到物流请求后终止旧进程。候选先核对迁入的持有人、到期时间和尝试次数，等待原租约到期再用原物流命令接续。另用隔离签名服务演练第二个仓库的拆包、临时 503 和待出库回执。停止候选后归档 PostgreSQL，将九表写回单独的 MySQL V4，再用旧镜像读取历史及新增发货并受理新接单。运行结果、迁移清单和归档记录保存在本次隔离项目目录。[履约隔离验收记录](../IMPLEMENTED.md#2120-2026-11-26-履约服务-postgresql-隔离验收)

### 履约服务日常发布窗口

发布目录为 `%LOCALAPPDATA%/CommerceFlow/releases/fulfillment-pg-ready-1795692135/`。日常履约现役库为 PostgreSQL 17.11 V2，使用 `fairy-commerceflow_fulfillment-postgresql-data`。`fulfillment_migrator` 持有结构和 Flyway 历史，应用以 `fulfillment_runtime` 连接并执行履约和追加恢复审计。MySQL 原卷 `fairy-commerceflow_fulfillment-db-data` 已持久设为只读。独立回退卷 `fairy-commerceflow_fulfillment-rollback-data` 保留 V4 结构和原迁移历史，实例暂停。[日常履约切换验收](../IMPLEMENTED.md#2122-2026-11-26-日常履约切换-postgresql)

从仓库根目录加载镜像绑定和三组 Compose 参数。日常重启现役履约应用使用 `$pg` 参数。下列正向步骤记录切换前的镜像、备份及权限检查，备份保存在仓库外的发布目录。

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$release = Join-Path $env:LOCALAPPDATA 'CommerceFlow/releases/fulfillment-pg-ready-1795692135'
$binding = Get-Content "$release/release-binding.json" -Raw | ConvertFrom-Json
$old = Get-Content "$release/original-args.json" -Raw | ConvertFrom-Json
$pg = Get-Content "$release/postgresql-args.json" -Raw | ConvertFrom-Json
$back = Get-Content "$release/rollback-args.json" -Raw | ConvertFrom-Json
$source = 'fairy-commerceflow-fulfillment-db'
$target = 'fairy-commerceflow-fulfillment-db-postgresql'
if ((docker inspect fairy-commerceflow-fulfillment-service --format '{{.Image}}') -cne $binding.legacyId -or
    (docker image inspect $binding.legacyTag --format '{{.Id}}') -cne $binding.legacyId -or
    (docker image inspect $binding.candidateTag --format '{{.Id}}') -cne $binding.candidateId -or
    (Get-FileHash "$release/fulfillment-mysql.sql").Hash -cne (Get-Content "$release/fulfillment-backup.sha256")) {
    throw '履约镜像或备份与发布准备记录不一致'
}
if ((docker exec $target psql -U postgres -d fulfillment -XAtq -v ON_ERROR_STOP=1 -c (Get-Content infrastructure/fulfillment/verify-runtime.sql -Raw)) -cne 't') {
    throw '履约运行账号权限已变化'
}
```

窗口开始时只停履约应用并冻结原 MySQL，保留履约命令入口上下游的原回执。冻结后生成原生备份，复制九张业务表及两个自增序列，再迁移到 V2，保留已有水位和执行模式。复制入口核对实际卷、原 MySQL 持久只读和 PostgreSQL 运行账号停写状态。目标已有数据时，同方向执行只做账本校验。

```powershell
$stamp = Get-Date -Format yyyyMMdd-HHmmss
docker @old stop fulfillment-service
docker exec $source sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -e "SET PERSIST super_read_only=ON"'
docker exec $source sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-lock-tables --hex-blob fulfillment' > "$release/frozen-$stamp.sql"
node scripts/fulfillment/fulfillment-postgresql-ledger.mjs fairy-commerceflow ToPostgreSQL "$release/forward-$stamp.json"
docker @pg run --rm --no-deps fulfillment-postgresql-migrator
docker exec $target psql -U postgres -d fulfillment -v ON_ERROR_STOP=1 -c 'ALTER ROLE fulfillment_runtime LOGIN'
docker @pg up -d --no-deps --no-build --wait fulfillment-service
```

从 PostgreSQL 连接确认运行账号，按原命令号核对在途任务、租约、仓库出库单和物流回执。补做一笔日常新发货并核对下游记录和履约指标。如果新应用还未启动且确认 PostgreSQL 没有新增业务写入，可禁用 PostgreSQL 运行账号、解除原 MySQL 的 `super_read_only` 和 `read_only` 后用 `$old` 恢复应用。

新应用已接单时，先停履约应用，禁用 PostgreSQL 运行账号并结束余留连接。归档新账本后反向恢复到独立的 MySQL 卷，保留原 MySQL 只读。用回退镜像按原命令号核对当前任务、回执和审计。

```powershell
$stamp = Get-Date -Format yyyyMMdd-HHmmss
docker @pg stop fulfillment-service
docker exec $target psql -U postgres -d fulfillment -v ON_ERROR_STOP=1 -c 'ALTER ROLE fulfillment_runtime NOLOGIN'
docker exec $target psql -U postgres -d fulfillment -v ON_ERROR_STOP=1 -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='fulfillment_runtime'"
docker exec $target pg_dump -U postgres -d fulfillment -Fc > "$release/postgresql-$stamp.dump"
docker @back start --wait fulfillment-db-rollback
node scripts/fulfillment/fulfillment-postgresql-ledger.mjs fairy-commerceflow ToMySQL "$release/reverse-$stamp.json"
docker @back up -d --no-deps --no-build --wait fulfillment-service
```

### 商品目录账本隔离演练

使用日常 MySQL V8 的单事务原生备份。隔离 PostgreSQL 17.11 的 Flyway V1 建立八张业务表、三个自增序列及显示器属性视图。正向和反向复制均核对全字段摘要和下一自增值。反向恢复按外键关系先插入公开商品，再插入引用它的销售单元，回退库保留八条原 MySQL Flyway 记录。

在仓库根目录使用 PowerShell 7.4+、Node.js 24+ 及本机 Docker 执行。源、目标和回退库均使用本次隔离项目的数据卷，数据库端口仅存在于该项目的内部网络。

```powershell
pwsh scripts/commerce/verify-catalog-postgresql-ledger.ps1 -BackupPath "$release/catalog-mysql.sql" -EvidenceDirectory $release
```

库存核对按实际交易口径比较。非默认 SKU 对应的内部销售单元隐藏在目录列表中，激活的销售单元仍按库存事件用于交易。搜索核对同时使用 SQL 文档的版本及 Elasticsearch 当前代次的商品文档，区分可搜索商品和隐藏条目。

应用层演练从这份 PostgreSQL V1 账本启动目录候选，应用 Flyway V2，再接上隔离库存服务与 Elasticsearch。V2 初始化空库的搜索状态，并给商品和 SKU 的更新补上时间戳。复验覆盖创建请求重放、乐观锁、翻译回退、JSON 规格与显示器属性、价格与评分排序，以及同一目录事件由库存幂等确认。

```powershell
docker build -t commerceflow-catalog-pg:runtime services/catalog-service
$inventoryImage = docker inspect fairy-commerceflow-inventory-service --format '{{.Config.Image}}'
pwsh scripts/commerce/verify-catalog-postgresql-runtime.ps1 -BackupPath "$release/catalog-mysql.sql" -CandidateImage commerceflow-catalog-pg:runtime -InventoryImage $inventoryImage -EvidenceDirectory $release
```

PostgreSQL 的数据库搜索使用子串筛选，关键词匹配忽略大小写并保留重音区别，覆盖包含、排除和短语三种行为。默认相关性优先匹配标题，再按搜索文本中匹配词的出现次数排序，最后按商品编号排序。Elasticsearch 可用时沿用其相关性。演练在 Elasticsearch 尚未启动时先测 SQL 查询，随后做索引重建和逐文档核对，停掉索引后再复测 SQL 回退。[账本与投影基线](../IMPLEMENTED.md#2116-2026-11-26-商品目录-postgresql-账本隔离验收)

### 商品目录日常发布窗口

准备目录为 `%LOCALAPPDATA%/CommerceFlow/releases/catalog-pg-ready-1795689831/`。日常目录现在使用 PostgreSQL 17.11 V2 和 `fairy-commerceflow_catalog-postgresql-data`，原 MySQL V8 及 `fairy-commerceflow_catalog-db-data` 已强制只读。重启现役目录服务使用下文的 `$pg` 参数。[日常目录切换验收](../IMPLEMENTED.md#2119-2026-11-26-日常商品目录切换-postgresql)

`catalog_migrator` 持有 schema，`catalog_runtime` 只能写入目录、库存事件和搜索投影所需的字段。允许删除待重建的 SQL 搜索文档。独立 MySQL 回退卷为 `fairy-commerceflow_catalog-rollback-data`，已载入 V8 结构、显示器属性视图和八条迁移记录。

从仓库根目录加载锁定的镜像、备份及发布配置。隔离演练复用当前的目录候选、旧版目录和库存镜像，在独立库存和 Elasticsearch 上验证。

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$release = Join-Path $env:LOCALAPPDATA 'CommerceFlow/releases/catalog-pg-ready-1795689831'
$binding = Get-Content "$release/release-binding.json" -Raw | ConvertFrom-Json
$old = Get-Content "$release/original-args.json" -Raw | ConvertFrom-Json
$pg = Get-Content "$release/postgresql-args.json" -Raw | ConvertFrom-Json
$back = Get-Content "$release/rollback-args.json" -Raw | ConvertFrom-Json
$source = 'fairy-commerceflow-catalog-db'
$target = 'fairy-commerceflow-catalog-db-postgresql'
if ((docker inspect fairy-commerceflow-catalog-service --format '{{.Image}}') -cne $binding.legacyId -or
    (docker image inspect $binding.legacyTag --format '{{.Id}}') -cne $binding.legacyId -or
    (docker image inspect $binding.candidateTag --format '{{.Id}}') -cne $binding.candidateId -or
    (docker inspect fairy-commerceflow-inventory-service --format '{{.Image}}') -cne $binding.inventoryId) {
    throw '目录或库存镜像与发布准备记录不一致'
}
foreach ($db in 'catalog','inventory') {
    if ((Get-FileHash "$release/$db-mysql.sql").Hash -cne (Get-Content "$release/$db-backup.sha256")) {
        throw "$db 备份摘要不一致"
    }
}
if ((docker exec $target psql -U postgres -d catalog -XAtq -v ON_ERROR_STOP=1 -c (Get-Content infrastructure/catalog/verify-runtime.sql -Raw)) -cne 't') {
    throw '目录运行账号权限已变化'
}
pwsh scripts/commerce/verify-catalog-postgresql-release.ps1 -BackupPath "$release/catalog-mysql.sql" -InventoryBackupPath "$release/inventory-mysql.sql" -ReleaseEnvFile "$release/standby.env" -EvidenceDirectory $release
```

正式窗口只停止目录服务并冻结 MySQL。保留库存回执和现役 Elasticsearch 代次。复制最终八张业务表和三个自增序列后，再迁移到 PostgreSQL V2。V2 在空库初始化两个搜索状态行，必须在复制之后运行，以沿用迁入的搜索代次。

```powershell
$stamp = Get-Date -Format yyyyMMdd-HHmmss
docker @old stop catalog-service
docker exec $source sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -e "SET PERSIST super_read_only=ON"'
docker exec $source sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-lock-tables --hex-blob catalog' > "$release/frozen-$stamp.sql"
node scripts/commerce/catalog-postgresql-ledger.mjs fairy-commerceflow release-forward "$release/forward-$stamp.json"
docker @pg run --rm --no-deps catalog-postgresql-migrator
docker @pg up -d --no-deps --no-build --wait catalog-service
```

确认应用以 `catalog_runtime` 连接。用迁移清单中的最终版本核对目录事件、库存回执和 SQL 文档版本，并从目录索引比对入口核对 Elasticsearch 文档。若应用启动前中止，确认 PostgreSQL 无新业务写入后解除 MySQL 的 `super_read_only` 和 `read_only`，使用 `$old` 恢复旧服务。

若新应用已接单，先停目录应用，禁用 PostgreSQL 运行账号并保存原生备份，再将最新账本反向复制到独立 MySQL 回退库。

```powershell
$stamp = Get-Date -Format yyyyMMdd-HHmmss
docker @pg stop catalog-service
docker exec $target psql -U postgres -d catalog -v ON_ERROR_STOP=1 -c 'ALTER ROLE catalog_runtime NOLOGIN'
docker exec $target psql -U postgres -d catalog -v ON_ERROR_STOP=1 -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='catalog_runtime'"
docker exec $target pg_dump -U postgres -d catalog -Fc > "$release/postgresql-$stamp.dump"
docker @back start --wait catalog-db-rollback
node scripts/commerce/catalog-postgresql-ledger.mjs fairy-commerceflow release-reverse "$release/reverse-$stamp.json"
docker @back up -d --no-deps --no-build --wait catalog-service
```

回退后按原事件号核对库存确认，按当前索引代次对比 Elasticsearch 文档。受停写保护的目标中已有数据时，迁移入口只做双库逐表比对，可用同一方向重试校验。

### 发票模拟库隔离演练

以日常 `invoice_platform` MySQL V2 的单事务原生备份开始，同时导出商城 `commerceflow.invoice_attempts` 中成功的请求号、业务编号、票号、原票号、金额、币种和状态。蓝票重放使用商城支付号，红票重放使用原蓝票和退款号。历史回执按请求号同时匹配开票查询和重放结果。

PostgreSQL 17.11 使用独立 Flyway V1，保留业务编号区分大小写、活跃业务引用唯一、金额和状态约束、原票外键及回执去重。切换方向分别核对申请、票据、回执三张表的全字段摘要。回迁先插入外键引用的原票，再插入红票。迁移工具校验隔离 Compose 项目、三个数据卷和模拟服务停写。

在仓库根目录使用 PowerShell 7.4+、Node.js 24+ 及本机 Docker。使用当前日常镜像作为回退基线，将原始备份和商城回执保存在仓库外。

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$release = Join-Path $env:LOCALAPPDATA ("CommerceFlow/releases/invoice-pg-" + (Get-Date -Format yyyyMMdd-HHmmss))
New-Item -ItemType Directory $release | Out-Null
$baselineImage = docker inspect fairy-commerceflow-invoice-simulator-service --format '{{.Config.Image}}'
docker exec fairy-commerceflow-invoice-db sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-lock-tables --hex-blob --routines --events --triggers invoice_platform' > "$release/invoice-mysql.sql"
(Get-FileHash "$release/invoice-mysql.sql").Hash | Set-Content "$release/backup.sha256"
$query = @'
SELECT json_build_object(
    'requestId', request_id, 'orderId', order_id, 'kind', kind, 'referenceNo', reference_no,
    'paymentNo', payment_no, 'originalInvoiceNo', original_invoice_no, 'amount', amount,
    'currency', currency, 'status', status, 'invoiceNo', invoice_no
)
FROM commerceflow.invoice_attempts
WHERE status = 'SUCCEEDED'
ORDER BY request_id
'@
docker exec fairy-commerceflow-commerce-db-postgresql psql -U postgres -d commerceflow -XAtq -v ON_ERROR_STOP=1 -c $query > "$release/commerce-receipts.jsonl"
docker build -t commerceflow-invoice-pg:rehearsal simulators/invoice-platform/service
pwsh scripts/invoice/verify-invoice-postgresql.ps1 -BackupPath "$release/invoice-mysql.sql" -CommerceReceiptsPath "$release/commerce-receipts.jsonl" -CandidateImage commerceflow-invoice-pg:rehearsal -BaselineImage $baselineImage -EvidenceDirectory $release
pwsh scripts/invoice/verify-invoice-simulator.ps1 -PostgreSqlImage commerceflow-invoice-pg:rehearsal
```

验收在隔离 MySQL 受理一张蓝票，停服务后执行正向迁移，由 PostgreSQL 接续出具蓝票及签名回执。随后在 PostgreSQL 开红票并制造一次投递失败，停止模拟器后反向迁移到独立 MySQL。旧版服务查询并重放两张新票，接续发送原红票事件，拒绝超额红冲。验收结束停止隔离服务并保留卷与迁移摘要。[发票隔离验收](../IMPLEMENTED.md#2113-2026-11-26-发票模拟库-postgresql-隔离验收)

### 发票模拟库日常发布窗口

准备目录为 `%LOCALAPPDATA%/CommerceFlow/releases/invoice-pg-ready-1795686700/`。日常发票使用 PostgreSQL 17.11 V1 和 `fairy-commerceflow_invoice-postgresql-data`。原 MySQL V2 与 `fairy-commerceflow_invoice-db-data` 已冻结为只读。重启现役应用使用下文的 `$pg` 参数。[日常切换验收](../IMPLEMENTED.md#2115-2026-11-26-日常发票切换-postgresql)

`invoice_migrator` 管理 V1 schema。应用以 `invoice_runtime` 连接，Flyway 由独立进程运行。运行账号只追加票据，可以更新开票状态及回执投递字段。红冲继续按原蓝票串行校验额度，PostgreSQL 通过锁定原票对应的申请行实现。独立 MySQL 回退卷 `fairy-commerceflow_invoice-rollback-data` 已恢复 V2 结构和两条 Flyway 记录，平时保持停止。

切换前核对本机发布配置绑定的镜像 ID、源卷、备份摘要和目标账号权限。用同一组发布配置和最新备份在隔离项目中复测并记录账本摘要：

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$release = Join-Path $env:LOCALAPPDATA 'CommerceFlow/releases/invoice-pg-ready-1795686700'
$binding = Get-Content "$release/release-binding.json" -Raw | ConvertFrom-Json
$old = Get-Content "$release/original-args.json" -Raw | ConvertFrom-Json
$pg = Get-Content "$release/postgresql-args.json" -Raw | ConvertFrom-Json
$back = Get-Content "$release/rollback-args.json" -Raw | ConvertFrom-Json
$source = 'fairy-commerceflow-invoice-db'
$target = 'fairy-commerceflow-invoice-db-postgresql'
if ((docker inspect fairy-commerceflow-invoice-simulator-service --format '{{.Image}}') -cne $binding.legacyId -or
    (docker image inspect $binding.legacyTag --format '{{.Id}}') -cne $binding.legacyId -or
    (docker image inspect $binding.candidateTag --format '{{.Id}}') -cne $binding.candidateId) {
    throw '发票镜像与已验收版本不一致'
}
if ((Get-FileHash "$release/invoice-mysql.sql").Hash -cne (Get-Content "$release/backup.sha256")) { throw '发票备份摘要不一致' }
if ((docker exec $target psql -U postgres -d invoice_platform -XAtq -v ON_ERROR_STOP=1 -c (Get-Content infrastructure/invoice/verify-runtime.sql -Raw)) -cne 't') {
    throw '发票运行权限与已验收配置不一致'
}
pwsh scripts/invoice/verify-invoice-postgresql.ps1 -BackupPath "$release/invoice-mysql.sql" -CommerceReceiptsPath "$release/commerce-receipts.jsonl" -CandidateImage $binding.candidateTag -BaselineImage $binding.legacyTag -EvidenceDirectory $release -ReleaseEnvFile "$release/standby.env"
```

正式切换只停止发票模拟服务。冻结原 MySQL，保存快照并核对申请、票据和回执三表后启动候选。迁移入口核对 Compose 项目、数据卷、停写和只读标志，目标已有数据时只允许逐表比对。

```powershell
$stamp = Get-Date -Format yyyyMMdd-HHmmss
docker @old stop invoice-simulator-service
docker exec $source sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -e "SET PERSIST super_read_only=ON"'
docker exec $source sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-lock-tables --hex-blob invoice_platform' > "$release/frozen-$stamp.sql"
node scripts/invoice/invoice-rehearsal-data.mjs fairy-commerceflow release-forward "$release/forward-$stamp.json"
docker @pg up -d --no-deps --no-build --wait invoice-simulator-service
```

启动后核对运行账号连接、接受中的开票请求、回执是否接续以及商城确认的请求号、蓝票号和原票号。若 PostgreSQL 版本启动前中止，确认目标无业务写入后解除源库的 `super_read_only` 和 `read_only`，用 `$old` 恢复旧服务。

PostgreSQL 开始接单后的回退使用独立 MySQL 回退卷。先停止应用并禁用运行账号，终止残余会话，再备份和反向复制。

```powershell
$stamp = Get-Date -Format yyyyMMdd-HHmmss
docker @pg stop invoice-simulator-service
docker exec $target psql -U postgres -d invoice_platform -v ON_ERROR_STOP=1 -c 'ALTER ROLE invoice_runtime NOLOGIN'
docker exec $target psql -U postgres -d invoice_platform -v ON_ERROR_STOP=1 -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='invoice_runtime'"
docker exec $target pg_dump -U postgres -d invoice_platform -Fc > "$release/postgresql-$stamp.dump"
docker @back start --wait invoice-db-rollback
node scripts/invoice/invoice-rehearsal-data.mjs fairy-commerceflow release-reverse "$release/reverse-$stamp.json"
docker @back up -d --no-deps --no-build --wait invoice-simulator-service
```

回退后按原请求查询与重放，核实同一票号和原票号。逐条确认待投递的签名事件沿用原事件号和尝试次数，并与商城账本对齐。

### 物流模拟库隔离演练

隔离演练使用切换前日常物流的 MySQL V7 备份。演练在独立 Docker 内部网络运行，从日常单事务备份恢复一个源库，并另建 PostgreSQL 17.11 目标库和 MySQL 回退库。物流应用只与隔离网络中的数据库和签名回调接收端通信。服务停止后按外键顺序复制五张业务表，各引擎保留自己的 Flyway 历史。每张表单独核对行数、全字段摘要和下一自增值，包含轨迹顺序、回调载荷、重试时间、领取租约、审计信息和用于防止重复请求的 nonce。

在仓库根目录使用 PowerShell 7.4+、Node.js 24+ 及本机 Docker：

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$dir = New-Item -ItemType Directory (Join-Path $env:LOCALAPPDATA ("CommerceFlow/releases/logistics-rehearsal-" + [guid]::NewGuid().ToString('N')))
$baseline = docker inspect fairy-commerceflow-logistics-simulator-service --format '{{.Config.Image}}'
docker exec fairy-commerceflow-logistics-simulator-db sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-lock-tables --default-character-set=utf8mb4 --hex-blob --routines --events --triggers logistics_platform' > "$dir/logistics.sql"
docker build -t commerceflow-logistics-pg:rehearsal simulators/logistics-platform/service
pwsh scripts/fulfillment/verify-logistics-postgresql.ps1 -BackupPath "$dir/logistics.sql" -CandidateImage commerceflow-logistics-pg:rehearsal -BaselineImage $baseline -EvidenceDirectory $dir
```

脚本通过物流 API 读取历史发货、退货各一单，然后创建两张新运单，按真实事件号比对轨迹和签名回执。接收端返回 HTTP 503 时产生待重试记录。一次在 PostgreSQL 上恢复，另一次在反向复制后由旧版 MySQL 服务恢复。结果目录保留两份逐表摘要和两次重试的事件号，隔离容器停止。

### 物流模拟库日常发布窗口

准备目录为 `%LOCALAPPDATA%/CommerceFlow/releases/logistics-pg-ready-1795680814/`。日常物流现写入 PostgreSQL 17.11 V1，使用卷 `fairy-commerceflow_logistics-postgresql-data`。原 MySQL V7 和卷 `fairy-commerceflow_logistics-simulator-db-data` 保持冻结时的只读状态。重新启动当前物流应用使用下文的 `$pg` 参数。[日常切换验收](../IMPLEMENTED.md#2112-2026-11-26-日常物流切换-postgresql)

独立回退卷 `fairy-commerceflow_logistics-rollback-data` 已恢复 MySQL 的表结构、索引、约束及七条迁移历史，回退实例平时停止。

发布参数沿用原物流服务的 Compose 文件、端口、签名密钥、回调和调度配置。`logistics_migrator` 持有业务 schema，应用以 `logistics_runtime` 连接，进程内 Flyway 已关闭。运行账号可建运单、追加轨迹和审计，只能更新 Mapper 所用的运单与投递状态字段；签名 nonce 仅可追加。

从仓库根目录用 PowerShell 7.4+ 加载本机发布参数：

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$release = Join-Path $env:LOCALAPPDATA 'CommerceFlow/releases/logistics-pg-ready-1795680814'
$old = Get-Content "$release/original-args.json" -Raw | ConvertFrom-Json
$pg = Get-Content "$release/postgresql-args.json" -Raw | ConvertFrom-Json
$back = Get-Content "$release/rollback-args.json" -Raw | ConvertFrom-Json
$binding = Get-Content "$release/release-binding.json" -Raw | ConvertFrom-Json
$source = 'fairy-commerceflow-logistics-simulator-db'
$target = 'fairy-commerceflow-logistics-simulator-db-postgresql'
```

**切换前**核对镜像、源卷、备份摘要和账号权限。隔离复验入口使用同一份发布配置，在独立网络重放备份之后产生的最后一批事件：

```powershell
if ((docker inspect fairy-commerceflow-logistics-simulator-service --format '{{.Image}}') -ne $binding.originalId -or
    (docker image inspect $binding.originalTag --format '{{.Id}}') -ne $binding.originalId -or
    (docker image inspect $binding.candidateTag --format '{{.Id}}') -ne $binding.candidateId) { throw '物流镜像已变化' }
if ((Get-FileHash "$release/logistics-backup.sql").Hash -ne (Get-Content "$release/backup.sha256")) { throw '备份摘要不同' }
if ((docker exec $target psql -U postgres -d logistics_platform -XAtq -v ON_ERROR_STOP=1 -c (Get-Content infrastructure/logistics/verify-runtime.sql -Raw)) -ne 't') { throw '物流运行权限已变化' }
pwsh scripts/fulfillment/verify-logistics-release.ps1 -BackupPath "$release/logistics-backup.sql" -ReleaseEnvFile "$release/standby.env" -EvidenceDirectory $release
```

**正式切换**只停止物流模拟服务，让签名请求和回调投递停止写入。固定原库只读并保存冻结备份，复制最终五张业务表和全部自增序列。每张表的摘要、行数和下一自增值一致后，再启动候选服务。

```powershell
$stamp = Get-Date -Format yyyyMMdd-HHmmss
docker @old stop logistics-simulator-service
docker exec $source sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -e "SET PERSIST super_read_only=ON"'
docker exec $source sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-lock-tables --default-character-set=utf8mb4 --hex-blob --routines --events --triggers logistics_platform' > "$release/frozen-$stamp.sql"
node scripts/fulfillment/logistics-rehearsal-data.mjs fairy-commerceflow release-forward "$release/forward-$stamp.json"
docker @pg up -d --no-deps --no-build --wait logistics-simulator-service
```

核对受限账号的实际连接，再按商户运单号核对商城的发货、退货轨迹和物流事件号。对窗口内待投递的事件核对尝试次数、租约、审计和商城回执。如果在新应用启动前中断，确认目标无业务写入后，解除原 MySQL 的 `super_read_only` 和 `read_only`，用 `$old` 恢复原服务。

**新应用启动后的回退**使用独立 MySQL 回退卷。停止物流应用，禁用 PostgreSQL 运行账号并断开残余连接，取原生备份，将最新账本反向复制后启动旧版。两个方向都核对项目和数据卷，目标已含数据时只接受逐表内容和序列均一致的重试。

```powershell
$stamp = Get-Date -Format yyyyMMdd-HHmmss
docker @pg stop logistics-simulator-service
docker exec $target psql -U postgres -d logistics_platform -v ON_ERROR_STOP=1 -c 'ALTER ROLE logistics_runtime NOLOGIN'
docker exec $target psql -U postgres -d logistics_platform -v ON_ERROR_STOP=1 -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='logistics_runtime'"
docker exec $target pg_dump -U postgres -d logistics_platform -Fc > "$release/postgresql-$stamp.dump"
docker @back start --wait logistics-simulator-db-rollback
node scripts/fulfillment/logistics-rehearsal-data.mjs fairy-commerceflow release-reverse "$release/reverse-$stamp.json"
docker @back up -d --no-deps --no-build --wait logistics-simulator-service
```

回退后按原事件号核对待重试回调由下一次尝试完成、签名回执与轨迹对应，再复核旧版可以推进切换后创建的运单。[发布准备验收](../IMPLEMENTED.md#2111-2026-11-26-物流模拟库日常发布准备)

### 仓库模拟库隔离演练

从日常 MySQL V4 获取一致性备份。仓库模拟服务继续接收日常出库和退货，复测脚本只向新建的隔离 Compose 项目写入。PowerShell 7.4+ 保留原生备份字节，迁移工具使用 Node.js 24+。

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$evidence = New-Item -ItemType Directory (Join-Path $env:LOCALAPPDATA ("CommerceFlow/releases/warehouse-rehearsal-" + [guid]::NewGuid().ToString('N')))
$baseline = docker inspect fairy-commerceflow-warehouse-simulator-service --format '{{.Config.Image}}'
docker exec fairy-commerceflow-warehouse-simulator-db sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-lock-tables --default-character-set=utf8mb4 --hex-blob warehouse_platform' > "$evidence/warehouse.sql"
docker build -t commerceflow-warehouse-pg:rehearsal simulators/warehouse-platform/service
pwsh scripts/fulfillment/verify-warehouse-postgresql.ps1 -BackupPath "$evidence/warehouse.sql" -CandidateImage commerceflow-warehouse-pg:rehearsal -BaselineImage $baseline -EvidenceDirectory $evidence
```

当本机已有本批次商城、仓库管理台等验收镜像时，可加 `-SkipBuild`。脚本从备份恢复隔离 MySQL 源库和独立回退库的表结构与 Flyway 历史。PostgreSQL 使用自己的 Flyway 基线和 `postgresql` Profile。迁移校验逐字段比较两张表，统一微秒时间及 JSON 对象的键顺序，保留明细数组的顺序、长整型编号、实发数量与空值。反向复制前还会检查历史单据与原始快照一致。运行后保留隔离卷和证据，服务停止。

2026-11-26 隔离环境从日常备份迁入 29 张出库单、16 张退货单，经过短发补发和退货等业务后，反向恢复 43 张出库单、20 张退货单。旧版 MySQL 服务读回了质检回执，并完成了 PostgreSQL 上创建的待出库单。[仓库隔离验收记录](../IMPLEMENTED.md#2107-2026-11-26-仓库模拟库-postgresql-隔离验收)

### 仓库模拟库日常发布窗口

**日常现状（2026-11-26 15:30）**。仓库通过 `warehouse_runtime` 写入 PostgreSQL 17.11 的 `warehouse_platform`，现役卷为 `fairy-commerceflow_warehouse-postgresql-data`。原 MySQL V4 卷持久化为强制只读，保留切换时的 29 张出库单、16 张退货单。独立回退卷 `fairy-commerceflow_warehouse-rollback-data` 已有 V4 表结构与迁移历史，回退实例平时停止。新订单 752 完成出库、退货及回补后，现役库有 30 张出库单、17 张退货单。

发布目录保存原服务镜像、候选镜像 ID、原 MySQL 单事务备份及其哈希、凭据和三组 Compose 参数。参数沿用仓库服务现役覆盖文件、签名配置和端点。使用 PowerShell 7.4+ 从仓库根目录加载：

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$release = Join-Path $env:LOCALAPPDATA 'CommerceFlow/releases/warehouse-pg-ready-1795676371'
$old = Get-Content "$release/original-args.json" -Raw | ConvertFrom-Json
$pg = Get-Content "$release/postgresql-args.json" -Raw | ConvertFrom-Json
$back = Get-Content "$release/rollback-args.json" -Raw | ConvertFrom-Json
$binding = Get-Content "$release/release-binding.json" -Raw | ConvertFrom-Json
$source = 'fairy-commerceflow-warehouse-simulator-db'
$target = 'fairy-commerceflow-warehouse-simulator-db-postgresql'
```

日常启动已创建的 PostgreSQL 和仓库服务时，使用现役参数；需要回退时加载以上变量后直接执行下方“新服务启动后的回退”。

```powershell
docker @pg up -d --no-deps --no-build --wait warehouse-simulator-db-postgresql
docker @pg up -d --no-deps --no-build --wait warehouse-simulator-service
```

**切换前的复验记录**。下列检查以旧镜像仍承接业务为前提。

```powershell
if ((docker inspect fairy-commerceflow-warehouse-simulator-service --format '{{.Image}}') -ne $binding.originalId -or
    (docker image inspect $binding.originalTag --format '{{.Id}}') -ne $binding.originalId -or
    (docker image inspect $binding.candidateTag --format '{{.Id}}') -ne $binding.candidateId) { throw '仓库镜像已变化' }
if ((Get-FileHash "$release/warehouse-backup.sql").Hash -ne (Get-Content "$release/backup.sha256")) { throw '备份摘要不同' }
pwsh scripts/fulfillment/verify-warehouse-release.ps1 -BackupPath "$release/warehouse-backup.sql" -ReleaseEnvFile "$release/standby.env" -EvidenceDirectory $release
```

**本次正式切换步骤**。单独停止仓库模拟服务，把原库持久化为只读，备份并复制这一刻的全部出库和退货记录。最后一次全量包括备份后已签收或质检的旧单。复制核对成功后启动候选服务。

```powershell
$stamp = Get-Date -Format yyyyMMdd-HHmmss
docker @old stop warehouse-simulator-service
docker exec $source sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -e "SET PERSIST super_read_only=ON"'
docker exec $source sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-lock-tables --default-character-set=utf8mb4 --hex-blob --routines --events --triggers warehouse_platform' > "$release/frozen-$stamp.sql"
node scripts/fulfillment/warehouse-rehearsal-data.mjs fairy-commerceflow release-forward | Tee-Object "$release/forward-$stamp.json"
docker @pg up -d --no-deps --no-build --wait warehouse-simulator-service
```

切换后从仓库管理接口按原单号查出库与退货，核对履约和库存中的仓库、数量及来源单，并从 PostgreSQL 的 `pg_stat_activity` 确认业务连接使用 `warehouse_runtime`。新服务尚未启动便中断时，确认目标库没有后续业务写入，再解除原 MySQL 的 `super_read_only` 和 `read_only`，用 `$old` 恢复旧服务。

**新服务启动后的回退**使用独立回退卷。原 MySQL 保持只读。停止新服务，禁用运行账号并结束残余连接，保存 PostgreSQL 原生备份，再把当前数据逐字段复制、校验并启动旧版。

```powershell
$stamp = Get-Date -Format yyyyMMdd-HHmmss
docker @pg stop warehouse-simulator-service
docker exec $target psql -U postgres -d warehouse_platform -v ON_ERROR_STOP=1 -c 'ALTER ROLE warehouse_runtime NOLOGIN'
docker exec $target psql -U postgres -d warehouse_platform -v ON_ERROR_STOP=1 -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='warehouse_runtime'"
docker exec $target pg_dump -U postgres -d warehouse_platform -Fc > "$release/postgresql-$stamp.dump"
docker @back start --wait warehouse-simulator-db-rollback
node scripts/fulfillment/warehouse-rehearsal-data.mjs fairy-commerceflow release-reverse | Tee-Object "$release/reverse-$stamp.json"
docker @back up -d --no-deps --no-build --wait warehouse-simulator-service
```

回退后重查切换前后的出库和退货凭证，并核对待签收或待质检单据能按原号继续办理。两种复制均检查项目、卷、写入方状态和源目标摘要；目标含不同数据时脚本退出并保留数据供核对。[发布准备验收](../IMPLEMENTED.md#2108-2026-11-26-仓库模拟库日常发布准备)

### 站外消息模拟库隔离演练

在仓库根目录使用以下命令构建候选镜像并演练。默认基线为日常使用的旧版消息模拟服务镜像。可用 `-BaselineImage` 指定本机的对应版本。需要本机 Docker、PowerShell 7 和 Node.js。

```powershell
docker build -t commerceflow-messaging-pg:rehearsal simulators/messaging-platform/service
pwsh scripts/messaging/verify-messaging-postgresql.ps1 -CandidateImage commerceflow-messaging-pg:rehearsal -WithDailySnapshot
```

`-WithDailySnapshot` 从日常 MySQL 的同一只读事务读取两张表，并从通知库读取带供应商编号的终态回执。省略该参数时仅使用隔离样本。每次运行新建独立 Compose 项目，源 MySQL、目标 PostgreSQL 和回退 MySQL 各有独立卷。切换前停止项目内的消息服务，复制后按请求号顺序对比两表所有字段的 SHA-256，包括微秒时间、空值、内容指纹和故障设置。Flyway 历史分别由各自引擎生成。

候选镜像使用 `postgresql` Spring Profile 选择 PostgreSQL 迁移和 Mapper；MySQL 使用默认 Profile。演练结束后服务停止，三个隔离卷保留，项目名和两次迁移摘要写入输出。

2026-11-26 已完成带日常快照的演练，正向迁入 46 条投递，反向恢复 55 条投递。随后使用日常发布配置与原生备份复验了停写期间的最终同步，账号、目标卷和回退卷均已准备。[回执与恢复验收](../IMPLEMENTED.md#2104-2026-11-26-站外消息模拟库-postgresql-隔离验收)

### 站外消息模拟库日常发布窗口

**日常现状（2026-11-26 14:15）。** 供应商通过 `messaging_runtime` 写入 `messaging-simulator-db-postgresql/messaging_platform`。原 MySQL 持久化为强制只读，保留切换时的 40 条投递。日常查询与恢复启动沿用发布目录的 `daily-pg-args.json`。已创建的容器可按该配置执行 `docker @pg start --wait messaging-simulator-service`。下方窗口前、窗口内命令记录本次迁移；当前需要回退时使用“切换后回退”一节。[日常切换与投递结果](../IMPLEMENTED.md#2106-2026-11-26-站外消息模拟库日常切换)

本机发布目录 `%LOCALAPPDATA%/CommerceFlow/releases/messaging-pg-ready-1795670404` 已保存 MySQL 一致性备份、SHA-256、原版与候选镜像 ID、Compose 参数、角色凭据及回退表结构。日常 PostgreSQL 17.11 已使用卷 `fairy-commerceflow_messaging-postgresql-data` 建立 V1。独立回退卷 `fairy-commerceflow_messaging-rollback-data` 含 MySQL V1/V2 的结构和迁移历史，业务表为空，实例平时停止。原 MySQL 卷为 `fairy-commerceflow_messaging-simulator-db-data`。

`messaging_migrator` 持有业务 schema 并执行 Flyway。服务使用 `messaging_runtime`，关闭进程内 Flyway 和故障注入。该账号可读和新增投递，只可更新 `status`、`finished_at` 两列。构建发布配置时叠加消息服务当前的 Compose 文件、仓库 `.env` 和发布目录的 `standby.env`，保留原端点与签名密钥。服务切换期间，通知服务将供应商暂不可用的请求留待重试。

**窗口前复验。** 在仓库根目录用 PowerShell 7.4+ 运行。备份及数据库凭据留在本机发布目录。校验镜像 ID、卷和备份哈希。以下加载的三个参数数组分别对应原服务、PostgreSQL 和回退服务。

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$release = Join-Path $env:LOCALAPPDATA 'CommerceFlow/releases/messaging-pg-ready-1795670404'
$old = Get-Content "$release/original-args.json" -Raw | ConvertFrom-Json
$pg = Get-Content "$release/daily-pg-args.json" -Raw | ConvertFrom-Json
$back = Get-Content "$release/rollback-args.json" -Raw | ConvertFrom-Json
$binding = Get-Content "$release/release-binding.json" -Raw | ConvertFrom-Json
if ((docker inspect fairy-commerceflow-messaging-simulator-service --format '{{.Image}}') -ne $binding.providerImage -or
    (docker image inspect $binding.candidateTag --format '{{.Id}}') -ne $binding.candidateImage) { throw '消息服务镜像已变化' }
if ((Get-FileHash "$release/messaging-backup.sql").Hash -ne (Get-Content "$release/mysql-backup-sha256.json" -Raw | ConvertFrom-Json).Hash) { throw '备份哈希不一致' }
pwsh scripts/messaging/verify-messaging-release.ps1 -BackupPath "$release/messaging-backup.sql" -ReleaseEnvFile "$release/standby.env" -EvidenceDirectory $release
```

**窗口内切换。** 本轮数据量小，停写后一次复制最终全量，包含备份之后新增、更新和已消耗的故障设置。记录窗口开始时通知库的待发送数及已有供应商回执。停消息服务，持久化原库只读，再保存此刻的完整备份并同步。复制入口要求服务已停、源库已锁定，目标为空或全字段摘要已一致。

```powershell
$stamp = Get-Date -Format yyyyMMdd-HHmmss
$mysql = 'fairy-commerceflow-messaging-simulator-db'
$target = 'fairy-commerceflow-messaging-simulator-db-postgresql'
docker @old stop messaging-simulator-service
docker exec $mysql sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -e "SET PERSIST super_read_only=ON"'
docker exec $mysql sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-lock-tables --hex-blob --routines --events --triggers messaging_platform' > "$release/frozen-$stamp.sql"
node scripts/messaging/messaging-rehearsal-data.mjs fairy-commerceflow release-forward | Tee-Object "$release/forward-$stamp.json"
docker @pg up -d --no-deps --no-build --wait messaging-simulator-service
```

启动后核对服务健康、受限账号的实际连接，以及历史通知与供应商的请求号、供应商编号、渠道与状态。日常直接送测短信和邮件可运行 `pwsh scripts/messaging/verify-messaging-simulator.ps1`。同步检查通知库中窗口内待投递记录能否恢复，记录新增回执。若在新服务首次启动之前中断，确认 PostgreSQL 没有写入方后，可在原 MySQL 执行 `SET PERSIST super_read_only=OFF` 和 `SET PERSIST read_only=OFF` 并通过原配置恢复旧版服务。新服务启动后按下列流程保留所有后续写入。

**切换后回退。** 停止供应商、封住 PostgreSQL 运行账号并断开其残留连接。备份 PostgreSQL，把最终账本复制到独立 MySQL 回退卷。原 MySQL 继续只读。下面沿用前文参数，单独执行时先重新加载参数并设置目标容器名。

```powershell
$stamp = Get-Date -Format yyyyMMdd-HHmmss
$target = 'fairy-commerceflow-messaging-simulator-db-postgresql'
docker @pg stop messaging-simulator-service
docker exec $target psql -U postgres -d messaging_platform -v ON_ERROR_STOP=1 -c 'ALTER ROLE messaging_runtime NOLOGIN'
docker exec $target psql -U postgres -d messaging_platform -v ON_ERROR_STOP=1 -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='messaging_runtime'"
docker exec $target pg_dump -U postgres -d messaging_platform -Fc > "$release/postgresql-$stamp.dump"
docker @back start --wait messaging-simulator-db-rollback
node scripts/messaging/messaging-rehearsal-data.mjs fairy-commerceflow release-reverse | Tee-Object "$release/reverse-$stamp.json"
docker @back up -d --no-deps --no-build --wait messaging-simulator-service
```

回退后用旧版服务读取切换前后各一笔回执，核对消息状态及供应商编号，再观察通知服务重试收敛。若复制发现非空目标与源摘要不同，将保留两端数据并报错。此时核对项目、卷和写入记录后，在独立空目标恢复相应表结构再重试。[发布准备验收](../IMPLEMENTED.md#2105-2026-11-26-站外消息模拟库日常发布准备)

### 核心商城的启动与回退

日常核心商城服务通过 `commerce-db-postgresql:5432/commerceflow` 读写，业务 schema 为 `commerceflow`。MySQL 原卷 `fairy-commerceflow_commerce-db-data` 及 `commerce_verification` 保持只读。PostgreSQL 数据卷为 `fairy-commerceflow_commerce-postgresql-data`。运行账户仅持有 49 张表的授权，五个生成列继续约束结算、售后、打款和运单唯一性。[日常切换验收](../IMPLEMENTED.md#2102-2026-11-26-核心商城切换-postgresql)

**日常恢复启动。** 本机发布目录保存了覆盖文件顺序、两个数据库密码和现役镜像。使用发布参数检查状态。已存在的容器可用同一组参数执行 `start --wait`，这会沿用创建时的配置。基础 Compose 的 MySQL 配置用于独立学习环境。

```powershell
$release = Join-Path $env:LOCALAPPDATA 'CommerceFlow/releases/commerce-v74-pg-1795662278646'
$daily = Get-Content "$release/daily-pg-args.json" -Raw | ConvertFrom-Json
docker @daily ps commerce-service commerce-db-postgresql
```

**迁移或演练。** PostgreSQL 覆盖文件位于 `infrastructure/compose/compose.commerce-postgresql.yaml`，Flyway 基线位于 `services/commerce-service/src/main/resources/db/postgresql`。先创建独立目标卷、执行 Flyway 并核对 schema。源端停写或使用已恢复的静态副本；目标业务表须为空。PowerShell 7 和 Node.js 24 或以上可运行 `scripts/commerce/verify-commerce-postgresql-data.ps1`。显式给出两个容器、数据库和摘要输出路径，`ToPostgreSQL` 复制到 PostgreSQL，`Compare` 只读核对。每次比较均校验逐表行数、逻辑值及序列。按 `infrastructure/commerce/postgresql-runtime-grants.sql` 配置运行账户，并与源端表授权逐项核对。日常读数及定时任务验收使用单独的列级权限，见同目录的 `postgresql-verification-grants.sql`。

**已有新交易时回退。** 暂停商城写入，备份当前 PostgreSQL。在独立 MySQL 卷恢复 V74 表结构和原 Flyway 历史，保留空业务表，将触发器及过程留到数据复制之后恢复。用同一脚本的 `ToMySQL` 复制最新 PostgreSQL 数据并校验 35 个自增序列。恢复受限账户与必要的数据库对象，然后启动原版商城并通过 HTTP 核对切换后的新订单、库存及纠正回执。切回经过核对的新 MySQL 实例后，再开放写入。原切换前备份和只读卷用于核对历史。

PostgreSQL 专项与服务拆分、目录及命名重构分开推进。2026-11-21 已核实 M3 六个服务的单一运行所有权。
入口退役、准入审计和当前版本恢复已通过隔离验收，M3 已在[日常发布与收尾核验](M3-SINGLE-OWNERSHIP-REVIEW.md#14-2026-11-21-日常发布与-m3-验收)
后正式关闭。M4 已完成通知服务日常切换及[门户路径收尾验收](#2026-11-22-m4-门户路径收尾验收)。
其他 MySQL 业务库的行锁、唯一约束、幂等、Outbox 和事务竞态继续使用已实测的 InnoDB 基线。

## PostgreSQL 专项的启动条件

以下保留 M4 通知库试点的准入口径。后续各单库迁移按 [架构待办](README.md#5-按需启动的架构事项) 评估收益、范围与验收。

- M3 清单中的 notification、catalog、inventory、order、payment-orchestration、fulfillment 已全部独立，
  且不存在过渡期双写、单体回退或待完成的数据所有权交接；后续新增待拆服务也已完成。
- 明确的学习目标包含 JSONB、部分/表达式索引、Advisory Lock、`SKIP LOCKED` 或 PostgreSQL 锁模型。
- 结构命名和 MyBatis 数据访问治理已完成，不与包/目录迁移混合。
- 允许从空卷重建，并愿意重新执行 M1 全部并发与故障门禁。

## 迁移工作包

1. 建立 PostgreSQL 方言分支，不长期同时维护两套生产 SQL。
2. 重写 Flyway 基线：Identity、时间精度、JSONB、约束和索引。
3. 转换 `INSERT IGNORE`、`ON DUPLICATE KEY UPDATE`、`DATE_ADD`、MySQL JSON 和锁等待配置。
4. 核对 MyBatis Mapper XML 与 PostgreSQL 返回类型。
5. 重新验证支付/取消锁超时、崩溃恢复、物流乱序、收货/完成竞态和 MQ 故障。
6. 记录两种数据库在锁、索引、执行计划和故障恢复方面的学习对比。

## 微服务后的数据库原则

- 每个服务独立拥有 Schema/实例和 Flyway 历史。
- 不为技术多样性强行引入多种数据库；只在查询、一致性或扩展需求不同时使用多模存储。
- 跨服务一致性使用事件、Outbox/Inbox、幂等和可观测补偿，不通过跨库事务掩盖边界。

## M4 通知服务 PostgreSQL 17 隔离试点

试点只迁通知服务的数据库。商城通知 Outbox、Kafka 消费协议、站外渠道契约和日常 Compose 继续沿用现有配置。
先用原通知镜像在隔离 MySQL 8.4 复跑，再从同一份八表快照迁入 PostgreSQL 17。PostgreSQL 使用独立 Flyway
基线和历史表，以 PostgreSQL SQL 实现冲突处理及时间计算。投递沿用现有的到期查询、尝试次数比较和租约校验。

### 复跑方式

```powershell
pwsh -NoProfile -File scripts/messaging/verify-notification-postgresql.ps1
```

通过 `-MySqlDump` 指定已有通知库归档可复跑存量场景，`-MySqlOnly` 单独运行 MySQL 对照和恢复。
缺少默认通知试点镜像时，脚本从当前代码构建支持 PostgreSQL 的验收镜像。通过 `-CandidateImage` 可固定指定镜像。
脚本使用一次性网络、Kafka、短信邮件模拟器及数据库，应用端口绑定在环回地址。每组 Kafka 和模拟器独立，
同组恢复和反向回退沿用该组 Kafka 及模拟器账本。默认将证据保存到系统临时目录，`-EvidenceDirectory` 可指定目录。

在同一验收入口添加 `-CutoverAndCapacity` 可运行分区偏移和容量专项。默认每个数据库预热 60 条，测量三轮，
每轮 3,000 个唯一事件，每 10 条额外重放一条。可通过 `-LoadRecords` 调整单轮规模。

```powershell
pwsh -NoProfile -File scripts/messaging/verify-notification-postgresql.ps1 -CutoverAndCapacity
```

默认应用构建沿用 MySQL 依赖。PostgreSQL 试点构建增加 JDBC 和 Flyway PostgreSQL 模块，由 `postgresql` 运行
Profile 选择迁移及 Mapper 方言。事件 ID、消费者名及投递请求 ID 使用不区分大小写和重音的 ICU 排序规则，
对照验证了大小写变体去重。两种引擎的所有 Unicode 权重等价关系尚未评估。
[Flyway PostgreSQL 驱动和模块](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database)
[PostgreSQL 17 排序规则](https://www.postgresql.org/docs/17/collation.html)

### 验收口径

| 项目 | 断言 |
| --- | --- |
| 存量迁移 | 八张业务与审计表按字段规范化后逐表比较摘要，覆盖 JSON 对象、UTC 微秒、空值和历史业务 ID。目标使用独立 Flyway 历史，导入后验证新通知可使用独立序列写入。 |
| 消费与已读 | 同事件重复、大小写变体均落一条 Inbox 和一条可见通知，核对 Kafka 消费偏移已提交。跨客户标记不生效，单条与全部已读保留原首次已读时间。 |
| 短信与邮件重试 | 模拟器停止时两个渠道进入重试，恢复后沿用原请求号完成投递。重复提交和异内容冲突得到预期响应，供应方回执与本地投递记录一致。 |
| 备份和反向回退 | 各自用原生备份恢复到空库，逐表核对并复跑 Kafka 和站外重放。停候选消费者和投递进程后，把含 PostgreSQL 新写入的八表快照回填隔离 MySQL，用原通知镜像并沿用同组 Kafka 和模拟渠道复验。 |
| Kafka 切换与回退 | 分别停旧写入方，保留同一 Kafka 集群、主题和消费组的分区提交位置。导入八表后核对快照和偏移，再以 `auto.offset.reset=none` 启动接管者。切换停窗内继续投递，接管后逐分区核对积压归零，并按事件 ID 核对 Inbox、可见通知及已读时间。 |
| 数据库故障和偏移重放 | 拒绝 PostgreSQL 应用账号连接并断开已建立连接。故障期间验证偏移不前移、正常事件不进死信；恢复后排空积压。另用隔离消费组回拨到旧检查点，模拟数据库提交成功而偏移提交丢失。回放后的业务快照必须一致，坏消息仍须进入死信。 |
| 容量对照 | 同一起始数据、同一隔离 Kafka 实例，两库各使用独立主题依次运行。记录从开始发送到全部唯一事件可见的三轮吞吐中位数，以及压测结束后连续 30 次签名未读计数请求的 P95。每轮核对三分区偏移提交，最终逐条核对事件 ID 和重复消除。 |

### 2026-11-21 验收记录

- MySQL 8.4.13 和 PostgreSQL 17.11 实例均通过上述用例。通知库归档 SHA-256 已核对。归档与一条 JSON 测试行
  共八表 635 行，正向导入摘要逐表一致。含 PostgreSQL 新写入的八表 642 行经过原生恢复及反向 MySQL 回退后，
  签名 API 的首次已读时间、Kafka 重放后的通知及 Inbox 记录、短信和邮件请求号及供应方回执均一致。
- PostgreSQL 试点 Dockerfile 构建通过，通知服务两项 Maven 测试和 77 项 PowerShell 脚本契约审计通过。
  本轮用模拟器停机与恢复验证投递重试，性能、进程中断组合和生产偏移迁移需在扩大试点前另行评审。
- 结果、逐表摘要、原生备份和校验清单保存在
  `%LOCALAPPDATA%/CommerceFlow/releases/m4-notification-postgresql-20261121/fairy-commerceflow-m4-75d2cf2557`。
  临时容器与网络已清理，日常 42 个容器的 ID、启动时间、镜像、挂载及 15 个项目数据卷清单前后一致。

### 2026-11-21 Kafka 偏移、故障恢复与容量验收

专项使用同一个隔离 Kafka 实例的三个分区，保留主题 `customer-notification-commands` 和消费组
`commerceflow-independent-notification-v1`。既有 Kafka 偏移由同一消费组接续，数据库迁移和回退时均核对提交位置。

| 阶段 | 分区 0、1、2 的提交位置 | 核对结果 |
| --- | --- | --- |
| 停止 MySQL 写入方 | 6、6、6 | 停窗内追加 18 条，提交位置保持不动。 |
| PostgreSQL 接管 | 12、12、12 | 18 条积压消费完毕。 |
| PostgreSQL 拒绝应用连接 | 12、12、12 | 4 次处理失败，三个分区各积压 4 条。正常事件没有进死信。 |
| 恢复并重放 | 16、16、16 | 排空 12 条积压。回拨到 12、12、12 后再次消费，八表快照一致。 |
| 停止 PostgreSQL 写入方 | 16、17、16 | 一条坏消息进入死信。 |
| MySQL 回退完成 | 20、21、20 | 含新写入的八表反向快照一致，停窗 12 条全部处理。 |

切换、断连、恢复、回拨和回退累计核对 60 个有效事件。每个事件在 Inbox 与可见通知表各有一条，归属正确，
首次已读时间保持一致。候选对暂时性存储异常保留重试，避免数据库断连耗尽固定次数后把正常通知送进死信。
[Spring Kafka 错误处理](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)

| 数据库 | 三轮吞吐（唯一通知/秒） | 吞吐中位数（唯一通知/秒） | 未读计数 P95 |
| --- | --- | ---: | ---: |
| MySQL 8.4.13 | 317.84 / 441.31 / 516.18 | 441.31 | 15.78 ms |
| PostgreSQL 17.11 | 560.68 / 648.77 / 727.23 | 648.77 | 19.76 ms |

每库处理 9,000 个唯一事件及 900 次重复发送，三分区积压归零，Inbox 和可见通知逐条一致，容量阶段均无死信。
本次 PostgreSQL 的写入吞吐较高，压测结束后的未读计数延迟也略高。两库均使用临时内存挂载和单节点 Kafka，
结果用于本轮隔离对照。日常磁盘持久化、峰值并发和长时间积压恢复仍需用日常基线复测。

证据保存在 `%LOCALAPPDATA%/CommerceFlow/releases/m4-offset-capacity-20261121/fairy-commerceflow-m4-c45a605079`，
包含逐分区偏移、回放检查点 CSV、完整压测结果和 SHA-256 清单。日常 42 个容器的 ID、启动时间、镜像、
挂载以及 15 个项目数据卷清单前后一致。

同一候选镜像也通过原有的 MySQL 对照、八表导入、去重、已读、短信邮件重试、原生备份恢复和反向 MySQL 回归。
回归证据保存在同级目录 `fairy-commerceflow-m4-f1ed074fa0`。

**结论：通知服务可以进入日常切换评审。** 发布审批须通过下面的门槛。

### 日常切换评审门槛

- 核对日常 Kafka 集群、主题、消费组、分区清单和日志起止位置，确认 Kafka 保留期及消费组偏移保留期覆盖预计停窗、故障排查和回退时间。需要在切换前留存逐分区提交位置和通知库原生备份。
- 暂停通知 API 写入、Kafka 消费和站外投递，确认旧进程退出后导出八表快照。候选接管前核对快照和逐分区偏移，禁止缺失偏移时自动跳到新位置。回退前停候选，用含 PostgreSQL 新写入的快照恢复 MySQL，同时保留当前消费组偏移。
- 确认日常峰值事件速率、容许积压、恢复时间和 API 延迟目标，再以代表性数据量和并发配置复测。数据库不可用时允许积压，需同时监控 Kafka Lag、`commerceflow_notification_consumer_storage_retries_total` 和死信，并约定触发处置与回退的门槛。
- 批准范围仅限通知服务，并单独确认日常维护窗口和回退负责人。扩大 PostgreSQL 到其他服务仍需独立评审。

### 2026-11-21 日常切换评审

#### 日常环境只读基线

| 项目 | 观测结果 |
| --- | --- |
| 通知 Kafka 消费 | Prometheus 七日查询实际覆盖北京时间 11 月 19 日 20:47 至 11 月 21 日 21:34，共 13,731 个抓取状态样本，269 个为不可用。最大五分钟增量约 1.03 条。MySQL Inbox 现存 310 条，固定五分钟、一分钟和一秒桶的最高值分别是 14、8 和 4 条。 |
| 通知 API 与站外工作 | 内部 API 最大五分钟增量约 8.28 次。未配置可计算延迟 P95 的请求直方图。MySQL 站外投递最大固定五分钟桶为 10 条，检查时 24 条均已投递。 |
| 分区及偏移 | Topic ID 为 `1mqc4XpKSfuluERCCZrxPA`，共 3 个分区，副本数 1。分区 0、1、2 的日志起点均为 0，已提交偏移与日志末尾分别为 45、41、38，积压均为 0。 |
| 保留期 | Kafka 采用 `cleanup.policy=delete`，消息保留 604,800,000 ms（7 天），无字节保留上限。空消费组的偏移保留为 10,080 分钟（7 天）。 |
| 实际日志位置 | 通知主题 3 个分区和 `__consumer_offsets` 50 个分区均由 broker 报告在 `/tmp/kafka-logs`，该路径未挂载持久卷。现有 `fairy-commerceflow_kafka-broker-data` 卷挂在 `/var/lib/kafka/data`。 |

五分钟 Prometheus 增量是采样估算值。数据库历史反映已处理业务，无法测出生产端瞬时峰值；当前观测窗负载也较低。
Kafka 的七天配置只有在日志文件持续存在时才有意义。重建当前 broker 容器会让旧容器可写层里的日志和消费组偏移无法接续。
[Prometheus 查询函数](https://prometheus.io/docs/prometheus/latest/querying/functions/) · [Kafka 4.1 Broker 配置](https://kafka.apache.org/41/configuration/broker-configs/) · [Kafka 4.1 Topic 配置](https://kafka.apache.org/41/configuration/topic-configs/)

#### 磁盘复测方法

```powershell
pwsh -NoProfile -File scripts/messaging/verify-notification-postgresql.ps1 `
  -CutoverAndCapacity -DiskBacked -LoadRecords 6000
```

Kafka、MySQL 和 PostgreSQL 数据落到独立临时 Docker 磁盘卷。实际 Kafka 日志目录已核对在测试卷上。
MySQL 开启 binlog 和提交刷盘，PostgreSQL 启用 `fsync`、`synchronous_commit` 和 `full_page_writes`。
同一隔离 broker 上按引擎顺序压测，每库三轮，每轮发送 6,000 个唯一事件和 600 次重复发送。
计时覆盖生产到通知可见，随后进行 30 次顺序未读查询。积压恢复从重启通知实例开始计时，直到三个分区均确认提交到末尾。

| 数据库 | 吞吐中位数（唯一通知/秒） | 单轮最高（唯一通知/秒） | 压测后未读查询 P95 | 重启后清空 6,600 条积压 |
| --- | ---: | ---: | ---: | ---: |
| MySQL 8.4.13 | 116.43 | 117.73 | 30.20 ms | 68.51 s |
| PostgreSQL 17.11 | 352.65 | 356.75 | 8.40 ms | 31.81 s |

每库容量组核对 18,000 个唯一事件和 1,800 次重复发送。积压组另核对 6,000 个唯一事件和 600 次重复发送。
两组均逐条比对 Inbox 与可见通知，三分区归零、无死信。切换及故障回退用例也复跑通过。未读 P95 来自压测后的
顺序请求，尚缺日常的延迟分布与高并发请求证据。

**结论：暂缓日常切换。** PostgreSQL 磁盘复测通过，当前阻断项是日常 Kafka 实际日志及消费组偏移未落入持久卷。
需单独修复并通过保留集群 ID、日志和偏移的恢复验收，再批准通知服务维护窗口和性能门槛。
证据及 SHA-256 清单位于 `%LOCALAPPDATA%/CommerceFlow/releases/m4-daily-review-20261121`。复核日常
42 个容器的 ID、启动时间、镜像及挂载和 15 个项目数据卷一致，隔离容器、网络和测试卷已清理。

#### 拟议停写与切换顺序

1. **前置条件。** 单独评审 Kafka 持久化修复。在隔离环境验证保留同一集群 ID、KRaft 元数据、主题和消费组分区偏移的恢复流程，并实际重建测试 broker 核对卷上数据仍在。当前日常 broker 和数据卷保留。待修复获独立授权并验收后，再确定通知服务维护窗口。发布前指定维护负责人和回退负责人、固定双端镜像摘要、准备 PostgreSQL 目标库与预演过的迁移和反向恢复命令。
2. **停写窗口建议预留 30 分钟。** 前 5 分钟进入维护并建立检查点，接下来 10 分钟备份、迁移和校验，至少留 15 分钟给判断及回退。窗口起点关闭商城通知已读写入及直连通知服务的写入口，暂停商城站外 Outbox 投递，让其在原业务库中持久积压。Kafka 生产可继续写同一主题。等待站外在途请求结束，确认无正在投递的租约，再停止唯一的 MySQL 通知实例，包括 Kafka 消费者、通知 HTTP 写接口及短信邮件调度。保留 MySQL 容器与卷。
3. **检查点与备份。** 确认原消费组无成员，记录集群和主题 ID、分区、日志起止、提交偏移、死信末尾、未投递请求号及状态，并确认提交偏移仍位于日志范围。备份旧镜像及部署配置，使用 `mysqldump --single-transaction --set-gtid-purged=OFF --no-tablespaces` 导出通知库。保存 SHA-256、八表行数和规范化摘要以及 UTC 时间，在独立 MySQL 验证归档可恢复。敏感连接配置随受控备份单独保存。
4. **迁移与放行。** 从静止的 MySQL 导出八表，导入独立 PostgreSQL 17，校验每张表及序列。启动候选前再次比较偏移，设置 `SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET=none`，同组接管。保持外部写入口暂停，观察积压降至零、无新增死信、已读状态和站外请求号一致。随后用新事件验证签名读写、站外回执和监控，达到验收门槛再恢复入口和站外 Outbox 投递。

#### 回退条件与步骤

- 任一分区缺失、偏移超出日志范围、快照或序列不一致、有效事件进死信、出现漏写或重复投递时立即停止放行。建议阈值为候选启动后 5 分钟内三个分区积压归零，30 次签名未读查询全部成功且 P95 不高于 100 ms，验证通知的短信和邮件在 5 分钟内取得供应方回执。任一阈值未达到，或自停写 15 分钟仍不能完成校验，则进入回退。阈值是本次评审提案，窗口前由维护负责人确认。
- 候选尚无写入且偏移未前进时，确认 PostgreSQL 实例已停止，复核原 MySQL 快照与已提交偏移，用原通知镜像接管。候选已写入时，先再次冻结入口、停止候选并记录最新偏移，再用 `pg_dump -Fc` 保存 PostgreSQL 原生备份及 SHA-256，将含已读、Inbox 和站外请求号的最新八表反向导入独立的 MySQL 恢复目标。旧 MySQL 卷及原始备份留作兜底。
- 逐表核对目标后，以当前消费组已提交的偏移和严格偏移模式启动原镜像。检查三个分区积压归零，核对新旧事件、已读时间和供应方回执后再放开入口。若快照、偏移和日志范围无法对账，继续停写并保全证据；不能按 `earliest` 或 `latest` 盲目重置。

### 2026-11-21 Kafka 持久化修复演练

修复范围是 Kafka 4.1.1 单节点 KRaft 的数据目录。当前布局把业务日志、`__consumer_offsets` 和
`__cluster_metadata-0` 放在 broker 的同一个实际目录。整个目录必须在 broker 干净停机后一起复制，
保留 `meta.properties` 内的集群、节点及目录身份。目标 broker 使用相同镜像和节点配置，显式设置
`KAFKA_LOG_DIRS=/var/lib/kafka/data`。运行中禁止复制整个日志目录、同时运行两个写入方或用新集群重新格式化。
[Kafka 4.1 存储与 KRaft 元数据](https://kafka.apache.org/41/operations/hardware-and-os/) · [Docker 容器文件复制](https://docs.docker.com/reference/cli/docker/container/cp/)

```powershell
pwsh -NoProfile -File scripts/messaging/verify-kafka-persistence.ps1
```

复跑只创建独立网络和临时容器。备份与装载复用 `scripts/messaging/kafka-storage.ps1` 的两个操作。
`Backup` 接受已经干净退出的 broker 和已从实况核对的日志路径，保存整目录、镜像 ID、集群身份和逐文件 SHA-256。
`Stage` 复验归档及镜像，把文件写入显式指定的空 Docker 卷，再于卷内核对文件校验值和 Kafka 用户所有权。
回退使用相同的备份和装载操作，保留原数据及切换后的卷。

审批后的操作单可使用以下命令。变量由发布人根据发现结果与维护单填入，`$targetVolume` 必须是新建的空卷。

```powershell
pwsh -NoProfile -File scripts/messaging/kafka-storage.ps1 -Action Backup `
  -Broker $stoppedBroker -LogDirectory $effectiveLogDirectory -Archive $archive
pwsh -NoProfile -File scripts/messaging/kafka-storage.ps1 -Action Stage `
  -Archive $archive -Volume $targetVolume -Image $sourceImageId
```

#### 获得发布授权后的操作顺序

1. 核对当前 broker 的镜像 ID、监听器、集群 ID、节点 ID、KRaft 元数据位置、所有主题 ID 和分区起止位置，
   导出所有消费组的分区提交位置，确认临时磁盘空间足以保留两份归档和独立恢复卷。先停生产者及消费者，
   等待生产确认和消费提交，再抓取最后一份检查点并干净停止 broker。发布窗口内暂停任何自动拉起或重建 broker 的操作。
2. 从确认过的实际路径以 `Backup` 导出，然后以 `Stage` 导入新建空卷。保留旧容器和旧卷直到备份验证结束。
   用部署覆盖配置将新卷挂到 `/var/lib/kafka/data` 并显式指定 `KAFKA_LOG_DIRS`，固定相同镜像和身份配置。
   执行 broker 单服务重建，确认实际日志目录落卷，逐项比较集群和主题 ID、分区起点和末尾、消息样本以及所有组偏移。
   定位指标、主题和组状态一致后，先恢复一组受控消费者验证接续，再开放其余客户端。
3. 放开写入前失败时，若停机源容器仍在可直接恢复；若源容器被部署过程替换，使用切换前归档装载到独立空卷，
   用同一镜像把它挂到旧日志路径启动。放开写入后失败时，先重新冻结生产和消费，正常停止候选，
   备份候选当前持久卷并装载新的回退卷，再以旧日志路径和同一集群身份启动。此路径必须保留候选期间新增的主题记录、
   新消费组及推进过的偏移。核对完整检查点和接续结果后恢复流量。
4. 干净停机、归档校验、身份、主题或偏移任何一项有差异时停止放行。候选无法取得一致的最新快照时保留候选卷并继续停写，
   先对账候选期间确认过的生产和消费记录。禁止用旧归档直接覆盖新进度、清空卷或重置偏移。

#### 隔离验收结果

后台完整验收退出码为 0。隔离集群使用独立 ID `rNvl_S9L9kWqIUS978U6rg`，以 Kafka 4.1.1 复现
`/tmp/kafka-logs` 位于容器可写层的布局。切换前归档 302 个文件，恢复写入后回退归档 311 个文件。两份归档的
KRaft 集群 ID、节点 ID、目录 ID 均一致，逐文件大小和 SHA-256 复核通过。

| 阶段 | 验收结果 |
| --- | --- |
| 迁入持久卷、删除并重建 broker、放开写入前回退 | 集群 ID、两个主题 ID、六个业务分区的起止位置和 24 条消息逐条一致。两个消费组的 12 个分区偏移与切换前完全一致。 |
| 放开写入后回退 | 候选每个业务分区新增 2 条消息，共新增 12 条。原通知消费组的偏移推进，并新增一个消费组。用候选的最新归档回退后，36 条消息及三个消费组的 18 个分区提交位置与回退前逐项一致。 |
| 回退后从保存的偏移继续消费 | 三个消费组在两个主题上以严格偏移模式恢复。按组核对只消费各自尚未提交的消息，合计 61 次交付。最终 18 个提交位置全部到达对应分区末尾，Lag 全部为 0。 |

证据位于 `%LOCALAPPDATA%/CommerceFlow/releases/m4-kafka-storage-20261121/fairy-commerceflow-m4-kafka-5a175ef09a`。
`05-before-late-rollback.json` 与 `06-late-rollback.json` 的 SHA-256 完全一致，
`07-resumed.json` 记录三个消费组恢复后提交到末尾的结果。验收后核对日常 42 个容器的 ID、启动时间、镜像和挂载一致，
全部 Docker 卷清单前后一致，涵盖 15 个项目数据卷；隔离资源已清理。

**发布评审结论：Kafka 持久化修复通过隔离验收，可在获得授权后单独发布。** 发布前需安排停写窗口并留存日常集群完整检查点，
正式放行前必须比对日常全部主题与消费组。此次验收覆盖单节点 KRaft 和同版本回退。通知服务 PostgreSQL 日常切换仍待 Kafka 修复实际发布并验收后重审。

### 2026-11-21 日常 Kafka 持久化发布验收

发布窗口内仅将 broker 从日常桥接网络移入临时隔离网络。等待通知和订单消费组退出后，记录全量主题及偏移，
干净停止 Kafka 4.1.1，并对旧实际目录 `/tmp/kafka-logs` 的 531 个文件做冷备和逐文件 SHA-256 校验。
将归档装载到新持久卷 `fairy-commerceflow_kafka-broker-persistent-data`，卷内复验文件校验值后，仅重建
`kafka-broker`。镜像、节点与集群身份保持一致，broker 报告的实际日志目录为卷内的 `/var/lib/kafka/data`。

| 放行前核对项 | 结果 |
| --- | --- |
| 集群与主题 | 集群 ID `5L6g3nShT-eMCtK--X86sw` 一致，12 个主题（含 `__consumer_offsets`）的 ID 和 81 个分区的起止位置一致。 |
| 业务消息 | 对 31 个业务分区的 357 条可读记录核对偏移、时间戳、键、正文和 Kafka 头部的摘要，结果一致。 |
| 消费组 | 三个消费组共 12 个已提交分区位置完全一致。通知主题分区 0、1、2 分别为 45、41、38。 |

放行前的两份完整检查点文件 SHA-256 相同。将 broker 接回原日常网络地址和 `kafka-broker` 别名后，
通知与订单消费组均恢复到 `Stable`，原本无成员的支付投影消费组保持空闲，12 个分区的 Lag 均为 0。
商城、订单及通知服务健康检查为 `UP`。其余 41 个日常容器的 ID、启动时间、镜像和挂载前后一致，
全部原有 Docker 数据卷保留。临时隔离网络已清理，未触发回退。

发布证据与冷备归档位于 `%LOCALAPPDATA%/CommerceFlow/releases/m4-kafka-daily-20261121-225639`。
恢复流量后，如需回退，应先重做停写检查点并备份当前持久卷，使用含后续写入的最新归档。
通知服务仍以 MySQL 运行，PostgreSQL 切换另行重审。

### 2026-11-21 Kafka 修复后的通知服务切换复审

#### 日常基线和最新备份

Kafka 集群 `5L6g3nShT-eMCtK--X86sw` 的通知主题已在持久卷的 `/var/lib/kafka/data` 上运行。
消费组 `commerceflow-independent-notification-v1` 有一个活动成员。主题分区 0、1、2 的日志起点均为 0，
已提交位置和日志末尾分别为 45、41、38，三个分区与死信主题均无积压。消息保留 7 天，空消费组偏移也保留 7 天。
Prometheus 能抓取通知服务，Lag 及死信计数均为 0。已加载的通知消费和 Outbox 规则检查正常，当前均未触发。

北京时间 23:10 通过 `mysqldump --single-transaction --quick --skip-lock-tables` 取得新的日常通知库备份。
八张业务表均为 InnoDB。只读检查共 658 行，含 310 条可见通知、310 条 Inbox、12 条站外 Inbox 和 24 条已投递记录。
通知库无在途投递租约。商城站外 Outbox 共 5 条、支付编排退款站外 Outbox 共 7 条，检查时均已发布。
归档与 SHA-256 保存在 `%LOCALAPPDATA%/CommerceFlow/releases/m4-notification-rereview-20261121-231037`。
正式发布时应在冻结后的同一检查点重取备份并记录 Kafka 偏移。[MySQL 一致性备份](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html)

#### 最新备份隔离复演

同一份新归档用于磁盘切换专项和原生备份回归。磁盘专项在 MySQL 8.4.13 和 PostgreSQL 17.11 间沿用同一隔离
Kafka 主题及消费组，关闭自动重置偏移。

| 阶段 | 三个分区提交位置 | 验收结果 |
| --- | --- | --- |
| MySQL 停止后 | 6、6、6 | 18 条切换窗口积压，原偏移保持。 |
| PostgreSQL 接管 | 12、12、12 | 八表摘要匹配，18 条积压排空。 |
| PostgreSQL 拒绝应用连接 | 12、12、12 | 4 次处理失败，三个分区各积压 4 条，有效事件未进入死信。 |
| 恢复并回拨重放 | 16、16、16 | 12 条积压排空，回拨后业务快照完全一致。 |
| 停止候选并回退 MySQL | 16、17、16 → 20、21、20 | 一条畸形消息进入死信；包含候选写入的反向快照一致，回退窗口的 12 条全部处理。 |

专项逐条核对 60 个有效事件，未发现丢失或重复，首次已读时间一致。原生恢复回归另追加一条 JSON 测试数据，正向导入的
八表 659 行逐表摘要一致。含 PostgreSQL 新写入的 666 行经过原生备份恢复和反向 MySQL 回退后，去重、已读、两渠道重试、
站外请求号和供应方回执检查均通过。专项证据在 `fairy-commerceflow-m4-b0cda97271`，原生恢复证据在
`fairy-commerceflow-m4-98481c4848`，均位于上述复审目录。

两库磁盘对照各处理 9,000 个唯一事件和 900 次重复发送。MySQL / PostgreSQL 的吞吐中位数为 87.89 / 234.68 条每秒，
压测后 30 次顺序未读查询 P95 为 28.25 / 10.49 ms。重启后清空 3,300 条积压分别耗时 61.19 / 27.26 秒。
复演结束后复核日常 42 个容器及全部卷清单一致，项目的 16 个数据卷均完整，隔离资源已清理。

#### 停写范围和发布前门槛

- 现有的日常切换方案还需覆盖三个写入入口。暂停用户单条和全部已读动作，以及商城售后、支付编排退款两路
  `POST /internal/v1/offsite-notifications` 的投递；在调用端保留原 Outbox 记录、请求号和尝试次数。
  商城当前为 5 次尝试、5 秒重试间隔，支付编排的站外任务失败会进入重试。发布前必须实测两路在长于商城重试预算的
  停写窗口内均能安全暂存，并验证放行后原事件和原请求号各完成一次。仅停止通知服务会让商城任务耗尽重试。
- 发布包须固定 MySQL 原镜像与 PostgreSQL 候选镜像，提供可复核的日常目标 Compose 覆盖、独立 PostgreSQL 17 持久卷、
  运行与迁移账号、停写及恢复操作、双向数据迁移入口。发布前使用相同发布包隔离验收，明确维护和回退负责人。
- 建议预留 30 分钟。前 5 分钟完成入口冻结、清退在途站外请求和旧实例，记录集群、主题、分区偏移与死信末尾。
  Kafka 生产可继续，由 Kafka 保留新增消息。第 5 至 15 分钟完成冷态备份、八表迁移、序列和数据摘要核对、候选接管与验证。
  从停写起满 15 分钟仍无法完成校验就启动回退，余下 15 分钟留给恢复。整个窗口保持单一通知写入方。

#### 放行监控与回退

| 检查项 | 放行或回退条件 |
| --- | --- |
| Kafka 和数据库 | 同一集群、主题和消费组，三个分区的旧提交位置仍在日志范围内，八表摘要和序列匹配。设置 `SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET=none`。缺少位置、越界、摘要不符、漏写或重复，立即停止放行。 |
| Lag、死信和指标可用性 | 既有 Lag 持续非零 1 分钟触发告警。候选启动后 5 分钟内三分区须归零。任一分区新增一条死信立即停止放行。连续两次采集须见 `up=1`、通知 Consumer 指标刷新成功以及候选的存储重试计数；存储重试持续增长 1 分钟或指标刷新持续失败 1 分钟即回退。 |
| 读接口与站外投递 | 30 次签名未读查询全部成功且 P95 ≤ 100 ms，逐条核对已读时间。发出受控短信及邮件验证请求后，5 分钟内以原请求号核对供应方回执。发现新增失败、重复或超时即停止放行，排查不能在回退决策时间内完成则回退。 |
| 上游 Outbox | 两路站外任务在停写时可积压，禁止出现新增 `DEAD` 或无主租约。恢复时按业务事件和请求号对账。商城现有 Outbox 规则在最老任务达到 120 秒并持续 1 分钟时告警。 |

候选尚无业务写入且偏移仍在停写检查点时，用原 MySQL 和原镜像接续。候选已写入或推进偏移时，先再次冻结入口并停止候选，
记录最新偏移，用 `pg_dump -Fc` 取得 PostgreSQL 原生归档，同时把最新八表反向导入独立 MySQL 恢复库。
核对内容、已读、供应方回执和请求号后，才允许原镜像从当前已提交位置接续。新快照与 Kafka 位置无法对账时维持停写并保全证据，
禁止用旧快照覆盖候选新写入或跳过缺失偏移。[Kafka 严格偏移配置](https://kafka.apache.org/41/configuration/consumer-configs/) · [PostgreSQL 17 原生备份](https://www.postgresql.org/docs/17/app-pgdump.html)

**复审结论：暂缓发布通知服务 PostgreSQL 切换。** Kafka 持久化阻断项已关闭。仍需验收商城和支付编排两路
站外通知在整个停写窗口成功暂存并按原请求号恢复，同时固化可直接用于日常的发布及回退入口。隔离脚本以测试通知客户端
驱动用例，日常上游的停写能力需单独验证。

### M4 两路站外停写与通知库切换工具

商城售后和支付编排退款仍向各自的 Outbox 正常入队。应用确认维护标记后，在取待投递批次时过滤对应的站外通知，
因此不领取任务、不改状态或尝试次数，其他 Kafka 通知和支付任务继续处理。指标
`commerceflow_notification_offsite_dispatch_paused` 为 1，表示当前进程的上一轮投递已结束。
通知服务的写入标记暂停内部 HTTP 写请求和站外 worker，读请求和 Kafka 消费可继续。指标
`commerceflow_notification_offsite_worker_paused` 为 1，表示 worker 已停止领取新的待投递任务。

日常需先单独获批安装支持停写的应用镜像和 [通知维护配置](../../infrastructure/compose/compose.notification-maintenance.yaml)。
先验证两个上游进程、当前通知实例和目标实例都读同一个维护卷。准备停止状态的 PostgreSQL 目标通知实例和备用
MySQL 通知实例，两者使用支持停写的已验收镜像。目标库各使用独立新卷及 `notification` Schema，先由对应方言的
Flyway 初始化八表，再关闭初始化实例。目标消费者启用原组及 `auto.offset.reset=none`。
预先核对目标容器的日常网络别名、端口以及所有签名和供应方配置，切换期间只能运行一个正式通知实例。

`scripts/messaging/release-notification-database.ps1` 的 `Forward` / `Rollback` 负责关闭 HTTP 写入与两路
投递并等待确认，优雅停止当前实例，核对集群、三分区提交位置和死信末尾，保存原生备份和八表摘要。
仅向独立空目标导入数据，验证后才启动接管实例。全部分区由目标实例接管且 Lag 为零、无新增死信时，
可显式执行 `Resume` 解开两个维护标记。仍须按复审阈值核对签名 API 和两渠道受控回执。
切换失败时保持写入暂停，保留源、目标和证据。接管实例已推进偏移或已有新写入时，以它为源执行
`Rollback` 到另一份空 MySQL 恢复库，然后执行该批次的 `Resume`。

隔离入口使用 PowerShell 7 的 `verify-notification-postgresql.ps1 -OffsitePause`，通过
`-CommerceImage`、`-PaymentImage` 和 `-CandidateImage` 可固定本轮验收的三个镜像。未指定的默认镜像在本机缺失时从当前代码构建。

隔离验收从实际商城和支付编排 Outbox 写入售后、退款测试记录。停写 38.22 秒，超过商城 5 次、每 5 秒一次的
重试预算。商城一直保持 `NEW / 0`，支付一直保持 `RETRY / 2`，通知库和供应方均未收到这两条任务。
解除停写后，两路分别变为 `PUBLISHED / 1` 和 `PUBLISHED / 3`。各事件的短信与邮件按照事先计算的
原请求号各生成一条供应方回执，合计四条。通知 worker 的回执查询沿用同一请求号。

同一隔离环境随后实际执行 `Forward → Resume → Rollback → Resume`。切换到 PostgreSQL 时三个分区从
`2 / 2 / 2` 接续。候选继续消费 6 条新事件并写入一条首次已读时间，反向回退后 MySQL 从 `4 / 4 / 4` 接续。
两个方向的八表摘要一致，归档 SHA-256 复核通过，放行时三分区 Lag 为 0。反向回退后仍可核对候选新写入、
首次已读时间和四个原供应方请求号。

证据位于 `%LOCALAPPDATA%/CommerceFlow/releases/m4-notification-release-20261121/fairy-commerceflow-m4-b0c8653e4c`。
日常 42 个容器与 16 个项目卷保持一致。Docker 全局卷清单在本轮开始时比上一轮日常核验多一个非项目匿名卷，
本轮结束时与上一轮的 423 项一致，现有证据无法确认该匿名卷的来源。

**结论：两路停写和正反向切换工具通过隔离验收，可以进入分阶段发布。** 日常仍运行现有 MySQL 版本。
先在单独获批的窗口装上支持维护标记的商城、支付编排和通知镜像，验明两路确认指标及现行 HTTP 写入门控，
再准备目标容器、日常网络别名和独立数据卷。上述日常前置条件验收后才能执行数据库切换。

### 2026-11-22 日常维护门控发布验收

只重建商城、支付编排和通知服务。发布前比较三个服务的 JAR 条目和运行配置。
支付编排与通知以原日常镜像为底，仅替换门控相关条目，通知保留原 MySQL 依赖及配置。
三个服务以只读方式挂载同一卷 `fairy-commerceflow_notification-maintenance`。先更新并确认两个上游暂停，再更新通知服务。

| 核对项 | 日常验收结果 |
| --- | --- |
| 暂停确认 | 两个上游的投递暂停指标和通知 worker 暂停指标均为 1。通知 HTTP 写请求返回 503，健康检查仍返回 200。 |
| 两路 Outbox 暂存 | 完整暂停窗口为 38.89 秒。商城售后测试事件保持 `NEW / 0`，支付退款测试事件保持 `RETRY / 2`。通知 Inbox 与短信邮件模拟器均未接收到这两条事件。 |
| 恢复投递 | 移除两个标记，三个暂停指标均恢复为 0，未签名写探针重新返回 401。两个事件分别变为 `PUBLISHED / 1` 和 `PUBLISHED / 3`，模拟器有四条与原请求号匹配的唯一回执。 |
| Kafka 与 MySQL | 通知消费组重新加入，三个分区从 `45 / 41 / 38` 接续，Lag 全为 0。通知库仍为 MySQL。 |

首次验收回执查询使用了隔离库名，解除暂停后该组四条测试回执也全部送达。改用日常 `messaging_platform` 后，
用一组新测试事件完整重跑暂停和恢复，无需再次重建服务。两组测试记录均保留。验收结束后维护卷内无暂停标记。

其余 39 个日常容器的 ID、启动时间、镜像及挂载前后一致。原 16 个项目数据卷均保留，仅增加上述维护卷。
Docker 卷清单包含上一次已核验的全部 423 个卷和新增维护卷；本轮预检时额外出现的一项匿名卷在收尾清单中不存在，
现有记录无法确认其来源或生命周期。发布与验收证据位于
`%LOCALAPPDATA%/CommerceFlow/releases/m4-maintenance-daily-20261122-001006`。

日常暂停能力已验收，通知服务 PostgreSQL 目标仍待单独准备和批准。下一次操作按切库窗口重新建立快照和偏移检查点。

### 2026-11-22 日常通知库切换 PostgreSQL 17

日常通知服务已切到 PostgreSQL 17.11。独立容器 `fairy-commerceflow-notification-db-postgresql` 使用
`fairy-commerceflow_notification-postgresql-data`，数据库未发布宿主机端口。初始化单独执行 Flyway，运行实例使用
`notification` 数据访问账号。实测 `fsync`、`synchronous_commit` 和 `full_page_writes` 均为 `on`。

使用 `release-notification-database.ps1` 执行 `Forward` 和 `Resume`。先确认两个上游投递端和通知 worker
暂停、写接口返回 503、没有投递租约，再停止原通知应用，保存原生 MySQL 备份与 Kafka 提交位置。
停写至恢复共 **147.57 秒**。

| 放行检查 | 日常结果 |
| --- | --- |
| 八表与已读 | 670 行逐表规范化摘要一致。签名接口读取历史通知成功，首次已读时间与 MySQL 一致。30 次连续未读查询的 P95 为 19.81 ms。 |
| 历史站外回执 | 从原 MySQL、PostgreSQL 和短信邮件模拟器核对 32 条已投递回执，请求号、供应方回执号和状态全部一致。 |
| Kafka 接管 | 在原集群和消费组的分区 0、1、2 上从 `45 / 41 / 38` 接续。放行前后 Lag 均为 0，Prometheus 采集成功，无新增死信，存储重试计数为 0。 |
| 放行与新投递 | 两路上游通过原服务别名到达 PostgreSQL 实例。解除停写后，三个门控指标均为 0；一条独立的合成退款通知写入 PostgreSQL，短信和邮件按原请求号取得两条唯一回执。 |

原 MySQL 数据库容器继续运行，原通知应用容器停止并保留。其余 41 个日常容器的 ID、启动时间、镜像及挂载一致，
原 17 个项目卷全部保留，仅增加 PostgreSQL 卷。宿主机全局清单同时观测到项目之外的 Quartz 验收卷移除及 JWT 测试匿名卷新增。
全程未触发回退。证据、原生备份、校验清单及本次启动配置保存在
`%LOCALAPPDATA%/CommerceFlow/releases/m4-notification-cutover-20261122`。

日常实际运行的应用容器为 `fairy-commerceflow-notification-service-postgresql`，沿用原有 `notification-service` 网络别名、
原服务地址和宿主机端口 18086。后续维护以该 PostgreSQL 实例为准。
恢复流量后发生回退时，须重新停写并以 PostgreSQL 最新快照和当前 Kafka 位置执行反向脚本，保留已经写入的数据。

### 日常通知部署与验收

根 `compose.yaml` 固定日常的 PostgreSQL 17.11 通知拓扑。应用容器固定名为
`fairy-commerceflow-notification-service-postgresql`，发布 `18086:8080`，在默认网络保留
`notification-service` 与 `fairy-commerceflow-notification-service` 两个别名。商城、支付站外调用和
Prometheus 采集沿用原地址。PostgreSQL 在容器网络内使用 `notification-db-postgresql:5432/notification`。
日常项目复用以下三个具名卷。

| 卷 | 用途 |
| --- | --- |
| `fairy-commerceflow_notification-postgresql-data` | 通知库 |
| `fairy-commerceflow_notification-maintenance` | 通知应用与两个上游只读挂载 `/gate` |
| `fairy-commerceflow_kafka-broker-persistent-data` | 同一 Kafka 集群及消费组偏移 |

日常重建前核对卷、Kafka 集群及各分区提交位置，并在 `.env` 使用当前的通知应用和 PostgreSQL 管理凭据。
默认关闭通知 Flyway，数据库健康检查会检查应用账号能否读取既有通知表。默认偏移策略为 `none`，
要求保留消费组提交位置。只替换通知应用时，在维护窗口先确认数据库和 Kafka 健康，再执行以下命令。
首次采用仓库配置管理数据库容器也要安排维护窗口，核对新容器仍挂载上述原卷。保留旧 MySQL 应用时，
重建命令省略 `--remove-orphans`。

```powershell
docker compose -f compose.yaml config --quiet
docker compose -f compose.yaml up -d --build --no-deps --wait notification-service-postgresql
```

维护写入辅助容器用 `docker compose --profile notification-maintenance up -d notification-maintenance` 启动。
需要用旧路径组装维护配置时可叠加 `compose.notification-maintenance.yaml`。

MySQL 学习基线使用 [MySQL 覆盖文件](../../infrastructure/compose/compose.notification-mysql.yaml)。
在独立 Compose 项目覆盖固定容器名和宿主机端口，使用新的 MySQL 与 Kafka 卷，再叠加该文件。
MySQL 配置指向 MySQL 通知应用，日常 PostgreSQL 应用在这个组合中默认停用。启动时按需启用单个 Profile。
日常反向切换使用 [M4 停写和回退工具](#m4-两路站外停写与通知库切换工具)的 `Rollback` 和 `Resume`，
通过最新 PostgreSQL 快照、供应方回执和原 Kafka 提交位置核对后再放行。

#### Kafka 切回默认启动网络

日常恢复期间，Kafka 曾引用内部桥接网络 `fairy-commerceflow-kafka-maintenance-225639` 作为启动网络，实际通信通过
`fairy-commerceflow_default`。接入默认网络后再断开临时桥接网络，Docker 仍会保留容器创建时的
`HostConfig.NetworkMode`。11 月 23 日已按以下步骤使用原镜像和原卷完成单服务重建、同卷冷启动和通知复测，
随后清理原内部桥。[日常验收记录](../IMPLEMENTED.md#2026-11-23-日常-kafka-启动网络验收)

1. 核对当前镜像 ID、`kafka-broker` 监听和别名、集群 ID、节点配置及
   `fairy-commerceflow_kafka-broker-persistent-data:/var/lib/kafka/data` 的卷名和创建时间。
   从根目录解析正式 Compose，确认 `kafka-broker` 使用现有 `fairy-commerceflow_default`，没有维护网络覆盖。
   等待业务流量进入安静窗口，停止现役 PostgreSQL 通知应用。若原启动桥仍存在且已核对无其他端点，可让原 broker
   短时仅连接原内部桥，阻止新写入。等待在途请求结束和消费组退出，记录各消费组提交位置、主题分区末尾、积压和死信偏移。
   按[持久化修复步骤](#2026-11-21-kafka-持久化修复演练)在 broker 干净停机后备份原日志目录。
2. 核对本地 `apache/kafka:4.1.1` 的镜像 ID 与原运行实例一致，再从根 Compose 只重建 broker。保留已有具名卷，
   保持原节点配置和 `KAFKA_LOG_DIRS=/var/lib/kafka/data`。

   ```powershell
   docker compose -p fairy-commerceflow -f compose.yaml up -d --no-deps --force-recreate --no-build --pull never --wait --wait-timeout 150 kafka-broker
   ```

3. 确认新 broker 的 `HostConfig.NetworkMode` 和唯一网络端点均为 `fairy-commerceflow_default`，DNS 别名仍是
   `kafka-broker`。比对原镜像、卷、集群 ID、主题和提交偏移。在通知应用仍停止时，对同一 broker 做一次停止和启动，
   等待健康后再次核对提交偏移及分区末尾。
4. 启动保留的 PostgreSQL 通知应用。保持 Flyway 关闭，消费策略使用 `none`。核对应用日志从保存的提交偏移接管各分区，
   等待积压归零，检查死信偏移和门户的通知列表、未读查询，再恢复写入。集群身份、卷或偏移有差异时暂停放行，
   保留现有数据并按核实过的配置与最新检查点恢复。
5. 验收通过且 Docker 中的容器均不再引用该启动网络后，才清理日常内部桥接网络。其余日常容器及所有数据卷保留。

运行验收应使用已初始化商品、仓库库存和独立 Kafka 的隔离项目。传入对应 `-ProjectName` 和商城直连
`-BaseUrl` 执行 [通知运行验收](../../scripts/messaging/verify-notification-runtime-cutover.ps1)。

```powershell
pwsh -NoProfile -File scripts/messaging/verify-notification-runtime-cutover.ps1 -ProjectName fairy-commerceflow-notification-lab -BaseUrl http://127.0.0.1:38081
```

控制台验收需指向隔离控制台，并传入一致的项目名和 Compose 文件。隔离控制台可通过
`commerceflow.navigation.project` 和 `commerceflow.navigation.compose-file` 设置采集范围。当前通知应用及数据库
必须存在且健康，HTTP 探测和 PostgreSQL 容器健康检查结果必须可见。保留的 MySQL 应用及库显示为“回退保留”，
现役停机或缺失会导致验收失败。

### 2026-11-22 部署入口与实例验收

| 验收项 | 结果 |
| --- | --- |
| PostgreSQL 运行 | 隔离栈从空卷完成 PostgreSQL 17.11 初始化。真实取消订单经 Kafka 投递，商城通知代理可读。重复已读保留首次时间，通知停机返回 HTTP 503，商城健康仍为 UP，恢复后可读。错误数据库模式及错误商城端口均在停机前被拒绝。 |
| Compose 重建和偏移接续 | 原隔离卷上重建 Kafka、PostgreSQL 和通知应用，Kafka 集群身份与提交位置保留。分区 0、1、2 从 `9/8/8` 接续到 `13/12/12`，12 条停机积压归零。24 个验收事件在 Inbox 与顾客通知逐条一致，各出现一次。现役应用单实例持有三分区，商城通过 `notification-service:8080` 可达。 |
| 运行控制台 | 隔离中验证现役停机显示异常、移除容器显示缺失且验收失败。恢复后通过。新版控制台在临时端口对日常环境只读核验 56 个服务、44 个容器，PostgreSQL 应用和库均就绪，旧 MySQL 应用和库显示回退保留。 |
| MySQL 学习基线 | 独立项目按覆盖文件启动，仅运行 MySQL 通知库、MySQL 通知应用和独立 Kafka。通知应用健康为 UP。 |

78 项脚本契约、控制台前端构建和 4 项后端测试通过。隔离快速回归复跑了权限、国际化、取消订单、远程支付退款和
Redis 降级。完整快速回归入口仍受现有的 `MessagingDeliveryMetrics.java` 类声明空行审计阻挡。
本批保留 44 个日常容器及既有 CommerceFlow 数据卷，仓库变更尚未发布到日常环境。

### 2026-11-23 通知候选发布前验收

从 Git `86c2c25` 的通知服务源码构建 PostgreSQL 候选 `commerceflow-notification-candidate:20261123-001941`，
固定镜像 ID `sha256:f676e851bccb1728f9911aab97584c1f5bcee875a493406c9cfa9043739f4779`。启动覆盖按镜像 ID 引用应用和
PostgreSQL 17.11，禁止拉取，关闭 Flyway，Kafka 偏移策略为 `none`。锁定记录包含 PostgreSQL、Kafka 和回退镜像 ID、
控制台 JAR 摘要、启动覆盖和已解析隔离 Compose 的 SHA-256。验收在 `fairy-commerceflow-upgrade-ee529bcc0c03` 进行。

| 验收项 | 结果 |
| --- | --- |
| PostgreSQL 原卷 | 重建数据库和候选应用后，PostgreSQL 仍挂载原 `notification-postgresql-data`，卷创建时间、历史八表摘要和 Flyway 校验和一致。候选继续使用原维护卷及 `notification-service` 别名。 |
| Kafka 单实例接续 | 停止通知时三个分区各积压 4 条，已提交偏移保持 `13/13/12`。同一 Kafka 集群及原卷由候选单实例接续三个分区，提交到 `17/17/16`，积压与死信均为 0。PostgreSQL 写入 12 条 Inbox 和 12 条通知。 |
| 停机与恢复 | 门户通知列表和未读查询停机时均为 503，商城健康保持 `UP`。候选启动后两接口均返回 200，历史列表和未读计数一致。候选镜像另通过真实取消订单 6、重复标记已读保留首次时间、通知再次停机 503 与恢复。 |
| 运行控制台 | 交付的 Vue 页面通过验证。现役 PostgreSQL 应用和库显示 `READY`，现役应用停机显示 `FAILED`。隔离 MySQL 应用保持停机，连同 MySQL 库均显示 `ROLLBACK_RETAINED`。验收覆盖 55 个配置服务和 41 个容器。 |

控制台前端构建、4 项后端测试、79 项脚本契约和代码质量审计通过。隔离项目已停止，新增的 MySQL 回退标识夹具卷一并保留。
该轮保留日常 44 个容器和所有既有数据卷。后续结果见[日常发布验收](#2026-11-23-通知候选日常发布验收)。

证据目录为 `%LOCALAPPDATA%/CommerceFlow/releases/notification-candidate-20261123-001941/`。
`candidate-lock.json` 固定候选及配置摘要，`compose.notification-candidate.yaml` 为启动覆盖，
`candidate-recovery.json`、`outage-http.json`、`console-outage.json`、`console-after.json` 和 `verify-02.log` 记录实测。

### 2026-11-23 通知候选日常发布验收

日常现役 PostgreSQL 通知应用已切换到锁定候选 `commerceflow-notification-candidate:20261123-001941`，镜像 ID 为
`sha256:f676e851bccb1728f9911aab97584c1f5bcee875a493406c9cfa9043739f4779`。从候选启动覆盖解析单服务配置，
21 项日常运行设置沿用原应用。只读连接验证了应用账号和既有通知表。日常 PostgreSQL 继续使用原 17.11 实例与现有连接地址，
保留日常库镜像、原数据卷、维护卷、Kafka 卷、端口 18086 及两个通知别名。

首次替换因原手工容器占用固定名称失败，恢复原实例及写入后重试。重试通过维护卷暂停两路站外投递和通知 worker，
确认无在途站外租约后停机并记录提交偏移。暂存已停止的原容器，使用 `--no-deps --no-build --pull never`
只重建 PostgreSQL 通知应用，候选健康并完成偏移复核后移除原应用容器及本轮门控文件。原 PostgreSQL 应用镜像保留供应用回退，
旧 MySQL 应用及数据库容器继续作为数据库回退资产。

| 验收项 | 实测结果 |
| --- | --- |
| Kafka 单实例 | 旧实例退出后消费组无活动成员。候选日志从 `45/41/38` 接管三个分区，提交位置与日志尾部一致，发布前后积压均为 0，三个死信分区均为 0。集群和 Kafka 容器未变化。 |
| 门户与存储 | 门户通知列表和未读查询均返回 200，读取 20 条，未读总数为 302。Flyway 保持关闭，偏移策略为 `none`。八张通知表的行数和内容摘要在窗口前后一致。两路站外投递及通知 worker 的门控指标恢复为 0。 |
| 控制台 | 用锁定 JAR 临时只读核对日常项目，Vue 交付及 56 个服务、44 个容器完成验收。PostgreSQL 应用和库为 `READY`，MySQL 应用和库均为 `ROLLBACK_RETAINED`。 |
| 资源保全 | 44 个日常容器中仅通知应用换 ID，其余 43 个容器的 ID、镜像、启动时间、状态及挂载一致。所有基线数据卷保留。全局清单新增一个属于并行 Redis 恢复演练的匿名卷。 |

日常 Prometheus 发布前已处于退出状态，本轮门控用应用指标端点复核。后续采集恢复及同窗实测见[发布后日常观测](#2026-11-23-通知发布后日常观测)。
证据目录为 `%LOCALAPPDATA%/CommerceFlow/releases/notification-daily-candidate-20261123-012329/`，含 `preflight.json`、
`compose.daily-runtime.yaml`、`kafka-assignments.log`、`console-verified.json`、`acceptance.json` 和 `preservation-verified.json`。

### 2026-11-23 通知发布后日常观测

启动原日常 Prometheus 容器，复用原时间序列卷。就绪检查通过，11 个采集目标均为 `UP`，41 条告警规则已加载。
`notification-service` 作业通过原网络别名采集现役应用。通知采集 `up` 和消费者指标刷新成功均为 1，
恢复后的基线读数中 Kafka 积压、存储重试与死信均为 0。

首次只读观察以五分钟滚动窗口对齐门户访问日志、商城 HTTP 计时器、
Kafka 与存储重试序列，以及通知库和模拟供应方回执。当前商城路由没有延迟直方图，只有计时器增量可以逐笔对应门户日志时
才计算 P95。零请求窗口不计算 P95 和错误率。

01:34:18–01:45:27 的只读观察未发现门户通知列表请求或供应方新接单。同期抽取的连续十分钟内，
通知采集有 60 个样本且始终为 1，消费者指标刷新成功，最大 Kafka 积压为 0，存储重试与死信新增均为 0。
这些读数记录空闲基线。恢复采集时，其余 43 个日常容器和所有既有数据卷保留。

采集恢复证据与观察明细保存到
`%LOCALAPPDATA%/CommerceFlow/releases/notification-postrelease-observation-20261123-013146/`，
包括 `prometheus-restored.json`、`idle-window-evidence.json`、`observation-progress.json` 和 `preservation-final.json`。

#### 日常学习退款同窗验证

01:50:48–01:51:19，使用现有学习顾客和商家账号购买一件学习商品（SKU 6），完成模拟支付、商家接单和未发货退款。
订单 749、退款 `REF-RMT-E1619712B76841D8` 共 12.50 元，退款账本状态为 `SUCCEEDED`。SKU 6 可售库存恢复至 100。
门户查询沿日常 Nginx → 商城 → PostgreSQL 通知应用路径执行。

| 同窗检查 | 实测结果 |
| --- | --- |
| `GET /api/notifications` | 83 次均返回 200，错误率 0%。按客户端对完整响应的计时计算，最近秩 P95 为 11.833 ms，最大 16.840 ms。供应方接单至送达期间有 5 次查询。 |
| Kafka 与存储 | 三分区偏移前后均为 `45/41/38`，同窗最大和最后积压均为 0。存储重试及死信均无新增。应用端采到 28 个指标样本，Prometheus 同窗 4 个时点均显示采集和消费者指标刷新正常。 |
| 模拟供应方回执 | 事件 `offsite-refund:REF-RMT-E1619712B76841D8` 的短信和邮件各有一条 `DELIVERED` 回执。通知库和模拟供应方的请求号、供应方回执号、渠道、业务引用与状态一致。短信请求号 `269ebf4f-9808-3cbd-b38d-0dc499a9b69c`，邮件请求号 `ac64d169-7d96-3d7c-b30e-ecc591f8b899`。 |

受控发布后同窗验收完成。44 个日常容器及全部数据卷前后清单一致，保留本笔学习订单、退款和回执记录。
证据位于 `%LOCALAPPDATA%/CommerceFlow/releases/daily-notification-refund-20261123-015025/`，
包括 `summary.json`、`portal-requests.json`、`provider-receipts.json` 和 `preservation.json`。

### 2026-11-22 最新备份恢复与反向回退验收

01:43:43 开始从日常 PostgreSQL 17.11 取得一致性原生备份，导出耗时 1.24 秒。备份前后八表摘要一致，
Kafka 三分区提交位置均为 `45 / 41 / 38`，Lag 为 0。从放行到该备份，日常新增一条站外验证通知和两条渠道回执；
这段时间没有新增站内通知或首次已读。本轮在隔离环境补入站内通知、首次已读和新的两条渠道回执，
再用现有回退脚本验证向 MySQL 迁移。

原生恢复使用独立磁盘卷执行 `pg_restore --exit-on-error --single-transaction`。从复制归档到还原提交耗时
0.508 秒，从启动恢复后的通知应用到严格偏移接管并成功返回签名未读查询耗时 19.525 秒。
从开始还原到完成整个原生恢复验收约 56 秒，期间包含独立供应方账本导入、Kafka 种子校验和首次已读复核。
八表共 673 行，逐表规范化 SHA-256 与日常源一致，8 条历史首次已读记录全部保留。

隔离 Kafka 使用独立集群。通过只读分区消费取得日常提交位置之前的 124 条记录，按原分区和偏移导入后再导出，
键、值、头和时间戳逐字节一致。以 `45 / 41 / 38` 初始化消费组提交位置后，单个 PostgreSQL 通知实例接管三个分区。
备份中的 34 条已送达通知回执与独立供应方账本逐条匹配，包含日常切换后新增的两条渠道回执。

| 核对表 | 日常备份与 PostgreSQL 原生恢复 | 反向迁移前 PostgreSQL 与恢复后 MySQL |
| --- | ---: | ---: |
| `customer_notifications` | 310 | 12,310 |
| `notification_inbox` | 310 | 12,310 |
| `notification_delivery_outbox` | 0 | 0 |
| `notification_storage_state` | 1 | 1 |
| `notification_storage_audits` | 1 | 1 |
| `notification_storage_quarantine` | 0 | 0 |
| `offsite_notification_inbox` | 17 | 18 |
| `offsite_notification_deliveries` | 34 | 36 |
| 合计 | 673 | 24,676 |

两组对照均逐表核对业务键、首次已读时间、字段和规范化 SHA-256。PostgreSQL 首轮隔离积压演练已消费
6,000 个唯一事件，重启后的临时宿主端口变更使后续查询采样中断。重新读取端口后发布第二批并完成复测，
两批站内通知和隔离新增的两条渠道回执均纳入反向迁移。

现有[回退入口](../../scripts/messaging/release-notification-database.ps1)的 `Rollback` 和 `Resume` 在独立 MySQL 8.4
和两个真实隔离上游的维护门控下执行。进入回退到
目标就绪且仍停写耗时 138.835 秒，至解除门控并成功返回签名未读查询共 163.611 秒。
MySQL 单实例从 `4445 / 4441 / 4438` 接管三个分区，放行时 Lag 为 0。回放历史和新增已读请求后，
首次已读时间均保持原值。重发日常切换验证请求和隔离新增站外请求，36 条通知回执全部匹配，
回退前后的供应方账本均为 38 行。

积压复测沿用 5 分钟归零、未读查询 P95 ≤ 100 ms 的门槛。每批 6,600 条 Kafka 记录含 6,000 个唯一事件和
600 条重复记录。重启后从停机前的提交位置接续，逐个校验 Inbox 与通知表中的事件，以及消费组 Lag 和死信。
未读查询在积压归零后发起 30 次签名请求，计时包含 HTTP 往返。

| 隔离运行 | 重启至首次观测 Lag 为 0 | 三分区提交位置 | 未读 P95 |
| --- | ---: | --- | ---: |
| PostgreSQL 17.11 恢复实例 | 46.110 秒 | `2245 / 2241 / 2238` → `4445 / 4441 / 4438` | 14.968 ms |
| 回退 MySQL 8.4 实例 | 199.130 秒 | `4445 / 4441 / 4438` → `6645 / 6641 / 6638` | 30.969 ms |

两轮的 30 次未读请求均成功，三分区积压和死信均为 0。MySQL 耗时取容器 `StartedAt` 至首次零积压快照落盘，
积压快照至零积压快照的总窗口为 199.431 秒。PostgreSQL 存储重试为 0，保留的 MySQL 回退镜像缺少该计数器。

日常 Prometheus 的 30 分钟只读采样中，通知和供应方模拟器采集正常，通知 Lag、存储重试和死信均为 0。
这段窗口没有新增未读查询或站外受理请求。持续观察沿用 `notification-service` 作业的未读请求数量、状态码、
平均与最大延迟，以及 Kafka Lag、存储重试、死信和站外受理状态。阶段验收的未读 P95 使用隔离环境的逐次
签名 HTTP 样本，站外最终送达以应用账本和供应方账本的同一请求号、回执号交叉核对。

备份、八表摘要、Kafka 分区检查点、逐次查询样本、恢复计时和复跑脚本保存在
`%LOCALAPPDATA%/CommerceFlow/releases/m4-postcutover-recovery-20261122-014255`。

演练结束后，日常 44 个容器的身份、启动时间、状态与挂载一致，日常 Kafka 仍为 `45 / 41 / 38` 且积压为 0。
日常挂载的 23 个卷、既有的 275 个具名 CommerceFlow 卷和 8 个演练卷均保留。全局 Docker 清单期间少了
一个 Quartz 卷和一个匿名卷，二者均未挂载到日常 CommerceFlow 容器。本轮未执行删卷或日常发布。

### 2026-11-22 通知告警送达、确认与升级验收

独立项目使用真实 PostgreSQL 通知应用、Kafka、Prometheus 和 Alertmanager。向通知命令主题发送两个版本为
`99` 的合成命令，应用投递到真实死信主题，Prometheus 的 `CommerceFlowNotificationConsumerDeadLetter`
进入 firing，再由 Alertmanager 投递到本地 SQLite 值班收件箱。

- 主值班按期确认的轮次只向主值班发出触发和恢复通知。
- 另一轮由接收端在 12 秒确认期限后升级给备值班，备值班确认后，主备账号各收到一条恢复通知。
- 两轮均按死信主题、分区和偏移读回原始事件 ID，发送修正版本后在 PostgreSQL Inbox 各落一行。
  死信窗口结束后告警恢复。重启接收端并重放之前的 firing Webhook，确认人、确认时间和投递记录均保持一致。

| 轮次 | 原始事件 ID | 死信分区:偏移 | 自主值班送达起计 |
| --- | --- | --- | --- |
| 主值班确认 | `notification-alert-primary-c39eabfa50` | `2:0` | 0.37 秒确认，30.01 秒收到恢复 |
| 超时升级 | `notification-alert-escalation-c39eabfa50` | `2:1` | 12.05 秒升级，12.40 秒由备值班确认，30.01 秒收到恢复 |

79 项脚本契约与三套 Prometheus 规则测试通过。复跑核对了 44 个日常容器、285 个既有 CommerceFlow 卷
和 7 个本轮隔离卷。首轮全局卷清单少了两个 `zk-business-fencing` 项目卷，本轮未执行删卷操作。
复跑的 `workflow-result.json` 和 `preservation.json` 保存在
`%TEMP%/fairy-commerceflow-notification-alert-c39eabfa50`。

专项用隔离的主备值班账号演练，默认日常 Webhook 配置沿用现有入口。
[运行手册](../ALERTING.md#通知告警的隔离值班流程)

### 2026-11-22 M4 收尾评审

**当时的评审结论为 M4 保持开放。** 日常 PostgreSQL 17.11 实例的切换验收请求满足已测门槛。放行后的请求可全部追溯到切换验收。后续按确认的学习环境验收范围完成了门户路径验证。[最终评审](#2026-11-22-m4-门户路径收尾验收)

#### 日常实例只读观测

回看 12 小时的 Prometheus 数据，按通知进程启动时间切分计数器，从 00:32:35 的 PostgreSQL 实例基线核对到 02:23:53（北京时间）。以 00:33:30.978 恢复放行为界，对照切换脚本记录、Kafka 分区和两侧投递账本。

| 核对项 | 观测结果 |
| --- | --- |
| 放行前 API | 30 次签名未读查询全部返回 200。客户端逐请求计时 P95 为 19.81 ms，满足 ≤ 100 ms；服务端均值 5.97 ms、最大值 18.144 ms。另有一次列表查询成功，三次停写探针按预期返回 503。 |
| 放行后 API | 一次列表查询返回 200（19.809 ms），一次受控站外通知返回 204（379.998 ms），耗时均取自服务端。异常响应 0/2。此段未读查询为 0，商城通知入口计数无增长，站内账本无新增通知或首次已读。现有指标只有计数、总耗时和最大值，无法据此计算日常业务 P95。 |
| Kafka 与存储 | 放行后 662 个采样点中，积压、存储重试及死信指标均为 0，偏移刷新成功。随后只读查询 broker，三个分区提交位置和日志末端分别同为 45/41/38，死信末端为 0/0/0。此段没有新的业务消息推进偏移。 |
| 站外投递 | 事件 `offsite-refund:PG220D74AA83` 的邮件、短信分别在 2.502 s、2.520 s 完成投递，均在 5 分钟内，可按原请求号查到供应方回执。累计核对 17 个事件、34 条投递，全部为 DELIVERED，请求号、回执号、渠道和事件匹配，无重复渠道投递。放行后两个渠道的近期失败指标均为 0。 |

#### 第 1–4 阶段验收证据

| 阶段 | 已验证的门槛 |
| --- | --- |
| 1．文档与验收入口 | 已对齐履约命令 API、日常 PostgreSQL、MySQL 学习基线和回退入口。隔离测试中，错库、错端口均在停机前被拒绝，正确实例停机返回 503，恢复后可读。[入口与实例验收](#2026-11-22-部署入口与实例验收) |
| 2．日常部署拓扑 | 隔离重建后由单一消费者接管，12 条积压归零，分区提交位置从 9/8/8 推进至 13/12/12。控制台区分现役、回退保留和故障，漏掉现役服务时验收失败。相关专项通过；完整快速回归仍有原记录所列格式检查阻断。[部署与重建验收](#2026-11-22-部署入口与实例验收) |
| 3．备份恢复与反向回退 | 隔离核对八表、首次已读、站外回执和 Kafka 偏移。PostgreSQL 原生恢复全程 56 s，反向 MySQL 回退至恢复签名查询用时 163.611 s。两端各 6,600 条积压分别在 46.110 s、199.130 s 归零，未读 P95 分别为 14.968 ms、30.969 ms。[恢复与回退验收](#2026-11-22-最新备份恢复与反向回退验收) |
| 4．通知告警 | 隔离死信触发告警，主值班在 0.37 s 确认；另一次在 12.05 s 升级，备班在 12.40 s 确认。两次均约 30 s 恢复，可由死信分区和偏移定位事件 ID。送达范围为隔离值班接收端。[告警验收](#2026-11-22-通知告警送达确认与升级验收) |

当时拟在日常业务窗口继续采集逐请求延迟，并同窗核对错误率、Kafka 积压与恢复、存储重试和供应方回执。后续检查确认了门户当前的实际取数路径。

**同日跟踪。** 截至 02:38，日常通知实例的未读成功计数仍为 30，站外接收成功计数仍为 1，均与上次验收基线一致。当前实例关闭了追踪导出。只读复核可利用相邻 Prometheus 原始抓取：在进程连续且单条指标计数仅增加 1 的区间，总耗时增量即为该请求耗时。仅在覆盖窗口内全部未读请求时才用这组样本计算 P95；同次抓取聚合多笔请求时，应另取可核对的逐笔耗时或直方图。站外请求另按事件与供应方回执核实投递时间。

本次原始查询与摘要保存在 `%LOCALAPPDATA%/CommerceFlow/releases/m4-close-review-20261122-022353/`，结论见 `close-review.json`。评审前后 44 个日常容器的身份、启动时间、状态和挂载一致，原有 490 个 Docker 卷全部保留，其中 23 个挂载于日常容器。本次仅更新评审文档，未发布。

日常门户当前加载的 `main-QGKJ4Y52.js` 与 [导航栏源码](../../web/commerce-portal/angular/src/app/inc/navbar/navbar.component.ts) 一致：顾客打开门户会请求通知列表，导航栏在浏览器按 `UNREAD` 统计数量。独立的 `/api/notifications/unread-count` 在该构建中只有客户端方法定义。因此，门户展示未读数无法为这项 API 门槛提供样本，仍需观察直接调用该接口的业务流量。

03:55 结束本次只读跟踪时，两项成功计数仍分别为 30 和 1。仓库内可定位的主动调用来自测试和切换验收；按当前学习项目的业务入口，日常操作无法自然形成所需窗口。本轮业务样本数为零，P95 和错误率均不可计算。M4 保持开放，后续需决定是否以门户实际读取的通知列表作为业务观测入口；独立未读接口的已有性能结果仍按受控验收记录。

### 2026-11-22 M4 门户路径收尾验收

**M4 学习里程碑验收通过，可以关闭。** 本轮按确认的范围使用门户实际读取的通知列表，在隔离环境以业务接口发起交易。独立未读计数继续采用切换时的 30 次签名查询结果，P95 为 19.81 ms。候选版本的日常发布和发布后观测单独跟踪。

04:50:37–04:51:00（北京时间），在独立 Compose 项目 `fairy-commerceflow-merchant-isolated-m4-20261122-044358` 使用 PostgreSQL 17.11。通知服务镜像与日常现役实例一致。顾客沿门户 Nginx → 商城 → notification-service 路径读取列表。同一个窗口内，订单 2 完成下单、模拟支付、商家确认和 80.00 元售后退款，触发站外短信和邮件。订单 3 通过取消入口产生 Kafka 站内通知，覆盖列表的新消息读取。

| 核对项 | 实测 |
| --- | --- |
| 门户通知列表 | `GET /api/notifications` 94 次均返回 HTTP 200 和 JSON 列表，其中 81 次落在交易起止之间。错误率 0/94，非 200、传输或解析失败均计入。客户端逐请求计时按最近秩取 P95 为 **31.485 ms**，最大 43.046 ms。本次将 100 ms 用作列表的参考验收线。列表在发起取消请求后 1.107 s 读到事件 `db643489-94a6-4927-8b37-e1dde47d13e9:notification`。 |
| Kafka 与存储 | 19 次采集均有积压读数 0、偏移刷新成功、存储重试计数 0、死信计数 0。取消事件所在分区 2 的日志末端与提交位置均为 1。分区 0、1 为空，三个分区均由同一实例接管。死信主题三个分区的末端均为 0。积压恢复门槛沿用第 3 阶段的 6,600 条积压归零复测。 |
| 站外供应方回执 | 退款事件 `offsite-refund:REF-RMT-2AA5A2BE17D04FF9` 的邮件和短信按原请求号匹配供应方模拟器回执，各一条且均为 `DELIVERED`。从通知侧创建投递记录到确认回执分别为 4.316 s、4.331 s，均满足 5 分钟门槛。邮件请求号 `0d25620e-bab5-39ba-895e-13ebcc5225ed`，短信请求号 `7a8caf53-83b9-3648-b882-d93334dd9653`。 |

部署与实例保护、备份恢复与反向回退、隔离告警送达确认升级沿用第 1–4 阶段验收。此次样本覆盖学习环境中可复现的门户业务流程。证据保存在 `%LOCALAPPDATA%/CommerceFlow/releases/m4-portal-close-20261122-044358/`，其中 `summary.json` 汇总请求和指标，`flow.json`、`provider-receipts.json` 和 `kafka-after.json` 可交叉复核。

本次无发布操作。前后快照显示 44 个日常容器的身份、启动时间、状态和挂载一致，325 个既有 CommerceFlow 卷及本次新增的 15 个隔离卷均保留。隔离项目仅执行停止操作。全局卷清单另发现一个其他 Compose 项目卷消失，现有证据无法确定原因，名称已记录在 `preservation.json`。

### M4 切换后续门禁

- [x] 使用最新 PostgreSQL 备份隔离复跑原生恢复和反向 MySQL 回退，核对八表、首次已读、站外回执、三分区提交位置及恢复时间。
- [x] PostgreSQL 恢复实例和 MySQL 回退实例通过 5 分钟内积压归零及未读查询 P95 ≤ 100 ms 的复测。
- [x] 在隔离环境验证通知死信告警送达、确认、超时升级和恢复，并按死信位置定位事件 ID。
- [x] 按门户实际业务路径完成隔离收尾，测量通知列表 P95 和错误率，并同窗核对 Kafka 积压、存储重试和站外回执。[门户路径收尾验收](#2026-11-22-m4-门户路径收尾验收)

日常候选发布和运行验证在 [架构待办的当前批](README.md#2-当前必须收尾与修复) 跟踪。容量、高可用和 Outbox 认领策略见 [后期专项](README.md#4-后期修复与容量专项)。

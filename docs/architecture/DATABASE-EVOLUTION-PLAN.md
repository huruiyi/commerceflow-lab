# 数据库演进计划（架构待办）

## 当前决策

当前保留 MySQL 8.4，各已独立进程继续拥有独立数据库：

- `commerce-db` / `commerceflow`
- `payment-simulator-db` / `payment_platform`
- `logistics-simulator-db` / `logistics_platform`
- `notification-db` / `notification`
- `catalog-db` / `catalog`
- `inventory-db` / `inventory`
- `order-db` / `orders`（已完成单一所有权 CUTOVER）
- `payment-db` / `payments`（支付编排）
- `fulfillment-db` / `fulfillment`

PostgreSQL 专项与服务拆分、目录及命名重构分开推进。2026-11-21 已核实 M3 六个服务的单一运行所有权。
入口退役、准入审计和当前版本恢复已通过隔离验收，M3 已在[日常发布与收尾核验](M3-SINGLE-OWNERSHIP-REVIEW.md#14-2026-11-21-日常发布与-m3-验收)
后正式关闭。PostgreSQL 专项仍按下列条件单独评审。
若 M3 清单增加新的待拆服务，新服务也必须先完成。当前行锁、唯一约束、幂等、Outbox 和事务竞态基线均已在
InnoDB 上实测。

## PostgreSQL 专项的启动条件

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
docker build --build-arg NOTIFICATION_POSTGRESQL_PILOT=true -t commerceflow-m4-notification:pilot services/notification-service
pwsh -NoProfile -File scripts/messaging/verify-notification-postgresql.ps1
```

通过 `-MySqlDump` 指定已有通知库归档可复跑存量场景，`-MySqlOnly` 单独运行 MySQL 对照和恢复。
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
`-CommerceImage`、`-PaymentImage` 和 `-CandidateImage` 固定本轮验收的三个镜像。

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
原服务地址和宿主机端口 18086。后续通知运行维护以这次 PostgreSQL 实例为准；现有 MySQL Compose 定义继续作为备份恢复入口。
恢复流量后发生回退时，须重新停写并以 PostgreSQL 最新快照和当前 Kafka 位置执行反向脚本，保留已经写入的数据。

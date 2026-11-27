# notification-service 单一所有权切换计划

> 实施状态（2026-11-15）：代码、Windows/Bash 通知专项、快速回归、Windows 完整验收、Bash 等价全链路与最终复核已完成；提交 `88d22c6` 已推送。
> 当前继续使用独立 原数据库。PostgreSQL 必须等 M3 清单中的 notification、catalog、inventory、order、
> payment-orchestration、fulfillment 以及后续新增待拆服务全部独立并验收后，另立专项实施。

## 1. 目标结论

notification 不长期维护单体版和微服务版两套实现。完成下一批后：

- `notification-service` 是通知查询、未读统计、单条已读和全部已读的唯一业务实现。
- `notification-db` 是客户通知读模型和已读状态的唯一运行数据源。
- commerce-service 不再查询或更新 `customer_notifications`，不保留双读、双写或数据库回退。
- commerce 只保留订单事务侧的 `notification_delivery_outbox` 和 V1 命令生产能力；这是上游可靠发布职责，
  不是第二份通知读模型。
- 浏览器现阶段仍可经 commerce 的身份代理访问通知，但代理只负责 Session/RBAC、服务签名和 HTTP 转发，
  不包含通知业务规则或数据库访问。未来独立 identity/API Gateway 就绪后再移除此薄代理。

独立微服务故障时，通知功能明确返回不可用并由界面降级，不回退到一份可能过期的单体数据。

## 2. 为什么不采用 LOCAL/COMPARE

长期双读或双写会产生两套 Mapper、两套主键、状态镜像、差异审计、回退分支和测试矩阵。通知属于允许最终
一致的非核心副作用，适合直接建立单一所有权，而不是用永久双实现换取表面可用性。

迁移只允许一次性、限时的数据交接：迁移前做备份和水位确认，迁移完成后切换唯一 API，再删除 commerce
运行路径。历史迁移文件保留以保证旧库可升级，但不继续承载运行时通知业务。

## 3. 最终调用链

```text
订单事务
  -> commerce notification_delivery_outbox
  -> Kafka customer-notification-commands
  -> notification-service Inbox
  -> notification-db customer_notifications

浏览器
  -> commerce 身份薄代理
  -> signed HTTP
  -> notification-service
  -> notification-db
```

这条链路只有一份通知读模型。commerce 的 Outbox 是事件生产端可靠性设施，不能移动到 notification-db，
否则订单事务与事件写入之间会出现不可恢复窗口。

## 4. notification-service 唯一 API

notification-service 增加版本化接口，响应 DTO 不直接暴露 MyBatis row：

| 方法与路径 | 用途 |
| --- | --- |
| `GET /internal/v1/customers/{customerId}/notifications?limit=20` | 最近通知 |
| `GET /internal/v1/customers/{customerId}/notifications/unread-count` | 未读数量 |
| `PATCH /internal/v1/customers/{customerId}/notifications/{eventId}/read` | 按稳定 eventId 标记已读 |
| `PATCH /internal/v1/customers/{customerId}/notifications/read-all` | 全部标记已读 |

规则：

- `eventId` 是跨进程稳定标识；独立库自增 id 只作为内部存储主键。
- 所有查询和更新必须同时带 customerId 条件，禁止仅凭 eventId 越权操作。
- 已读命令天然幂等；重复请求不得改变首次 `readAt`。
- `limit` 首版固定上限 20，不在拆分批次夹带分页产品改造。
- 固定 V1 JSON fixture 同时约束服务端和 commerce 薄代理客户端。

## 5. 身份边界

浏览器不能提交受信 customerId，也不直接访问内部接口。commerce 从已有 Session 解析客户身份，再对 method、
规范化 target、时间戳和 requestId 做独立 HMAC 签名；当前四个无请求体接口不虚构 body hash。notification 在
`infrastructure/security` 验签、限制时间偏差并校验调用方。

commerce 身份代理不得：

- 访问 notification-db；
- 解释通知状态；
- 在远端失败时查询 commerce 通知表；
- 修改或补写通知正文；
- 把 Session Cookie、密钥或完整正文写入日志。

生产环境应把 HMAC 升级为 mTLS 或短期服务身份令牌。独立 identity/API Gateway 落地后，浏览器可经统一网关
直接访问 notification-service，届时删除 commerce 薄代理。

## 6. 一次性迁移与切换顺序

### 6.1 先补齐独立服务能力

1. 在 notification-service 增加查询、未读统计和已读 application/API。
2. 扩展独立 Mapper，所有 SQL 只位于 notification-service。
3. 增加服务间验签、客户隔离、幂等和契约测试。
4. 增加超时、错误码、低基数指标、日志脱敏和 HTTP Trace。

此时不新增 commerce 双读代码；验证直接针对 notification-service 和 notification-db。

### 6.2 一次性数据交接

1. 记录 commerce 通知最大水位和行数，备份相关表。
2. 停止 commerce 进程内 notification consumer，确保稳定消费者组只由独立服务持有。
3. 将尚未进入 notification-db 的历史记录按 eventId 幂等回填一次。
4. 等待 Kafka lag 为 0，核对总数、eventId、业务字段和已读状态。
5. 差异不为 0 时停止切换并修复数据；不启用另一套运行路径兜底。

核对是一次性切换门禁，不是长期 COMPARE 模式。

### 6.3 原位替换公开接口实现

1. commerce 的 `/api/notifications` 路径保持前端兼容，但 Controller 改为纯 HTTP 代理。
2. 列表、未读数、单条已读、全部已读全部调用 notification-service。
3. Angular 改用 eventId 执行单条已读；旧数字 id 仅在兼容窗口返回，不参与跨服务更新。
4. notification-service 不可用时返回稳定 `503/NOTIFICATION_SERVICE_UNAVAILABLE`，通知菜单显示可重试状态；
   订单、支付、物流等核心功能不受影响。

### 6.4 删除单体运行实现

切换验收通过后在同一里程碑内删除：

- commerce `CustomerNotificationService` 的本地/独立数据库路由；
- commerce notification 查询和已读 Mapper/XML；
- commerce 进程内 notification Kafka consumer；
- commerce 独立库 DataSource/MyBatis/Flyway/事务配置；
- 投影比较、存储回填、核对和隔离管理端运行代码；
- `NOTIFICATION_STORAGE_MODE/ENABLED/CONSUMER_ENABLED` 等旧运行开关。

保留：

- V1 通知命令生产契约；
- commerce `notification_delivery_outbox`、Dispatcher、失败重试和 DLT 生产侧配置；
- 已发布 Flyway 历史文件及必要的数据迁出标记迁移；
- notification-service 自有 Inbox、读模型和 API。

## 7. 故障模型

采用单一实现后，notification 故障语义必须明确：

| 故障 | 行为 |
| --- | --- |
| notification-service 停机 | 通知 API 返回稳定 503；核心商城继续工作 |
| notification-db 停机 | 服务健康降级，通知 API 503；不查询 commerce-db |
| Kafka 停机 | commerce Outbox 保留 RETRY；已有通知仍可查询和标记已读 |
| Kafka 消费积压 | 新通知延迟出现；指标和告警展示 lag，不产生第二份本地通知 |
| 重复命令 | Inbox/eventId 幂等，只产生一条通知 |
| 已读请求重放 | 保持 READ 和首次 readAt，不产生额外状态 |

通知是非核心能力，短时不可用优于维护两套可能分叉的数据真相。

## 8. 验收门禁

1. notification-service 测试覆盖列表、未读数、单条/全部已读、客户隔离、幂等和 UTC 序列化。
2. 服务签名覆盖合法、篡改 customerId/path、过期时间戳和错误密钥。
3. 一次性回填后 commerce 历史通知与 notification-db 按 eventId 核对为零差异，Kafka lag 为 0。
4. 公开四个通知接口全部只经 notification-service 返回，commerce 日志不得出现通知 Mapper SQL。
5. notification-service/database 分别停机时通知 API 返回稳定 503，订单、支付和物流链路继续健康。
6. Kafka 停机期间新通知留在 commerce Outbox，恢复后只进入 notification-db 一次。
7. Tempo 同一 Trace 包含 commerce 身份代理和 notification API；指标不记录正文等高基数数据。
8. Windows 与容器化 Bash 均验证正常、越权、重复、停机、积压恢复和服务重启。
9. 代码质量门禁证明 commerce 不再含通知读模型 Mapper、独立 notification 数据源或本地读写路由。

## 9. 明确不在本批中的内容

- 不维护 LOCAL/COMPARE/REMOTE 三套运行模式。
- 不保留 commerce 数据库通知回退。
- 不拆第二个微服务。
- 不启动 PostgreSQL、服务注册中心、Kubernetes 或多节点中间件。
- 不在通知拆分中改造订单、支付、物流状态机。

## 10. 推荐提交顺序

本批可以分提交降低审查成本，但最终只交付一套运行实现：

1. notification-service API、安全、Mapper 和测试；此提交不切公开入口。
2. 一次性回填/核对工具和验收脚本。
3. commerce 身份薄代理、Angular eventId 兼容和公开入口切换。
4. 删除 commerce 通知读模型运行代码、旧配置和专项；执行全量门禁后独立推送。

前三个提交只是同一切换批次的可审查步骤，不作为长期双实现版本发布。

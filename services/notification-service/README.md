# notification-service

CommerceFlow 的独立通知服务。它消费冻结的 `CUSTOMER_NOTIFICATION_REQUESTED` V1 Kafka 命令，使用独立
MySQL Schema、稳定消费者组和 Inbox 幂等写入通知读模型，并独占通知查询与已读状态业务。

## 当前边界

- 提供列表、未读数、eventId 单条已读和全部已读的内部 V1 API。
- 不访问 commerce Schema、Mapper、Session 或 `AppUser`。
- 默认随 Compose 启动；commerce-service 只从 Session 提取 customerId，经 HMAC 签名薄代理访问。
- 独立服务不可用时明确返回 503，不回退到 commerce 通知表。
- 当前继续使用 MySQL；所有计划内相关服务全部独立并验收前不考虑 PostgreSQL。

## 启动

从仓库根目录运行：

```powershell
docker compose up -d --build --wait notification-service commerce-service
```

健康检查为 `http://localhost:18086/actuator/health`。内部业务 API 只接受 commerce-service HMAC 身份，
浏览器继续通过 `/api/notifications` 访问，不直接调用 `18086`。

配对验收入口：

- Windows：`scripts/windows/verify-notification-runtime-cutover.ps1`
- Bash：`scripts/linux/verify-notification-runtime-cutover.sh`

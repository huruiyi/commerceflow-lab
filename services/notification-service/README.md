# notification-service

CommerceFlow 的首个独立影子进程。它消费冻结的 `CUSTOMER_NOTIFICATION_REQUESTED` V1 Kafka 命令，
使用独立 MySQL Schema、稳定消费者组和 Inbox 幂等写入通知读模型。

## 当前边界

- 只承担影子消费与独立存储，不提供客户通知读 API。
- 不访问 commerce Schema、Mapper、Session 或 `AppUser`。
- 默认 Compose Profile 关闭；commerce-service 继续承载权威客户流量和单体回退。
- 不使用 PostgreSQL，也不删除商城中的 notification 表。

## 启动

从仓库根目录运行：

```powershell
docker compose --profile notification-service up -d --build --wait notification-service
```

健康检查为 `http://localhost:18086/actuator/health`，只读影子状态为
`http://localhost:18086/internal/shadow/status`。

配对验收入口：

- Windows：`scripts/windows/verify-notification-service-shadow.ps1`
- Bash：`scripts/linux/verify-notification-service-shadow.sh`

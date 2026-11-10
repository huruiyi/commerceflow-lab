# education-angular-2022

这是一个可独立运行的前后端分离教学项目，已将原 Angular Demo 中的登录、商品、订单和用户功能改为本地 Spring Boot + MySQL 实现，不再依赖外部 JsonBulut API。

## 技术架构

```text
浏览器
  └─ http://localhost:14200
       └─ Nginx
            ├─ Angular 22 静态页面
            └─ /api/* → Spring Boot 2.6.15 → MySQL 8.4
```

- `frontend/`：Angular 前端、Nginx 配置和前端 Dockerfile。
- `backend/`：Spring Boot 2.6.15、Spring Security、Spring Data JPA 和后端 Dockerfile。
- `compose.yaml`：一键启动 MySQL、后端和前端。

## 一键启动

在本目录执行：

```powershell
docker compose up --build -d --wait
docker compose ps
```

访问地址：

- 前端：`http://localhost:14200`
- 前端健康检查：`http://localhost:14200/healthz`
- 后端健康检查：`http://localhost:18081/actuator/health`

教学账号：

```text
邮箱：demo@example.com
密码：demo-password
```

### 如何登录

1. 确认 `docker compose ps` 中 `mysql`、`backend`、`web` 均为 `healthy`。
2. 浏览器打开 `http://localhost:14200`。
3. 输入 `demo@example.com` 和 `demo-password`。
4. 点击登录后进入商品页，可继续查看订单和用户列表。

本项目**没有 Spring Boot 启动时随机生成的默认密码**。`demo@example.com` 是应用首次连接空数据库时创建的 MySQL 演示用户，密码以 BCrypt 哈希保存，数据库中不保存明文。Spring Security 的 `User` 是认证时从该数据库记录转换出来的运行时对象，不是另一个内置账号。

账号初始值由 Compose 中的两个环境变量提供：

```yaml
DEMO_USER_EMAIL: demo@example.com
DEMO_USER_PASSWORD: demo-password
```

它们只在用户表为空时用于初始化。数据卷中已有用户后，仅修改 Compose 环境变量不会覆盖现有密码。如需从零重建本地演示数据，可执行：

```powershell
docker compose down --volumes
docker compose up --build -d --wait
```

`--volumes` 会同时删除已有订单和所有 MySQL 演示数据，使用前请确认数据可以丢弃。

停止服务：

```powershell
docker compose down
```

需要同时删除本地演示数据时使用 `docker compose down --volumes`。也可在上一级 `legacy-reference` 目录通过聚合 Compose 与其他 Demo 一起启动。

## 已实现功能

- Spring Security 邮箱密码登录、BCrypt 密码哈希、服务端 Session 和注销。
- `HttpOnly` 会话 Cookie，以及 Angular 兼容的 `XSRF-TOKEN` Cookie / `X-XSRF-TOKEN` Header 防护。
- 商品查询、按数量创建订单、订单数量调整、单价/小计计算、当前用户订单查询和用户列表。
- MySQL 数据持久化与演示数据自动初始化。
- 前后端单元/集成测试、Docker 健康检查和 Nginx API 反向代理。

## 安全和生产边界

Compose 中的数据库密码和教学账号只适用于本地 Demo。真实部署应使用密钥管理、HTTPS、自定义 Cookie Secure 策略、数据库迁移工具（如 Flyway），并移除对外暴露的后端调试端口。

详见 [前端说明](frontend/README.md) 和 [后端说明](backend/README.md)。

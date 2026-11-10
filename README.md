# education-angular-2022

这是一个可独立运行的前后端分离教学项目，已将原 Angular Demo 中的登录、商品、订单和用户功能改为本地 Spring Boot + MySQL 实现，不再依赖外部 JsonBulut API。

## 技术架构

```text
浏览器
  └─ http://localhost:14200
       └─ Nginx
            ├─ Angular 22 静态页面
            └─ /api/* → Spring Boot 3.5.16 → MyBatis-Plus → MySQL 8.4
                                      └────→ Redis 7.4

支付平台浏览器 http://localhost:14201
  └─ Vue 3 + Nginx → 独立 Spring Boot 支付服务 → 独立 MySQL
                                      └────────────→ 独立 Redis Session 命名空间
```

- `frontend/`：Angular 前端、Nginx 配置和前端 Dockerfile。
- `backend/`：Java 21、Spring Boot 3.5.16、Spring Security 6、MyBatis-Plus、Flyway 和后端 Dockerfile。
- `mock-payment-service/`：独立支付数据库、后台安全、商户凭据和三渠道交易服务。
- `mock-payment-ui/`：Vue 3 支付管理后台与支付宝/微信/银联模拟收银台。
- `compose.yaml`：一键启动 MySQL、Redis、后端和前端。

## 一键启动

在本目录执行：

```powershell
docker compose up --build -d --wait
docker compose ps
```

访问地址：

- 前端：`http://localhost:14200`
- 支付平台：`http://localhost:14201`
- 前端健康检查：`http://localhost:14200/healthz`
- 后端健康检查：`http://localhost:18081/actuator/health`
- 支付服务健康检查：`http://localhost:18082/actuator/health`

教学账号（密码均为 `demo-password`）：

```text
客户：demo@example.com
商家：merchant@example.com
管理员：admin@example.com
```

支付平台账号（密码同为 `demo-password`）：

```text
平台管理员：payment-admin
支付运营员：payment-operator
支付审计员：payment-auditor
```

### 如何登录

1. 确认 `docker compose ps` 中商城与支付平台的七个服务均为 `healthy`。
2. 浏览器打开 `http://localhost:14200`。
3. 输入 `demo@example.com` 和 `demo-password`。
4. 点击登录后进入商品页，可继续查看订单和用户列表。

支付平台使用方法：

1. 打开 `http://localhost:14201`，用 `payment-admin` / `demo-password` 登录。
2. 在“商户与凭据”创建商户，立即保存只显示一次的 App Secret。
3. 在“渠道交易”填写 App ID 与 App Secret，依次体验支付宝跳转、微信二维码和银联网关。
4. 可在“后台账号”创建包含邮箱、MFA 策略、角色和商户范围的独立支付后台用户。

完整自动验收：

```powershell
./verify-payment.ps1
```

脚本会验证三渠道创建、收银成功、后台关闭、幂等重放、运营员商户范围、审计员越权拒绝和审计日志。

本项目**没有 Spring Boot 启动时随机生成的默认密码**。三个演示账号由 Flyway 创建，密码以 BCrypt 哈希保存。Spring Security 的 `User` 是认证时从数据库记录转换出来的运行时对象，不是另一个内置账号。

演示账号、禁用的用户列表资料和商品由 Flyway 的 `V1__baseline_schema_and_demo_data.sql` 创建。为了让公开教学仓库克隆后直接运行，演示账号的 BCrypt 哈希随迁移脚本提交；数据库中仍不保存明文密码。如需从零重建本地演示数据，可执行：

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

- Spring Security 邮箱密码登录、BCrypt、CUSTOMER/MERCHANT/ADMIN 权限和注销。
- Redis 服务端 Session、14 天持久化 Remember-Me、注销撤销令牌和五分钟登录失败限流。
- `HttpOnly` 会话 Cookie，以及 Angular 兼容的 `XSRF-TOKEN` Cookie / `X-XSRF-TOKEN` Header 防护。
- 商品查询、去重购物车、数量调整、移除、清空、单价/小计计算和管理员用户列表。
- 客户收货地址 CRUD、唯一默认地址、中英文地址字段和结算地址选择。
- 多商品结算、商品与地址快照、订单历史，以及支付宝/微信支付/银联选择、幂等键管理、请求重放和支付回执 UI。
- MyBatis-Plus 数据访问、Flyway 版本化结构/演示数据初始化。
- Redis Session、登录限流、持久化卷和健康检查；商品缓存等业务增强留在后续章节。
- 前后端单元/集成测试、Docker 健康检查和 Nginx API 反向代理。

## 安全和生产边界

Compose 中的数据库密码和教学账号只适用于本地 Demo。支付按钮只写入本地模拟支付记录，不会连接支付机构或产生真实资金交易。真实部署应使用密钥管理、HTTPS、自定义 Cookie Secure 策略，并移除对外暴露的后端调试端口。Flyway 迁移已启用；已经发布的迁移脚本不应直接修改，而应新增后续版本。

详见 [前端说明](frontend/README.md) 和 [后端说明](backend/README.md)。

高级三渠道模拟支付、Kafka、幂等、安全和高可用实验的设计与分章计划见
[高级模拟支付实现方案](docs/ADVANCED-PAYMENT-PLAN.md)。

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

## 学习路线与代码阅读顺序

建议按“界面调用 → 安全边界 → 业务事务 → 数据结构 → 异步可靠性”的顺序阅读：

1. 从 `frontend/src/app` 看登录、商品、购物车、地址、订单和商家页面如何调用 API。
2. 阅读 `backend/.../configuration/SecurityConfiguration.java` 和认证 Controller，理解 Session、CSRF、Remember-Me 与角色授权。
3. 阅读购物车、下单、支付、履约、收货评价对应的 Controller、Service、Mapper。
4. 按版本查看 `backend/src/main/resources/db/migration`，把业务模型与数据库约束对应起来。
5. 阅读支付 Outbox、Kafka 发布/消费和补偿任务，再进入独立的 `mock-payment-service`。
6. 最后使用根目录 `verify-*.ps1` 做成功、失败、幂等、越权和故障实验。

分章目标和提交边界见 [IMPLEMENTATION-PLAN.md](IMPLEMENTATION-PLAN.md)，接口索引见 [docs/API.md](docs/API.md)。

## 一键启动

在本目录执行：

```powershell
docker compose up --build -d --wait
docker compose ps
```

默认配置可直接用于本地学习。需要改端口以外的数据库密码、回调密钥或 Remember-Me 密钥时，复制 `.env.example` 为 `.env` 后修改；不要把生产密钥提交到仓库。

访问地址：

- 前端：`http://localhost:14200`
- 支付平台：`http://localhost:14201`
- 前端健康检查：`http://localhost:14200/healthz`
- 后端健康检查：`http://localhost:18081/actuator/health`
- 支付服务健康检查：`http://localhost:18082/actuator/health`
- Kafka UI：`http://localhost:14202`

教学账号（密码均为 `demo-password`）：

| 账号 | 角色 | 可体验功能 |
| --- | --- | --- |
| `demo@example.com` | CUSTOMER | 购物车、地址、下单、支付、收货、评价 |
| `merchant@example.com` | MERCHANT | 查看所属订单、确认订单、录入物流并发货 |
| `admin@example.com` | ADMIN | 用户与角色数据查看 |

支付平台账号（密码同为 `demo-password`）：

| 账号 | 角色 | 可体验功能 |
| --- | --- | --- |
| `payment-admin` | PAYMENT_ADMIN | 用户、商户、凭据、安全设置与全量后台能力 |
| `payment-operator` | PAYMENT_OPERATOR | 商户和渠道交易运营 |
| `payment-auditor` | PAYMENT_AUDITOR | 只读审计与交易检查 |

### 如何登录

1. 确认 `docker compose ps` 中所有长期运行服务均为 `running`，带健康检查的服务均为 `healthy`。
2. 浏览器打开 `http://localhost:14200`。
3. 输入 `demo@example.com` 和 `demo-password`。
4. 点击登录后进入商品页，可继续查看订单和用户列表。

支付平台使用方法：

1. 打开 `http://localhost:14201`，用 `payment-admin` / `demo-password` 登录。
2. 先进入“安全中心”绑定 TOTP，并离线保存只显示一次的恢复码。
3. 在“商户与凭据”创建 HMAC 或 RSA 商户，立即保存只显示一次的 App Secret。
4. 在“渠道交易”使用 Web Crypto 签名，依次体验支付宝跳转、微信二维码和银联网关。
5. 密钥轮换、商户启停、创建后台用户和权限变更前，需在安全中心生成一次性 Step-up 令牌。

完整自动验收：

```powershell
./verify-all.ps1
```

统一脚本会在 Docker 内构建并测试各应用，启动完整环境，验证认证、CSRF、CUSTOMER/MERCHANT/ADMIN 权限边界、国际化、支付结果、Kafka、商家履约、收货评价、Redis 缓存及降级。权限矩阵可单独执行 `./verify-authorization.ps1`；支付平台的三渠道签名、重复 Nonce、金额篡改、TOTP、Step-up 和哈希链专项实验可单独执行 `./verify-payment.ps1`。

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
- 商家确认与发货、客户确认收货、一次性商品评价和评分聚合。
- MyBatis-Plus 数据访问、Flyway 版本化结构/演示数据初始化。
- Redis Session、登录限流、商品/评分缓存、支付幂等辅助键、MySQL 降级和健康检查。
- 中英文运行时切换、商品翻译、稳定错误码和本地语言偏好。
- Kafka Transactional Outbox、幂等消费、对账补偿和高可用教学 Overlay。
- 后端集成测试、支付服务测试、Docker 构建验收、健康检查和 Nginx API 反向代理。

## 安全和生产边界

Compose 中的数据库密码、商户号、App ID/Secret、RSA/HMAC 密钥和教学账号只适用于本地 Demo。界面和协议刻意模拟真实支付，但不会连接支付机构或产生真实资金交易；应用级幂等、签名、Kafka 和多实例实验也不等同于金融机构级容灾与合规。真实部署必须使用密钥管理与定期轮换、HTTPS、Secure Cookie、网络隔离、真实渠道证书、PCI/等保等合规控制，并移除对外暴露的调试端口。Flyway 迁移已启用；已经发布的迁移脚本不应直接修改，而应新增后续版本。

## 常见故障

- 端口被占用：检查 `14200`、`14201`、`14202`、`18081`、`18082`、`13307`，或修改 Compose 端口映射。
- 服务一直 `starting`：运行 `docker compose ps` 和 `docker compose logs <service>`，优先查看 MySQL、Kafka 和后端依赖健康状态。
- 修改源码未生效：执行 `docker compose up -d --build --wait` 重新构建镜像。
- 演示数据需要重置：确认数据可删除后执行 `docker compose down --volumes`，再重新启动。
- Redis 暂停时登录 Session 会受影响，但公开商品会降级查询 MySQL；恢复后等待健康检查通过。
- Windows 脚本执行策略阻止运行时，可使用 `powershell -ExecutionPolicy Bypass -File .\verify-all.ps1`。

详见 [前端说明](frontend/README.md) 和 [后端说明](backend/README.md)。

高级三渠道模拟支付、Kafka、幂等、安全和高可用实验的设计与分章计划见
[高级模拟支付实现方案](docs/ADVANCED-PAYMENT-PLAN.md)。

05A-5～05A-8 的 Kafka、三渠道收银台、HA、监控和故障实验见
[可靠支付交付指南](docs/CHAPTER-05A-5-TO-8.md)。

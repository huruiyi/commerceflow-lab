# CommerceFlow Lab / 全栈商城与支付流程实验室

这是一个可独立运行的前后端分离教学项目，已将原 Angular Demo 中的登录、商品、订单和用户功能改为本地 Spring Boot + MySQL 实现，不再依赖外部 JsonBulut API。

## 项目标识 / Project identity

| 用途 | 标识 |
|---|---|
| 英文标题 | `CommerceFlow Lab` |
| 中文标题 | `全栈商城与支付流程实验室` |
| GitHub 仓库 | `commerceflow-lab` |
| Docker Compose 项目前缀 | `commerceflow` |
| 商城后端 Maven artifactId | `commerceflow-backend` |
| Java 命名空间 | `lab.commerceflow.storefront` / `lab.commerceflow.payment` |

## 技术架构

```text
浏览器
  └─ http://localhost:14200
       └─ Nginx
            ├─ Angular 22.2.6 + SimUI 1.8.0 / Spartan UI 静态页面
            └─ /api/* → Spring Boot 4.1.2 → MyBatis-Plus → MySQL 8.4
                                      └────→ Redis 7.4

支付平台浏览器 http://localhost:14201
  └─ Vue 3.5.45 + Ant Design Vue Next + VXE-Table + Nginx
                  → 独立 Spring Boot 支付服务 → 独立 MySQL
                                      └────────────→ 独立 Redis Session 命名空间
```

- `frontend/`：Angular 22.2.6、SimUI 1.8.0 / Spartan UI 1.8.0、Tailwind CSS 4.3.5、Bootstrap 5.3.9、Nginx 配置和前端 Dockerfile。
- `backend/`：Java 25 LTS、Spring Boot 4.1.2、Spring Security 7、MyBatis-Plus、Flyway 和后端 Dockerfile。
- `mock-payment-service/`：独立支付数据库、后台安全、商户凭据和三渠道交易服务。
- `mock-payment-ui/`：Vue 3.5.45、Ant Design Vue Next 和 VXE-Table 支付管理后台与模拟收银台。
- `compose.yaml`：一键启动 MySQL、Redis、后端和前端。

## 学习路线与代码阅读顺序

建议按“界面调用 → 安全边界 → 业务事务 → 数据结构 → 异步可靠性”的顺序阅读：

1. 从 `frontend/src/app` 看登录、商品、购物车、地址、订单和商家页面如何调用 API。
2. 阅读 `backend/.../configuration/SecurityConfiguration.java` 和认证 Controller，理解 Session、CSRF、Remember-Me 与角色授权。
3. 阅读购物车、下单、支付、履约、收货评价对应的 Controller、Service、Mapper。
4. 按版本查看 `backend/src/main/resources/db/migration`，把业务模型与数据库约束对应起来。
5. 阅读支付 Outbox、Kafka 发布/消费和补偿任务，再进入独立的 `mock-payment-service`。
6. 最后使用 `scripts/windows/` 或 `scripts/linux/` 中的验收脚本做成功、失败、幂等、越权和故障实验。

文档统一从 [docs/README.md](docs/README.md) 进入：[已实现现状](docs/IMPLEMENTED.md) 只记录可验证事实，[统一代办](docs/TODO.md) 是订单生命周期、超时策略、模拟物流和页面补齐的唯一未实现清单，接口索引见 [docs/API.md](docs/API.md)。分章历史见 [IMPLEMENTATION-PLAN.md](IMPLEMENTATION-PLAN.md)。

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
- 模拟物流健康检查：`http://localhost:18083/actuator/health`
- 物流 MySQL：`localhost:13308`
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

## 验收脚本

根目录不再放置平台脚本。两套脚本的验收范围与文件名一一对应：

- `scripts/windows/*.ps1`：Windows 上使用 PowerShell 7（`pwsh`）运行。
- `scripts/linux/*.sh`：Linux、WSL 或 Git Bash 使用，不依赖 PowerShell；`_common.sh` 是各脚本共用的内部函数库，不需要单独运行。

脚本会自行定位项目根目录，所以既可以在项目根目录执行，也可以从其他目录使用绝对路径执行。运行前需要安装并启动 Docker（含 Compose v2）；Bash 版还需要 `curl`、`jq`、`openssl`，支付安全脚本额外使用 GNU `base32`、`xxd` 和 `od`。

Debian/Ubuntu 可安装 Bash 版依赖：

```bash
sudo apt-get update
sudo apt-get install -y curl jq openssl coreutils xxd
```

Fedora/RHEL 可使用：

```bash
sudo dnf install -y curl jq openssl coreutils vim-common
```

Windows 若选择 `.ps1`，请先安装 PowerShell 7；若使用 Git Bash，则仍需额外安装或放入 `PATH` 的 `jq`。

### 完整自动验收

Windows：

```powershell
./scripts/windows/verify-all.ps1
```

Bash：

```bash
bash ./scripts/linux/verify-all.sh
```

完整验收会先构建镜像并运行镜像内测试，再启动完整环境，最后验证认证、CSRF、权限边界、国际化、支付结果、Kafka、签名物流和回调轨迹、收货评价、Redis 缓存、MySQL 降级与健康状态。已有最新镜像时可跳过构建：

```powershell
./scripts/windows/verify-all.ps1 -SkipBuild
```

```bash
bash ./scripts/linux/verify-all.sh --skip-build
```

需要验证非默认商城地址时，Windows 使用 `-BaseUrl http://host:port`，Bash 使用 `--base-url http://host:port`。

### 单项脚本及作用

| Windows / Bash 同名脚本 | 作用 | 默认地址 |
| --- | --- | --- |
| `verify-all` | 构建、启动并串联主要验收，最后检查商城/支付/物流健康端点 | 商城 `14200` |
| `verify-authorization` | 匿名访问、登录、禁用账号、CSRF 和三种角色权限矩阵 | 商城 `14200` |
| `verify-i18n` | 中英文商品、按语言隔离的 Redis 缓存、稳定错误码和前端入口 | 商城 `14200` |
| `verify-payment-notifications` | 创建订单、发起待支付交易、模拟签名回调并拒绝伪造回调 | 商城 `14200` |
| `verify-kafka-payment` | 支付回调、Kafka Topic、Outbox 发布和消费记录 | 商城 `14200` |
| `verify-payment-outcomes` | 失败、余额不足、取消和超时四类支付结果 | 商城 `14200` |
| `verify-refunds` | 全额退款、资金账一致性、请求/通知幂等、重复申请和伪造通知拒绝 | 商城 `14200` |
| `verify-merchant-fulfillment` | 已支付订单的商家确认、发货及客户越权拒绝 | 商城 `14200` |
| `verify-logistics` | 独立物流建单、签名/防重放、`EXCEPTION（异常件） -> RECOVERY（异常恢复）`、六段轨迹和回调投影 | 商城 `14200`、物流 `18083` |
| `verify-logistics-automation` | 临时启用秒级自动轨迹，验证自动送达与 `AUTO_RECEIVE`，结束后恢复默认关闭 | 商城 `14200`、物流 `18083` |
| `verify-logistics-reliability` | 验证 backend 停机后的 `RETRY -> PUBLISHED`，以及永久丢失回调由主动对账恢复为 `RECONCILED` | 商城 `14200`、物流 `18083` |
| `verify-logistics-callback-governance` | 制造 `DEAD（停止自动重试）`，验证管理员人工重放、审计、权限、幂等和 Prometheus 指标 | 商城 `14200`、物流 `18083` |
| `verify-auto-lifecycle` | 手动收货取消自动任务、`DELIVERED（已送达） -> RECEIVED（已收货） -> COMPLETED（已完成）`，以及 backend 重启后租约恢复/幂等重放 | 商城 `14200`、物流 `18083` |
| `verify-rabbitmq-scheduling` | RabbitMQ 延迟任务的确认发布、策略快照、原子领取、审计事件和未支付订单自动关闭；需先启用 RabbitMQ Profile 并使用秒级测试期限 | 商城 `14200`、RabbitMQ `15672` |
| `verify-receipt-reviews` | 收货、一次性评价、评分校验及商品评分聚合 | 商城 `14200` |
| `verify-redis-business` | 商品缓存 TTL、支付幂等键、Redis 停机时 MySQL 降级及恢复 | 商城 `14200` |
| `verify-payment-security` | HMAC/RSA 三渠道签名、Nonce/篡改/过期拒绝、TOTP、Step-up 和审计哈希链 | 支付平台 `14201` |
| `verify-payment` | `verify-payment-security` 的兼容入口 | 支付平台 `14201` |

Windows 单项脚本通过 `-BaseUrl` 改地址：

```powershell
./scripts/windows/verify-authorization.ps1 -BaseUrl http://localhost:14200
./scripts/windows/verify-payment.ps1 -BaseUrl http://localhost:14201
```

Bash 单项脚本把地址作为第一个位置参数；也可省略并使用表中的默认值：

```bash
bash ./scripts/linux/verify-authorization.sh http://localhost:14200
bash ./scripts/linux/verify-payment.sh http://localhost:14201
```

RabbitMQ 调度专项验收需要显式启用可选适配器；默认 `docker compose up` 仍只使用 Database，不依赖 RabbitMQ：

```powershell
$env:SCHEDULING_RABBITMQ_ENABLED='true'
$env:ORDER_PAYMENT_TIMEOUT='5s'
docker compose --profile rabbitmq up -d --build --wait rabbitmq backend web
./scripts/windows/verify-rabbitmq-scheduling.ps1 -MaxWaitSeconds 30
```

```bash
SCHEDULING_RABBITMQ_ENABLED=true ORDER_PAYMENT_TIMEOUT=5s \
  docker compose --profile rabbitmq up -d --build --wait rabbitmq backend web
bash ./scripts/linux/verify-rabbitmq-scheduling.sh http://localhost:14200 30
```

这些脚本不是纯只读检查：支付、履约、评价脚本会创建演示订单、交易、商户或评价；`verify-redis-business` 会短暂停止再恢复 Redis；完整验收会构建镜像并启动 Compose 服务。建议只在本地学习环境运行。

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
- 独立模拟物流服务和数据库，支持 HMAC/时间戳/Nonce 防重放、幂等建单、手动/可配置自动六段轨迹、异常件/恢复、回调 Outbox/重试、`DEAD（停止自动重试）` 管理员重放/审计/指标、商城主动对账、投影和客户物流查询。
- 物流 `DELIVERED（已送达）` 后可通过 `AUTO_RECEIVE（自动确认收货）` 进入 `RECEIVED（已收货）`，再通过 `AUTO_COMPLETE（自动完成订单）` 进入 `COMPLETED（已完成）`；两个观察期可配置。
- 未发货已支付订单原路全额退款：独立退款单、签名渠道退款、异步通知、请求/通知幂等、退款事件、Outbox、对账补偿，以及客户/商家/支付后台三端展示。
- MyBatis-Plus 数据访问、Flyway 版本化结构/演示数据初始化。
- Redis Session、登录限流、商品/评分缓存、支付幂等辅助键、MySQL 降级和健康检查。
- 中英文运行时切换、商品翻译、稳定错误码和本地语言偏好。
- Kafka Transactional Outbox、幂等消费、对账补偿和高可用教学 Overlay。
- 后端集成测试、支付服务测试、Docker 构建验收、健康检查和 Nginx API 反向代理。

## 安全和生产边界

Compose 中的数据库密码、商户号、App ID/Secret、RSA/HMAC 密钥和教学账号只适用于本地 Demo。界面和协议刻意模拟真实支付，但不会连接支付机构或产生真实资金交易；应用级幂等、签名、Kafka 和多实例实验也不等同于金融机构级容灾与合规。真实部署必须使用密钥管理与定期轮换、HTTPS、Secure Cookie、网络隔离、真实渠道证书、PCI/等保等合规控制，并移除对外暴露的调试端口。Flyway 迁移已启用；已经发布的迁移脚本不应直接修改，而应新增后续版本。

## 常见故障

- 端口被占用：检查 `14200`、`14201`、`14202`、`18081`、`18082`、`18083`、`13307`、`13308`，或修改 Compose 端口映射。
- 服务一直 `starting`：运行 `docker compose ps` 和 `docker compose logs <service>`，优先查看 MySQL、Kafka 和后端依赖健康状态。
- 修改源码未生效：执行 `docker compose up -d --build --wait` 重新构建镜像。
- 演示数据需要重置：确认数据可删除后执行 `docker compose down --volumes`，再重新启动。
- Redis 暂停时登录 Session 会受影响，但公开商品会降级查询 MySQL；恢复后等待健康检查通过。
- Windows 脚本执行策略阻止运行时，可使用 `pwsh -ExecutionPolicy Bypass -File .\scripts\windows\verify-all.ps1`。
- Bash 提示缺少命令时，先安装 `curl`、`jq` 和 `openssl`；运行支付安全专项时还需 `base32`、`xxd`、`od`。
- Bash 脚本没有可执行权限时，直接使用 `bash ./scripts/linux/verify-all.sh`，或先执行 `chmod +x scripts/linux/*.sh`。

详见 [前端说明](frontend/README.md) 和 [后端说明](backend/README.md)。

高级三渠道模拟支付、Kafka、幂等、安全和高可用实验的设计与分章计划见
[高级模拟支付架构与实现记录](docs/reference/ADVANCED-PAYMENT.md)。

05A-5～05A-8 的 Kafka、三渠道收银台、HA、监控和故障实验见
[可靠支付交付指南](docs/learning/CHAPTER-05A-5-TO-8.md)。

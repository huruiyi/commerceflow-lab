# Spring Boot backend

## 技术栈

- Java 21
- Spring Boot 3.5.16 / Spring Security 6
- MyBatis-Plus 3.5.18
- Flyway 数据库迁移
- MySQL 8.4（Docker 运行）
- Redis 7.4（Session、登录限流和持久化；缓存增强在后续章节实现）
- H2 MySQL 兼容模式（自动化测试）

## 当前用户与密码

| 邮箱 | 密码 | 角色 | 当前用途 |
| --- | --- | --- | --- |
| `demo@example.com` | `demo-password` | CUSTOMER | 商品和订单 |
| `merchant@example.com` | `demo-password` | MERCHANT | 后续章节的确认与发货 |
| `admin@example.com` | `demo-password` | ADMIN | 用户列表与管理 |

项目还初始化 `ada@example.com`、`grace@example.com`、`alan@example.com` 三条用户资料，用于用户列表展示；它们均处于禁用状态，不能登录。

账号和商品由 Flyway 迁移 `src/main/resources/db/migration/V1__baseline_schema_and_demo_data.sql` 初始化。为了保证公开教学仓库克隆后可直接运行，演示账号的 BCrypt 哈希随代码提交；数据库中没有明文密码，也不会使用 Spring Boot 随机生成的默认 `user` 密码。

### Spring Security `User` 的作用

```text
MySQL app_users
    → AppUserMapper 按邮箱查询
    → org.springframework.security.core.userdetails.User
    → Authentication
    → SecurityContext
    → HTTP Session
```

`AppUser` 是 MyBatis-Plus 映射的数据库对象；Spring Security `User` 是认证过程中构造的 `UserDetails`。两者表示同一身份，不是两个账号。

## 登录请求流程

1. Angular 调用 `GET /api/auth/csrf`，后端签发 `XSRF-TOKEN` Cookie。
2. Angular 调用 `POST /api/auth/login`，并发送 `X-XSRF-TOKEN` Header。
3. `AuthenticationManager` 通过 `UserDetailsService` 和 `AppUserMapper` 查询用户。
4. BCrypt 校验密码成功后更换 Session ID，防止 Session Fixation。
5. 认证结果写入 Redis Session，浏览器收到 `HttpOnly` 的 `SESSION` Cookie。
6. 后续业务请求同时校验会话和 CSRF Token。

勾选“Keep me signed in”时，后端额外签发随机 Remember-Me Cookie，服务端只保存可撤销令牌。浏览器不会保存明文密码；注销会同时删除 Session、Cookie 和服务端令牌。连续五次失败会对该邮箱限流五分钟。

直接只向登录接口发送邮箱和密码会因缺少 CSRF Token 返回 HTTP 403；从页面登录时 Angular 会自动完成取 Token 的步骤。

## API

| 方法 | 路径 | 认证 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/auth/csrf` | 否 | 签发 CSRF Cookie 并返回 Header 名与 Token |
| POST | `/api/auth/login` | 否 | 邮箱密码登录 |
| GET | `/api/auth/me` | 是 | 当前登录用户 |
| POST | `/api/auth/logout` | 是 | 注销并失效 Session |
| GET | `/api/products` | 是 | 商品列表 |
| GET | `/api/cart` | CUSTOMER | 当前购物车 |
| POST | `/api/cart/items` | CUSTOMER | 添加商品；重复商品累加数量 |
| PUT | `/api/cart/items/{id}` | CUSTOMER | 调整数量，范围 1–99 |
| DELETE | `/api/cart/items/{id}` | CUSTOMER | 移除商品 |
| DELETE | `/api/cart` | CUSTOMER | 清空购物车 |
| GET | `/api/addresses` | CUSTOMER | 当前客户的地址列表 |
| POST | `/api/addresses` | CUSTOMER | 新增地址，首个地址自动设为默认 |
| PUT | `/api/addresses/{id}` | CUSTOMER | 编辑自己的地址 |
| PUT | `/api/addresses/{id}/default` | CUSTOMER | 设为唯一默认地址 |
| PUT | `/api/addresses/{id}/select` | CUSTOMER | 选择结算地址 |
| DELETE | `/api/addresses/{id}` | CUSTOMER | 删除地址并维护默认/结算选择 |
| POST | `/api/purchase-orders/checkout` | CUSTOMER | 按服务端实时价格结算购物车并保存快照 |
| GET | `/api/purchase-orders` | CUSTOMER | 当前客户的订单历史 |
| GET | `/api/purchase-orders/{id}` | CUSTOMER | 当前客户的订单详情 |
| POST | `/api/purchase-orders/{id}/payments` | CUSTOMER | 模拟支付；请求携带唯一幂等键 |
| GET | `/api/users` | ADMIN | 演示用户列表 |
| GET | `/actuator/health` | 否 | MySQL、Redis 与应用健康检查 |

## 数据访问和迁移

- `mapper/` 中的接口继承 MyBatis-Plus `BaseMapper`；联表查询使用显式 SQL，便于学习实际执行逻辑。
- `mybatis-plus.configuration.map-underscore-to-camel-case=true` 负责下划线列名到 Java 属性的映射。
- Flyway 是唯一的数据库结构和固定演示数据来源。
- 对旧的 Hibernate 数据卷，`baseline-on-migrate` 会先登记版本 0，再执行可重复兼容的 V1 迁移。
- 发布后不要修改 V1；结构变化应新增 `V2__...sql`、`V3__...sql`。

当前数据模型：

- `app_users`：用户、BCrypt 密码哈希、角色和启用状态。
- `products`：商品名称、价格、描述、图片 URL 和所属商家。
- `carts`、`cart_items`：每个客户的当前购物车和去重商品项。
- `shipping_addresses`：客户可维护并选择用于结算的地址。
- `shop_orders`、`order_items`、`order_shipping_addresses`：正式订单及下单时不可变的商品、价格和地址快照。
- `payment_records`、`order_events`：模拟支付结果、幂等键和关键状态变更。
- `payment_merchant_accounts`：支付宝、微信支付和银联的公开教学商户配置。
- `payment_notifications`、`payment_events`：后续异步通知处理和支付状态审计的数据基础。
- `payment_idempotency_locks`：并发请求按幂等键串行化，避免“同时查不到、同时创建”的竞态。
- `customer_orders`：早期基线 Demo 的兼容表，新流程不再把它当作购物车或正式订单。

支付请求支持 `ALIPAY`、`WECHAT_PAY` 和 `UNION_PAY` 渠道。客户端幂等键、商户账户、请求指纹和数据库唯一约束共同保证安全重试；同一键改变订单、渠道、金额或测试结果会返回 409。模拟支付仅用于学习状态机、事务和幂等处理，不连接真实支付渠道。失败支付不会改变订单状态；成功支付只允许把当前客户自己的订单从 `PENDING_PAYMENT` 变为 `PAID`。

## 通过 Docker 运行

推荐在项目根目录运行完整环境：

```powershell
docker compose up --build -d --wait
docker compose ps
```

后端直接地址为 `http://localhost:18081`，浏览器正常使用 Nginx 入口 `http://localhost:14200`。

如需重建全部教学数据：

```powershell
docker compose down --volumes
docker compose up --build -d --wait
```

`--volumes` 会删除 MySQL 和 Redis 的本地数据，只应在确认教学数据可丢弃时使用。

## 本地运行

需要 Java 21、Maven、MySQL 和 Redis：

```powershell
$env:DATABASE_URL = 'jdbc:mysql://localhost:3306/commerceflow?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
$env:DATABASE_USERNAME = 'commerceflow'
$env:DATABASE_PASSWORD = 'commerceflow-password'
$env:REDIS_HOST = 'localhost'
$env:REDIS_PORT = '6379'
mvn spring-boot:run
```

## 测试

本机有 Maven 时执行：

```powershell
mvn clean test
```

只有 Docker 时执行：

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace `
  maven:3.9.11-eclipse-temurin-21-alpine mvn -q clean test
```

集成测试使用 H2 和真实 Flyway 脚本，覆盖 CSRF、登录与权限、购物车、地址、结算、地址快照、支付失败/成功/幂等、用户列表和注销。

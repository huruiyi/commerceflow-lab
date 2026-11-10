# Spring Boot backend

## 技术栈

- Java 17
- Spring Boot 2.6.15
- Spring Web / Spring Security / Spring Data JPA / Validation / Actuator
- MySQL 8.4（Docker 运行）
- H2 MySQL 兼容模式（自动化测试）

## 认证模型

登录后认证状态保存在服务端 HTTP Session 中，浏览器仅保留 `HttpOnly` 的 `JSESSIONID`。密码使用 BCrypt 保存。所有改变状态的请求均启用 CSRF 保护，Angular 从 `XSRF-TOKEN` Cookie 取值并发送 `X-XSRF-TOKEN` Header。

### 当前用户与密码

```text
用户名（邮箱）：demo@example.com
密码：demo-password
角色：ROLE_USER
状态：启用
```

这个账号由 `DemoDataConfiguration` 在 `app_users` 表为空时创建。邮箱和初始密码分别读取 `DEMO_USER_EMAIL`、`DEMO_USER_PASSWORD`；密码经 `BCryptPasswordEncoder` 编码后才写入 MySQL。

项目另会初始化 `ada@example.com`、`grace@example.com`、`alan@example.com` 三个用于用户列表展示的资料，但这三个账号均为禁用状态，且密码是启动时随机值，不能用于登录。

### Spring Security `User` 的作用

当前并不使用 Spring Boot 默认的 `user` 账号，因此启动日志中不会生成一个另外的随机密码。认证转换关系为：

```text
MySQL app_users 中的 AppUser
    → UserDetailsService 按邮箱查询
    → org.springframework.security.core.userdetails.User
    → Authentication
    → SecurityContext
    → HTTP Session
```

`AppUser` 负责 JPA 持久化及订单关联；Spring Security `User` 只是每次认证时构建的 `UserDetails` 对象。两者表示同一个登录身份，不是两套用户或两个密码。

### 登录请求流程

1. Angular 先调用 `GET /api/auth/csrf`，后端签发 `XSRF-TOKEN` Cookie。
2. Angular 调用 `POST /api/auth/login`，同时发送 `X-XSRF-TOKEN` Header。
3. `AuthenticationManager` 调用 `UserDetailsService`，从 MySQL 按邮箱查找用户。
4. BCrypt 校验成功后更换 Session ID，防止 Session Fixation。
5. 认证结果写入服务端 Session，浏览器收到 `HttpOnly` 的 `JSESSIONID`。
6. 后续 `/api/products`、`/api/orders`、`/api/users` 请求都由 Spring Security 校验会话。

因此直接只向 `/api/auth/login` 发送邮箱和密码会因缺少 CSRF Token 而返回 HTTP 403；从页面登录时 Angular 会自动完成该流程。

### 修改演示账号

Compose 中的当前配置为：

```yaml
DEMO_USER_EMAIL: demo@example.com
DEMO_USER_PASSWORD: demo-password
```

只有在 `app_users` 表为空时，该配置才会创建用户。已初始化数据库后修改环境变量不会重置密码。学习环境如需使用新的初始账号，可先修改 Compose，再执行：

```powershell
docker compose down --volumes
docker compose up --build -d --wait
```

此操作会永久删除该 Demo 数据卷中的用户、商品及订单，然后重新初始化。

## API

| 方法 | 路径 | 认证 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/auth/csrf` | 否 | 签发 CSRF Cookie 并返回 Header 名与 Token |
| POST | `/api/auth/login` | 否 | 邮箱密码登录 |
| GET | `/api/auth/me` | 是 | 当前登录用户 |
| POST | `/api/auth/logout` | 是 | 注销并失效 Session |
| GET | `/api/products` | 是 | 商品列表 |
| GET | `/api/orders` | 是 | 当前用户订单 |
| POST | `/api/orders` | 是 | 为当前用户创建订单，`quantity` 范围 1–99 |
| PUT | `/api/orders/{orderId}/quantity` | 是 | 调整当前用户订单数量，返回新小计 |
| GET | `/api/users` | 是 | 演示用户列表 |
| GET | `/actuator/health` | 否 | 健康检查 |

## 本地运行

需要 Java 17、Maven 和已启动的 MySQL：

```powershell
$env:DATABASE_URL = 'jdbc:mysql://localhost:3306/education_demo?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
$env:DATABASE_USERNAME = 'education'
$env:DATABASE_PASSWORD = 'education-password'
mvn spring-boot:run
```

可选配置：`DEMO_USER_EMAIL` 和 `DEMO_USER_PASSWORD`。默认为 `demo@example.com / demo-password`。

## 测试

```powershell
mvn test
```

集成测试覆盖未登录拒绝、真实 CSRF Cookie/Header、正确与错误密码、商品查询、创建/查询订单、用户列表和注销后会话失效。

## 数据模型

- `app_users`：用户、BCrypt 密码哈希、角色和启用状态。
- `products`：商品名称、价格、描述和图片 URL。
- `customer_orders`：订单所属用户、商品、数量、备注和创建时间；小计由后端按商品单价×数量计算。

启动时会补全教学用户和商品。当前使用 Hibernate `ddl-auto=update` 便于 Demo 学习，生产系统应改为 Flyway/Liquibase 等可审计迁移。

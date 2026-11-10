# Spring Boot backend

## 技术栈

- Java 21
- Spring Boot 3.5.16 / Spring Security 6
- MyBatis-Plus 3.5.18
- Flyway 数据库迁移
- MySQL 8.4（Docker 运行）
- Redis 7.4（本章先提供连接、持久化和健康检查，后续用于 Session、缓存和限流）
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
- `products`：商品名称、价格、描述和图片 URL。
- `customer_orders`：所属用户、商品、数量、备注和创建时间。

购物车和正式订单会在后续章节拆分，当前表用于兼容基线功能。

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
$env:DATABASE_URL = 'jdbc:mysql://localhost:3306/education_demo?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
$env:DATABASE_USERNAME = 'education'
$env:DATABASE_PASSWORD = 'education-password'
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

集成测试使用 H2 和真实 Flyway 脚本，覆盖 CSRF、正确与错误密码、商品查询、创建/调整/查询订单、用户列表和注销。

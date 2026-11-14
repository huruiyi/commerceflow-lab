# CommerceFlow Lab 代码规范

## 1. 格式化基准

- 以 IntelliJ IDEA 的 Java、XML、HTML 格式化结果为主，同时遵守根目录 `.editorconfig`。
- UTF-8、两空格缩进、文件末尾保留一个换行，不保留行尾空格。
- 每个字段、方法和主要 HTML/XML 节点独立换行。不接受为减少行数而压缩的代码。
- 导入使用显式类名；除非框架 API 的常规用法，不新增通配符导入。
- 格式化不得夹带业务语义改动，格式化提交与功能/重构提交分开。

## 2. 实体与字段注释

持久化实体类使用 JavaDoc 说明对应的业务事实。字段注释优先说明：

1. 字段的业务意义，而不是重复字段名。
2. 外部系统编号、商城编号或内部主键的来源。
3. 金额的币种和精度，时间的触发语义，状态字段的状态机范围。
4. 字段是否可空，以及什么条件下才有值。

数据库列注释由 Flyway 迁移管理；JavaDoc 和列注释应表达相同语义。

## 3. MyBatis-Plus 与 SQL

| 场景 | 实现方式 |
| --- | --- |
| 按主键查询、新增、删除 | `BaseMapper` |
| 简单单表条件查询 | `LambdaQueryWrapper` |
| 简单单表条件更新 | `LambdaUpdateWrapper` |
| 动态简单条件 | Wrapper 链式 API |
| 多表 join、聚合、复杂投影 | Mapper XML |
| `FOR UPDATE`、条件 upsert、批量 SQL | Mapper XML |
| 数据库方言函数和性能调优 SQL | Mapper XML |

不在 Controller、Service、Job 或 Dispatcher 中使用 `JdbcTemplate/JdbcClient` 内联业务 SQL。Mapper 接口不使用 `@Select/@Insert/@Update/@Delete` 承载长 SQL。

## 4. 事务与并发

- 重构前后保持原锁定顺序、行锁范围、唯一约束和条件更新语义。
- `FOR UPDATE`、幂等锁、Outbox 和任务租约查询属于复杂 SQL，必须有专用 Mapper 方法和 XML 语句。
- 不为了迎合 Wrapper API 而将原子 SQL 拆成多次读写。
- 数据访问重构后必须执行相关单元/集成测试、快速回归和对应并发专项。

## 5. 审查门禁

- 新增 Java 一行不得同时声明多个字段或多个方法。
- 新增实体和字段必须有业务注释。
- 新增内联 SQL 必须在评审中说明为什么无法使用 BaseMapper/Wrapper/Mapper XML；默认不接受。
- Java、XML、HTML、TypeScript 及 Maven POM 必须通过格式检查后才能提交。

## 6. 自动检查入口

`scripts/windows/audit-code-quality.ps1` 与 `scripts/linux/audit-code-quality.sh` 是只检查入口，
不会自动改写文件。当前纳入门禁的是三个后端服务的主源码 Java、Mapper XML 和 Maven POM：

- 文件应使用空格缩进、无行尾空格并以换行结束；编码仍以根目录 `.editorconfig` 的 UTF-8 为准。
- Java 和 Mapper XML 每行不超过 120 个字符；Java 不允许通配符导入或单行多成员声明。
- 禁止 MyBatis SQL 注解；除 ShedLock 官方适配器配置外，禁止直接引入 Spring JDBC。
- Mapper XML 的 namespace 必须唯一对应 Java Mapper；statement id 必须唯一且存在同名接口方法。

`verify-quick` 与 `verify-all` 会先执行该门禁。默认构建模式随后运行 Maven 测试和商城 ArchUnit
模块边界测试；显式跳过构建只复用已有镜像，不冒充重新执行了这些测试。

Angular 商城门户和支付模拟器 Web 都使用模块本地 Prettier 3.10.1，各模块配置同时供 IntelliJ IDEA 读取：

```powershell
cd web/commerce-portal/angular
npm run format:check
npm run format:write
```

```powershell
cd simulators/payment-platform/web
npm run format:check
npm run format:write
```

`format:check` 只读检查 Vue、TypeScript、HTML、CSS/SCSS 和项目配置；`format:write` 仅在人工明确执行时改写。
两个正式前端 Dockerfile 都会在生产构建前执行只读检查，因此 `verify-quick`/`verify-all` 默认构建模式
可覆盖前端门禁；显式跳过构建时不会重新执行。

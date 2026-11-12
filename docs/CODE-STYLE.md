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

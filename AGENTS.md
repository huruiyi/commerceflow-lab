# CommerceFlow Lab agent guide

本文件适用于整个仓库。M1 旧实现保留在 Git 标签中；当前开发基线使用本文件定义的正式命名。

## 1. 项目标识

最终命名体系如下：

| 类型 | 规范名称 |
| --- | --- |
| 项目标题 | `CommerceFlow Lab` |
| 中文名称 | `CommerceFlow 电商全链路实验室` |
| 仓库名称 | `commerceflow-lab` |
| 组织级 Java 根包 | `vip.fairy` |
| 项目级 Java 根包 / Maven groupId | `vip.fairy.commerceflow` |
| Docker Compose 项目名 | `fairy-commerceflow` |
| Compose 资源逻辑前缀 | `fairy-commerceflow-*` |
| 示例容器名 | `fairy-commerceflow-mysql-1` |

`fairy-infra-*` 只用于 `spring-boot-infrastructure-labs` 这类基础设施集成仓库。CommerceFlow 是电商业务流程实验室，不得使用 `fairy-infra-*`，避免不同仓库的容器、网络、卷和监控标签混在同一命名空间。

## 2. 当前兼容边界

M1 已在 Git 标签 `m1-baseline-2026-11-13` 冻结，该标签仍保留 `lab.commerceflow` 包名、`commerceflow` Compose 项目名和旧目录。当前开发基线已统一为：

- Java/Maven：`vip.fairy.commerceflow.*` / `vip.fairy.commerceflow`
- Compose 项目名：`fairy-commerceflow`
- 源码边界：`services/`、`web/`、`simulators/`、`infrastructure/`
- 应用卷：由 Compose 以 `fairy-commerceflow_*` 形式生成

数据库 Schema、Topic、Queue、Consumer Group、Spring 配置键和 Redis Key 中的 `commerceflow` 是持久协议标识，本次展示命名迁移不改它们。后续新代码不得重新引入 `lab.commerceflow.*`或旧根目录。

## 3. Java 与 Maven 命名

完成专门迁移后，包边界应为：

```text
vip.fairy.commerceflow.commerce
vip.fairy.commerceflow.simulator.payment
vip.fairy.commerceflow.simulator.logistics
```

- Maven `groupId` 统一为 `vip.fairy.commerceflow`。
- artifactId 使用小写 kebab-case：`commerce-service`、`payment-simulator-service`、`logistics-simulator-service`。
- 不创建模糊的 `common`、`utils` 或 `base` 包；只有被两个以上模块稳定复用的契约才能进入明确命名的共享模块。
- 核心商城保持模块化单体，按 `identity/catalog/cart/ordering/inventory/payment/refund/fulfillment/logistics/receipt/review/notification/scheduling` 业务包分界，不回退到全局 `controller/service/mapper/domain` 分层。
- Java 类型用 PascalCase，方法/字段用 camelCase，常量用 UPPER_SNAKE_CASE。
- 数据库表、列、索引和约束继续使用 snake_case；API JSON 字段继续使用 camelCase。

## 4. Docker Compose 命名

最终 Compose 项目名为 `fairy-commerceflow`，服务名必须表达业务归属和角色。Compose 自动生成的容器名应类似：

```text
fairy-commerceflow-commerce-service-1
fairy-commerceflow-commerce-db-1
fairy-commerceflow-payment-simulator-service-1
fairy-commerceflow-rabbitmq-broker-1
```

- 不设置 `container_name`；它会破坏多项目隔离和横向扩容。
- 不把 `-1` 从 Compose 自动生成名称中人为去掉。
- Compose 命名卷实际会使用 `<project>_<volume>` 形式，例如 `fairy-commerceflow_mysql-data`；文档中的 `fairy-commerceflow-*` 是资源族的逻辑前缀。
- 服务名、Profile 名和卷的逻辑名使用小写 kebab-case。
- 破坏性清理必须有明确参数；默认脚本不得执行 `down -v`、`docker volume rm` 或 `docker volume prune`。

若迁移 Compose 项目名，必须先为旧卷建立显式 `name:` 映射或执行经过校验的卷迁移，再启动新项目名。禁止让 Compose 静默创建空卷后把它误判为升级成功。

## 5. 配置与协议名称

- Spring 自定义配置继续使用 `commerceflow.*`，环境变量使用对应的 UPPER_SNAKE_CASE。
- 数据库 Schema 维持 `commerceflow`、`payment_platform`、`logistics_platform`，除非有单独的数据迁移方案。
- Kafka Topic、RabbitMQ Exchange/Queue、RocketMQ Topic/Consumer Group、Redis Key 和幂等键都是持久协议，不随展示名称直接改名。
- API 路径使用复数资源和 kebab-case；事件类型使用稳定的 UPPER_SNAKE_CASE。
- 用户可见中文使用“CommerceFlow 电商全链路实验室”，英文使用“CommerceFlow Lab”，不要再新增其他项目别名。

## 6. 修改与验证要求

1. 修改前检查 `git status`、当前分支和最近提交，不覆盖无关变更。
2. 当前源码、迁移、脚本和实际验证结果高于文档；冲突时同步修正文档。
3. 默认不清空数据卷。只有用户明确允许丢弃学习数据时，才可在列出精确 Compose 项目和卷后执行带 `--volumes`的破坏性重建；不得使用全局 volume prune。
4. 普通改动至少运行相关测试；跨模块或命名改动运行 Windows/Bash 脚本契约审计和快速回归。
5. 命名迁移必须同时更新源码目录、package/import、Maven、Spring 扫描、Docker/Compose、脚本、文档和测试，不能提交半迁移状态。
6. M1 后“整个开发生命周期的可复测验证体系”文档、UI 收尾和生产级告警仍按项目状态文档中的顺序推进，不因命名治理提前新增业务功能。

## 7. 代码和数据访问规范

- 以 IntelliJ IDEA 默认 Java/XML/HTML 格式化结果和根目录 `.editorconfig` 为基准；禁止把多个字段、方法、XML 节点或 HTML 元素压在一行。
- 实体类与持久化字段必须有简洁的 JavaDoc，说明业务含义、单位、状态范围或是否为外部系统标识；不写“ID 字段”这类无信息注释。
- 简单单表 CRUD 优先使用 MyBatis-Plus `BaseMapper`、`LambdaQueryWrapper` 和 `LambdaUpdateWrapper`。
- 多表连接、聚合、批量操作、行锁、条件 upsert 和性能敏感 SQL 放入 `src/main/resources/mapper/*.xml`，不得在 Java 注解或 Service 中内联长 SQL。
- Mapper XML 的 `namespace`、方法名和参数必须与 Java Mapper 契约一致；简单 CRUD 不得在 XML 中重复实现。
- 禁止为了清除 SQL 而改变锁顺序、幂等约束或事务边界；数据访问重构必须通过原有并发和恢复验收。

详细规则见 `docs/CODE-STYLE.md`。架构层待办只记录在 `docs/architecture/`，不与业务 `docs/TODO.md` 混用。

详细理由、现状差异和未来迁移步骤见 `docs/NAMING-CONVENTIONS.md`。

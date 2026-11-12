# CommerceFlow Lab 命名规范

## 1. 最终命名

| 类型 | 最终名称 | 说明 |
| --- | --- | --- |
| 项目标题 | `CommerceFlow Lab` | 简短、稳定，不把具体中间件写进标题 |
| 中文名称 | `CommerceFlow 电商全链路实验室` | 覆盖商城、支付、退款、物流、任务和可靠性实验 |
| 仓库名称 | `commerceflow-lab` | 与现有 GitHub 仓库一致，不需要改仓库 URL |
| 组织级 Java 根包 | `vip.fairy` | 与个人/组织命名空间一致 |
| 项目级 Java 根包 | `vip.fairy.commerceflow` | 防止不同实验仓库的类落入同一根包 |
| Maven groupId | `vip.fairy.commerceflow` | 与项目级 Java 根包一致 |
| Compose 项目名 | `fairy-commerceflow` | 与其他 Fairy 实验环境隔离 |
| Compose 资源族 | `fairy-commerceflow-*` | 容器、网络、监控标签的逻辑前缀 |
| 示例容器 | `fairy-commerceflow-mysql-1` | Compose 自动生成，不设置 `container_name` |

`spring-boot-infrastructure-labs` 使用 `fairy-infra-*` 是合理的，因为它是基础设施实验集合；本仓库属于电商业务流程实验，因此使用 `fairy-commerceflow-*` 更准确。两者共享 `vip.fairy` 组织根，但不共享项目级包或 Compose 项目名。

## 2. 模块命名

| 目录/模块 | Maven artifactId | 最终 Java 包 |
| --- | --- | --- |
| `services/commerce-service/` | `commerce-service` | `vip.fairy.commerceflow.commerce` |
| `simulators/payment-platform/service/` | `payment-simulator-service` | `vip.fairy.commerceflow.simulator.payment` |
| `simulators/logistics-platform/service/` | `logistics-simulator-service` | `vip.fairy.commerceflow.simulator.logistics` |
| `web/commerce-portal/angular/` | `commerce-portal-angular` | 不适用 |
| `web/commerce-portal/vue/` | `commerce-portal-vue` | 不适用 |
| `simulators/payment-platform/web/` | `payment-simulator-web` | 不适用 |

`backend/frontend/mock-*` 只能表达技术形态，无法表达核心商城、外部平台模拟器和双前端实现。因此目录按 `services/web/simulators/infrastructure` 责任分组。

## 3. 当前状态与目标差异

M1 标签 `m1-baseline-2026-11-13` 的实际状态是：

| 范围 | 当前值 | 最终值 |
| --- | --- | --- |
| Java 根包 | `lab.commerceflow` | `vip.fairy.commerceflow` |
| Maven groupId | `lab.commerceflow` | `vip.fairy.commerceflow` |
| Compose 项目名 | `commerceflow` | `fairy-commerceflow` |
| Compose 命名卷 | `commerceflow_*` | 最终资源族为 `fairy-commerceflow_*` |

这些差异不应通过零散提交逐个消除。Java 包和 Compose 项目名分别影响约 620 处源码引用以及现有持久卷识别，必须单独验收。

## 4. 推荐迁移顺序

### 4.1 第一阶段：只统一 Java/Maven

1. 从干净工作区和 M1 标签创建迁移阶段。
2. 移动根目录到 `services/web/simulators/infrastructure`。
3. 一次性移动三个服务的 Java 源码目录并替换 package/import。
4. 将三个 Maven `groupId` 改为 `vip.fairy.commerceflow`。
5. 检查 Spring Boot 启动类、MyBatis Mapper/XML 扫描、配置属性扫描和测试包。
6. 构建五个应用镜像并运行快速回归。

### 4.2 第二阶段：迁移 Compose 展示命名

1. 本项目为学习环境，用户已明确旧 Docker 数据无需保留。
2. 使用旧项目名 `commerceflow` 显式执行 `down --volumes --remove-orphans`，只删除已校验的本项目资源。
3. Compose 项目名改为 `fairy-commerceflow`，服务名改为业务归属 + 角色，不设置 `container_name`。
4. 从空卷启动三个 MySQL 和独立 Redis，执行 Flyway 和完整业务回归。
5. M1 Git 标签保留旧实现证据；新空卷验收不改写 M1 历史。

### 4.3 第三阶段：评估持久协议标识

Kafka Topic、RabbitMQ Queue/Exchange、RocketMQ Topic/Consumer Group、Redis Key 和数据库 Schema 不需要为了品牌统一而改名。只有出现实际冲突或跨仓库共享需求时才设计双写、迁移和回滚；否则保留现有 `commerceflow` 协议名称更安全。

## 5. 通用格式

| 对象 | 格式 | 示例 |
| --- | --- | --- |
| Git 仓库、目录、artifactId、Compose 服务/Profile | kebab-case | `mock-payment-service` |
| Java package | 全小写点分层 | `vip.fairy.commerceflow.payment.security` |
| Java 类型 | PascalCase | `ScheduledTaskExecutor` |
| Java 方法/字段、JSON 字段 | camelCase | `primaryTriggerSnapshot` |
| Java 常量、事件类型、环境变量 | UPPER_SNAKE_CASE | `ORDER_AUTO_COMPLETED` |
| 数据库表/列/索引 | snake_case | `scheduled_task_events` |
| Spring 配置 | 小写 kebab-case 层级 | `commerceflow.scheduling.rabbitmq.enabled` |
| REST 资源路径 | 小写复数 + kebab-case | `/api/purchase-orders` |

## 6. 禁止事项

- 不在同一源码树长期混用 `lab.commerceflow` 和 `vip.fairy.commerceflow`。
- 不再创建模糊的根目录 `backend`、`frontend` 或无边界的 `mock-*`。
- 不把本项目命名为 `fairy-infra-commerceflow`；`infra` 不是它的领域边界。
- 不使用固定 `container_name` 伪造没有 `-1` 的容器名。
- 不因 Compose 项目名变化而默认接受新建空卷。
- 不为“看起来统一”而直接重命名持久消息协议、数据库或 Redis Key。
- 不把尚未执行的命名迁移写成已经完成。

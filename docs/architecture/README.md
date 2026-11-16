# 架构层待办

本目录只记录跨服务、数据存储和部署边界的长期演进。它不是当前业务功能进度表，不得将架构设想写成已实现功能。

| 文档 | 范围 |
| --- | --- |
| [MICROSERVICE-EVOLUTION-PLAN.md](MICROSERVICE-EVOLUTION-PLAN.md) | 模块化单体向微服务的候选边界、前置条件和门禁 |
| [M3-PRE-SPLIT-AUDIT.md](M3-PRE-SPLIT-AUDIT.md) | M3.1 数据所有权、跨模块依赖、契约、Inbox 与追踪基线审计 |
| [NOTIFICATION-SERVICE-CUTOVER-PLAN.md](NOTIFICATION-SERVICE-CUTOVER-PLAN.md) | notification 单一所有权、完整 API、一次性数据交接和单体运行路径删除门禁 |
| [CATALOG-SERVICE-EVOLUTION-PLAN.md](CATALOG-SERVICE-EVOLUTION-PLAN.md) | catalog 应用端口、数据所有权、V1 契约和单一所有权切换门禁 |
| [INVENTORY-SERVICE-EVOLUTION-PLAN.md](INVENTORY-SERVICE-EVOLUTION-PLAN.md) | inventory 准入、命令边界与单一所有权演进门禁 |
| [INVENTORY-SERVICE-CUTOVER-RUNBOOK.md](INVENTORY-SERVICE-CUTOVER-RUNBOOK.md) | inventory 创建准入、五表迁移、停写追平、Trace、故障与回退运行手册 |
| [DATABASE-EVOLUTION-PLAN.md](DATABASE-EVOLUTION-PLAN.md) | MySQL 现状、PostgreSQL 迁移评估和数据库演进门禁 |

业务功能、UI 和当前交付顺序仍以 `docs/TODO.md` 为准。

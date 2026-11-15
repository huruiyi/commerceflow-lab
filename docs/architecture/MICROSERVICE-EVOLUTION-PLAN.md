# 微服务演进计划（架构待办）

## 定位

当前 `commerce-service` 保持模块化单体。支付与物流模拟器是外部平台模拟，不是从商城单体中拆出的内部微服务。

## 开始拆分的前置条件

- 模块已按业务能力分包，并有自动化依赖边界检查。
- 候选模块有独立数据所有权、部署周期、故障边界或扩容需求。
- 跨模块调用已通过应用接口或领域事件，不直接共享 Mapper/表。
- 已建立契约测试、分布式追踪、幂等、Outbox/Inbox 和失败补偿基线。

## 候选顺序

1. `notification-service`：副作用边界清晰，允许最终一致。
2. `catalog-service`：读多写少，可独立索引与缓存。
3. `inventory-service`：拆分前必须固化预占、转正、释放和幂等消费契约。
4. `order-service`：依赖订单状态机和 Saga/补偿模型稳定。
5. `payment-orchestration-service`：只负责商城支付编排、回调投影与退款，与外部 `payment-simulator-service` 区分。
6. `fulfillment-service`：商家履约和物流投影稳定后再拆分。

## 每次拆分门禁

- 不共享数据库 Schema，不跨库 join。
- API/事件版本可向后兼容，具有消费者契约测试。
- 并发、重放、超时、迟到消息和部分失败有实测证据。
- 具有可恢复的数据迁移、备份和失败停止门禁；不得用长期双实现代替清晰所有权。
- 架构评审确认拆分收益大于分布式事务和运维成本。

M3.1 前置审计和 M3.2 notification 单一所有权切换已完成实现，实测结论见
[M3-PRE-SPLIT-AUDIT.md](M3-PRE-SPLIT-AUDIT.md)。notification-service 默认启动并独占消费、存储和通知业务
API；commerce 原公开路径只做身份薄代理，旧通知读写、数据源、投影和回填运行代码已删除。

切换边界与证据见 [单一所有权切换计划](NOTIFICATION-SERVICE-CUTOVER-PLAN.md)。下一步先独立提交推送该批，
再单独评审 `catalog-service`；每次仍只拆一个边界。PostgreSQL 不属于拆分步骤，必须等 notification、
catalog、inventory、order、payment-orchestration、fulfillment 以及后续新增待拆服务全部完成单一所有权切换
并验收后另立专项。

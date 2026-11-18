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

notification、catalog、inventory 与 order 已完成单一所有权切换；inventory 的命令与商家管理边界均只访问独立
服务/独立 MySQL，commerce 运行账号无五张库存源表权限。完整门禁与复跑入口见
[inventory-service 准入与单一所有权演进计划](INVENTORY-SERVICE-EVOLUTION-PLAN.md)和
[inventory-service 切换运行手册](INVENTORY-SERVICE-CUTOVER-RUNBOOK.md)。M3.5 已通过
[order-service 计划](ORDER-SERVICE-EVOLUTION-PLAN.md)已完成独立 MySQL、远程 Order V1、CUTOVER 与源权限撤销。
M3.6 已完成 payment-orchestration 的 CREATE、影子迁移、远程协议、一次性 CUTOVER 和切流后观察：独立服务与
payment-db 是唯一资金所有者，commerce 本地支付/退款实现和旧回调已删除，14 张源表权限为 0；
[Payment V1](PAYMENT-V1-CONTRACT.md)保持冻结。Prometheus 已采集独立服务，并覆盖 Outbox、Process Manager、
孤儿、退款和切流模式漂移告警。M3.7 已开始 [fulfillment-service CREATE 准入治理](FULFILLMENT-SERVICE-EVOLUTION-PLAN.md)。
下一步在 commerce 内补齐持久履约流程与下游幂等，再重新评审创建资格。
不引入 Seata、DTM 或其他全局事务协调器，每次仍只拆一个边界。
PostgreSQL 不属于拆分步骤，必须等 notification、
catalog、inventory、order、payment-orchestration、fulfillment 以及后续新增待拆服务全部完成单一所有权切换
并验收后另立专项。

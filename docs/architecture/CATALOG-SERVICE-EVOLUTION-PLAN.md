# catalog-service 单一所有权演进计划

> 实施状态（2026-11-15）：M3.3 第一批应用边界治理、双平台快速回归和独立提交推送已完成；尚未创建 catalog-service、独立数据库或数据迁移。
> 当前继续由 commerce-service 和 MySQL 承载商品目录，PostgreSQL 仍处于全服务拆分完成后的冻结阶段。

## 1. 目标边界

catalog-service 最终只负责商品主数据、标准售价、展示图片、多语言资料、上下架状态和目录缓存。切换完成后：

- catalog-service 与独立 MySQL Schema 是商品目录的唯一运行实现；
- commerce 不访问 catalog 表，也不保留本地 Mapper、双读或数据库回退；
- cart、ordering 等调用方只消费稳定的商品快照或版本化 API，不远程调用 Mapper 语义；
- inventory 独占可售、预占、已售数量和库存流水；
- review 独占评价明细，目录需要的评分摘要通过应用查询契约或事件投影获得。

## 2. 当前硬阻塞

1. `products` 同时保存商品资料和 `stock_available/stock_reserved/stock_sold`，物理表仍是 catalog/inventory
   混合聚合。
2. `CartMapper.xml`、`CustomerOrderMapper.xml` 和 `ShopOrderMapper.xml` 仍通过 join 读取 `products`；独立后不能
   跨库 join。
3. `products.merchant_id` 外键仍指向 identity 的 `app_users`；服务化后只能保存稳定商家标识或授权快照。
4. 商品列表仍同步组合 review 明细与聚合；已消除 Mapper 直连，但远程形态和缓存失效契约尚未冻结。
5. `customer_orders` 是早期轻量订单模型，需要在 catalog 切换前明确保留或退役策略。

## 3. M3.3 第一批：应用边界治理

本批只在模块化单体内建立未来可替换的端口：

- `CatalogProductLookup` 向 cart 提供最小商品快照，cart 不再注入 `ProductMapper`；
- `CatalogCacheInvalidator` 隔离 review 对具体目录 Service 的依赖；
- `ProductReviewCatalogQuery` 由 review 基础设施实现，catalog 不再注入 `ReviewMapper`；
- catalog 自有评价响应 DTO，公开 JSON 保持兼容，不向外暴露 review 持久化对象；
- 架构基线将 `cart -> catalog` 和 `catalog -> review` 的跨模块 infrastructure 引用上限收紧为 0。

这一步不创建 HTTP 调用，不改变事务边界，不迁移表，也不制造 LOCAL/COMPARE 双实现。

## 4. 后续工作包

### 4.1 数据所有权冻结

- 设计 catalog 商品主数据表和 inventory 数量表的最终边界；
- 为现有 `products` 库存列制定可停止、可核对的一次性迁移方案；
- 去除 catalog 对 identity 外键的运行依赖，保留稳定 merchantId 与必要授权校验；
- 将 cart/order 的商品名称、价格和图片固化为创建时快照，消除跨库 join；
- 明确早期 `customer_orders` 的退役或迁移路径。

### 4.2 V1 契约与失败语义

- 冻结列表、详情、批量快照和商家维护 V1 API；
- 定义商品创建、资料更新、价格变化、上下架和翻译更新事件；
- 固定 eventId、版本、occurredAt、correlationId、causationId 和业务版本；
- 明确超时、重试、缓存失效、重复和乱序语义，并增加消费者契约 fixture。

### 4.3 单一所有权切换

- 创建独立 catalog-service 和独立 MySQL Schema；
- 执行一次性数据迁移、数量核对和调用方切换；
- 切换完成后删除 commerce 的 catalog Mapper、表访问和回退路径；
- 验证中英文目录、购物车、下单快照、商家维护、缓存、停机错误、恢复和跨进程 Trace；
- 不建立长期双读、双写或 LOCAL/COMPARE 模式。

## 5. 本阶段明确不做

- 不启动 catalog-service 或 catalog-db；
- 不移动 `products`、`product_translations` 或库存数据；
- 不同时拆 inventory-service；
- 不引入 PostgreSQL、方言分支、注册中心或 Kubernetes；
- 不改变商品 API JSON、订单状态机或支付/物流流程。

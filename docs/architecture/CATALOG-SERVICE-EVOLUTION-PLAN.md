# catalog-service 单一所有权演进计划

> 实施状态（2026-11-15）：M3.3 前两批已完成应用边界治理、catalog/inventory 物理表责任分离、商品快照
> 固化和双平台回归；尚未创建 catalog-service 或独立数据库。
> 当前继续由 commerce-service 和 MySQL 承载商品目录，PostgreSQL 仍处于全服务拆分完成后的冻结阶段。

## 1. 目标边界

catalog-service 最终只负责商品主数据、标准售价、展示图片、多语言资料、上下架状态和目录缓存。切换完成后：

- catalog-service 与独立 MySQL Schema 是商品目录的唯一运行实现；
- commerce 不访问 catalog 表，也不保留本地 Mapper、双读或数据库回退；
- cart、ordering 等调用方只消费稳定的商品快照或版本化 API，不远程调用 Mapper 语义；
- inventory 独占可售、预占、已售数量和库存流水；
- review 独占评价明细，目录需要的评分摘要通过应用查询契约或事件投影获得。

## 2. 当前状态与剩余阻塞

1. V28 已把数量和必要商品快照迁入 inventory 自有 `product_inventory`，`products` 不再保存库存列。
2. cart/customer order 已持久化名称、图片和单价快照，结算锁定、订单读取和商家库存查询不再 join
   `products`；现有跨库 join 阻塞已解除。
3. `products.merchant_id` 仅保留稳定标识，已删除指向 identity 的数据库外键；独立服务仍需冻结授权契约。
4. 商品列表仍同步组合 review 摘要；虽然 Mapper 直连已消除，远程查询/事件投影和缓存失效契约尚未冻结。
5. `customer_orders` 的快照完整性已满足切换要求，但该早期轻量订单模型仍需明确长期保留或退役策略。

## 3. M3.3 第一批：应用边界治理

本批只在模块化单体内建立未来可替换的端口：

- `CatalogProductLookup` 向 cart 提供最小商品快照，cart 不再注入 `ProductMapper`；
- `CatalogCacheInvalidator` 隔离 review 对具体目录 Service 的依赖；
- `ProductReviewCatalogQuery` 由 review 基础设施实现，catalog 不再注入 `ReviewMapper`；
- catalog 自有评价响应 DTO，公开 JSON 保持兼容，不向外暴露 review 持久化对象；
- 架构基线将 `cart -> catalog` 和 `catalog -> review` 的跨模块 infrastructure 引用上限收紧为 0。

这一步不创建 HTTP 调用，不改变事务边界，不迁移表，也不制造 LOCAL/COMPARE 双实现。

## 4. 工作包

### 4.1 数据所有权冻结（已完成）

- catalog 的 `products`/`product_translations` 只持有商品资料；inventory 的 `product_inventory` 持有数量和
  履行业务所需快照。
- V28 一次性复制库存并改接库存外键，确认记录数及数量总和守恒后删除 `products` 库存列。
- catalog 只保存稳定 merchantId，不再以数据库外键绑定 identity；运行授权仍由现有应用层校验。
- cart/customer order 保存创建时商品快照，ordering 已使用订单项快照，运行查询不再跨边界 join 商品表。
- `customer_orders` 暂时保留；其退役决策不再阻塞 catalog 数据切换，但必须在独立服务切换前记录。

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
- 不把 `products`、`product_translations` 或库存数据迁入独立服务数据库；
- 不同时拆 inventory-service；
- 不引入 PostgreSQL、方言分支、注册中心或 Kubernetes；
- 不改变商品 API JSON、订单状态机或支付/物流流程。

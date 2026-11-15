# catalog-service 单一所有权演进计划

> 实施状态（2026-11-16）：M3.3 第四批已完成独立 catalog-service/catalog-db 的一次性迁移和单一所有权
> 切换。commerce 只保留签名 HTTP 薄代理与 review 组合；没有 LOCAL/COMPARE、双写或数据库回退。
> 当前仍使用 MySQL，PostgreSQL 继续冻结到 M3 清单全部服务独立验收之后。

## 1. 目标边界

catalog-service 最终只负责商品主数据、标准售价、展示图片、多语言资料、上下架状态和目录缓存。切换完成后：

- catalog-service 与独立 MySQL Schema 是商品目录的唯一运行实现；
- commerce 不访问 catalog 表，也不保留本地 Mapper、双读或数据库回退；
- cart、ordering 等调用方只消费稳定的商品快照或版本化 API，不远程调用 Mapper 语义；
- inventory 独占可售、预占、已售数量和库存流水；
- review 独占评价明细，目录需要的评分摘要通过应用查询契约或事件投影获得。

## 2. 当前状态

1. V28 已把数量和必要商品快照迁入 inventory 自有 `product_inventory`，`products` 不再保存库存列。
2. cart/customer order 已持久化名称、图片和单价快照，结算锁定、订单读取和商家库存查询不再 join
   `products`；现有跨库 join 阻塞已解除。
3. `products.merchant_id` 仅保留稳定标识；commerce 完成 Session/RBAC 后把 merchantId 作为签名内部请求头传递。
4. 公开商品列表由 commerce 调 catalog V1 后组合本地 review 摘要；catalog-service 不访问 review 数据库。
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

### 4.2 V1 契约与失败语义（已完成）

- 已冻结列表、详情、批量快照和商家资料/价格/状态/翻译维护 V1 API；
- 已定义商品创建、资料更新、价格变化、状态变化和翻译更新事件；创建入口等待 inventory 初始化命令；
- 已固定 eventId、schemaVersion、occurredAt、correlationId、causationId 和 businessVersion；
- 已明确 expectedVersion 冲突、缓存失效、eventId 去重、旧事件忽略和版本缺口重试语义，并增加 fixture。

### 4.3 单一所有权切换（已完成）

- 已创建独立 catalog-service 和独立 MySQL Schema；
- 一次性迁移以 V29、记录数和内容指纹为门禁，并以 COMPLETE 标记禁止陈旧源数据再次覆盖；
- commerce 已删除 catalog Mapper、XML、Outbox 写入和过渡投影，全部调用改为签名 HTTP；
- 已验证中英文目录、停机 503、核心健康隔离、恢复和 commerce→catalog 跨进程 Trace；
- 不建立长期双读、双写或 LOCAL/COMPARE 模式。

## 5. 当前后续边界

- 不同时拆 inventory-service；
- 不引入 PostgreSQL、方言分支、注册中心或 Kubernetes；
- 不改变商品 API JSON、订单状态机或支付/物流流程。

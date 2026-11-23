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

## 6. 商品全文搜索与索引重建验收

目录库 V4 增加搜索文档和代次状态。目录服务继续持有商品资料和价格，库存余额由库存服务维护。
MySQL InnoDB ngram 索引提供中文相关性。空白分词后的必含词逐一核对，价格筛选包含两端，
价格和评分可分别排序，同分时按商品编号确定分页顺序。商城传入当前允许公开的店铺范围，
目录同时核验商品当前状态，详情和结算继续使用权威价格。 [MySQL ngram 说明](https://dev.mysql.com/doc/refman/8.4/en/fulltext-search-ngram.html)

增量处理独立记录 Outbox 的 `search_indexed_at`，库存投递继续使用原来的状态和重试字段。
消费者根据变化的商品编号重读当前目录和译文，在同一事务刷新文档并确认对应事件。
确认晚到的旧事件时读取当前版本。管理员通过 `GET /api/admin/catalog-search` 查看代次和待消费数，
通过 `POST /api/admin/catalog-search/rebuild` 请求重建。

重建将目录商品和译文复制到新代次，在切换前处理当时已提交的待消费事件。代次切换和确认同一事务提交，
失败时保留原查询代次。随后提交的事件由正常消费继续处理。重建任务与库存投递可分别调度。

隔离和日常均用 [PowerShell 7 验收脚本](../../scripts/commerce/verify-merchant-operations.ps1) 的 `Search` 章节。
隔离目录从 V3 升到 V4，覆盖中文多词、店铺与价格边界、稳定分页、顾客越权、下架与店铺停用。
暂停消费期间搜索返回 80.00 元，商品详情、报价和隔离订单 722 的成交单价均为当前 95.00 元。
重放旧事件后下架商品保持不可见。派生文档清空后，重建完成代次 2，重建请求后提交的改名也可检索。
Chromium 核对顾客页面的关键词、店铺、价格筛选及权威商品详情。

2026-11-20 日常发布使用隔离通过的同一批目录、商城及门户镜像。先将日常目录库从 V3 升级至 V4，
确认初建完成且待消费数为零，再切换商城与门户。日常商品 15 通过检索、边界筛选、改价、结算和下架复验，
订单 719 的成交单价为 95.00 元。全量重建完成代次 2，待消费数归零。三个发布容器健康。
Chromium 在日常门户复核搜索、筛选和商品详情，Prometheus 九个抓取目标均正常。
对比升级前和验收后的九个数据库容器、原有商品及译文、库存余额、订单金额及退款。五笔历史归档保持关闭，
没有重放旧预占或新增对应库存流水。

本机验收记录位于 `%LOCALAPPDATA%/CommerceFlow/releases/merchant-20261120`，其中
`search-isolated.json`、`search-browser.json`、`search-daily.json` 和 `search-daily-browser.json`
保存脚本及浏览器结果。

## 7. Elasticsearch 商品搜索

目录 MySQL 继续持有商品、译文和价格。V5 为 Elasticsearch 单独记录 Outbox 消费时间和索引代次，
库存投递和 MySQL 搜索投影的确认字段各自独立。处理事件时按商品编号读取当前版本。
索引代次重建并刷新后再启用。中文和英文分别索引，缺少译文时用商品原文，搜索词按字面匹配。
店铺范围由商城交给目录，按价格、评分或相关性排序时，同分按商品编号稳定排列。

学习环境通过 [搜索 Compose 覆盖](../../infrastructure/compose/compose.catalog-elasticsearch.yaml) 启用
Elasticsearch 9.5.6 单节点，节点仅在 Compose 网络提供服务，索引用独立数据卷。
目录参数为 `COMMERCEFLOW_CATALOG_SEARCH_ELASTICSEARCH_ENABLED` 和
`COMMERCEFLOW_CATALOG_SEARCH_ELASTICSEARCH_URL`。索引缺失、同步落后、查询失败、结果版本不符
或请求超出 Elasticsearch 的 10,000 条结果窗口时，查询回退到 MySQL 搜索投影。
[Elasticsearch 分页说明](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/paginate-search-results)

管理员沿用 `POST /api/admin/catalog-search/rebuild`，先重建 MySQL 投影，随后同步新一代 Elasticsearch
索引。 `GET /api/admin/catalog-search` 展示 MySQL 投影状态。目录 Prometheus 指标
`commerceflow_catalog_search_elasticsearch_reads_total` 和
`commerceflow_catalog_search_elasticsearch_fallbacks_total` 用于核对查询命中和回退。

2026-11-23 隔离空卷验证了首轮建索引、改价、语言切换、下架、评分排序、节点停机回退和重启追赶。
节点恢复后管理员触发了新代次重建。日常仍使用 MySQL 搜索投影。
[门户分页与商品搜索隔离验收](../IMPLEMENTED.md#240-2026-11-23-门户分页与商品搜索隔离验收)
旧卷从 V4 升级至 V5 后，停节点、关闭开关和换回原目录镜像均返回相同的两件商品。
[目录旧卷升级与搜索回退](../IMPLEMENTED.md#241-2026-11-23-目录旧卷升级与搜索回退)

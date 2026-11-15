# catalog V1 API 与商品事件契约

## 1. 适用范围

本契约是 commerce 模块化单体切换到独立 catalog-service 时保持不变的边界。当前实现仍运行于
commerce-service 与 MySQL；它不是第二套实现，也不代表 catalog-service 或 catalog-db 已启动。

## 2. 查询 API

基础路径为 `/api/catalog/v1/products`：

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| `GET` | `/api/catalog/v1/products` | 返回 ACTIVE 商品列表；支持 `lang` 或 `Accept-Language`。 |
| `GET` | `/api/catalog/v1/products/{productId}` | 返回单个 ACTIVE 商品；不存在或已下架统一返回 404。 |
| `POST` | `/api/catalog/v1/products/snapshots` | 按请求顺序批量返回最多 100 个去重快照；任一商品不存在或未激活时整体返回 404。 |

响应固定带 `schemaVersion=1`、稳定 productId/merchantId、商品资料、`lifecycleStatus`、
`businessVersion`；批量快照只返回购物车/下单所需的名称、标准单价、缩略图及版本，不携带评价明细。

## 3. 商家维护 API

基础路径为 `/api/merchant/catalog/v1/products`，要求 `MERCHANT` 身份且只允许操作本人商品：

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| `GET` | `/` | 查询本人 ACTIVE/INACTIVE 商品及当前业务版本。 |
| `PUT` | `/{id}/profile` | 更新名称、简介、说明和图片。 |
| `PUT` | `/{id}/price` | 更新大于 0 的标准单价。 |
| `PUT` | `/{id}/status` | 在 ACTIVE/INACTIVE 间切换。 |
| `PUT` | `/{id}/translations/{locale}` | 新增或更新本地化文案。 |

每个写请求必须携带 `expectedVersion`。成功后版本严格加一；并发旧版本返回
`409/CATALOG_VERSION_CONFLICT`，调用方必须重新读取后决定是否重试。非本人商品与不存在商品统一返回 404，
避免泄露归属。商品创建入口本批不开放：必须先冻结 inventory 初始化命令，不能产生可见但没有库存聚合的商品。

## 4. 商品事件 V1

每次成功维护在同一本地事务写入 `catalog_event_outbox`。事件类型为：

- `PRODUCT_CREATED`：契约已冻结，留给创建与 inventory 初始化流程；
- `PRODUCT_PROFILE_UPDATED`；
- `PRODUCT_PRICE_CHANGED`；
- `PRODUCT_STATUS_CHANGED`；
- `PRODUCT_TRANSLATION_UPDATED`。

信封固定包含 `schemaVersion`、UUID `eventId`、`eventType`、`occurredAt`、productId、merchantId、
`businessVersion`、商品状态/快照、可选 locale、correlationId 和 causationId。fixture 位于
`services/commerce-service/src/test/resources/contracts/catalog-product-profile-updated-v1.json`。

## 5. 重复、乱序和失败语义

消费者必须先按 eventId 建立 Inbox 去重，再按每个 productId 的最后业务版本判断：

- `event.businessVersion == lastVersion + 1`：应用；
- `event.businessVersion <= lastVersion`：重复或旧事件，记录后忽略；
- `event.businessVersion > lastVersion + 1`：存在版本缺口，不得越过应用，应重试或触发按 productId 重建快照。

Outbox 状态固定为 `NEW → RETRY → PUBLISHED`，超过有限次数进入 `DEAD`；下一批实现独立服务传输时必须
明确连接/请求超时、指数或固定退避、人工重放与指标告警。查询可在超时后安全重试；写请求超时后必须先按
productId 重读 businessVersion，不能盲目重放。服务不可用时返回稳定 503，commerce 不回退本地 Mapper，
不建立 LOCAL/COMPARE、长期双读或双写。

## 6. 模块化单体过渡行为

profile/price 更新在提交前通过 shared 过渡事件同步 inventory 的展示快照，失败则整个事务回滚；catalog
事件 Outbox 与商品版本也不会单独提交。独立服务切换时，该同步事件由 catalog V1 事件消费者替代并删除，
inventory 的数量所有权不回流 catalog。

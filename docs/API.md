# API 学习索引

本项目使用浏览器 Session 认证。除公开商品和支付回调外，先请求 `GET /api/auth/csrf`，登录后在写请求中携带返回的 CSRF Header。前端已经自动完成该流程。

## 商城 API

| 领域 | 主要接口 | 权限 |
| --- | --- | --- |
| 认证 | `GET /api/auth/csrf`、`POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/logout` | 公开/已登录 |
| 商品 | `GET /api/products`、`GET /api/products/search`、`GET /api/products/{productId}`、`GET /api/products/{productId}/reviews`、`POST /api/products/lookup`、`POST /api/products/availability` | 公开，POST 需 CSRF |
| 购物车 | `GET /api/cart`、`POST /api/cart/items`、`PUT/DELETE /api/cart/items/{id}`、`DELETE /api/cart` | CUSTOMER |
| 地址 | `GET/POST /api/addresses`、`PUT/DELETE /api/addresses/{id}`、`PUT /api/addresses/{id}/default|select` | CUSTOMER |
| 订单 | `POST /api/purchase-orders/checkout/quote`、`POST /api/purchase-orders/checkout`、`GET /api/purchase-orders/search`、`GET /api/purchase-orders`、`GET /api/purchase-orders/{id}`、`POST /api/purchase-orders/{id}/cancel` | CUSTOMER |
| 顾客优惠券 | `GET /api/coupons/campaigns`、`POST /api/coupons/campaigns/{id}/claim`、`GET /api/coupons` | CUSTOMER |
| 商家优惠活动 | `GET/POST /api/merchant/coupon-campaigns`、`POST /api/merchant/coupon-campaigns/{id}/disable` | MERCHANT，仅本人活动 |
| 物流 | `GET /api/purchase-orders/{id}/logistics` | CUSTOMER，仅本人订单 |
| 退货运单 | `POST /api/purchase-orders/after-sales/{id}/return`、`GET /api/purchase-orders/after-sales/{id}/return-tracking` | CUSTOMER，仅本人售后单 |
| 商家退货轨迹及验收 | `GET /api/merchant/after-sales/{id}/return-tracking`、`POST /api/merchant/after-sales/{id}/inspection` | MERCHANT，仅本人售后单 |
| 支付 | `POST /api/purchase-orders/{id}/payments` | CUSTOMER |
| 顾客发票 | `GET/POST /api/purchase-orders/{id}/invoices`、`GET /api/purchase-orders/{id}/invoices/eligibility` | CUSTOMER，仅本人订单，需启用发票能力 |
| 退款 | `POST/GET /api/purchase-orders/{id}/refunds`、`GET /api/refunds/{refundNumber}` | CUSTOMER，仅本人订单 |
| 收货评价 | `POST /api/purchase-orders/{id}/receive`、`POST /api/order-items/{id}/reviews` | CUSTOMER |
| 商家履约 | `GET /api/merchant/orders/search`、`GET /api/merchant/orders/{id}`、`POST /api/merchant/orders/fulfillment-v1/commands`、`GET /api/merchant/orders/fulfillment-v1/commands/{commandId}`、`POST /api/merchant/orders/{id}/packages` | MERCHANT，仅本店订单 |
| 用户管理 | `GET /api/users` | ADMIN |
| 分页查询用户 | `GET /api/users/page?q=&page=&size=` | ADMIN |
| 商家账号 | `POST /api/users/merchants` | ADMIN |
| 店铺 | `GET /api/stores/{id}`、`GET/PUT /api/merchant/store`、`GET /api/admin/stores`、`PUT /api/admin/stores/{id}/status` | 公开店铺 / 商家本人 / 管理员 |
| 新建商品 | `POST /api/merchant/catalog/v1/products` | 已启用店铺的商家 |
| 商品 SKU | `GET/POST /api/merchant/catalog/v1/products/{productId}/skus`、`PUT /api/merchant/catalog/v1/products/{productId}/skus/{skuId}` | MERCHANT，仅本店商品 |
| 商家应结 | `PUT /api/admin/stores/{merchantId}/settlement`、`GET/POST /api/admin/stores/{merchantId}/statements`、`GET /api/merchant/settlement/statements` | 管理员开通和冻结，商家读本店 |
| 商家打款 | `GET/POST /api/admin/stores/{merchantId}/statements/{id}/payouts`、`GET /api/merchant/settlement/statements/{id}/payouts` | 管理员发起，商家读本店 |
| 定时任务治理 | `GET/PUT/DELETE /api/admin/scheduling/policies...`、`GET/POST /api/admin/scheduling/tasks...` | ADMIN |

当前仓库的商品目录和公开店铺通过 `GET /api/products/search` 分页，返回 `products`、`total`、`page` 和 `size`。
页码从 0 开始，默认每页 12 件。商品只来自当前对外开放的店铺。公开店铺页以当前店铺的 `merchantId`
查询。目录可再传入 `q`、`lang`、`minPrice`、`maxPrice` 和 `sort`。
全站与店内共用高级检索参数。查询在所选语言的商品名称、简介和描述中按字面匹配。

| 参数 | 行为 |
| --- | --- |
| `match=all` | 默认值，`q` 中所有以空白分隔的关键词都要出现，顺序不限。 |
| `match=any` | 至少命中 `q` 中的一个词。 |
| `match=phrase` | 连续匹配去除首尾空白后的整段 `q`，保留词序和内部空格。 |
| `exclude` | 排除含任一指定词的商品。可单独使用，也可与上述模式、店铺、价格及 SKU 组合。 |

例如 `GET /api/products/search?lang=en&q=coffee%20tea&match=any&exclude=decaf&sort=price-asc`。
`q` 与 `exclude` 各限 120 个字符、12 个不同词。公开 API 对未知匹配模式或超限输入返回 400。
Elasticsearch 和 MySQL 回退使用相同的匹配条件，相关性排序由各自的引擎计算。

目录 V8 将属性和销售规格分成两个参数，均使用以分号分隔的 `name=value`。名称和值忽略大小写并去除首尾空白。
商品层的 `attributes` 支持最多六项固定属性，可用 `screen size=27;resolution=2560 × 1440` 查询 27 英寸 QHD。
其余显示器属性名为 `brand`、`panel`、`refresh rate` 和 `port`。尺寸用英寸数值，刷新率用 Hz 数值。
销售层的 `sku` 支持最多四项规格，例如 `package=stand-bundle`。旧链接中的商品属性不会从 `sku` 映射到 `attributes`。
同一启用 SKU 必须同时满足所有规格和价格条件。商品只返回一次，候选商品卡使用
`minimumSkuPrice` 和 `maximumSkuPrice` 展示全部可售 SKU 的价格范围。

传入 `includeSkuFacets=true` 时，`attributeFacets` 和 `skuFacets` 分别返回商品属性和销售规格的 `name`、`value`、`productCount`。
省略参数时两个数组为空。属性和值均按符合当前全部条件的商品去重。
规格值按匹配整批查询的商品去重，当前页码和排序不会改变计数。关键词、排除词、语言、店铺、价格及全部已选规格都会参与筛选，
其中价格、已选规格和待计数的规格值必须出现在同一条可售 SKU 上。
可点击选项使用对应参数的 `name=value;name=value` 格式，含分隔符且无法用该格式表达的名和值不出现在选项中。
Elasticsearch 使用嵌套 SKU 聚合及商品级去重。聚合超过 2,000 个规格值、结果不完整或 Elasticsearch 不可用时，
改由 MySQL 返回搜索页及完整计数。

商品列表和搜索返回 `averageRating`、`reviewCount`，其中 `reviews` 为空数组。详情内嵌最新 5 条评价。
`GET /api/products/{productId}/reviews` 返回 `reviews`、`total`、`page`、`size`，
从第 0 页起算，默认 5 条，最大 20 条，按评价时间和评价 ID 倒序。商品不可对外展示时返回 404。

2026-11-24 第 1 批发布后，日常公开店铺使用带 `merchantId` 的搜索分页。日常目录为 Flyway V7，搜索优先读取 Elasticsearch，故障时回退到 MySQL。独立商详首屏展示详情内嵌的最新评价，随后翻页调用独立评价接口。

日常购物车以 `POST /api/products/lookup?lang=zh-CN` 读取当前商品名和价格。请求体是最多 100 个商品 ID 的数组，
响应按商品 ID 提供 `name` 和 `price`。不存在、已下架或店铺未开放的商品不出现在映射中。
购物车保留这些行的原商品快照，库存仍由可售量接口单独读取。

日常店铺、评价、购物车的 Chrome 实测和 Elasticsearch 索引核对见[第 1 批日常发布记录](IMPLEMENTED.md#268-2026-11-24-第-1-批商品目录日常发布)。

管理员通过 `GET /api/admin/catalog-search` 读取目录及 Elasticsearch 代次、同步阶段、待同步数和最早待同步时间。
`GET /api/admin/catalog-search/comparison` 按商品编号逐批对照 MySQL 和 Elasticsearch，返回已核对数量以及缺失、多余和内容不一致的商品。
每批带 `generation`，翻页时把 `nextAfterId` 作为 `afterId` 传入。代次切换时返回 `RESTART`，积压中的核对返回 `CATCHING_UP`。
`POST /api/admin/catalog-search/rebuild` 触发原索引重建流程。

SKU 创建接受 `requestId`、`code`、`specifications`、`unitPrice` 和 `status`。编码在店内唯一，规格组合在商品内唯一。
已创建 SKU 可通过 `expectedVersion` 修改编码、价格及状态。默认 SKU 的售价与状态沿用商品编辑入口，
可在改 SKU 时提交 `specifications` 命名默认销售组合，已创建的非默认 SKU 规格保持固定。

V8 的 `PUT /api/merchant/catalog/v1/products/{productId}/monitor-profile` 接受 `expectedVersion` 和 `profile`。
`profile` 包含 `brand`、`screenSizeInches`、`widthPixels`、`heightPixels`、`panel`、`refreshRateHz` 和 `ports`。
商家目录及公开商详返回 `monitorProfile`，商详另返回独立的销售 SKU。

目录 V7 在商详返回 `skus`。选定规格后，门户用该行的 `stockProductId` 查询可售量，
并在 `POST /api/cart/items` 提交公开商品的 `productId`、`skuId` 与 `quantity`。
省略 `skuId` 时仍按原商品号购买默认销售单元。SKU 不属于该商品或不可售时返回 404。
购物车和订单行的 `productId` 为具体销售单元的库存编号。成交时冻结商品名中的规格值及单价，
履约、退货和库存流水继续使用原订单行与库存编号。

顾客订单详情中的 `payment.idempotencyKey` 对应原支付提交。门户按原键核对支付单，结果未知时
保留渠道和原参数，手动重试复用原键。取消用原订单和取消原因重试，订单侧完成和审计分别使用
`finish-cancellation:{id}`、`audit-cancellation:{id}`。顾客开票按订单查原申请号，回执迟到时
继续读取同一申请。服务端为每个订单保留一条开票申请，渠道开票请求使用账本中持久化的请求号。

商家的 `GET /api/merchant/return-work-orders` 与平台的 `GET /api/admin/exchanges` 返回
`rows`、`total`、`page`、`size`，页码从 0 起算，默认每页 20 条，最大 50 条。
支持按 `caseId`、`orderId`、`status`、`createdFrom` 和 `createdBefore` 查询。
商家按工单阶段过滤且只查本店，平台按换货状态过滤。创建时间包含起点，排除终点。

商家使用 `GET /api/merchant/settlement/statements/{id}/reconciliation` 核对本店一个冻结账期。
平台使用 `GET /api/admin/stores/{merchantId}/statements/{id}/reconciliation`。响应包含账期总额、
打款尝试、汇总差异及逐单凭证与差异。订单页从 0 开始，默认每页 10 条，最大 20 条。

第 07 项扩展上述只读响应。逐单的 `fulfillment` 包含库存出库行、WMS 计划与实发、WMS 实收及质检回执。
库存和 WMS 按商家及当页订单批量查询。实发与库存出库按出库单、仓库、订单行和商品核对件数。
退货回执保留原出库号和售后单号，可关联退款单及已完成退款的件数。库存及 WMS 可用性各自返回，查询失败保留资金报告。
日常商城、门户和两项凭证服务均已发布。店铺 8 抽检中两项凭证来源均可用，已读取库存出库记录，抽检的旧订单未返回对应的 WMS 回执。
逐单增加支付确认时间、退款金额与成功时间、正红票金额和引用，门户在原冻结账单详情中展示。打款尝试增加渠道确认时间。

平台管理员可通过 `GET /api/admin/stores/{merchantId}/statements/{id}/reconciliation/reviews`
读取复核历史。响应为 `rows`、`total`、`page` 和 `size`，从第 0 页开始，默认每页 10 条，最大 100 条。
向同一路径发送 `POST`，提交 `requestId`（UUID）、原报告的 `page` 和 `size`、`target`、`decision` 和 `reason`。
`target` 包含 `orderId`、`orderLineId`、`kind`、`reference`、`expected` 和 `observed`。
账单汇总差异的订单和订单行为空，资金差异沿用报告中的引用，件数差异以出库单号作为引用。
结论可选 `CONFIRMED` 或 `NO_ACTION`，依据须为 1 至 300 个字符。
服务端复算证据，证据变化时返回 409。同一管理员重试相同请求返回原记录，请求号冲突返回 409。
复核只追加结论、差异快照、操作人和时间。商家访问复核接口返回 403，店铺与账单错配返回 404。

V72 在单条复核下提供 `GET /reconciliation/reviews/{review}/disposition`（沿用上述平台账单前缀）。
向其 `/receipts` 发送 `POST` 关联原业务回执，提交 `requestId` 和 `receipt`。向其 `/closure` 发送 `POST` 重新对账和结案，提交 `requestId` 和 `reason`。
已确认的库存、打款或发票差异须通过对应业务核验回执，重算后相应差异消失才可结案。无需处置须重新对账并记录结案依据。
同一操作者以相同请求号和内容重试可读回原结果。无回执、来源暂不可用或结果仍有差异时，保持待处理并返回 409。日常商城已升至 V72。

V72 提供 `GET /api/admin/exception-work` 和 `GET /api/merchant/exception-work`。
返回 `rows`、`total`、`page`、`size`，页码从 0 开始，默认每页 20 条，最大 100 条。
可以按 `kind`（`STATEMENT_DIFFERENCE`、`RETURN_WORK`）、`priority`（`HIGH`、`NORMAL`）、
`state`（`OPEN`、`WAITING_EVIDENCE`、`CLOSED`）和 `orderId` 筛选。平台可按 `merchantId` 筛选，商家视图固定为当前店铺。
账单条目只取同一差异最新的确认记录，售后条目直接读取原工单阶段。两个入口均只读，商家列表只含业务标识和状态。
平台可通过 `GET /api/admin/stores/{merchantId}/statements/{id}/reconciliation/reviews/{review}` 定位单条复核，
也可用 `GET /api/admin/stores/{merchantId}/return-work-orders/{caseId}` 和其 `/evidence` 读取原售后工单及仓库凭证。

跨期对账使用 `GET /api/merchant/settlement/statements/{id}/period-comparison`，平台使用
`GET /api/admin/stores/{merchantId}/statements/{id}/period-comparison`。指定本期冻结账单，
返回与其起点相接的上期账单 `previous`（首期为 `null`）、本期 `current` 和本期退款冲正分页。
两期摘要分别保留冻结金额、成功打款和退款汇总。退款冲正按时间及账本行标识排序，可追到
同店原订单行、销售账本和原冻结账单。原销售账单未确认时返回空引用，不计入已确认的跨期退款。
分页从 0 开始，默认每页 10 条，最大 20 条。账单属于其他店铺时返回 404。

### 列表分页查询

以下分页接口返回 `rows`、`total`、`page`、`size`。页码从 0 开始，默认每页 10 条，最大 100 条。页码超出范围时返回最后一页，空结果返回第 0 页。
用户查询支持按姓名、邮箱和电话筛选。任务实例和回调按 ID 倒序排列，库存流水按发生时间和 ID 倒序排列。结算明细按发生时间和原凭证号排序。

| 列表 | 分页路径和筛选 |
| --- | --- |
| 定时任务实例 | `GET /api/admin/scheduling/tasks/page`，可按 `taskType`、`status` 筛选 |
| 回调事务发件箱 | `GET /api/admin/logistics-callbacks/page`，可按 `status` 筛选，商城转发到物流模拟器的签名分页入口 |
| 商品库存流水 | `GET /api/merchant/inventory/{productId}/movements/page` |
| 结算账单 | 商家用 `GET /api/merchant/settlement/statements/page`，管理员用 `GET /api/admin/stores/{merchantId}/statements/page` |
| 待结记录 | 商家用 `GET /api/merchant/settlement/pending/page`，管理员用 `GET /api/admin/stores/{merchantId}/settlement/pending/page` |
| 冻结明细 | 商家用 `GET /api/merchant/settlement/statements/{id}/entries/page`，管理员用 `GET /api/admin/stores/{merchantId}/statements/{id}/entries/page`，商家只能查看本店账单 |

## 管理员人工恢复 API

| 操作 | 入口与审计 |
| --- | --- |
| 盘点审批 | `POST /api/admin/inventory-command-recoveries/stocktakes/{reference}/approve` 接收审批人独立填写的 `reason`。登记人无法审批自己的报告。回执包含盘点报告、审批人、理由、时间及库存调整流水。 |
| 失败打款恢复 | `POST /api/admin/stores/{merchantId}/statements/{id}/payouts` 复用冻结账单。恢复须传 `requestId`、最近一笔渠道已确认失败的 `recoveryOf` 和 `reason`。管理员可查原单号、操作人和理由，商家只读本店打款。 |
| 发票复核 | `POST /api/admin/invoices/{orderId}/review-actions` 需要管理员的发票运营授权，并传 `requestId` 和 `note`。审计保存复核前的原因、证据、操作人和时间，同一请求重放不重复复核。 |
| 通知投递重试 | `GET /api/admin/notification-deliveries/{eventId}` 查询商城发送 Outbox 的原事件及审计。仅允许通过 `POST /api/admin/notification-deliveries/{eventId}/retries` 重试 `DEAD` 事件，提交 `requestId` 和 `reason`。重试使用原事件号及载荷。 |

## 商家履约 API

- 商家订单搜索默认每页 10 笔，`page` 从 0 开始，`size` 可设为 1–20。按创建时间和订单 ID 倒序返回，`pendingConfirm` 和 `pendingShip` 是本店待办总数。`orderNumber` 用完整订单号精确匹配，可配合 `status` 使用。关键词、商品和运单等模糊查询用 `q`，两个参数互斥。查不到或订单归属其他店铺时返回空页，按内部 ID 查其他店铺订单返回 404。原 `GET /api/merchant/orders` 全量入口已移除。
- 统一提交接口用 `operation` 区分接单 `CONFIRM`、发货 `SHIP` 和改运单 `UPDATE_SHIPMENT`。重试保留原 `commandId`。提交返回 `202 Accepted` 时，按 `Location` 或 `commandId` 查询结果。
- 独立包裹创建使用 `POST /api/merchant/orders/{id}/packages`，提交 `packageId`、承运商、运单号和包裹明细。两仓发货须提交选定的 `warehouseId`，仅使用默认仓时可省略。返回命令可按 `commandId` 查询。
- 门户在提交接单、包裹发货或改运单前保存原请求，刷新后用 `GET /api/merchant/orders/fulfillment-v1/commands/{commandId}` 核对结果。包裹请求用 `packageId` 作为查询的 `commandId`。查询无回执时，商家可手动重发原请求。已受理的命令继续使用原回执，改运单重试保留原运单版本。

仓库模拟器管理员使用 `GET /api/admin/outbounds` 和 `GET /api/admin/returns` 分页查询。
响应包含 `rows`、`total`、`page`、`size`，页码从 1 开始，默认每页 20 条，可选 1–200。
可按 `orderId`、`jobId`、`status`、`createdFrom` 和 `createdBefore` 查询。时间采用 ISO 8601，
起点包含、终点排除。列表中的单据编号可以继续用于仓库原有的实发、实收和质检操作。

## 结算与优惠券 API

- 创建活动提交 `title`、`minimumAmount`、`discountAmount`、`supply`、`startsAt`、`expiresAt`。开始和结束时间采用带时区的 ISO 8601 时间。领券重试返回原券。
- 报价提交 `addressId`、`itemIds` 以及可选 `couponId`。确认订单提交同样的结算数据作为 `selection`，并带上 `quoteId`。活动关闭、券已锁定、商品门槛未满足或报价变动时，以服务端校验为准。
- 订单详情返回 `couponId`、`discountAmount` 和 `items[].discountAmount`。分摊、核销和返还状态见 [结算与优惠券](features/CHECKOUT-COUPONS.md)。

## 退货物流 API

登记退货提交承运商 `carrier`、运单号 `trackingNumber` 和可选的顾客退回运费 `returnShippingCost`。签名回调及主动对账把渠道当前状态与轨迹投影到售后单。查询接口返回 `merchantShipmentNo`、`platformShipmentNo`、`carrier`、`trackingNumber`、`status`、`deliveredAt` 和 `events`。没有渠道绑定的历史退货查询返回空响应。商家验收提交 `accepted`、`receivedQuantity` 和 `note`。新登记退货尚未送达时返回状态冲突。

## 通用定时任务管理 API

管理员可维护任务类型策略，但不能从页面上传或执行代码。任务类型只有同时注册了受信任的 Java `ScheduledTaskHandler`，才允许创建实际任务。

| 操作 | 接口 | 说明 |
| --- | --- | --- |
| 策略目录 | `GET /api/admin/scheduling/policies` | 合并内置目录、YAML、数据库覆盖和已注册 Handler |
| 保存覆盖 | `PUT /api/admin/scheduling/policies/{taskType}` | 写数据库覆盖并递增配置版本，只影响后续新任务 |
| 恢复配置 | `DELETE /api/admin/scheduling/policies/{taskType}` | 删除数据库覆盖，恢复 YAML 任务策略或默认策略 |
| 策略审计 | `GET /api/admin/scheduling/policies/{taskType}/events` | 查看新增、修改和恢复配置的操作者与完整参数 |
| 任务分页 | `GET /api/admin/scheduling/tasks/page?taskType=&status=&page=&size=` | 按类型和状态查询历史 |
| 最近任务查询 | `GET /api/admin/scheduling/tasks?taskType=&status=&limit=` | 保留既有调用，最大 500 条 |
| 任务事件 | `GET /api/admin/scheduling/tasks/{id}/events` | 查看创建、领取、成功、失败、重试和取消轨迹 |
| 人工重试 | `POST /api/admin/scheduling/tasks/{id}/retry` | 仅允许重试 `FAILED` 任务，次数从零重新开始 |
| 人工取消 | `POST /api/admin/scheduling/tasks/{id}/cancel` | 仅允许取消尚未完成的待执行/失败任务 |

保存策略请求体：

```json
{
  "enabled": false,
  "primaryTrigger": "DATABASE",
  "reconciliationEnabled": true,
  "gracePeriodSeconds": 120,
  "claimLeaseSeconds": 120,
  "maxAttempts": 5,
  "retryDelaySeconds": 30
}
```

配置覆盖不会修改已经创建任务的策略快照。`RABBITMQ` 和 `ROCKETMQ` 枚举已为后续适配器保留，但适配器完成前不得在实际业务任务上启用。

Angular 管理员可从导航栏“定时任务”进入 `/admin/scheduling` 操作上述接口。新增任务类型时，页面默认以禁用状态保存；只有后端已有对应 Handler 时才可安全启用并创建任务。

## 订单取消 API

`POST /api/purchase-orders/{id}/cancel` 仅允许订单所属客户操作。请求体可省略：

```json
{
  "reason": "客户改变购买计划"
}
```

后端不会直接把订单从 `PENDING_PAYMENT` 改成 `CANCELLED`。它先进入 `CANCELLING`，逐个签名关闭仍为 `PENDING` 的渠道交易，关闭成功后再写本地支付记录和订单终态。重复请求幂等；若支付成功先发生，则支付事实优先并保留异常事件供审计。

取消成功后同一本地事务会写入 `ORDER_CANCELLED` Outbox。订单查询响应包含 `availableActions`，供页面决定显示支付、取消、退款、收货和评价等操作；该字段不代替服务端授权校验。

## 客户通知 API

| 能力 | 方法与路径 | 说明 |
|---|---|---|
| 最近通知 | `GET /api/notifications` | 返回当前客户最近 20 条通知 |
| 未读数量 | `GET /api/notifications/unread-count` | 返回 `count`，包含最近 20 条之外的全部未读通知。门户用它显示角标 |
| 单条已读 | `PATCH /api/notifications/{eventId}/read` | 只能更新当前客户自己的通知，重复标记保留首次已读时间 |
| 全部已读 | `PATCH /api/notifications/read-all` | 将当前客户的未读通知批量标记为已读 |

两种已读操作成功时返回 204，门户随后刷新列表和总数。通知服务不可用时返回 503，门户保留上次成功加载的内容并提示重试。

当前通知由 `ORDER_CANCELLED` Kafka 消费者创建。消费记录、库存流水事件 ID 和通知事件 ID 均有唯一约束，重复消息不会重复释放库存或重复通知。

## 商家库存 API

| 能力 | 方法与路径 | 说明 |
|---|---|---|
| 库存列表 | `GET /api/merchant/inventory` | 返回当前商家商品的可售、预占和已售余额 |
| 库存流水 | `GET /api/merchant/inventory/{productId}/movements` | 返回当前商家指定商品最近 100 条流水 |
| 人工调整 | `POST /api/merchant/inventory/{productId}/adjustments` | 请求体为 `delta` 与必填 `reason`，只调整可售库存且不能减为负数 |

库存接口只允许 `MERCHANT` 角色访问。commerce-service 完成 Session/CSRF/RBAC 后，以 merchantId 和 HMAC
调用 inventory-service；后者在独立 inventory-db 中再次执行商品归属校验。人工调整和订单库存变化使用同一
流水模型，独立服务不可用时返回 503，不读取 commerce 源库存表。

## 模拟物流 API

| 接口 | 认证 | 说明 |
| --- | --- | --- |
| `POST /api/channel/shipments` | App ID + 时间戳 + Nonce + HMAC | 幂等创建运单 |
| `GET /api/channel/shipments?merchantShipmentNo=...` | 同上 | 查询物流平台权威运单 |
| `PUT /api/channel/shipments/{merchantShipmentNo}` | 同上 | 修改承运商和运单号 |
| `POST /api/channel/shipments/{merchantShipmentNo}/advance` | 同上 | 学习环境手工推进一个轨迹节点 |
| `POST /api/channel/shipments/{merchantShipmentNo}/exception` | 同上 | 运单进入 `EXCEPTION（异常件）`，请求体可携带 `location/description` |
| `POST /api/channel/shipments/{merchantShipmentNo}/recover` | 同上 | 通过 `RECOVERY（异常恢复）` 返回异常前状态 |
| `POST /api/logistics-notifications/callback` | 回调 HMAC | 物流平台推送状态和轨迹 |
| `GET /api/admin/logistics-callbacks?status=DEAD` | ADMIN | 查询 Callback Outbox，可按状态筛选 |
| `GET /api/admin/logistics-callbacks/page?status=DEAD&page=0` | ADMIN | 按状态分页查询历史，返回匹配总数 |
| `GET /api/admin/logistics-callbacks/{id}/events` | ADMIN | 查询回调投递和人工操作审计 |
| `POST /api/admin/logistics-callbacks/{id}/replay` | ADMIN + CSRF | 填写 `reason` 重放 `DEAD（停止自动重试）` 回调 |

商城订单列表和详情中的 `logistics` 字段会返回当前状态、承运商、运单号、发货/送达时间和完整轨迹。完整协议和一致性边界见 [独立模拟物流](MOCK-LOGISTICS.md)。

物流回调由平台 Callback Outbox 异步投递，接口返回成功不代表商城已在同一毫秒完成投影。客户端应读取商城订单/物流接口观察最终一致结果。商城同时运行主动对账，回调永久丢失时会用权威运单事件补齐；稳定 `eventId` 保证回调与对账并发时不重复入库。

## 退款 API

已支付订单退款使用独立退款单、渠道退款、签名异步通知、幂等、Outbox 和对账补偿。原支付记录继续保持 `SUCCESS`，成功退款金额写入 `refundedAmount`。

| 领域 | 接口 | 权限 |
| --- | --- | --- |
| 客户退款 | `POST /api/purchase-orders/{id}/refunds`、`GET /api/purchase-orders/{id}/refunds` | CUSTOMER，仅本人订单 |
| 退款查询 | `GET /api/refunds/{refundNumber}` | CUSTOMER，仅本人退款 |
| 商家查看 | `GET /api/merchant/orders/{id}/refunds` | MERCHANT，只读且限所属商品 |
| 渠道退款 | `POST /api/channel/refunds`、`GET /api/channel/refunds?merchantRefundNo=...`、`POST /api/channel/refunds/{merchantRefundNo}/notify` | 商户签名请求 |
| 支付后台 | `GET /api/admin/refunds`、`POST /api/admin/refunds/{channelRefundNo}/notify` | `REFUND_READ` / `REFUND_OPERATE` |

首版仅支持 `PAID` 或 `CONFIRMED` 且尚未发货订单的原路全额退款。请求体包含 `idempotencyKey`、`reasonCode`（`CUSTOMER_CANCELLED`、`ORDER_ERROR` 或 `OTHER`）和可选 `reasonDetail`；同一请求安全重放返回原退款，不同的第二次申请返回冲突。详细设计见 [退款实现说明](reference/REFUND.md)。

登录请求示例：

```json
{
  "email": "demo@example.com",
  "password": "demo-password",
  "remember": true
}
```

支付请求示例：

```json
{
  "idempotencyKey": "每次新支付使用一个 UUID；安全重试复用原值",
  "channel": "ALIPAY",
  "simulateFailure": false
}
```

`channel` 可取 `ALIPAY`、`WECHAT_PAY`、`UNION_PAY`。支付最终状态以支付服务签名异步通知为准，浏览器页面不能直接修改订单状态。

## 支付平台 API

| 领域 | 主要接口 | 权限/认证 |
| --- | --- | --- |
| 后台认证 | `GET /api/auth/csrf`、`POST /api/auth/login`、`GET /api/auth/session`、`POST /api/auth/logout` | 公开/已登录 |
| MFA 与提权 | `POST /api/auth/mfa/enroll|confirm`、`POST /api/auth/step-up` | 后台用户 |
| 商户 | `GET/POST /api/admin/merchants`、轮换密钥、启停、下载平台公钥 | PAYMENT_ADMIN/OPERATOR |
| 后台用户 | `GET/POST /api/admin/users`、分配商户 | PAYMENT_ADMIN |
| 审计 | `GET /api/admin/audit-logs` | PAYMENT_ADMIN/AUDITOR |
| 渠道交易 | `POST/GET /api/channel/transactions`、关闭交易 | 商户签名请求 |
| 收银台 | `GET /api/checkout/{token}`、`POST /api/checkout/{token}/complete` | 模拟收银台 |

商城后端调用渠道交易接口时使用 App ID、时间戳、Nonce、请求体摘要和 RSA/HMAC 签名。完整协议、安全实验和生产边界见 [高级模拟支付](reference/ADVANCED-PAYMENT.md)。

## 错误响应

商城 API 返回稳定的 `code`，前端按语言映射文案。常见代码包括：

- `AUTH_INVALID`：账号或密码错误。
- `AUTH_RATE_LIMITED`：登录尝试过于频繁。
- `VALIDATION_ERROR`：字段校验失败。
- `BUSINESS_RULE`：状态机或业务条件不允许。
- `NOT_FOUND`：资源不存在或当前用户不可见。

安全原则是后端强制鉴权和资源归属校验；前端隐藏菜单只改善体验，不构成访问控制。

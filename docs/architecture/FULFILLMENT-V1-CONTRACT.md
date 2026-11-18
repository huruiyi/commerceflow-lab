# Fulfillment V1 契约

商城提供 Fulfillment V1。商家会话通过 `POST /api/merchant/orders/fulfillment-v1/commands` 提交命令，通过
该路径下的 `GET /{commandId}` 查询接单、发货和改运单。默认部署中，独立服务提供无业务流量的签名查询与迁移接口。

## 命令与前置条件

命令包含 `version=1`、UUID `commandId`、`operation`、正整数 `merchantId` 与 `orderId`、UTC
`occurredAt`。商城检查请求中的商家身份与登录会话一致。未来服务间传输采用内部签名并传递 Trace 上下文。
新命令的订单归属以 Order V1 为准，已受理命令的查询按原商家授权。

| 操作 | 命令参数 | Order V1 条件 |
| --- | --- | --- |
| `CONFIRM` | 公共命令字段 | 商家归属成立且订单为 `PAID` |
| `SHIP` | `carrier`、`trackingNumber` | 商家归属成立、订单为 `CONFIRMED`、库存为 `COMMITTED`，并原子登记发货意图 |
| `UPDATE_SHIPMENT` | `carrier`、`trackingNumber`、非负整数 `expectedShipmentVersion` | 商家归属成立、订单为 `SHIPPED`、前置运单版本匹配 |

承运商和运单号去除首尾空白后须非空，最大长度分别为 100 和 120。订单状态由订单库原子校验，版本只用于修改
运单。业务指纹由协议版本、操作、商家、订单、适用的前置运单版本和归一化参数计算。发生时间、传输签名和
Trace 上下文属于传输元数据。 [发货命令样例](fixtures/fulfillment-ship-command-v1.json)

## 受理、查询与恢复

- 先核对同命令 ID 的记录。同 ID、同商家、同指纹返回原操作及当前结果，同商家复用 ID 提交异正文返回冲突。
  查询时查不到记录或调用商家不符，返回不存在。
- 新命令与同订单已有未结束命令竞争持久化准入。只有一个命令可以进入 `PENDING` 或 `REVIEW_REQUIRED`，
  其他命令返回可重试冲突。占用准入和保存任务在同一事务完成。重试原命令复用已有记录。
- 受理代表任务已持久化。结果包含协议版本、命令 ID、订单 ID、业务操作、当前步骤、状态、可用的运单版本及
  稳定错误码。提交结果为 `PENDING` 时返回 202 和查询地址，其余已受理状态返回 200。已知拒绝
  `REJECTED` 与未知结果 `REVIEW_REQUIRED` 分别记录。参数校验失败返回 400，前置条件与准入冲突返回 409。
- Order V1 重放使用原下游命令 ID。渠道发货使用固定运单引用，改运单使用前置版本及原参数。响应未知时继续
  查询或回放原步骤。发货成功以订单和物流均完成为准。人工恢复保留原准入及原命令，记录管理员、原因和证据。
- 商家 HTTP 继续聚合订单、退款和物流展示。准入与后台执行只依赖权威业务事实和履约记录。

## 兼容入口与再次受理

既有商家入口继续由服务端生成命令 ID，响应形状沿用原接口。两种入口共用持久化准入、任务执行和管理员恢复。
V1 使用调用方命令 ID 识别重放，忽略发生时间等传输字段；相同版本且运单参数无变化的新修改命令返回 409。
V47 冻结商城新命令时返回 503 与 `FULFILLMENT_WRITE_FROZEN`。已受理命令的查询、同正文重放和后台推进可继续。

V45 保留每个命令的历史结果。接单和初始发货只有在订单步骤明确拒绝、尚未调用物流时才允许以新 ID 重新受理。
新请求重新校验订单前置条件，发货另查已有物流证据，并由 Order V1 原子确认发货意图为空。原 ID 继续返回原结果。
已触及渠道且结果未知时继续核对原命令。 [重新受理规则](FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#已知拒绝与重新受理)

## 影子服务签名接口

- `POST /internal/v1/fulfillment/queries` 接收 `commandId` 和 `merchantId`，返回同字段形状的命令结果，
  并带有 `shadow: true`。只查询干净目标数据，命令缺失或归属不符返回 404。
- `POST /internal/v1/fulfillment/commands` 在默认影子模式校验签名后返回 503，商城继续承接履约写入。
  隔离执行模式使用单独的履约库。新命令登记后返回 202，同命令同正文重放返回原任务，根据状态返回 202 或 200。
  同命令异正文返回 409。签名查询的执行模式结果带 `shadow: false`。
- `GET /internal/v1/fulfillment/shadow/status` 读取最近成功的水位和核对结果。
  `POST /internal/v1/fulfillment/shadow/snapshot` 和 `POST /internal/v1/fulfillment/shadow/catch-up`
  触发目标库快照和追平。

签名头为 `X-CommerceFlow-Timestamp`、`X-CommerceFlow-Request-Id`、`X-CommerceFlow-Content-SHA256`
和 `X-CommerceFlow-Signature`。允许的时钟偏差为 300 秒。 [影子运行说明](FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#10-独立履约影子运行)

## 独立执行的管理员恢复

恢复入口默认关闭。隔离执行环境可显式启用 `FULFILLMENT_RECOVERY_ENABLED`，并通过
`FULFILLMENT_ADMIN_SECRET` 使用独立管理员签名密钥。业务签名访问恢复入口返回 401。

- `GET /internal/v1/fulfillment/admin/operations` 列出至多 50 条 `REVIEW_REQUIRED` 任务。
  `GET /{commandId}` 查询任务和最近 100 条恢复记录。
- `POST /{commandId}/retry` 接收 `recoveryId`（UUID）、`operatorId`（正整数）、`reason`（最多 300 字符）
  与 `evidence`（最多 500 字符）。签名方负责确认 `operatorId` 对应的管理员身份。
- 仅无租约的 `REVIEW_REQUIRED` 任务可重新入队。保留命令 ID、当前步骤和订单准入，在同一事务重置重试次数并
  保存原错误、操作者与人工核对证据。受理及同正文重放返回 202 和原审计记录，异正文重放和不可恢复状态返回 409。

## 商城远程兼容入口

隔离转换时，商城保留原公开 V1、旧商家和管理员接口。商城从会话取得商家 ID 和管理员 ID，管理请求以独立密钥
签名。新提交和历史查询均由当前履约所有者处理，远程不可用返回 `503/FULFILLMENT_SERVICE_UNAVAILABLE`。

内部 `POST /legacy-commands` 按订单和操作业务位置复用已受理记录，改运单同时以旧版本定位。
`POST /shipment-queries` 根据订单与商家查找历史发货。`POST /queries` 的 `executionOnly` 标志要求目标为执行
模式，供商城远程路由使用。管理员可通过 `POST /admin/operations/search` 按发货类型筛选，按订单读取发货详情，
按命令读取接单与改运单详情，重试请求通过签名正文携带发货订单或商家操作类别约束。

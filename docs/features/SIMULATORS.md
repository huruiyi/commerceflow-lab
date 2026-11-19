# 外部系统模拟器

| 平台 | 服务与使用方式 |
| --- | --- |
| 支付 | `simulators/payment-platform/service` 提供三渠道收款、原路退款和模拟商家打款，`web` 提供收银台及两个账本菜单。支持成功和失败模拟、渠道查询、关单、退款和打款结果回调。 |
| 物流 | `simulators/logistics-platform/service` 提供签名建单、运单、轨迹、异常处理和回调补偿。物流运营 API 可管理手动或自动轨迹，`web` 为独立管理界面预留目录。 |
| 消息、开票、仓储 | `messaging-platform`、`invoice-platform`、`warehouse-platform` 已预留 `service` 与 `web`，实施顺序以项目计划为准。 |

支付和物流通过本地模拟系统供商城练习签名、幂等和故障恢复。商家打款复用现有支付模拟平台的服务端和管理后台。专用出款应用访问独立 API，使用独立状态和账本，收款与退款依旧使用原渠道规则。后台按两类业务分设菜单。详见 [商家应结与打款](PAYMENTS-AFTER-SALES.md#模拟商家打款)。

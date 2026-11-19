# 外部系统模拟器

| 平台 | 服务与使用方式 |
| --- | --- |
| 支付 | `simulators/payment-platform/service` 提供三渠道收款和原路退款，`web` 提供收银台及支付管理入口。支持成功和失败模拟、渠道查询、关单、退款及回调。 |
| 物流 | `simulators/logistics-platform/service` 提供签名建单、运单、轨迹、异常处理和回调补偿。物流运营 API 可管理手动或自动轨迹，`web` 为独立管理界面预留目录。 |
| 消息、开票、仓储 | `messaging-platform`、`invoice-platform`、`warehouse-platform` 已预留 `service` 与 `web`，实施顺序以项目计划为准。 |

支付和物流通过本地模拟系统供商城练习签名、幂等和故障恢复。后续商家打款沿用同一个支付模拟平台，拟与收款退款使用各自接口、状态和账本，并在管理后台分设菜单。进度见 [项目计划](../TODO.md#26-外部系统模拟器计划)。

# CommerceFlow Lab 统一进度与未实现代办

## 1. 文档职责

| 项目 | 内容 |
| --- | --- |
| 性质 | 业务进度与阶段导航，架构收尾的勾选和验收标准由对应专题维护 |
| 主线 | 原 `temp.md` 中准备实施的订单闭环方案 |
| 合并内容 | 订单超时策略、Database/RabbitMQ/RocketMQ 讨论、截图阶段 4～6 和旧实施计划 |
| 当前状态 | P0–P4、计价与运费、按行售后、单商家满减券、退货跟踪，以及商家入驻与归属、应结账本、模拟打款均已验收并发布。 |
| UI 边界 | 客户订单中心已改为摘要、当前主操作与可展开详情，支付、退款、物流和时间线保持独立事实展示 |
| 最后更新 | 2026-11-20 |

本文档保留已完成勾选和未完成项，用于表示实施顺序和阶段门禁。已实现能力的详细证据见 [IMPLEMENTED.md](IMPLEMENTED.md)，当前快照见 [PROJECT-STATUS.md](PROJECT-STATUS.md)，详细策略见 [通用定时任务策略](reference/SCHEDULED-TASK-STRATEGIES.md)。第 13 节各批次和第 14 节实施记录的脚本数量、准入结论与拓扑均对应当时版本。旧草案和旧实施计划移入 `archive/`。

## 2. 当前基线与本轮边界

当前按真实入口验收基础业务，发现阻断即修复。先解决全新演示环境能登录、选商品、加购、下单和支付，再验证商家接单发货、客户看物流和收货，以及取消、退款与库存结果。

- [x] 全新隔离环境通过显式演示初始化跑通上述基础 API。支付宝模拟实付、接单发货、物流签收、手动收货、取消释放和全额退款回补已核对。[本轮验收记录](IMPLEMENTED.md#21-基础业务隔离复验)
- [x] 在隔离环境用实际浏览器走查客户和商家页面。修复商家履约、客户取消和退款的异步状态停滞，并验证异常提示。[页面验收记录](IMPLEMENTED.md#22-基础业务页面走查)
- [x] 支付失败后的同渠道重试、响应丢失后的幂等重试及微信转银联已通过隔离浏览器验收。前端修复已更新到日常 Docker 门户。[支付重试验收](IMPLEMENTED.md#23-支付失败与重试)
- [x] 日常商城和支付编排已发布银联契约修复。以 V51 运行基线构建，仅更新两处支付渠道枚举，使用同一批镜像完成隔离支付和库存核对。[发布记录](IMPLEMENTED.md#23-支付失败与重试)

### 基础功能实施顺序

1. [x] 商品列表排序。支持价格升降序和评分优先，与现有搜索一起使用。
2. [x] 客户订单查询。按订单号或商品搜索，按订单状态筛选，显示匹配数及无结果提示。
3. [x] 商家订单查询。复用订单筛选，并支持按收件人或运单号定位履约订单。

三项已通过隔离浏览器验收并更新到日常 Docker 门户。[验收记录](IMPLEMENTED.md#24-商品排序与订单查询)

### 基础业务第二批

1. [x] 结算前确认。直接选择收货地址，核对商品、数量和应付金额，校验失败后保留购物车。
2. [x] 商家商品管理。可维护自有商品资料、价格和上下架状态，保留历史订单快照。
3. [x] 库存不足和下架提示。顾客可查看库存、定位受影响的购物车条目，并在预占拒绝后调整购物车。
4. [x] 商家待处理订单。提供待接单和待发货队列，履约操作完成后刷新笔数。
5. [x] 订单服务端查询与分页。订单服务按角色、关键字、状态和下单日期查询，门户展示匹配总数和分页。

五项均已通过隔离 Docker 和 MySQL 验收。[第二批验收记录](IMPLEMENTED.md#25-基础业务第二批验收)

- [x] 发布到日常 Docker，实测结算、支付、接单、发货、履约笔数和历史订单查询。[日常发布记录](IMPLEMENTED.md#26-基础业务第二批日常发布)
- [x] 按选中商品结算。购物车支持单选和全选，订单与库存仅处理选中条目，未选商品继续保留。已发布到日常 Docker。[验收记录](IMPLEMENTED.md#27-购物车按选中商品结算)
- [x] 历史订单再次购买。已收货、已完成订单可按当前售价和库存加购，逐项展示结果。同一请求重试只加购一次。已发布到日常 Docker。[验收和发布记录](IMPLEMENTED.md#28-历史订单再次购买)
- [x] 订单详情直达与通知跳转。支持独立订单链接、相关区域定位和列表筛选恢复。已通过隔离验收并发布到日常 Docker。[验收记录](IMPLEMENTED.md#29-订单详情直达与通知跳转)

### 2.1 直接复用的现有能力

- [x] `PENDING_PAYMENT -> PAID -> CONFIRMED -> SHIPPED -> RECEIVED` 基础流转。
- [x] 三渠道模拟支付、签名通知、幂等、Outbox、Kafka 和支付对账。
- [x] 未发货已支付订单首版整单全额退款。
- [x] 商家确认、手工录入承运商/单号、发货和物流信息更正。
- [x] 客户手动确认收货和一次性商品评价。
- [x] ShedLock 与跨平台验收脚本。Windows、Linux 和 WSL 统一使用 PowerShell 7 运行 `scripts/` 中的同一份入口。

### 2.2 本轮必须补齐

- [x] `CANCELLING`、`CANCELLED`、`COMPLETED` 订单状态。
- [x] 客户主动取消未支付订单，Angular 客户页面已按 `availableActions` 接入。
- [x] 整单支付截止时间、Database 自动关单及与支付成功的并发状态处理，Windows/Bash 真实竞态浸泡压测已通过。
- [x] Database、RabbitMQ、RocketMQ 可插拔主触发和可独立开关的 Database 对账补偿。
- [x] 任务策略快照，保证同一任务不随全局配置中途换策略。
- [x] 独立模拟物流服务、签名回调、轨迹投影与客户查询。
- [x] 物流签收后由 `AUTO_RECEIVE（自动确认收货）` 推进收货，再由 `AUTO_COMPLETE（自动完成订单）` 执行 `RECEIVED -> COMPLETED`。
- [x] 订单响应新增 `availableActions`，客户页面已接入取消、支付、退款、物流、收货和评价操作，历史支付/退款/物流信息保留。

### 2.3 基础业务批次的范围边界

- 退货、换货、部分退款和售后工单列入第 2.5 节的后续实施批次。
- 真实支付机构或真实物流渠道接入。

### 2.4 后续项目计划与批次验收

截至 2026-11-19，日常 Docker 已运行购物车勾选结算、历史订单再次购买和订单详情直达。
商城库为 V53。再次购买和订单详情直达均已有对应发布提交。订单、目录、通知监控及取消跨库恢复有隔离验收记录，
日常部署状态在对应发布批次重新核对。计划按下列顺序推进，每批可连续完成实现、验收、记录和提交。

#### P0 对齐日常发布版本与源码

- [x] 推送已验收的两笔提交，核对 `origin/main`、日常容器镜像 ID、前端资产和商城 Flyway 版本。
- [x] 用隔离工作树基于该提交构建，只吸收本批需要的改动。记录其余架构改动对应的专题和验证状态，避免混入基础业务镜像。
- [x] 按第 2.5 节确定金额快照、订单行、后续服务所有权与权限边界，让 P1、P2 的验收一起验证兼容性。
- [x] 用现有 PowerShell 7 验收入口建立快速回归基线，记录失败项和可重现步骤。确认上线版本可由 Git 重建，
  以及五笔历史库存命令仍为 `CLOSED`、对应库存流水为零。

#### P1 顾客从选购到支付

- [x] 一起验收商品搜索排序、购物车勾选、地址选择和结算确认。覆盖下架、库存变动、价格变化、地址失效和双击提交；
  修复发现的阻断。价格变化以服务端实际应付金额为准，确认页能让顾客重新核对，失败后保留可继续编辑的购物车。
- [x] 统一购物车逐项异常提示和调整入口。覆盖再次购买的部分成功、同款合并、请求回执重试和继续结算；
  对照结算明细、回执表、购物车余项和库存预占核对。
- [x] 用日常拓扑对应的候选镜像覆盖三种模拟支付渠道、失败重试、切换渠道、超时和回调重放。
  门槛为同一订单最多一笔成功扣款，库存状态与实际预占或扣减流水一致。

#### P2 商家履约与顾客订单闭环

- [x] 完成商家改价、上下架、库存调整、待接单和待发货的同场景验收。商家只处理自有商品和订单，
  历史订单快照保持原值，刷新后队列数量与订单库一致。
- [x] 验收多商品订单发货、运单更正、物流轨迹、手动或自动收货、订单完成和评价。
  核对重复操作时出库和运单各只有一条有效业务记录。
- [x] 验收未支付主动取消和超时取消，以及未发货的整单全额退款。以取消释放、退款回补及渠道资金流水核对结果；
  从顾客订单详情检查状态、可用操作和通知跳转。按可用角色覆盖越权访问和错误提示。
- [x] 汇总 P1、P2 的修复，在日常 Docker 逐服务发布候选镜像。验收保留代表成功、缺货、取消、退款的订单证据，
  按日常实际产生的通知核对跳转与已读回执。基础业务发布完成后冻结可复测基线。

P0–P2 已通过隔离验收和日常 Docker 抽检，冻结基础商城源码及镜像基线。[验收与发布记录](IMPLEMENTED.md#210-基础商城-p0p2-验收)

#### P3 运行监控与订单取消恢复

- [x] 单独整理库存、订单、目录、通知四条链路的指标和告警，逐一确定已提交、隔离验收和日常运行状态。
  复用现有专项脚本，先验证正常、故障触发和恢复，再检查日常抓取及告警规则加载。
  商城库存命令监控与库存服务出库事件监控分别记录。
- [x] 单独发布取消跨库恢复。基于当前 V53 核对 V52 表在最小权限下的运行账号读写，
  用发布候选镜像隔离验证终态已提交、Outbox 尚未提交时重启补齐，库存释放和通知各完成一次。
  日常以新建验收订单抽查，五笔归档不重放。
- [x] 用同一份 PowerShell 7 入口复用现有模块测试、快速回归和专项。打通从订单号、命令 ID、事件 ID
  到相应服务日志和积压监控的查询方式。按 [现有验证体系](VERIFICATION-SYSTEM.md) 留存结果。

P3 已隔离验收并完成日常发布。[验收与发布记录](IMPLEMENTED.md#211-p3-运行监控与订单取消恢复)

#### P4 M3 收尾与旧源清理

- [x] 隔离验收履约候选版和带新增发货的版本化回退，随后将同一候选镜像发布到日常 Docker。
- [x] 定向归档并恢复验证旧源，确认无运行依赖后清理。日常九库与五笔历史归档已复核。见[P4 验收记录](architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#29-2026-11-20-p4-日常发布和旧源清理)。

#### 范围和推进规则

- P0–P3 按批次连续推进。已授权批次内的常规实现、隔离验收、可恢复故障修复、文档更新和提交自行完成。
  日常部署与推送按本轮授权范围执行。需要改变业务范围、清理日常数据、切换所有权或选择维护窗口时提出决策。
- 每批先固定 Git、运行镜像与库版本，再在隔离 Docker 用真实 MySQL 和浏览器复验。
  涉及数据库时先迁移、再验证最小权限和版本兼容，随后使用同一候选镜像发布并进行日常抽检。
  手写 SQL 放 Mapper XML，验收使用现有 PowerShell 7 入口。细分提交采用中文。
- 批次结束汇总验收用例、测试结果、日常发布状态、镜像标签、提交号及未解决项。
  `TODO.md` 跟踪任务，`IMPLEMENTED.md` 保存具体证据，`PROJECT-STATUS.md` 更新运行快照。
  有阻塞时记录复现与影响，批内可独立的任务继续推进。
- 运费、售后、促销等后续能力按第 2.5 节排期。商品收藏等新增体验在基础闭环验收后评估。
  真实渠道接入、PostgreSQL 与高可用按各自的业务与环境条件另行立项。

### 2.5 缺失能力与服务拆分顺序

日常 Docker 已运行商城及六个内部服务：目录、库存、订单、支付编排、履约和通知。
支付、物流模拟器代表外部平台。登录、收货地址、购物车、评价、调度以及物流桥接和轨迹投影仍由商城模块持有。
新增能力先明确状态归属、读写入口和测试边界，再根据业务负载和部署需求决定是否独立。

| 能力 | 当前范围 | 提前准备与建设顺序 |
| --- | --- | --- |
| 售后申请、退货和部分退款 | 未发货整单退款、按订单行部分退款和验收后入库已交付 | P0 确定售后单与订单行关联、可退数量和金额、退回签收及入库条件。基础闭环后先做按订单行退货退款，先在商城内独立建模，再评估售后服务。 |
| 运费与仓配规则 | 已支持填写运单、查询和更正物流信息 | P0 明确运费计价、结算快照与退运费规则；P2 日常验收通过后先加入最小运费模板。仓库选路、多包裹和拆单待业务场景明确后扩展。 |
| 定价、促销和优惠券 | 现有目录售价和下单价格快照 | P0 明确商品原价、优惠、运费与实付的快照边界。基础闭环后先做一种优惠，按订单行记录分摊，覆盖优惠资格、并发核销、取消返还和退款上限。先做商城模块。 |
| 物流接入 | 已交付正向物流和首版退货运单跟踪。运单桥接、回调治理和轨迹投影由商城持有，渠道运单使用现有物流模拟器。 | 需要第二个承运商或独立管理回调容量时，把桥接、回调 Inbox、运单和轨迹作为首个独立服务候选。 |
| 商家入驻与结算 | 已有商家角色、商品归属和履约入口 | 若项目范围包含多商户平台，先明确商家账号、店铺、归属及账期。订单行保留商家归属；商家账单、佣金和打款单独建模，先对账再评估独立服务。 |
| 商品搜索和推荐 | 已发布商品全文搜索、复合筛选和可重建索引 | 按容量需求评估独立扩容，推荐另行排期。商品售价和可售状态仍由各自权威服务核对。 |

P0 在设计记录中冻结四项业务约束，P1 的结算改动和 P2 的退款与履约验收直接使用：

- **金额与版本**。下单保存实际计价快照，明确商品金额、优惠、运费和实付的关系。
  退款按原订单的实付和累计已处理金额核对，商品当前价格只用于再次购买和新结算。
- **订单行与归属**。确定跨商家购物车的结算边界。售后、出库和退款保留稳定的订单行标识及商家归属，
  已购商品和商家信息使用下单事实。
- **所有权与事件**。售后持有申请和处理进度，订单持有交易事实，支付编排持有资金结果，
  库存持有实际退货入库。跨服务用现有命令、Outbox/Inbox 和幂等标识交接，退款与入库各按业务凭证确认。
- **权限与审计**。顾客、商家和管理员按各自职责访问。价格重算、退款、促销核销与承运商回调保留
  可关联订单、操作人及请求的审计字段。

身份、购物车、评价和调度目前继续保留为商城模块。多个应用共用登录时再评估统一身份中心，
多端入口需要独立治理时再评估网关。现有 Nginx、Prometheus、Alertmanager、Grafana 和 Tempo
继续支撑当前单机拓扑。数据库迁移和高可用按专题计划排期。拆分遵循
[微服务演进计划](architecture/MICROSERVICE-EVOLUTION-PLAN.md)，一次切换一个数据所有权。

#### 后续实施批次

**基础主线。** P0 先确定金额快照、订单行、商家归属及历史订单的数据迁移规则。
旧数据迁移后走统一业务路径，过渡兼容按[代码规范](CODE-STYLE.md#7-临时兼容与最终验收)退出。P1、P2 在现有商城闭环中核对这些规则。
P2 日常验收通过后，按下表交付；各阶段独立验收和发布。

| 顺序 | 计划交付 | 依赖与验收 |
| --- | --- | --- |
| 1. 结算计价与运费（已发布） | 建立统一计价和订单金额快照，再提供最小运费模板。记录商品金额、优惠分摊、运费和实付，首版普通订单优惠为零。见[发布验收](IMPLEMENTED.md#213-统一结算计价与最小运费模板)。 | 地址或购物车变动后重新计算，价格变动要求顾客重新确认。新单能支付，历史单仍按原金额查询和退款。 |
| 2. 按订单行售后与部分退款（已发布） | 先做申请、审核、部分退款与资金对账；需要退货时由签收或验收结果触发后续动作，核对库存回补和退款。首版跑通后再排换货与售后工单。[发布验收](IMPLEMENTED.md#214-按订单行售后与部分退款)。 | 读取下单金额快照，覆盖部分数量、累计退款上限、运费处理、越权、重复退款和重复入库。用有优惠分摊的隔离订单提前验证退款计算。 |
| 3. 促销与优惠券（已发布） | 顾客领券、单商家固定金额满减、结算试算、按行分摊、并发锁券和付款核销。[功能说明](features/CHECKOUT-COUPONS.md) | 同一候选版本通过并发领券与锁券、取消返还、超时、跨库中断、支付竞态、部分退款和页面验收。[发布验收](IMPLEMENTED.md#215-单商家订单满减券) |

**可并行和按条件启动的能力。**

| 能力 | 启动条件与验收 |
| --- | --- |
| 物流接入（首版退货跟踪已发布） | 复用模拟器，绑定、回调重放、乱序、跨库中断、存量运单查询及验收后退款回补已核对。[验收记录](IMPLEMENTED.md#216-首版退货运单跟踪) 接入第二家承运商或出现独立扩容需求后再评审拆分。 |
| 多包裹、多仓与拆单 | 明确实际履约场景后再做。验证拆分后商品数量、运费、库存预占、物流和退款仍能按订单行对清。 |
| 商家入驻与结算 | 多商户范围明确后可先做店铺归属。应结金额、佣金和打款在支付与退款事实稳定后建设，逐笔核对收入、退款和应结金额。 |
| 商品搜索与推荐 | 全文检索、组合筛选和索引重建已验收。后续按数据量和吞吐评估扩容，推荐独立评估。 |

P3 的监控和取消恢复按原计划交付。履约专项及旧源归档、备份恢复仍按 P4 收尾，与上表业务实施的依赖分别管理。

#### 商家经营与后续功能五章

前三章依次实施，每章独立进行隔离验收、日常 Docker 发布、文档更新及提交。第 4、5 章按业务条件排期。

| 章节 | 首版与实施顺序 | 隔离与日常验收 |
| --- | --- | --- |
| 1. 最小商家入驻、店铺与归属（已发布） | 一账号一店铺、存量回填、资料和审核、公开店铺、商品草稿与库存快照，订单沿用单商家边界。 | 隔离验证交易归属、越权、跨店结算及停用后存量退款。日常验收订单 712。[验收](IMPLEMENTED.md#217-商家入驻与店铺归属) |
| 2. 应结账本、账单与佣金（已发布） | 成交行实付入账、冻结佣金、暂缓活跃售后、跨期退款冲正与账单明细。 | 隔离和日常账单均逐笔核对，覆盖并发出账和中断重跑。[验收](IMPLEMENTED.md#218-商家应结账本与佣金) |
| 3. 模拟商家打款（已发布） | 冻结账单驱动、独立出款 API 和账本、后台双菜单、签名回调及主动查询。 | 隔离验证并发、失败重试、渠道提交后中断及重复回调。日常专用账单出款一次并核对金额。[验收](IMPLEMENTED.md#219-模拟商家打款) |
| 4. 单仓多包裹（已发布） | 单商家单仓、按订单行分配包裹，顾客与商家查看独立运单和轨迹，支持未发数量退款与收货后部分退货。 | 隔离和日常通过同一脚本。日常订单 716 完成 1+2 发货。[功能说明](features/ORDERS-FULFILLMENT.md#单仓多包裹) · [验收记录](architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#30-单仓多包裹首版验收) |
| 5. 商品全文搜索与可重建索引（已发布） | 中文多词匹配、店铺与价格区间筛选、目录事件增量和代次重建，顾客入口已切换。 | 隔离覆盖暂停消费、旧事件重放和空索引重建。日常订单 719 核对成交单价 95.00 元。[验收记录](architecture/CATALOG-SERVICE-EVOLUTION-PLAN.md#6-商品全文搜索与索引重建验收) |

第 4 章两仓拆履约首版已发布。日常订单 720 同一订单行由两仓各发 1 件，订单 721 验证第二仓未发取消、
默认仓已发退货均回补原仓并按快照退款。[功能说明](features/ORDERS-FULFILLMENT.md#两仓拆履约) ·
[验收记录](architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#31-两仓拆履约首版验收)

仓库独立实发、短发、退货实收与质检和售后异常工单均已发布。工单承接仓库短收、拒收和质检不通过，
已验证补证、按合格件数退款回补及拒绝结案。[工单验收](architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#34-售后异常工单验收)

- 同一商家保留一张顾客订单、一次支付和一份计价快照。按仓拆履约任务，每个任务独立出库和绑定运单。
- 能单仓发齐时优先单仓，同优先级按稳定仓库编号选路。单仓发不齐时按同一顺序分配。同一商品两仓各有
  1 件时，各预占 1 件。总量不足或任一分配失败时，整单预占失败并释放已取得的预占。
- 订单行净额、优惠和原运费以结算快照为准。仓间对账按行分配数量分摊，最后一个分配项吸收舍入尾差。
  仓间分摊合计须等于原金额。部分发货后未发件退款保留原运费。
- 出库恢复沿用原仓、原包裹和原凭证。未发取消或退款只释放原仓对应的数量，已发退货经实物验收后
  回补原出库仓。默认仓迁移须逐商品核对可售、预占、已售和未完结履约。

第 5 章开发前的查询基线来自日常门户只读探测。`GET /api/products?lang=zh-CN` 返回 6 件商品，
加 `q=键盘` 后仍返回相同的 6 件。门户在客户端对名称、简介和描述做子串过滤，支持价格、评分排序。
本机串行 20 次热请求的响应时间约为 P50 25 ms、P95 32 ms，此样本用于当前查询链路复测。

| 查询样例 | 准备的验收数据与预期 |
| --- | --- |
| `键盘` | 日常目录商品 2「机械键盘」可作为字面匹配样例。 |
| `无线 键盘` | 「无线机械键盘」命中，「有线键盘」和「无线耳机」排除。空白分隔的词均须匹配，可分别出现在名称、简介或描述。 |
| `键盘` + 店铺 + 价格区间 | 两个店铺各准备一件键盘，价格上下限均包含边界值。只返回指定店铺、区间内的上架商品。 |
| 上下架与陈旧索引结果 | 同一商品下架后退出搜索结果，乱序旧事件不能使它重新可见。旧售价经详情和结算按当前业务事实核对。 |

首版默认按名称命中优先、全文相关性和商品编号排序，保留价格排序与评分排序，分页同分以商品编号打破平局。
店铺可见性以商城当前店铺状态为准。日常小样本用于功能复验，容量目标待单独准备可复测数据后确定。

每章用同一份 PowerShell 7 脚本覆盖隔离和日常模式。日常使用隔离通过的候选镜像，核对既有业务数据和五笔历史归档，旧预占命令保持关闭。

### 2.6 外部系统模拟器计划

现有支付和物流模拟器继续复用。下列项目随对应业务启动，排序按业务依赖确定。
模拟器只实现外部系统的协议、响应和回执，用于开发和隔离验收。业务规则和权威数据由项目内对应模块或服务维护。

| 待办 | 启动条件 | 首版交付与验收 |
| --- | --- | --- |
| [x] 短信与邮件模拟供应商 | 共用一个模拟服务和双菜单后台。 | 完成签名受理与查询、投递账本、限流、超时和送达失败。[验收记录](IMPLEMENTED.md#223-短信与邮件模拟平台) |
| [x] 退款成功和售后工单站外通知 | 退款成功通知顾客，新建异常工单通知商家。 | 隔离验证事件去重、渠道独立送达和超时查回，日常 Docker 验证两类通知双渠道送达。[验收记录](IMPLEMENTED.md#224-退款与工单站外通知) |
| [x] 仓库系统（WMS）出库实发与短发 | 两仓履约发布后接入独立仓库出库回执。 | 两仓各实发 1 件、短发后取消退款、拒绝出库、重复回执和重启恢复已验收。[发布记录](architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#32-wms-出库实发与短发验收) |
| [x] 仓库系统（WMS）退货实收与质检 | 出库回执、幂等和查询对账已通过日常验收。 | 已验收原包裹与原仓绑定、质检后商家验收、原仓回补和差异待复核。[发布记录](architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#33-wms-退货实收与质检验收) |
| [x] 商家打款渠道 | 现有支付模拟平台已交付独立模拟打款。 | 完成受理、成功、失败、查询和对账。隔离和日常均核对一笔成功及原账单金额。[验收](IMPLEMENTED.md#219-模拟商家打款) |
| [ ] 电子发票平台 | 有电子发票业务需求时，结合订单实付和退款事实实施。 | 支持开票受理、异步结果、失败重试及退款后的红冲。核对发票与订单、退款记录，验证重复请求和回执不会重复开票或红冲。 |

模拟平台按需预留 `service/`（服务端）和 `web/`（管理后台 UI），沿用现有支付模拟器的目录结构。

| 平台 | 服务端 | 管理后台 UI |
| --- | --- | --- |
| 物流 | [logistics-platform/service](../simulators/logistics-platform/service/) | [logistics-platform/web](../simulators/logistics-platform/web/)（预留） |
| 短信与邮件 | [messaging-platform/service](../simulators/messaging-platform/service/) | [messaging-platform/web](../simulators/messaging-platform/web/) |
| 仓库系统 | [warehouse-platform/service](../simulators/warehouse-platform/service/) | [warehouse-platform/web](../simulators/warehouse-platform/web/) |
| 电子发票 | [invoice-platform/service](../simulators/invoice-platform/service/) | [invoice-platform/web](../simulators/invoice-platform/web/) |
| 商家打款 | 复用 [payment-platform/service](../simulators/payment-platform/service/) | 复用 [payment-platform/web](../simulators/payment-platform/web/) |

#### 支付模拟平台的收款与打款

收款退款和商家打款共用一个 `payment-platform` 模拟平台，沿用同一个服务端部署与管理后台。
商家结算已接模拟出款，渠道资金结果由独立打款账本持有。

- **收款与退款**沿用顾客订单链路，维护收款和退款自己的接口、状态机及账本。
- **商家打款**按商家结算单发起，拥有独立的打款发起和结果查询接口、出款状态机及账本。
  按打款单号保证幂等，验收重复请求、回执和对账。
- 管理后台分为 **「收款与退款」** 和 **「商家打款」** 两个菜单，分别查询各自的交易、状态和账本。

#### 正式支付后台的运营统计

- [ ] 首版交付只读运营报表，包含创建交易趋势、成功支付笔数、实收、退款、净收、渠道对比和状态分布。
  正式统计由支付编排服务在支付库侧聚合，前端只查询汇总结果，可复用模拟器分析页的布局。
  模拟渠道账本用于验收对照。商家结算、佣金和打款由各自账本核对。

**账本统计口径**

| 指标 | 事实来源与计数口径 |
| --- | --- |
| 创建支付笔数 | 按 `payment_records.created_at` 归入日期，每个支付单计一次。同一订单切换渠道产生不同支付单时分别计入。 |
| 成功支付笔数、实收 | 按支付账本确认成功的 `paid_at` 归入日期，每个成功支付单计一次，实收取该支付单原金额。后续退款独立入账。 |
| 成功退款笔数、退款金额 | 按 `refund_records.succeeded_at` 归入日期，每笔成功退款按退款单号计一次，归到原支付的渠道与商家。同一支付的多笔部分退款分别计算，待处理和失败退款不计入金额。 |
| 净收 | 同一时间桶、授权范围、渠道和币种内的实收减成功退款额。跨日退款计入退款成功日，当日净收允许为负。 |
| 状态分布 | 对所选期间创建的支付单按查询时的支付状态分组，页面标明「当前状态」。 |

- 按商家、渠道、币种和日期筛选。用户按报表时区选择日期，默认 `Asia/Shanghai`。服务端按该时区转换查询起点和
  结束日期次日零点，使用包含起点、排除终点的 UTC 范围，并用相同时区划分日桶。
- 同一次报表查询使用同一账本快照。支付与退款分别聚合，再按相同维度合并，避免多笔退款放大实收。金额使用定点十进制，
  不跨币种相加。所选币种无记录时显示零值和空结果提示。成功记录缺少有效成功时间时列入对账异常。

**商家归属与授权**

- 归属以订单服务的订单行商家快照为准。当前结算按单商家下单。支付账本的 `merchant_account_id` 用于识别
  渠道支付账户，正式报表通过 `order_id` 关联已核验的商城商家归属，退款沿用原支付的归属。
- 新支付建立后保存由订单契约核验的归属，历史记录按订单分批核对、可重跑回填。在支付侧查询已核验的归属和金额，
  订单服务继续持有订单数据。归属缺失或冲突的记录单列对账，仅有平台全量权限的账号可查看未归属汇总。
- 平台账号须有支付报表权限，查询限制在其授权商家范围。商家账号由服务端根据当前登录身份和店铺归属取范围。
  查询参数只能缩小范围，指定越权商家返回 403。无报表权限的账号无法访问接口。
  趋势、总览、渠道对比和状态分布共用同一套范围校验。

**分批实施与验收**

| 批次 | 交付和进入下一批的条件 |
| --- | --- |
| 1. 账本口径与汇总接口（隔离验收已完成） | 内部查询通过 MySQL 8.4 逐笔对账，覆盖重复回执、多次支付和部分退款、跨日负净收、多币种、时区边界和空结果。[首批隔离验收](IMPLEMENTED.md#225-正式支付运营统计首批隔离验收) |
| 2. 商家归属与权限（隔离验收已完成） | 共用支付宝渠道账户的两商家逐项核对日、渠道和币种汇总，验证新增归属、历史回填重跑、全量及限定平台权限、商家本人、越权过滤、既有会话撤权与未归属账本隔离。[第二批隔离验收](IMPLEMENTED.md#226-正式支付运营统计第二批商家归属与权限) |
| 3. 运营页面与发布验收 | 接入日期、时区、渠道、币种及授权范围内的商家筛选。浏览器核对趋势、卡片、状态分布、跨日退款、空结果和加载失败。隔离验收通过后用同一候选镜像发布日常 Docker，以新建验收订单和账本逐笔核对，并记录版本、验收证据与回退方式。 |

运维告警继续复用现有 [`alert-webhook`](ALERTING.md)。库存预占、运费计价、优惠核销、售后审核和商家应结金额
由业务代码实现，使用隔离数据验收。

## 3. 必须先固定的业务语义

### 3.1 目标订单状态机

```text
PENDING_PAYMENT
    ├─ 支付成功 ─> PAID
    └─ 客户取消/整单超时 ─> CANCELLING ─> CANCELLED

PAID
    ├─ 客户退款 ─> REFUNDING ─> REFUNDED
    └─ 商家确认 ─> CONFIRMED ─> SHIPPED ─> RECEIVED ─> COMPLETED
```

退款失败时恢复到退款前的 `PAID` 或 `CONFIRMED`，不能改写原支付成功事实。

### 3.2 三个不可混淆的边界

1. **支付尝试过期 ≠ 整单超时**：单次 `expires_at` 到期只使该尝试进入 `EXPIRED`；整单 `payment_due_at` 未到时仍可换渠道重试。
2. **物流签收 ≠ 客户收货**：`DELIVERED` 只代表物流渠道认为送达；客户可手动确认，或等待观察期后自动进入 `RECEIVED`。
3. **`RECEIVED` ≠ `COMPLETED`**：已收货后仍有观察期；观察期结束才进入稳定终态。`COMPLETED` 不依赖商品已评价。

## 4. 开工前需确认的决策

| 决策 | 建议默认值 |
| --- | --- |
| 整单支付超时 | 30 分钟 |
| 物流签收后自动收货 | 7 天 |
| 收货后自动完成 | 7 天 |
| 默认轻量方案 | Database 主触发 + Database 对账 |
| 生产学习推荐 | RabbitMQ 主触发 + Database 对账 |
| 高级实验 | RocketMQ 主触发 + Database 对账 |
| 已取消后意外支付成功 | 恢复资金事实，同事务创建自动退款单并进入 `REFUNDING` |
| 管理员策略入口 | Angular `ADMIN` 端 |

以上均应可配置；验收环境使用秒级时长。

## 5. 阶段 1：订单生命周期基础

- [x] 新增 `CANCELLING`、`CANCELLED`、`COMPLETED`。
- [x] `shop_orders` 新增 `payment_due_at`、`cancelled_at`、`cancel_reason`、`received_at`、`completed_at`、`logistics_delivered_at`。
- [x] 新订单写入 `payment_due_at = created_at + payment-timeout`；历史订单按 `created_at + 30m` 回填。
- [x] 新增 `payment-timeout`、`auto-receive-after-delivery`、`auto-complete-after-receipt` 配置。
- [x] 订单事件保留操作人、触发源、旧/新状态、原因和任务 ID。
- [x] 新增 `docs/ORDER-LIFECYCLE.md`，统一订单、支付、退款、物流和任务状态。
- [x] 订单响应新增 `availableActions`；写接口仍在后端重新验证。
- [x] Flyway 空库创建、旧数据升级和非法状态转换测试通过；Windows/容器化 Bash 已使用隔离 MySQL 验证 V1–V24 与 V16→V24，商城 25 项测试包含订单非法转换。

## 6. 阶段 2：未支付订单关闭与可插拔触发

先实现唯一正确的关单 Handler，再接三种触发器；不得各写一套业务逻辑。

### 6.1 客户主动取消

- [x] 新增 `POST /api/purchase-orders/{orderId}/cancel`，仅订单所属客户可取消 `PENDING_PAYMENT`。
- [x] 锁定订单，执行 `PENDING_PAYMENT -> CANCELLING`，关闭所有 `PENDING` 渠道交易。
- [x] 全部关闭后执行 `CANCELLING -> CANCELLED`，写入时间、原因和订单事件。
- [x] 单体阶段曾将取消终态与 `ORDER_CANCELLED` Outbox 同事务写入。切流后订单终态和商城 Outbox 分属两库，跨服务中断恢复列入第 13.25 节关联的架构清单。
- [x] 实现订单取消 Outbox 的库存释放、客户通知等下游消费者；Kafka 独立消费组与消费记录保证重复消息不重复释放或通知。
- [x] 无支付记录或全部已 `FAILED/CLOSED/EXPIRED` 时可直接完成取消。
- [x] 存在成功支付时普通取消不会覆盖支付事实，订单保持/恢复 `PAID`；前端已通过独立退款动作处理已支付订单。
- [x] 重复请求幂等返回原结果，不重复改变已关闭渠道交易。

### 6.2 支付/取消并发

- [x] 渠道关闭先成功时进入 `CANCELLED`。
- [x] 支付通知先落库时支付优先，订单进入 `PAID`，普通取消不覆盖。
- [x] `CANCELLED` 后意外成功通知不忽略：恢复支付事实，使用稳定幂等键创建自动退款单，转入 `REFUNDING` 并复用退款对账。
- [x] 使用两个真实线程和受控门闩复现“取消已进入 `CANCELLING`、支付成功回调随后到达”；数据库锁与条件更新保证最终为 `PAID/SUCCESS`，且不生成 `ORDER_CANCELLED`。
- [x] Docker MySQL + 真实模拟支付平台的长时间随机化竞态压测：Windows 120.6 秒/95 轮、容器化 Bash 121 秒/97 轮，两类合法终态均覆盖且 MySQL 矛盾记录为 0。

### 6.3 统一定时任务领域

- [x] 首批任务类型：`PAYMENT_TIMEOUT/AUTO_RECEIVE/AUTO_COMPLETE/REFUND_RECONCILIATION/PAYMENT_RECONCILIATION/LOGISTICS_RECONCILIATION/NOTIFICATION_RETRY/REVIEW_REMINDER/INVENTORY_RELEASE/AFTER_SALE_DEADLINE`；类型由管理员启停和配置，Handler 由代码安全注册。
- [x] 任务状态：`CREATED/SCHEDULED/PROCESSING/SUCCEEDED/FAILED/CANCELLED`。
- [x] 建立任务表，包含唯一业务键、版本、租约、尝试次数、最后错误、下次执行时间。
- [x] 保存主触发、对账、宽限期、租约、重试和配置版本快照。
- [x] 同一任务类型+业务键只允许一条任务，调用方使用稳定业务键保证聚合幂等。
- [x] Database 主触发与 Database 对账调用同一 `ScheduledTaskExecutor` 和 Handler；后续 MQ 也必须复用该入口。
- [x] `PAYMENT_TIMEOUT` 通过同一取消服务和 Handler 关单，禁止直接批量 SQL 改状态；事件名统一为取消阶段事件并携带任务来源。

### 6.4 三种主触发

- [x] **Database**：ShedLock + 分批领取，支持周期、批次、原子领取、租约恢复和最大尝试次数。
- [x] **RabbitMQ**：持久化分桶 TTL + DLX、Publisher Confirm/Return、有限退避重试、DLQ、早到重新分桶、数据库原子抢占和重启补发。
- [x] **RocketMQ**：5.x gRPC 客户端、原生绝对投递时间、Broker 回执、发布失败重试、消费端重试/死信、早到重投和重启后未发布任务恢复。
- [x] RabbitMQ/RocketMQ 消息只携带 `taskId/taskType/dueAt/strategySnapshot`，消费时从数据库重读任务和业务事实。

### 6.5 Database 对账和管理配置

- [x] `reconciliation.enabled` 是独立补偿开关，不是第四种主策略。
- [x] 只处理超过 `dueAt + gracePeriod` 仍未完成的任务，保留原策略快照和补偿来源。
- [x] 通过 `@ConfigurationProperties` 完整绑定并校验全部通用策略、RabbitMQ 和 RocketMQ 适配器字段，并支持环境变量覆盖。
- [x] 管理员改全局默认只影响新任务；旧任务继续使用快照。
- [x] 管理员 API 与 Angular 管理页可新增/覆盖/恢复策略，查询、重试、取消任务，并查看策略审计与任务事件。
- [x] 验证三种主触发 × 对账关/开的六种组合；RocketMQ 开启对账时已实测 Broker 停机、发布失败记录和 `DATABASE_RECONCILIATION` 兜底。

### 6.6 阶段门禁

- [x] 主动取消无支付、有待支付、重复请求均已通过 Windows 与容器化 Bash 专项验收。
- [x] 单次支付过期后，整单未超时时仍可重试。
- [x] 整单超时时必须先关闭待支付交易，再进入 `CANCELLED`。
- [x] 支付/取消真实竞态与长时间随机化浸泡压测不造成错账。
- [x] RabbitMQ/RocketMQ 重复、迟到旧消息与未确认重投不重复执行业务副作用。
- [x] 支付/取消锁等待超时专项：Windows 与容器化 Bash 均验证超时返回 `503/DATABASE_CONCURRENCY_RETRY` 和 `Retry-After`，事务回滚无中间事实，释放锁后重试成功。
- [x] 支付/取消关键事务窗口进程崩溃恢复专项：双平台 SIGKILL 覆盖 `CANCELLING` 恢复、渠道已关/本地未提交、回调事务回滚和迟到成功回调。

## 7. 阶段 3：独立模拟物流

### 7.1 服务、数据与状态

- [x] 新建独立 `logistics-simulator-service`、数据库和 Docker 服务；首版不做物流管理 UI。
- [x] 物流平台保存渠道运单号、商城请求号、承运商、单号、状态、轨迹、节点时间、回调和幂等信息。
- [x] 商城保存 `shipment_records`、`shipment_events`、当前投影、发货/签收时间、通知 ID 和回调摘要。
- [x] 模拟物流不得直接连接商城数据库。
- [x] 正常流为 `CREATED -> PICKED_UP -> IN_TRANSIT -> AT_STATION -> OUT_FOR_DELIVERY -> DELIVERED`；非终态可进入 `EXCEPTION（异常件）`，只能通过明确 `RECOVERY（异常恢复）` 回到异常前状态。
- [x] 自动轨迹可启停，扫描周期、每步间隔和批次可配置；默认关闭，测试环境可压缩为秒级，每节点有稳定事件 ID。

### 7.2 签名接口、回调与对账

- [x] `POST /api/channel/shipments`。
- [x] `GET /api/channel/shipments?merchantShipmentNo=...`。
- [x] `PUT /api/channel/shipments/{merchantShipmentNo}` 和 `POST /api/channel/shipments/{merchantShipmentNo}/advance`。
- [x] 回调 `POST /api/logistics-notifications/callback`。
- [x] 请求实现 App ID、时闳、Nonce、摘要、HMAC 和防重放；回调使用独立 HMAC 和通知 ID 去重。
- [x] 物流事件与 Callback Outbox 同事务落库，支持领取租约、HTTP 超时、失败重试、最大次数和重启接管。
- [x] 商城签名查询权威运单并主动对账，永久丢失回调以同一事件 ID 补齐，审计记录 `RECONCILED`。
- [x] `DEAD（停止自动重试）` 管理员查询、必填原因人工重放、操作人/原因审计、重复重放冲突与商城幂等。
- [x] 回调 Outbox 积压、投递结果、人工重放和主动对账 Prometheus 指标。
- [x] 覆盖暂时不可达、永久丢失、重复、迟到可变字段、篡改、超时和主动对账。
- [x] 物流回调高并发乱序浸泡压测：Windows 120 秒/12 并发/1320 条，容器化 Bash 120 秒/12 并发/2652 条；状态不倒退，不变字段不被旧回调覆盖。

### 7.3 商城投影与客户查询

- [x] 商家发货时创建运单，成功后再完成商城发货投影。
- [x] 新增 `GET /api/purchase-orders/{orderId}/logistics`，严格校验客户归属。
- [x] `SHIPPED/RECEIVED/COMPLETED` 均返回承运商、单号、当前状态和完整轨迹。
- [x] 已通过建单、手动/自动六节点推进、`EXCEPTION -> RECOVERY`、签名/重放拒绝、回调投影、客户查询、Outbox 重试和对账恢复。

## 8. 阶段 4：收货与完成

- [x] 客户手动 `SHIPPED（已发货） -> RECEIVED（已收货）` 时写入 `received_at`，取消 `AUTO_RECEIVE（自动确认收货）`，幂等创建 `AUTO_COMPLETE（自动完成订单）`。
- [x] 首次收到 `DELIVERED（已送达）` 时写入 `logistics_delivered_at`，创建延时 `AUTO_RECEIVE`。
- [x] `AUTO_RECEIVE` 执行时重新锁定订单并检查仍为 `SHIPPED`，再写入 `received_at`。
- [x] 手动/自动路径使用行锁、条件更新和唯一任务键；两线程同时抢占已验证只产生一次收货转换和一个后续任务。
- [x] 创建 `dueAt = received_at + auto-complete-after-receipt` 的 `AUTO_COMPLETE`。
- [x] 执行时只允许 `RECEIVED -> COMPLETED（已完成）`，写入 `completed_at`。
- [x] `COMPLETED` 后仍可查订单、支付、退款、物流，且未评价商品仍可一次性评价。
- [x] `COMPLETED` 后不提供取消、修改物流、确认收货和“未发货退款”动作，写接口仍会独立校验状态。
- [x] 已通过手动收货、任务重放、受控并发抢占、Docker 自动收货/完成，以及“业务已提交但任务未确认”窗口中的后端重启/租约恢复验收。

## 9. 阶段 5：客户页面必要功能

信息顺序统一为：订单头部 → 商品 → 收货地址 → 当前操作 → 物流 → 支付 → 退款 → 时间线 → 评价。

### 9.1 `availableActions`

- [x] 初始动作包含 `PAY`、`REFRESH_PAYMENT`、`CANCEL_ORDER`、`REQUEST_REFUND`、`VIEW_PAYMENT`、`VIEW_REFUND`、`VIEW_LOGISTICS`、`COPY_TRACKING_NUMBER`、`CONFIRM_RECEIPT`、`REVIEW_ITEM`。
- [x] Angular 的主要订单操作按 `availableActions` 展示；后端写接口仍独立验证权限和状态。

### 9.2 状态与展示

| 状态 | 必须展示 |
| --- | --- |
| `PENDING_PAYMENT` | 支付、刷新、取消、支付尝试 |
| `CANCELLING` | 正在关闭交易，禁用支付和重复取消 |
| `CANCELLED` | 取消原因、时间、历史支付尝试 |
| `PAID/CONFIRMED` | 支付记录、退款动作、商家确认信息 |
| `SHIPPED` | 支付、展开的物流、复制单号、确认收货 |
| `RECEIVED` | 支付、完整物流、收货时间、评价 |
| `COMPLETED` | 支付、完整物流、完成时间、评价 |
| `REFUNDING/REFUNDED` | 原支付、退款进度/结果、原因和金额 |

### 9.3 展示要求

- [x] 物流始终展开，显示承运商、单号、复制、状态和按时间排序的轨迹。
- [x] `RECEIVED/COMPLETED` 不能隐藏物流或支付。
- [x] 退款不覆盖支付卡片，作为独立资金事实展示。
- [x] 按状态展示支付截止、取消、签收、收货和完成时间。
- [x] 语言切换不闪回旧订单/空数据，不直接显示后端枚举英文值。
- [x] 移动端无水平滚动；已在 Edge Pixel 9 Pro 427×952 设备模式验收。

## 10. 阶段 6：验收、故障实验和文档

### 10.1 业务与故障验收

- [x] 主动取消无支付/有待支付订单、重复取消、支付/取消并发；Windows 与容器化 Bash 脚本已覆盖 `PAID/SUCCESS` 和 `CANCELLED/CLOSED` 两种竞态终态，并完成 95/97 轮两分钟浸泡实验。
- [x] 单次支付过期仍可重试，整单超时自动关闭；Windows 与容器化 Bash 已验证换渠道重试、渠道关闭和 Database 任务单次执行。
- [x] 六种主触发/对账组合。
- [x] RabbitMQ/RocketMQ 未投递与 Database 补偿。
- [x] RabbitMQ/RocketMQ 重复消息、迟到旧消息和毒消息专项：重放已完成任务消息只增加 ignored 指标，任务领取/完成仍各一次；非法 JSON 分别进入 RabbitMQ Dead Queue 和 RocketMQ `%DLQ%ConsumerGroup`。
- [x] 消费者已取得消息但尚未确认时进程退出、恢复后的重新投递专项：Windows Docker 与容器化 Bash 已分别验证 RabbitMQ/RocketMQ 任务 `239/240` 和 `241/242`，崩溃前后无新增发布，且领取/完成各一次。
- [x] 物流推进、回调丢失、重放、迟到可变字段、对账和越权查询已验收。长时间并发乱序压力验收结果见第 14 节的 2026-11-13 记录。
- [x] Windows Docker 已验证手动/自动收货、自动 `COMPLETED`，完成后仍可查支付/物流并评价；退款记录保留由现有响应契约覆盖。

### 10.2 双平台脚本和 Docker

- [x] M1 冻结时验收了 36 组双平台入口。当前使用一份 PowerShell 7 脚本和 `scripts/script-contracts.tsv` 参数清单。
- [x] 三种主策略可独立运行；RabbitMQ 和 RocketMQ 提供可选 Compose Profile，Database 默认模式不强制启动 MQ。
- [x] 空数据卷从 Flyway 到全链路验收通过。
- [x] 隔离旧库升级演练、当前持久卷服务/MQ 重启、任务恢复和历史一致性通过。
- [x] 脚本默认不清空现有数据卷；全新卷专项只在显式参数下删除其自身随机临时项目卷。

### 10.3 文档交付与完成门禁

- [x] `docs/ORDER-LIFECYCLE.md`、`docs/MOCK-LOGISTICS.md`、`IMPLEMENTED.md` 和 README 已同步自动收货/完成的实现边界与验收脚本。
- [x] 已写清六种配置、策略快照、切换边界、回滚、已有监控与待实现告警边界。
- [x] 订单、支付、退款、物流和任务已经快速回归、专项不变量和历史指纹审计，未发现矛盾状态。
- [x] 收货/完成已验证重试、重放、受控并发、Database 重启/租约恢复、RabbitMQ/RocketMQ 主触发故障恢复和双平台 120 秒/12 并发竞态浸泡。
- [x] 客户、商家、管理员和服务回调权限边界通过；快速入口含全角色矩阵，支付/物流专项含签名、伪造回调和越权拒绝。
- [x] Windows 与容器化 Bash 的 M1 专项和快速回归结果已对齐。

## 11. M1：可复测基线冻结与验证体系启动门禁

只有以下项目全部完成，才开始编写“整个开发生命周期的可复测验证体系”文档：

- [x] 完成支付/取消锁等待超时专项。
- [x] 完成支付/取消关键窗口进程崩溃恢复专项。
- [x] 完成物流高并发乱序压测。
- [x] 完成收货/完成的 MQ 故障与长时间高并发实验。
- [x] 全新空数据卷从 Flyway 到登录、下单、支付、履约、物流、收货、完成和评价的全链路通过。
- [x] 隔离 V16→V24 升级演练后，当前持久卷完成服务/MQ 重启、未完成任务恢复和历史数据一致性验收。
- [x] 完成 Windows/Bash 36 组主要脚本的同名、参数和语义对齐审计。
- [x] 确认所有默认脚本不清空现有数据卷，破坏性临时卷清理只能通过显式参数启用。
- [x] 建立可重复的 Windows/Bash 快速回归入口，并以 Git 标签 `m1-baseline-2026-11-13` 冻结 M1 基线。

M1 完成后依次完成了可复测验证体系文档、“退款区域/已收货/评价表单”视觉重做和 Prometheus Rule、Alertmanager/可替换 Webhook 告警集成；各阶段均独立验证后提交。

- [x] 已建立 [全开发生命周期可复测验证体系](VERIFICATION-SYSTEM.md)，统一 L0–L6 分层门禁、改动矩阵、数据安全边界、证据格式、失败处理和基线冻结清单。

## 12. M1 和验证体系文档之后再做的视觉与运维代办

- [x] 订单卡片改为“状态化紧凑摘要 + 当前主操作 + 可展开详情”；大屏固定对齐状态、金额与操作列，不重复显示可由订单状态推导的支付成功/已关闭；展开态支付信息扁平化、商品图失败回退且空历史区不渲染；桌面与 390 px 窄屏验收通过。
- [x] 客户通知面板改为中性紧凑菜单，正文独立滚动并使用细滚动条；支持关闭按钮、页面外点击、`Esc` 和选择通知后关闭，语言切换收敛为无障碍图标按钮。
- [x] 重做退款申请/回执、已收货/已完成状态和逐商品评价表单；Angular 25 项测试、生产构建、真实数据桌面与 390 px 窄屏验收通过。
- [x] 统一支付、退款、物流和订单时间线视觉。
- [x] 补齐骨架屏、空状态、错误状态和响应式布局。
- [x] 集成 Prometheus Alert Rule、Alertmanager 和可替换 Webhook 通知管道；10 条规则、三服务抓取、规则单测及 Windows/容器化 Bash Watchdog 实时投递均通过。

视觉改造和本地生产式告警管道均已完成。真实邮箱、短信、电话或值班平台凭据不提交到学习仓库，替换接收端和生产高可用边界见 [ALERTING.md](ALERTING.md)。本轮固定序列到此结束，停止新增业务代码并先汇报结果。

## 13. M2：代码质量与模块化单体治理

本节只跟踪跨模块工程质量，不混入新增业务功能，也不替代 `docs/architecture/` 下的架构待办。
详细审计基线和实施门禁见 [CODE-QUALITY-PLAN.md](CODE-QUALITY-PLAN.md)。

### 13.1 已完成批次

- [x] 完成三个 Java 服务的格式、实体注释、SQL 分布和质量门禁只读审计。
- [x] 修正支付退款实体主键映射并完成 9 项 Maven 测试及 Windows/容器化 Bash 真实退款专项回归。
- [x] 分批清理支付模拟服务的通配符导入和过长行，两项审计计数均已归零。
- [x] 完成物流模拟服务类型与格式治理，并复验乱序与 Outbox 恢复。
- [x] 建立商城服务 ArchUnit 模块依赖门禁，并移除用户 API 直接访问 Mapper 的越层依赖。
- [x] 分业务包治理商城服务的通配符导入和过长行；`identity`、`catalog`、`cart`、`customer`、
  `review`、`receipt`、`fulfillment`、`ordering`、`payment`、`refund`、`logistics`、`scheduling` 已完成，全商城业务源码计数已归零。
- [x] 通过新增 Flyway V24/V4/V5 迁移补充 16 张核心表和 133 个核心列注释，不改历史迁移。
- [x] 为三个 Java 服务接入与 IntelliJ IDEA 规则兼容的只检查格式与 Mapper 契约门禁，并纳入快速/完整验证矩阵。
- [x] 分模块治理 Angular 商城门户的历史 HTML/TypeScript/CSS 格式，并在正式镜像构建前执行 Prettier 只读检查。

### 13.2 已完成：支付模拟器 Web 格式与自动门禁

本批只治理 `simulators/payment-platform/web/`，不重做 UI、不改支付业务语义，不与 Angular 已完成批次混合。

- [x] 先做只读基线：统计 Vue/TypeScript/CSS/JSON 文件、压缩行、制表符和过长行，确认当前测试、构建与镜像入口。
- [x] 固定项目本地 Prettier 版本与配置，提供 `format:check` 只读入口和显式 `format:write` 写入入口。
- [x] 仅对本模块执行一次机械格式化，审查依赖锁文件及核心支付/收银台差异，不夹带业务重构。
- [x] 将 `format:check` 接入支付 Web 正式 Dockerfile，保证默认 Compose 构建可阻止未格式化代码。
- [x] 通过格式检查、Vitest、TypeScript/Vite 生产构建、正式 Docker 镜像构建、`14201/healthz` 和支付 Web 相关运行态验证。
- [x] 更新 `IMPLEMENTED.md`、`PROJECT-STATUS.md`、`CODE-QUALITY-PLAN.md` 和格式规范，独立提交并推送；不删除或重建数据卷。

### 13.3 M2 工程质量总验收与基线冻结

本节必须在 13.2 全部完成并独立提交推送后执行。本阶段只做门禁收口和基线冻结，
不继续格式类改造，不新增业务功能，不启动微服务拆分或 PostgreSQL 迁移。

- [x] 汇总三个 Java 服务、Angular 商城门户和支付模拟器 Web 的格式、Mapper 契约、模块边界和生产构建门禁。
- [x] 使用默认构建模式执行 Windows `verify-quick` 和 `verify-all`，不使用 `-SkipBuild` 冒充当前源码验收。
- [x] 执行容器化 Bash 质量门禁、脚本契约与快速/完整验证，记录与 Windows 的等价结果和平台差异。
- [x] 复验 Compose 配置、五个正式镜像、脚本默认非破坏性、主要健康检查和运行态服务名称。
- [x] 核对当前源码、迁移、脚本和实际验收证据与 `PROJECT-STATUS.md`、`IMPLEMENTED.md`、`TODO.md` 完全一致。
- [x] 确认工作区干净、本地 `main` 与远端 `origin/main` 一致，记录最终提交号。
- [x] 建立并推送 `m2-quality-baseline-2026-11-14` Git 标签；标签日期以实际验收日为准，不预先伪造。
- [x] 冻结后停止新增代码，先汇报 M2 完成范围、验收证据、遗留边界、提交和标签。

### 13.4 M2 冻结后的架构阶段顺序

以下是 M2 冻结后的架构层演进顺序，不属于当前业务待办，不得在实际验收前写入 `IMPLEMENTED.md`。
详细边界以 [微服务演进计划](architecture/MICROSERVICE-EVOLUTION-PLAN.md) 和
[数据库演进计划](architecture/DATABASE-EVOLUTION-PLAN.md) 为准。

1. **M3.1 微服务拆分前置条件审计**：盘点模块数据所有权、跨模块 Mapper/表依赖、应用接口、事件契约、
   Inbox、消费者契约测试和分布式追踪基线；本阶段不直接拆服务。
2. **M3.2 首个微服务试拆**：优先评估 `notification-service`，因其副作用边界清晰且允许最终一致；
   必须具备契约测试、故障恢复和可停止的一次性数据交接；切换完成后只保留独立服务实现。
3. **后续微服务候选顺序**：`catalog-service` → `inventory-service` → `order-service` →
   `payment-orchestration-service` → `fulfillment-service`；每次只拆一个边界并独立验收。
4. **M4 PostgreSQL 专项**：只有 M3 清单中的全部业务服务——`notification-service`、`catalog-service`、
   `inventory-service`、`order-service`、`payment-orchestration-service`、`fulfillment-service`——均完成单一
   所有权切换并逐一验收后才允许启动；若 M3 清单增加新的待拆服务，新服务也必须先完成。不与任何微服务
   拆分同时进行。启动前必须明确 JSONB、部分/表达式索引、
   Advisory Lock、`SKIP LOCKED` 或 PostgreSQL 锁模型等学习目标；迁移后重新执行支付/取消锁超时、
   崩溃恢复、物流乱序、RabbitMQ/RocketMQ 故障及收货/完成竞态等全部可靠性门禁。

### 13.5 M3.1 前置审计首批：代码结构基线治理

- [x] 将物流模拟器从根包平铺调整为 `api/application/domain/infrastructure`，并在基础设施层继续拆分
  `client/configuration/metrics/persistence/security`；根包只保留启动类。
- [x] 将 Mapper 持久化投影独立放入 `infrastructure/persistence/row`，同步 MyBatis namespace、
  `resultType` 和扫描路径，不改变数据库、事务、签名、Outbox 或状态机语义。
- [x] 为三个服务全部 256 个 Java 主源码/测试源建立“每个 `class` 声明后必须空一行”门禁；
  Windows 与容器化 Bash 质量审计均通过。
- [x] 物流正式镜像 Maven 打包、签名主链路、异常恢复、回调投影、Outbox DEAD 人工重放、RBAC、
  审计、幂等和指标专项通过；未删除或重建数据卷。
- [x] 完成 M3.1 的数据所有权、跨模块 Mapper/表依赖、应用接口、事件契约、Inbox、消费者契约测试和
  分布式追踪基线审计，并建立“现有债务可减少、不得未经评审增长”的 Maven 自动门禁。
- [x] 消除 `notification` 写路径对 InventoryMapper 的反向所有权，定义 V1 通知命令、notification 独立
  Inbox 和消费者契约 fixture；Windows/Bash 真实取消通知链路与重复消费幂等通过。
- [x] 为版本化通知命令增加进程内/Kafka 可替换传输、事务 Outbox、有限重试、本地 DEAD/人工重放、
  Kafka DLT、Broker 恢复和单体回退门禁；notification 查询只接收 customerId，V1 命令与 Kafka Header
  传播 correlation/causation/traceparent。
- [x] 定义 notification 影子投影核对窗口、连续通过与阻塞 Outbox 停止条件，并完成独立 MySQL Schema
  的 Flyway 迁移演练；未启动 `notification-service`，也未迁移 PostgreSQL。
- [x] 在现有 commerce-service 进程内让 notification 实际运行于独立 MySQL Schema，完成历史回填、
  Kafka 实时镜像、连续核对、独立读切换、数据库停机与商城 Schema 回滚；M3.2 尚未创建独立进程。

### 13.6 M3.2 准入批次：跨进程追踪基线

本批只补齐独立服务启动前的可观测性门禁，不创建 `notification-service`，不迁移 PostgreSQL。

- [x] 三个 Java 服务接入 OpenTelemetry，统一 W3C Trace Context、日志 `traceId/spanId` 和可配置采样；
  默认关闭 OTLP 导出，追踪后端故障不得成为业务依赖。
- [x] 增加可选 Tempo 后端和 Grafana 数据源，并完成 commerce-service 到物流模拟器的 HTTP 跨进程
  父子 Span 实测。
- [x] 开启 Kafka Producer/Listener Observation，完成发送、消费及 notification V1 来源/传输
  traceparent 分离兼容；合法两位 W3C trace-flags 不再被误送 DLT。
- [x] 提供 Windows/Bash 配对的 `verify-distributed-tracing`，验证同一 Trace 可查询及 Tempo 停机时业务
  健康；容器化 Bash 使用外部运行时模式，避免 Docker Desktop bind path 被容器内路径替换。
- [x] 完成 RabbitMQ 和 RocketMQ 的真实跨消息 Trace 传播、后端查询及故障降级证据。
- [x] MQ 门禁完成后只开放 `notification-service` 影子进程的下一步评审；本批不启动独立服务。

### 13.7 M3.2 首批：notification-service 独立影子进程

本批只让 notification 以影子消费者独立运行；客户查询和写入权威仍在 commerce-service，默认运行态仍为
`IN_PROCESS + COMMERCE`，不启动 PostgreSQL，也不删除单体通知表。

- [x] 新建分层的 `services/notification-service`，根包只保留启动类，业务代码进入
  `api/application/domain/infrastructure`，基础设施继续拆分 `messaging/persistence`。
- [x] 复用冻结的 V1 通知命令、稳定消费者组和独立 Schema V1→V3；独立进程以 Inbox + eventId 唯一键
  抵御重复和历史回放，不共享 commerce Schema、Mapper 或事务。
- [x] Compose 增加可选 `notification-service` Profile 和 `18086` 健康/状态端点；默认 `docker compose up`
  不启动影子进程，`notification-db` 数据卷保持不变。
- [x] 增加 Windows/Bash 配对专项，实测实时影子写入、进程停机期间 Kafka 积压、重启追平、
  commerce/notification 同 Trace 和显式切回 `IN_PROCESS` 的单体门禁。
- [x] 扩展质量审计到第四个 POM、notification 分层/子目录、Mapper 契约及两处 V1→V3 迁移字节一致性；
  双平台质量和 42 对脚本契约审计通过。
- [x] 收口 README、状态、实现、演进计划和验证体系；本批独立提交推送，不建立 M3 标签。
- [x] 后续批次一次性补齐独立 API、完成数据交接并删除单体通知读写路径；切换完成前不得宣称流量已迁出。

### 13.8 M3.2 下一批：notification 单一所有权与公开入口切换

详细边界和分阶段切换顺序见
[notification-service 单一所有权切换计划](architecture/NOTIFICATION-SERVICE-CUTOVER-PLAN.md)。
本批完成后只保留 notification-service + notification-db 一套通知读写实现，不建立长期双读、双写或数据库
回退。commerce 只保留订单事务侧通知 Outbox 和身份薄代理。

- [x] 在 notification-service 一次性补齐列表、未读数、按 eventId 单条已读和全部已读 V1 API。
- [x] 增加客户隔离、幂等、契约 fixture、HMAC 服务身份、错误码、脱敏日志和 HTTP Trace。
- [x] 停止 commerce 进程内 notification consumer；本机交接时 commerce 历史通知为 0 行，无历史需回填，
  独立消费者三分区 lag 均为 0。
- [x] 完成一次性交接核对：源侧 0 行，独立库运行数据 eventId 唯一；不建立长期 COMPARE 模式。
- [x] 将 commerce 公开四个通知接口原位替换成身份薄代理，Angular 使用 eventId 标记已读。
- [x] notification 故障时返回稳定 503 并让通知菜单可重试；不得回查 commerce 通知表。
- [x] 删除 commerce 通知查询/已读 Mapper、进程内 consumer、独立库连接配置、运行路由和旧存储开关。
- [x] 保留 commerce 事务 Outbox/V1 命令生产端和历史 Flyway，明确它们不构成第二份通知读模型。
- [x] Windows/Bash 验证正常、越权、重复、停机 503、服务恢复及核心商城不受影响；消费者三分区 lag 为 0。
- [x] 执行 Windows 完整验收、Bash 等价全链路与告警补验并完成文档收口；不启动 PostgreSQL，不拆第二个服务。
- [x] 本批独立提交并推送。

### 13.9 M3.3 首批：catalog-service 边界准入治理

详细边界见 [catalog-service 单一所有权演进计划](architecture/CATALOG-SERVICE-EVOLUTION-PLAN.md)。本批只在
commerce 模块化单体内建立应用端口并收紧依赖基线，不创建 catalog-service/catalog-db，不移动表，不启动
inventory-service 或 PostgreSQL。

- [x] 新增 `CatalogProductLookup` 最小商品快照端口，cart/order 不再直接注入 `ProductMapper`。
- [x] 新增 `ProductReviewCatalogQuery`，catalog 不再直接注入 `ReviewMapper` 或暴露 review 持久化对象。
- [x] 以 `CatalogCacheInvalidator` 隔离 review 对具体目录 Service 的反向依赖。
- [x] 将 `cart -> catalog`、`catalog -> review` 跨模块 infrastructure 引用上限从 2/1 收紧为 0/0。
- [x] Java 25 正式镜像 29 项测试、Windows/Bash 快速回归和质量/脚本契约审计通过。
- [x] 复核文档并独立提交推送。
- [x] 下一批冻结 products 商品资料与 inventory 数量的最终表归属、商品快照和跨库 join 消除方案；仍不
  启动独立服务。

### 13.10 M3.3 第二批：catalog/inventory 数据所有权冻结

本批继续运行于 commerce 模块化单体和同一 MySQL 实例，只拆清物理表责任与持久化访问，不创建
catalog-service、inventory-service 或独立数据库，不引入双读/双写和 PostgreSQL。

- [x] 新增 Flyway V28：将可售、预占、已售数量迁入 inventory 自有 `product_inventory`，保留稳定
  productId、merchantId 和必要商品快照，并删除 `products` 的三个库存列。
- [x] 将库存预占与流水外键切到 `product_inventory`；删除 catalog 对 identity、cart/order/review 对
  catalog 的跨边界数据库外键，服务化后不依赖共享数据库约束。
- [x] 为 `cart_items`、`customer_orders` 固化创建时的名称、图片和单价快照；购物车、轻量订单、结算锁定
  和商家库存查询不再跨模块 join `products`。
- [x] 现有 MySQL 非破坏升级保留 6 条库存记录和 642/1/65 可售/预占/已售总量；空库 V1→V28、旧库
  V16→V28 的 Windows/Bash 隔离生命周期演练均通过。
- [x] Java 25 正式镜像 29 项测试、Windows/Bash 快速回归和质量/脚本契约审计通过。
- [x] 复核文档并独立提交推送。
- [x] 下一批冻结 catalog V1 列表、详情、批量快照、商家维护 API 与商品变更事件契约，再评审独立
  catalog-service；仍不启动 inventory-service 或 PostgreSQL。

### 13.11 M3.3 第三批：catalog V1 契约与失败语义

详细契约见 [catalog V1 API 与商品事件契约](architecture/CATALOG-V1-CONTRACT.md)。本批仍只维护一套
commerce 内实现，通过稳定边界为下一批整体切换做准备。

- [x] 冻结认证后的 V1 列表、详情和最多 100 个 ID 的去重批量快照；只返回 ACTIVE 商品，批量缺失时
  整体 404，快照不混入 review 持久化结构。
- [x] 新增按 `api/merchant` 子目录分层的商家目录维护 API，支持本人商品资料、价格、ACTIVE/INACTIVE
  状态和翻译更新；全部写请求使用 expectedVersion，冲突稳定返回 `CATALOG_VERSION_CONFLICT`。
- [x] Flyway V29 增加 lifecycle/businessVersion 和 catalog 自有 Outbox；资料/价格更新在同一事务同步
  inventory 展示快照，任一失败整体回滚。
- [x] 冻结五类商品事件 V1、fixture、eventId 去重和按 businessVersion 处理 APPLY/STALE/版本缺口重试的
  消费规则；创建事件已定义，但创建 API 等 inventory 初始化命令后再开放。
- [x] Java 25 正式镜像 32 项测试、Windows/Bash V1→V29 与 V16→V29 隔离升级、真实 Docker V1 API、
  Windows/Bash 快速回归和质量/脚本契约审计通过。
- [x] 文档收口并独立提交推送。
- [x] 下一批创建独立 catalog-service/MySQL Schema，完成一次性迁移、调用方整体切换、停机/恢复和
  跨进程 Trace 验收；验收后删除 commerce catalog Mapper/表访问，不保留 LOCAL/COMPARE 或数据库回退。

### 13.12 M3.3 第四批：catalog 单一所有权切换

本批只保留一套独立微服务实现：`catalog-service` 与 `catalog-db` 是商品资料、翻译、业务版本和 catalog
Outbox 的唯一运行所有者；commerce 仅保留公开 API、Session/RBAC、评价组合和签名 HTTP 薄代理。commerce
数据库中的 V1–V29 历史表不改写，供 Git 级回退核对，但没有运行时 Mapper、双读、双写或数据库回退。

- [x] 新增按 `api/application/domain/infrastructure` 子目录分层的独立 Spring Boot 服务、MySQL/Flyway Schema、
  MyBatis 持久化、商家乐观锁写入和同事务 catalog Outbox；内部 API 只接受 commerce-service 的 HMAC 请求。
- [x] commerce 的列表、详情、购物车/轻量订单商品快照、商家维护整体切到 2 秒连接/3 秒读取的签名 HTTP
  客户端；404/409 保持原语义，其余连接失败稳定返回 `503/CATALOG_SERVICE_UNAVAILABLE`。
- [x] 删除 commerce 的 Product/Translation Mapper、XML、catalog Outbox 写入、过渡事件与 inventory 同步投影；
  公开目录继续由 commerce 组合 review，自主服务不读取 review 或 identity 数据库。
- [x] 一次性迁移要求 commerce Flyway 至少 V29，复制 6 条商品、6 条翻译和已有 Outbox，以计数和 CRC32
  XOR 指纹核对后写 `catalog-v29-single-owner=COMPLETE`；重复运行只读 COMPLETE 并拒绝覆盖新库。
- [x] 真实 Docker 非破坏切换完成；首次演练发现 mysqldump 字符集问题时门禁写 FAILED 并阻止切换，清理仅限
  可从源库恢复的失败目标行，补 `utf8mb4` 后计数/指纹一致并完成切换，未删除任何命名卷。
- [x] catalog 4 项、commerce 29 项 Java 25 镜像测试通过；Windows 运行门禁验证中英文代理、重复迁移、
  catalog 停机稳定 503 与 commerce 健康隔离；Trace `c04f5e90fb3e4162b28968ffc6b78b4b` 同时包含
  `commerce-service` 和 `catalog-service`。
- [x] 完成 Windows/Bash 快速回归、304 个 Java 类声明空行/294 个 Java 文件质量审计、42 对脚本契约审计、
  文档收口和本批独立提交推送。
- [x] 下一批只启动 inventory-service 准入评审与边界治理；不同时拆 inventory，不启动 PostgreSQL。

### 13.13 M3.4 首批：inventory-service 准入评审与边界治理

详细边界见 [inventory-service 准入与单一所有权演进计划](architecture/INVENTORY-SERVICE-EVOLUTION-PLAN.md)。
本批只在 commerce 模块化单体内治理依赖，不创建 inventory-service/inventory-db，不迁移数据，不改默认
Docker 拓扑，也不启动 PostgreSQL。

- [x] 新增 `inventory/application/port` 子目录和 `InventoryCommandPort`；ordering、payment、refund 不再
  注入具体 `InventoryService`。
- [x] 预占输入改为 inventory 自有 `ReservationItem`，清除 `inventory -> cart`；商家库存应用层只接收
  merchantId，减少对 identity 实体的泄露。
- [x] 新增 ArchUnit 门禁，库存外模块不得依赖 inventory 的 API、domain、infrastructure 或具体 application
  实现，只能使用 application port。
- [x] 复核三张库存自有表、残留 ordering 外键、取消通知副作用和本地事务耦合，明确当前只通过首批边界
  准入，尚未获准启动独立服务。
- [x] 冻结后续 V1 命令信封、幂等 Inbox、catalog 事件消费、失败恢复、一次性迁移、整体切换和 Git/数据
  回退顺序；明确禁止长期 LOCAL/COMPARE、双写或共享数据库。
- [x] Java 25 正式构建 30 项测试、Windows 新运行 JAR 快速回归及容器化 Bash 质量/脚本审计通过；跨模块
  实际依赖收紧为 39/109/36/18。Bash 全链路不以工具容器内 Compose 的宿主路径结果冒充通过。
- [x] 下一批冻结 RESERVE/COMMIT/RELEASE/REFUND V1 命令与消费者 fixture，移除 inventory 对 ordering/
  notification 的运行依赖和跨边界外键；仍不创建独立 inventory 服务，不启动 PostgreSQL。

### 13.14 M3.4 第二批：inventory V1 命令、Inbox 与跨边界外键清理

本批继续只维护 commerce 内一套库存实现，不创建 inventory-service/inventory-db，不改默认 Docker 拓扑，
不启动 PostgreSQL。

- [x] 新增分层 `application/contract/v1`，统一冻结 RESERVE、COMMIT、RELEASE、REFUND 的 schemaVersion、
  commandId、producer、orderId、业务字段、correlation/causation 和 W3C traceparent。
- [x] 新增四份消费者 fixture 和校验器；非法版本、字段、traceparent、RESERVE 空商品项及非 RESERVE 携带
  商品项均 fail closed。
- [x] Flyway V30 新增 inventory 自有 `inventory_command_inbox`；命令领取、余额/reservation/movement 变化和
  SUCCEEDED 状态在同一事务，重复同载荷不重复执行，同 commandId 异载荷明确拒绝。
- [x] V30 移除 inventory_reservations/inventory_movements 指向 shop_orders 的跨边界外键；删除
  `InventoryMapper.customerId`，inventory 不再读取 ordering 表。
- [x] 取消通知改由订单取消事务写 notification Outbox，并以 order eventId 建立 causation；库存释放不再查询
  customerId 或发布通知，`inventory -> notification` 运行依赖清零。
- [x] Windows 与容器化 Bash 均通过空库 V1→V30、旧库 V16→V30，验证 inventory Inbox 存在和两条 ordering
  外键消失；Java 25 正式构建 32 项测试、Windows 快速回归和双平台质量/脚本契约审计通过。
- [x] 默认 Docker 持久库升级到 V30 后完成真实链路复核：17 条 inventory 命令全部 SUCCEEDED（RESERVE 8、
  COMMIT 5、RELEASE 3、REFUND 1），非成功命令和两条跨边界订单外键均为 0；未删除或重建命名卷。
- [x] 下一批实现 catalog V1 事件到 inventory 商品快照的 Inbox、businessVersion、有限重试/DEAD 与 fixture；
  仍不创建独立 inventory 服务，不启动 PostgreSQL。

### 13.15 M3.4 第三批：catalog 事件驱动的 inventory 商品快照

本批只补齐独立 catalog-service 到 commerce 内 inventory 的单向事件投影，不把库存数量写回 catalog，
不创建 inventory-service/inventory-db，不启动 PostgreSQL。

- [x] catalog-service 增加分层 Outbox Dispatcher 和可配置批量、重试间隔、最大 5 次尝试；投递成功标记
  PUBLISHED，暂时失败进入 RETRY，达到上限进入 DEAD，测试覆盖第五次失败的终态。
- [x] 投递使用 HMAC 服务身份、时间窗、requestId 和已签名的 SHA-256 载荷摘要；commerce 内部端点同时校验
  签名与真实请求体摘要，未签名请求返回 401。
- [x] inventory 冻结 catalog V1 消费契约与 fixture；V31 新增 `inventory_catalog_event_inbox`，eventId 重放
  幂等，同 eventId 异载荷拒绝，旧版本记为 STALE，版本缺口回滚领取并由 catalog Outbox 重试。
- [x] `product_inventory` 增加 catalog 生命周期和 businessVersion；profile/price/status 仅更新商品快照字段，
  translation 只推进版本，任何事件均不改可售/预占/已售数量；INACTIVE 商品禁止新预占。
- [x] Java 25 正式构建通过 catalog-service 5 项和 commerce-service 35 项测试；真实 Docker 投递完成
  businessVersion 1→2，catalog Outbox PUBLISHED、inventory Inbox APPLIED。
- [x] Windows/容器化 Bash 均通过空库 V1→V31、旧库 V16→V31；Windows 快速回归和双平台质量/脚本契约
  审计通过。
- [x] 真实停机恢复通过：commerce-service 停机时事件进入 RETRY，恢复后第 4 次尝试 PUBLISHED，inventory
  同一 eventId 仅应用一次并推进到 businessVersion 3；未删除或重建命名卷。
- [x] 下一批冻结 RESERVE/COMMIT/RELEASE/REFUND 跨进程投递的生产端 Outbox、结果确认、超时与补偿语义，
  通过准入评审后再决定是否创建独立 inventory-service；仍不启动 PostgreSQL。

### 13.16 M3.4 第四批：inventory 命令投递、结果与补偿协议

详细协议见 [inventory V1 命令投递、结果与补偿协议](architecture/INVENTORY-COMMAND-DELIVERY-PROTOCOL.md)。
本批不启用远程传输，不创建 inventory-service/inventory-db。

- [x] 新增 `InventoryCommandGateway` 作为四类命令唯一入口；现有调用方不再直接注入 Handler，Handler 仍是
  唯一库存实现，不维护 LOCAL/COMPARE 或双写分支。
- [x] V32 新增生产端 `inventory_command_delivery_outbox` 和结果端 `inventory_command_result_inbox`；成功事务
  同时留下 inventory 消费 Inbox、库存变化、SUCCEEDED 结果和 ACKNOWLEDGED 投递证据。
- [x] 新增 `InventoryCommandResultV1`、SUCCEEDED/REJECTED fixture 和校验；共享语义指纹忽略重试时可变的
  occurredAt/traceparent，保留同 commandId 异业务载荷拒绝。
- [x] 冻结 NEW→IN_FLIGHT→RETRY/ACKNOWLEDGED/RECONCILIATION_REQUIRED 状态机；达到尝试上限但结果未知时
  不伪造 REJECTED，也不自动执行相反补偿。
- [x] 明确 RESERVE、COMMIT、RELEASE、REFUND 的成功、拒绝和未知结果矩阵；COMMIT 未确认阻止履约，财务
  REFUND 成功不因库存未知而自动撤销。
- [x] Java 25 commerce-service 37 项测试通过，覆盖 Outbox/结果 Inbox 证据、结果 fixture、重复命令和超时达到
  上限进入 RECONCILIATION_REQUIRED。
- [x] Windows/容器化 Bash 均通过空库 V1→V32、旧库 V16→V32；Windows 快速回归和双平台质量/脚本契约
  审计通过。默认持久库为 V32，32 条本批命令全部形成 SUCCEEDED/ACKNOWLEDGED 配对证据，未决投递为 0。
- [x] 下一批评审并实现远程切换前的订单等待库存状态、履约阻断、退款库存待对账状态和管理端恢复入口；
  门禁完成前不创建独立 inventory-service，不启动 PostgreSQL。

### 13.17 M3.4 第五批：inventory 业务等待态与人工恢复门禁

- [x] V33 为订单增加独立库存状态：WAITING_RESERVATION、RESERVED、WAITING_COMMIT、COMMITTED、
  RELEASED、REFUND_PENDING、REFUNDED 和 RECONCILIATION_REQUIRED；不把传输状态混入订单主状态机。
- [x] checkout、支付成功、取消释放和退款成功分别推进库存业务状态；客户与商家订单响应公开 inventoryStatus，
  便于界面明确展示等待、完成或待对账，而不是把未知结果冒充成功。
- [x] 商家发货前强制要求 inventoryStatus=COMMITTED；即使订单已 CONFIRMED，只要库存提交结果未知仍禁止履约。
- [x] 退款记录增加 NOT_STARTED、WAITING、REFUNDED、RECONCILIATION_REQUIRED；财务退款状态与库存归还状态
  分开表达，为远程结果未知时保留独立人工对账事实。
- [x] 超时达到上限时通过 StateProjection 同步订单/退款待对账状态；ADMIN API 可列出待恢复命令并使用原
  commandId/原 payload 重试，成功后恢复 ACKNOWLEDGED 及对应业务状态，不生成新业务命令；V34 强制记录
  恢复操作者、原因和时间。
- [x] Java 25 commerce-service 38 项测试通过，覆盖 COMMIT 超时、订单待对账、同 commandId 管理恢复、
  履约阻断和
  COMMITTED 收口；质量门禁覆盖 317 个 Java 文件、24 个 Mapper XML 和 221 个映射语句。
- [x] 双平台 V1→V34/V16→V34、Windows 快速回归和真实持久库升级通过；现有订单分布为 COMMITTED 124、
  RELEASED 118、REFUNDED 21，21 条退款均已 REFUNDED，53 条命令全部 ACKNOWLEDGED，无等待或待对账残留。
- [x] 下一批执行 inventory-service 最终准入评审：核对数据搬迁清单、停写/增量追平、单一远程适配器、
  跨进程 Trace 和失败回退运行手册；评审通过后才可创建独立 MySQL inventory-service，仍不启动 PostgreSQL。

### 13.18 M3.4 第六批：inventory-service 最终创建准入评审

本批只授予创建独立服务和 MySQL Schema 的资格，不创建服务、不搬迁数据、不切换流量。详细步骤见
[inventory-service 创建准入与单一所有权切换运行手册](architecture/INVENTORY-SERVICE-CUTOVER-RUNBOOK.md)。

- [x] 明确五张 inventory 所有权表必须迁入独立库：`product_inventory`、`inventory_reservations`、
  `inventory_movements`、`inventory_command_inbox`、`inventory_catalog_event_inbox`；生产端 delivery Outbox
  和结果 Inbox 留在 commerce，禁止把七张边界表机械地全部搬走。
- [x] 冻结快照导入、在线增量追平、短暂停写、未决事实归零、最终指纹核对和整体切换顺序；禁止分命令、
  分租户或按比例保留本地分支。
- [x] 冻结唯一远程适配器与跨进程 Trace 门禁：四类命令保持原 commandId，并以 correlationId、
  causationId、traceparent 串联生产 Outbox、传输、inventory Inbox、库存事务、结果和生产侧 Inbox。
- [x] 冻结失败规则：超时只进入等待/待对账，不改调本地 Handler；独立库接收新写后不得静默回旧库，
  必须先停写并对独立新增事实执行受控正向或反向对账。
- [x] 新增 Windows/Bash 只读准入审计，验证 V34、七张边界表、跨边界外键、非负余额、预占/已售汇总、
  Inbox/Outbox 闭环、业务等待态和单一 Gateway；两套实跑结果一致。
- [x] 完整字段五表基线为：库存聚合 `6|583|1|124|578381528`，预占 `263|263|1295838566`，流水
  `548|654|231355256`，命令 Inbox `87|673468363`，catalog Inbox `2|2798068318`；未决与待对账均为 0。
- [x] CREATE 准入通过；CUTOVER 仍未获准。当前没有 inventory-service/inventory-db，没有远程库存写入，
  PostgreSQL 继续冻结。
- [x] 下一批创建分层 `inventory-service` 与独立 MySQL `inventory-db`，实现五张所有权表及唯一远程适配器；
  先以不接流量状态启动并完成可重复迁移工具，未通过最终追平和故障演练前不得切流。

### 13.19 M3.4 第七批：inventory-service 影子运行时与可重复快照

- [x] 新增独立 `services/inventory-service`，按 `api/internal`、`application/port`、`application/service`、
  `domain`、`infrastructure/persistence`、`infrastructure/transport` 分层；普通 class 声明与首字段之间保留空行。
- [x] 独立 MySQL V1 创建五张所有权表及 migration state；不复制 delivery Outbox/结果 Inbox，不建立 ordering、
  catalog 或 identity 跨库外键。
- [x] 实现唯一命令 HTTP 适配器和 catalog 事件适配器，复用冻结的 V1 幂等指纹、Inbox、余额状态机、HMAC
  身份和 W3C Trace 信封；没有 LOCAL/COMPARE、双写或数据库回退分支。
- [x] `inventory-shadow` Profile 默认 `INVENTORY_ACCEPTING_TRAFFIC=false`；服务健康但已签名命令稳定返回 503，
  未签名命令返回 401。commerce 与 catalog 均未指向该服务，现有业务仍只有原本地路径。
- [x] 可重复迁移器只复制五张所有权表；每次先拒绝未决事实，再以完整字段、微秒时间戳、数量总和及 CRC
  指纹比较源/目标。连续至少 20 代快照均 READY 且指纹一致，未删除或重建任何命名卷。
- [x] 快速回归后的最终基线：库存 `6|579|1|128|828737161`、预占 `271|271|2545664639`、流水
  `565|671|815919868`、命令 Inbox `104|530920418`、catalog Inbox `2|2798068318`。
- [x] Java 25 正式镜像 2 项测试通过；Windows 与 Git Bash 影子运行验收均通过健康、双代迁移、401 和
  已签名 503。质量门禁扩大到 catalog/inventory，共覆盖 368 个 Java、30 个 Mapper XML、252 个语句和
  6 个 POM；脚本契约为 43 组。
- [x] 本批未切换任何流量、未让独立库接收新业务写入、未删除 commerce 库存实现；CUTOVER 和 PostgreSQL
  继续冻结。
- [x] 下一批先实现停写控制、最终增量追平和生产端远程传输预演；业务切换保持关闭，不能把预演描述为 CUTOVER。

### 13.20 M3.4 第八批：停写、最终追平与远程传输预演

- [x] `InventoryWriteFreezeGate` 统一阻止 RESERVE、COMMIT、RELEASE、REFUND 新写；默认关闭，仅在短暂停写窗口
  通过 `INVENTORY_WRITE_FROZEN=true` 启用，不增加 LOCAL/COMPARE 或数据库回退分支。
- [x] commerce 新增唯一 HMAC HTTP 客户端，复用原 V1 payload、commandId、correlationId、causationId 和
  `traceparent`；连接/读取超时分别为 2/3 秒，远端不可达统一返回 HTTP 503。
- [x] 管理端远程预演只允许重放已 ACKNOWLEDGED 的原命令、原 payload，并把 Result V1 幂等写回生产端结果
  Inbox；`INVENTORY_REMOTE_REHEARSAL_ENABLED` 默认关闭，不替换当前生产写入口。
- [x] Windows 与 Git Bash 实机完成“停写 -> generation 21/22 最终追平 -> 开启独立服务流量 -> 历史 COMMIT
  重放 -> 重复不改账 -> 服务停机 503 -> 恢复成功”；独立库统计始终为 `579|1|128|565|104`。
- [x] Windows 快速回归通过；随后 generation 23/24 再次连续追平，最终五表指纹为库存
  `6|575|1|132|1824707114`、预占 `279|279|2231803732`、流水 `582|688|2202142859`、命令 Inbox
  `121|2961863654`、catalog Inbox `2|2798068318`。
- [x] Java 25 正式镜像 40 项测试通过；质量门禁覆盖 372 个 Java、385 个类声明空行、30 个 Mapper XML、
  252 个语句和 6 个 POM，脚本契约扩展为 44 对。演练退出后停写、远程预演、独立接流量三项开关均为 false。
- [x] 本批没有让新业务走远程库存、没有删除 commerce 本地库存实现，也没有启动 PostgreSQL；CUTOVER 仍未获准。
- [x] 下一批实现唯一生产 Outbox 异步派发器并整体切换四类命令，逐类验证真实新写的跨进程 Tempo Trace、重复、
  乱序、超时、消费者已提交但结果丢失、服务/数据库停机恢复；命令链路已全部通过且没有本地回退。commerce
  库存 Mapper/XML 尚由商家库存管理 API 使用，必须先完成下一批管理边界迁出，不能误删或虚报完整单一所有权。

### 13.21 M3.4 第九批：库存命令生产切换与故障恢复

- [x] commerce 的唯一 `InventoryCommandGateway` 改为只在调用方事务内写生产 Outbox；唯一异步派发器按
  NEW/RETRY 顺序发送 RESERVE、COMMIT、RELEASE、REFUND，Result V1 幂等写入结果 Inbox 后才推进订单/退款
  inventoryStatus。不存在 LOCAL/COMPARE、同步本地 Handler、双写或数据库回退。
- [x] inventory-service 与 inventory-db 进入默认拓扑并默认接流量；catalog-service 也只把 catalog 事件投向
  inventory-service。历史五表迁移器移到 `inventory-migration` Profile，不成为第二套长期运行路径。
- [x] 实机真实新写四类命令均 ACKNOWLEDGED；commerce 源库存流水保持 582，独立库由 591 增至 627，证明
  切换后新库存事实只写目标库。订单/退款无等待或待对账残留。
- [x] 返回结果延迟 8 秒时，inventory 已提交而 commerce 保持 IN_FLIGHT；恢复后原 commandId 重试并 ACK，
  目标库只有一条流水。inventory-service 停机和 inventory-db 停机也均以原命令恢复，尝试次数增加且不重复记账。
- [x] 异步 Trace 使用命令信封的 W3C 远端父级；RESERVE/COMMIT/RELEASE/REFUND Trace 分别为
  `1e5b4f8812a5827adb5bf187e54d3d5e`、`9d6b51b825fd32561c72fccd9c9aedd8`、
  `30efbc4bbb617d4293fab2c145c58169`、`d67e97040c552e15220d6315ec397d38`，每条均同时包含
  commerce-service/inventory-service。Windows 可复跑脚本完整通过，Bash 等价脚本通过语法与 45 对契约审计。
- [x] Java 25 干净构建 42 项测试通过；质量门禁覆盖 374 个 Java、30 个 Mapper XML、253 个语句、6 个 POM
  和 388 个 Java 源文件类声明空行。本批没有启动或迁移 PostgreSQL。
- [x] 下一批先把商家库存查询/调整迁入 inventory-service，以 commerce Session/RBAC/HMAC 薄代理访问；完成
  真实验收后删除本地 Inventory Handler、Mapper/XML 与源表运行权限，冻结 inventory 完整单一所有权。不得
  在这一清理批次并行拆 order、payment-orchestration、fulfillment，也不得启动 PostgreSQL。

### 13.22 M3.4 第十批：商家库存管理迁出与完整单一所有权

- [x] inventory-service 新增按 merchantId 隔离的库存列表、人工调整和流水查询用例与内部 HMAC HTTP 适配器；
  人工调整继续执行非零、不可减为负数、原因必填和商家所有权校验，并在 inventory-db 写余额后流水。
- [x] commerce 公开 API 保留原 Session、CSRF 与 MERCHANT RBAC，只通过 2 秒连接/3 秒读取超时的 HMAC 薄代理
  调用独立服务；400/404/409 保留业务语义，连接或独立服务故障 fail-closed 为 503，不存在本地回退。
- [x] 删除 commerce 的本地 Inventory Handler、catalog event/command Handler、库存领域余额对象、四个 Mapper
  与 XML、旧内部 catalog 入口及专用签名拦截器；生产侧只保留命令 Outbox/Result Inbox 与公开身份薄代理。
- [x] Compose 在 commerce 启动前先用迁移账号完成 Flyway V34，再创建显式逐表授权的 `commerce_runtime`。
  运行账号可访问 commerce 自有表，但对五张库存源表均收到 MySQL 1142；源表保留为历史证据，不再是运行路径。
- [x] Java 25 正式镜像共 44 项测试通过（commerce 39、inventory 5）；代码质量门禁覆盖 368 个主源码 Java、
  27 个 Mapper XML、231 个映射语句、6 个 POM，并校验全部 383 个 Java 源文件类声明空行。
- [x] Windows 实机验收通过商品 1 的 `72 -> 73 -> 72` 调账恢复、目标流水、未签名 401、服务停机 503 与恢复；
  commerce 源流水保持 582，inventory 目标流水增至 641；随后 Windows 快速回归完整通过。Bash 对等脚本语法
  和 46 对参数/安全契约审计通过。
- [x] M3.5 第一批已完成 order-service 边界准入治理：四张所有权表的数据和状态一致，但 4 条出站、5 条入站
  跨边界外键及 7 个外部 `ShopOrderMapper` 消费者尚未收口，因此 CREATE 准入不通过；详细阻塞、无
  Seata/DTM 的 Saga 基线及后续批次见 `docs/architecture/ORDER-SERVICE-EVOLUTION-PLAN.md`。
- [x] M3.5 第二批已按 fulfillment/logistics/payment/receipt/refund 五种意图建立 order application port，
  7 个外部 `ShopOrderMapper` 消费者归零；支付通知自有 SQL 迁入 payment Mapper，禁止回流门禁通过。
- [x] 冻结 OrderCommandV1/OrderResultV1、状态前置条件、稳定 ID、W3C Trace、失败码、未知结果和补偿语义，
  命令/结果 fixture 通过 Java 25 兼容测试；当前仍是同进程本地适配器，不虚报远程 Saga 已完成。
- [x] Java 25 正式镜像 44 项测试、双平台 order 准入审计和 Windows 快速回归通过；回归后的四表关联、
  状态、金额和孤儿审计均为 0 异常，外部 `ShopOrderMapper` 消费者保持为 0。
- [x] M3.5 第三批清除 `ShopOrderMapper` 对 cart/address/payment 的外部表职责；cart checkout 和 payment record
  分别通过自有端口/Mapper 访问，order 仅持久化订单核心事实与地址/商品快照。
- [x] Flyway V35 删除 4 条出站、5 条入站跨边界外键，创建 `order_process_managers`、
  `order_command_outbox`、`order_result_inbox`；同事务启动流程、命令/结果 payload 指纹幂等、冲突拒绝、
  未知结果截止时间、待补偿/待对账和带操作者/原因/时间的人工恢复均有集成测试。
- [x] 最终 CREATE 准入通过：Windows/Git Bash 审计确认 7 张所有权表、双向跨边界外键 0、外部 Mapper
  消费者 0、Mapper 外部表职责 0、需恢复订单/流程 0；Java 25 正式镜像 45 项测试、394/31/243/6 质量门禁、
  410 个 Java 源文件 class 字段空行、空库 V1→V35、遗留 V16→V35 及 Windows 快速回归通过。
- [x] 回归后最终指纹为订单/商品项/地址各 329 条、事件 1565 条、金额 427371.00；process/command-outbox/
  result-inbox 当前均为 0，所有一致性错误计数为 0。第三批结束时尚未创建 order-service/order-db、未切流、
  未启动 PostgreSQL。
- [x] M3.5 第四批已创建分层 `order-service` 与独立 MySQL `order-db`，仅无业务流量影子运行；Flyway V36
  以提交顺序水位记录 7 张 order 所有权表变更，独立服务完成一致性可重复快照、增量追平、逐字段 SHA-256
  核对审计和孤儿隔离/重放。远程 Order V1 适配器复用冻结契约，但 `ORDER_ACCEPTING_TRAFFIC=false`，commerce
  未配置远程 order 依赖，不维护 LOCAL/COMPARE。双平台专项复跑后 generation 8、水位 120，337/337/337/1609
  与三张空协议表全部 MATCH；孤儿演练 1 条已 RESOLVED、OPEN 0，服务与 order-db 停机期间增量均在恢复后追平。
- [x] M3.5 第五批完成远程 Order V1 的 HMAC/正文摘要/持久防重放、真实跨进程 Trace、命令 Inbox、命令
  Outbox、结果 Inbox、条件状态推进及重复/冲突/乱序/超时/结果丢失验证；超时只进入未知结果待对账，人工
  RETRY 留下操作者、原因和时间。合成订单演练后自动清理并恢复默认 503 流量门，未接业务流量。
- [x] 预切换演练完成可重复最终快照、7 表零差异、临时账号源端 order 权限冻结/拒绝、整体回退到源端唯一
  所有者及 order-service 停机恢复；最终快速回归后 generation 20、水位 236、345/345/345/1653、金额
  448155.00 和三张空协议表全部 MATCH。该演练没有冻结实际 commerce 运行账号，也没有 CUTOVER。
- [x] M3.5 第六批在停写窗口完成 generation 21、水位 236 的最终快照和 7 表 MATCH 后一次性 CUTOVER。
  commerce 的订单查询/写入统一使用远程 Order Store V1，生产 Mapper 对 7 张源表引用归零；实际
  `commerce_runtime` 对库存与订单源表权限均已撤销，不存在 LOCAL/COMPARE 或数据库回退。
- [x] 切换验收覆盖新订单仅写 order-db、源订单保持 345/448155.00 不变、支付/取消竞态、退款、履约、物流、
  自动收货/完成、评论、租约恢复、Redis 故障及 order-service 停机 503/恢复。验收后 order-db 为
  359/359/359/1710、金额 466341.00，三张协议表和 OPEN 孤儿均为 0；50 对脚本契约与快速回归通过。
- [x] M3.6 第一批完成 `payment-orchestration-service` 边界审计：冻结 payment+refund 的 11 张候选所有权表，
  299 笔支付/32 笔退款的金额、状态、幂等、渠道证据、Outbox 与事件不变量均通过；未创建服务或数据库。
- [x] 自动门禁锁定当前阻塞只能减少：1 条 refund→identity 外键、5 个外部 payment/refund infrastructure
  消费者文件和 1 条 OPEN 对账记录；Mapper 外部表职责、入站跨边界外键、未发布 Outbox 和待恢复退款均为 0。
- [x] M3.6 第二批已收口 ordering/fulfillment 对 payment/refund 内部实现的直接依赖：外部 application、domain、
  infrastructure 和 Mapper 导入归零；订单侧只使用 Payment V1 创建/查询/关闭/结果契约和自有端口。
- [x] Payment V1 固定版本、稳定命令/结果 ID、业务幂等键、金额/币种、关联/因果 ID、W3C Trace、“超时结果
  未知”、支付成功优先和 payload 指纹语义；4 个 JSON 兼容样例通过测试。当前只有一套本地适配器。
- [x] 回归发现并修复库存结果乱序：迟到 RESERVE 成功不得把 COMMITTED 倒退为 RESERVED；人工核对后的
  RECONCILIATION_REQUIRED 重试仍可按原命令进入正确终态。48 项测试和完整快速回归通过。
- [x] M3.6 第三批删除 refund→identity 外键；原 OPEN `PAYMENT_TIMEOUT` 对应支付已为 `CLOSED`，以
  `TERMINAL_PAYMENT_CONFIRMED`、迁移执行者和详情审计式关闭，没有静默删除。
- [x] payment-backed 订单事件临时适配器已删除，179 条历史订单事件与消费记录分别迁入 ordering 自有
  Outbox/Inbox，payment Outbox/Inbox 中的订单主题归零。
- [x] 支付/退款恢复协议建立独立 Process Manager、命令 Outbox、结果 Inbox；同 ID 异 payload 冲突、结果
  未知转人工核对、超时转人工核对、人工重试操作人/原因/时间均有自动门禁。CREATE 准入已通过。
- [x] M3.6 第四批已创建分层 `payment-orchestration-service` 和独立 MySQL `payment-db`，只进行无业务流量的
  影子快照、增量追平、水位核对与孤儿隔离；`PAYMENT_ACCEPTING_TRAFFIC=false`，commerce 仍只使用唯一
  `LocalPaymentOrchestrationAdapter`，没有 LOCAL/COMPARE、CUTOVER、fulfillment 拆分或 PostgreSQL。
- [x] Flyway V38 以单例提交顺序时钟和 42 个触发器记录 14 张所有权表变更；独立服务使用 REPEATABLE READ
  一致性可重复快照、批量增量、逐字段 SHA-256 审计和外键孤儿隔离/重放。Windows 与 WSL 实机专项后
  generation 3、水位 83，353/3/248/706/353/2/326/326/40/80/80 与三张空协议表全部 MATCH；两次孤儿演练
  均为 RESOLVED，OPEN 为 0。
- [x] M3.6 第五批在独立服务实现受流量门保护的远程 Payment V1 创建/查询/关闭/结果入口、真实签名渠道创建/关闭、
  支付/退款渠道回调、
  HMAC/正文摘要/持久防重放和 W3C Trace；合成数据覆盖同 ID 异正文冲突、支付成功优先、关闭后迟到成功、退款、
  结果丢失转人工核对和带操作人/原因的重试，不在 commerce 中增加 LOCAL/REMOTE/COMPARE 选择器。
- [x] Windows 与 Ubuntu 26.04 WSL 对等专项完成 payment-service/payment-db 停机恢复、临时源权限冻结拒绝和
  源端唯一所有者回退；结束后清理合成数据并恢复 `PAYMENT_ACCEPTING_TRAFFIC=false`。最终 generation 13、
  水位 160，360/3/253/720/360/2/333/333/41/82/82 与三张空恢复协议表全部 MATCH，OPEN 孤儿/恢复均为 0。
- [x] M3.6 第六批完成一次性 payment-orchestration CUTOVER 评审，结论为不通过：独立服务尚无远程 Order V1/
  Inventory 命令适配器、支付 Outbox 投递器和退款发起命令；支付/退款回调仍指向 commerce，本地支付/退款路径及
  源表权限仍在。双平台自动审计已冻结这些阻塞，普通业务流量继续留在 commerce。
- [x] M3.6 第七批只在关闭流量门下补齐独立服务的订单/库存副作用、可靠 Outbox/Inbox、退款发起和恢复闭环；不得增加
  LOCAL/REMOTE/COMPARE，不得切换回调、删除本地路径、撤销源表权限、并行拆 fulfillment 或启动 PostgreSQL。
- [x] payment-db Flyway V3 为 Outbox 增加失败证据；支付/退款结果使用稳定命令 ID 条件推进远程 Order V1 和
  Inventory COMMIT/REFUND，失败进入带退避的 RETRY，退款发起及渠道命令复用持久 Inbox/Outbox。流量门、
  副作用门默认关闭，影子复制开启时即使误开前两门也拒绝正式副作用。
- [x] Windows 与 Ubuntu 26.04 WSL 缺失订单演练均确认 Order V1 鉴权后进入可恢复 RETRY、库存零写入，并精确
  清理合成记录、恢复 `false/false/true` 三门状态；本批未迁移真实业务流量。
- [x] M3.6 第八批先收口退款发起入口：客户退款 POST 只组装 `RefundCreateCommandV1` 并调用唯一
  `PaymentOrchestrationV1`；Local 适配器继续作为唯一运行实现，56 项 commerce 测试和真实全额退款专项通过。
  评审将 `RefundController` 更正为应保留的身份薄入口，本地 `RefundService/RefundMapper` 才是 CUTOVER 删除对象。
- [x] 新增 [payment-orchestration 一次性切换运行手册](architecture/PAYMENT-ORCHESTRATION-CUTOVER-RUNBOOK.md)，
  冻结停写、最终追平、唯一远程适配器、回调切换、源表撤权、开门、烟雾验收和不可直接回旧库的原子顺序。
- [x] 完整快速回归发现并关闭支付成功与库存预留结果并发到达的竞态：Order V1 新增原子
  `START_INVENTORY_COMMIT`，该批次兼容 `RESERVED/WAITING_RESERVATION`。此处保留当时结果，现行实现只接受 `RESERVED`。当时支付已成功但库存仍停留在预留态时，
  重放同一签名回调会补发幂等 COMMIT。两笔历史测试样本均恢复为 `PAID/COMMITTED`，最终完整快速回归通过。
- [x] M3.6 第九批补齐原子切换前的 commerce 停写闸门：`PAYMENT_WRITE_FROZEN=true` 通过稳定
  `PaymentWriteAdmissionV1` 在订单进入 `CANCELLING` 前拒绝创建支付、关闭支付和发起退款，查询及签名回调继续排空。
  Windows 真实演练确认三类新写均为 503、业务事实不变、已有回调仍收敛为 `PAID/SUCCESS`，退出后恢复默认 false；
  Ubuntu 26.04 WSL 后续补齐 jq，并修复 Windows Docker CLI 下 Compose 路径/环境透传后完成同等运行态验收。
- [x] M3.6 第十批完成[最终 GO/NO-GO 评审](architecture/PAYMENT-CUTOVER-GO-NO-GO-REVIEW.md)：generation 15、
  水位 603、14 表 MATCH，OPEN 孤儿、待投递 Payment/Command Outbox 和未完成 Process Manager 均为 0；Windows/WSL
  停写排空、权限冻结恢复及目标停机恢复通过。因本地实现、旧回调和源权限仍在，结论为 NO-GO/`REJECTED`。
- [x] M3.6 第十一批经明确授权完成一次性 CUTOVER：商城停止写入后执行 generation 16、水位 610 的最终快照，
  14 表全部 MATCH，OPEN 孤儿及 Payment/Command Outbox、Process Manager 未决项均为 0；commerce 仅保留唯一远程
  Payment V1 适配器，旧支付/退款 Service、Mapper、Gateway、回调和对账路径已删除，渠道回调改投独立服务，
  `commerce_runtime` 对 14 张源表权限为 0。受控订单 486 的支付/退款及两类幂等重放通过，订单、退款和库存均
  收敛为 `REFUNDED/SUCCEEDED/REFUNDED`，旧源库无该订单支付记录；未拆 fulfillment，未启动 PostgreSQL。
- [x] M3.6 第十二批完成切流后观察与监控收尾：payment 服务新增 10 个 Prometheus 运行态指标，独立采集目标及
  Outbox、人工恢复、孤儿、退款、切流模式和指标刷新告警；14 条规则通过 promtool。Windows 订单 517/520 与
  WSL 订单 527/530 在服务、payment-db、支付模拟器重启前后完成支付/退款，待支付取消同步完成 Process Manager/
  Command Outbox；源水位保持 610、14 表授权和全部未决项保持 0。下一批只评审 fulfillment CREATE 准入。

### 13.23 M3.7 第一批：履约创建准入审计与边界治理

- [x] 冻结履约编排与订单、资金、物流投影的权威边界，记录发货竞态与恢复门禁。
- [x] 商家入口向履约传递 merchantId，物流投影写入归 logistics 应用层，移除履约对 ShipmentMapper 的直接访问。
- [x] 交付 Fulfillment V1 草案、发货命令样例与 Windows/WSL 只读审计。CREATE 结论为 `REJECTED`。

### 13.24 M3.7 第二批：商城内的持久履约流程

- [x] 发货与退款通过 Order V1 原子互斥，发货任务在后台推进，并支持授权管理员使用原命令恢复。
- [x] 改运单携带前置版本，物流渠道、商城投影与订单各自检查或单调更新版本。
- [x] 接单和改运单先存任务，沿用原命令执行，商家可查询异步操作进度。
- [x] 接单和改运单接入授权管理员恢复及审计，完成订单回执丢失、并发认领和人工恢复验证。
- [x] 复核独立履约服务 CREATE 准入，记录同订单跨操作准入与可执行契约缺口。结论为 `REJECTED`。
- [x] 落地同订单持久化准入与商城内可执行 Fulfillment V1 契约，验证重放、跨操作冲突和原命令恢复。
- [x] 复核独立运行的物流端口、拒绝后重新受理和六张表的交接清单，完成两次快照还原。CREATE 为 `REJECTED`。
- [x] 在商城内实现物流命令回执端口和已知拒绝后的安全重新受理，验证故障与竞态。CREATE 通过。
- [x] 创建独立履约影子服务与 MySQL，关闭业务流量，验证六表快照、增量水位、孤儿隔离及签名契约。
- [x] 完成影子断线追平、只读授权恢复、新命令冻结与切换前撤回演练。切流准入为 `REJECTED`。
- [x] 补齐默认关闭的独立履约执行及签名适配，验证隔离的远程故障、六表转移和回退。切流准入仍为 `REJECTED`。
- [x] 在隔离环境接通真实商城物流桥和独立管理员恢复，演练最终水位与六表核对、旧商城撤权及商家路由回退。
- [x] 补齐商家会话和管理员身份到远程履约的单一路由替换，在隔离环境验证兼容入口、历史查询和远程断线。
- [x] 演练现有影子库的停复制和所有权转换、最终追平、撤权及失败回退。
- [x] 在部署配置中持久化六表权限交接，验证应用和两库重启、授权任务重跑后旧写权限仍为零，回退后旧执行配置拒绝启动。
- [x] 固定履约发布基线并完成构建、迁移、快速回归、只读准入和影子核对。
- [x] 获得明确授权后，完成停写排空、最终水位和六表核对、履约所有权切换及端到端验收。
- [x] 补齐独立履约监控与告警，完成已受理任务停机续跑、历史查询和下游幂等验收。

详见[履约服务演进计划](architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md)。

### 13.25 M3 各服务单一所有权复核与收尾

六个服务已完成运行路径切换，库存旧迁移入口已封住，35 笔历史投递已逐笔处理，其中 5 笔缺证测试数据保留归档。付款核对真实预占、空扣减拒绝和正常出库均已在日常环境复验。[订单 695／696 验收记录](architecture/INVENTORY-RECONCILIATION-REVIEW.md#2026-11-19-日常环境库存门禁验收)

商城已补齐库存命令待对账、在途积压和最老等待时间的指标及独立告警。隔离 MySQL 已核对正常零值、投递滞留、待人工对账、处理后恢复及终态排除，并验证库存服务的出库事件告警独立触发和恢复。[库存命令监控验收](architecture/INVENTORY-RECONCILIATION-REVIEW.md#2026-11-19-库存命令监控验收)

订单服务的发货意图滞留、取消协议待恢复及采集失败告警已完成。隔离 MySQL 验证了指标和告警，日常库已只读核对所有者、旧表授权、存量订单身份及取消投递记录。[订单监控与只读核对](architecture/ORDER-SERVICE-EVOLUTION-PLAN.md#10-订单监控与只读核对)

目录和通知的业务链路监控已通过隔离验收，覆盖目录到库存的投递、商城到 Kafka 的通知发送、通知消费 lag 和消费死信。日常库完成所有权、旧表授权、事件及账本只读核对。[目录与通知监控验收](architecture/M3-SINGLE-OWNERSHIP-REVIEW.md#10-目录与通知监控验收)

取消订单跨库恢复已通过隔离验收。模拟订单终态已提交、商城两份 Outbox 尚未提交时的进程退出，商城重启自动补齐，库存释放和取消通知各完成一次。[恢复验收记录](architecture/M3-SINGLE-OWNERSHIP-REVIEW.md#11-取消订单跨库中断恢复验收)

履约候选版本已通过隔离存量恢复、真实发货、重复发货和带新写入的六表回退。[履约新版本验收](architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#23-2026-11-19-履约新版本恢复与回退验收)

商城旧履约分支和 Mapper 的退役评审已完成。独立回退包验证了旧商城镜像能够接管候选版本的新写入。[独立回退包验收](architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#25-2026-11-19-履约独立回退包验收)

商城本地履约执行代码及专用 Mapper 已退役，通过隔离空库、存量部署、商家发货及管理员恢复验证。[退役记录](architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#26-2026-11-19-商城本地履约代码退役)

旧履约六表及捕获对象已通过隔离归档恢复。正式启动、审计及回退入口已通过无旧源部署、归档恢复和旧镜像接管验收。[入口改造验收](architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#28-2026-11-19-履约归档后启动审计与回退)

P4 日常履约发布及归档清理已通过。见[P4 验收记录](architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#29-2026-11-20-p4-日常发布和旧源清理)。

## 14. 实施记录模板

### 2026-11-15：M3.2 第二批——notification 单一所有权切换

- 所有权：notification-service 默认启动并独占通知读写与独立 MySQL；commerce 只保留事务 Outbox、V1
  生产契约及 Session/RBAC/HMAC 薄代理，不保留 `LOCAL/COMPARE`、本地读模型或数据库回退。
- API：独立服务补齐列表、未读数、eventId 单条已读和全部已读；所有查询/更新带 customerId，已读重放
  保留首次 readAt。代理使用 2 秒连接/3 秒读取超时，失败返回稳定 503，Angular 显示重试入口。
- 清理：删除 commerce 的本地通知 Handler/consumer、通知查询 Mapper、独立数据源、投影比较、回填核对、
  隔离管理端及旧配置；历史 Flyway 不改写。
- 数据交接：切换时 commerce 历史通知为 0 行，无需搬迁；独立库运行事件 eventId 唯一，Kafka 三分区 lag
  均为 0。该结论只适用于本次实际数据，不把零行交接描述成有历史数据的回填成功。
- 验证：commerce 29 项、notification 2 项测试和 Angular 生产构建通过；Windows 完整验收通过；Bash 等价
  全链路通过全部业务阶段，并在排除 Git Bash 对 `/opt/kafka`、`/etc/prometheus`、`/etc/alertmanager` 的
  MSYS 参数转换后单独补验告警尾段。277/21/198/4 质量审计、287 个 Java 类声明空行及 41 对脚本契约通过。
- 边界：仍使用 MySQL；M3 清单中的 notification、catalog、inventory、order、payment-orchestration、
  fulfillment 全部完成单一所有权切换并逐一验收前，不评审、不启动、不迁移 PostgreSQL，也不建立数据库
  方言分支；后续新增待拆服务同样纳入该门禁。本批已以 `88d22c6` 独立提交并推送。

### 2026-11-15：M3.2 首批——notification-service 独立影子进程

- 进程边界：新增 Spring Boot `notification-service`，使用独立 MySQL、Flyway、MyBatis、Kafka listener
  与本地事务；稳定消费者组继续使用 `commerceflow-independent-notification-v1`，避免切换时丢失既有 offset
  和 Inbox 身份。
- 兼容与恢复：沿用 V1 payload/Header 校验及 DLT 有限重试；通知插入使用 eventId 幂等，服务停机期间
  commerce 主投影继续工作，重启后从 Kafka offset 追平独立读模型。
- 验证：镜像内 1 项集成测试通过；Windows 事件
  `a6a4fb5a-9c37-466d-ad3e-cee7a132a570:notification` 实时写入、
  `221a7028-f2ae-4717-b85b-e40727d4cc8e:notification` 停机恢复，Trace
  `8750bd0c1079a7cb0c59f36166170d5c` 包含 commerce/notification；容器化 Bash 对应事件
  `14348143-fdd5-4d1d-9b02-d964f70c5f04:notification`、
  `a2c7f8f6-5421-4611-a51a-9cabb1d1a110:notification` 和 Trace
  `e603e4390efbb48c319a9334738c11d2` 均通过。
- 门禁：293 个主源码 Java、22 个 Mapper XML、239 条映射、4 个 POM、全部 303 个 Java 源文件和
  42 对脚本契约通过；容器化 Bash 使用外部运行时模式避开 Docker Desktop bind path 替换。
- 边界：验收后关闭 notification-service 并恢复 commerce 默认配置；notification-db 和历史数据保留，
  Tempo 保持原运行状态。客户 API 未切换，单体回退未删除，PostgreSQL 未启动。

### 2026-11-15：M3.2 准入——跨进程追踪基线首批

- 接入：三个 Spring Boot 服务增加 OpenTelemetry Starter，日志关联统一为
  `[application,traceId,spanId]`；OTLP 导出由 `TRACING_EXPORT_ENABLED` 显式开启，默认关闭且
  Prometheus 继续负责指标。
- 后端：Compose 增加可选 Tempo `3.1.1`，Grafana 自动装载 Tempo 数据源；本地存储使用临时目录，
  不引入新的业务数据卷或业务运行依赖。
- 传播：HTTP 实测 commerce-service 客户端 Span 与 logistics-simulator-service 服务端 Span 保持同一
  Trace 和正确父子关系；Kafka 实测 `send/process` Span。notification 命令保留业务来源
  `commerceflow-origin-traceparent`，Inbox 记录实际传输上下文，旧消息仍保持兼容校验。
- 验证：商城 31 项测试通过；Windows 与容器化 Bash 的分布式追踪专项均查询到 commerce/logistics
  同一 Trace，并在 Tempo 停机后确认三个服务业务健康。带 tracing 的 M3.1 运行时切换演练再次通过
  Kafka 实时镜像、连续三次核对、独立读、数据库故障和 Commerce Schema 回退；当前质量门禁覆盖
  283/21/235/3、全部 292 个 Java 源文件和 40 对脚本契约，Windows 快速回归通过。
- MQ 补齐：RabbitMQ 在 Spring Observation 之外显式注入/提取 W3C 上下文，确保 TTL 队列和 DLX
  转发后仍关联；RocketMQ 使用 Micrometer Tracer/Propagator 在 properties 中传播上下文。两者均以
  taskId 低基数标签精确查询同一 Trace 的 publish/process Span。
- MQ 验证：Windows 的 RabbitMQ/RocketMQ 任务 410/411 与容器化 Bash 任务 414/415 均完成真实延时
  投递、原子领取和支付超时关单；四条 Trace 均包含发布/消费 Span。两平台均停止 Tempo 并确认
  commerce-service 健康后恢复。脚本契约增至 41 对。
- 边界：HTTP、Kafka、RabbitMQ、RocketMQ 的本地单机追踪准入门禁已补齐；这不是多 Broker 节点、
  跨区域或生产采样验收。`notification-service` 与 PostgreSQL 均未启动。

### 2026-11-15：M3.1 最终门禁——notification 独立运行 Schema 与可逆切换

- 存储边界：新增可选 `notification-storage` Profile 的独立 `notification-db`，commerce-service 使用专用连接池、Flyway、MyBatis Session 和
  本地事务管理器；商城命令 Outbox 继续留在原业务事务侧，独立库只负责 Inbox、通知读模型和切换证据，
  不建立外键、跨 Schema JOIN 或伪跨库事务。
- 迁移与回填：notification Schema 升至 V3，增加可续跑水位、核对审计和孤儿隔离证据；159 条初始历史
  以两批追平，后续 Windows/Bash 实时演练结束时商城/独立库均为 167 条。
- Kafka 历史重放：新 consumer group 发现 `broker-recovery-*` 与 `poison-dlt-*` 两条权威库不存在的旧命令；
  门禁先以 orphan=2 拒绝切换，管理员将完整记录归档到 quarantine 后移出活动读模型，Inbox 继续阻止重放。
- 切换与回滚：SHADOW 模式连续 3 次达到零 missing/orphan/mismatch 后切到 INDEPENDENT；独立通知 ID
  与商城 ID 不同，已读状态按 eventId 双向镜像。停掉 notification-db 后独立查询明确失败，切回 COMMERCE
  且关闭独立存储后服务恢复健康，已读状态保留。
- 验证：商城 31 项测试及正式镜像通过；Windows 与容器化 Bash 的 Schema V1→V3 和运行时切换脚本均
  实测通过；质量门禁为 280/21/235/3 与全部 289 个 Java 源文件，39 对脚本契约一致。
- 边界：默认恢复 `IN_PROCESS + COMMERCE`，独立库保留且健康；未创建 notification-service，未迁移
  PostgreSQL。M3.1 已完成，M3.2 仅具备后续单独启动条件。

### 2026-11-15：notification 影子投影核对与独立 Schema 演练

- 迁移：商城 V27 新增投影验证窗、窗口级影子投影和核对审计三张 notification 自有表；当前 MySQL
  非破坏性升级到 V27，窗口从 `RUNNING` 经连续三次通过进入 `READY`，随后显式停止为 `STOPPED`。
- 核对：独立 Kafka consumer group 只在开关启用时写影子投影，以窗口起始通知水位隔离历史数据；停止条件要求
  最少 5 条匹配、missing/orphan/mismatch 均为 0、窗口期间阻塞 Outbox 为 0，并连续通过 3 次检查。
- 独立 Schema：新增只包含 `customer_notifications`、`notification_inbox`、
  `notification_delivery_outbox` 的 V1 Flyway 迁移；Windows 与容器化 Bash 均在随机网络和 tmpfs MySQL 中
  验证三表、零外键、身份/订单快照与 Inbox 幂等，未连接或删除开发数据卷。
- 验证：真实 Kafka 窗口收到 5 条影子投影，三次审计全部通过后才允许停止；商城 30 项测试及正式镜像、
  Windows/Bash 质量门禁和 38 对脚本契约通过。默认配置已恢复为 `IN_PROCESS` 且投影关闭。
- 边界：独立 Schema 目前只完成可重放迁移演练，实际运行仍使用商城 MySQL；未创建 notification-service、
  独立运行数据库或 PostgreSQL，M3.2 尚未启动。

### 2026-11-15：notification 可替换异步传输与恢复门禁

- 迁移：商城 V26 为 notification 增加自有 `notification_delivery_outbox`，Inbox 保存
  correlation/causation/traceparent；现有 MySQL 非破坏性升级到 V26。
- 传输：`CustomerNotificationCommandPublisher` 在 `IN_PROCESS/KAFKA` 间配置切换；Kafka 模式在原业务事务
  写 Outbox，Dispatcher 发布并以事件 ID + Inbox 唯一键抵御确认窗口重复投递。
- 恢复：发布失败进入持久 RETRY，超过阈值进入本地 DEAD 并可人工重放，消费毒消息有限重试后进入
  `customer-notification-commands-dlt`；共享调度池扩为 4，Producer 阻塞/请求/交付超时收紧为 5/3/5 秒。
- 身份与追踪：notification application 不再接收 `AppUser`，查询只使用 customerId；V1 payload 与 Kafka
  Header 双重传播并校验 correlationId、causationId 和 W3C traceparent，Inbox 保留审计证据。
- 验证：商城 29 项测试及正式镜像通过；真实 Kafka 取消链路、毒消息 DLT、Broker 停机 RETRY→恢复
  PUBLISHED、重复幂等及 `FALLBACK` 单体回退通过。当前库 V26，未删除或重建数据卷。
- 边界：默认仍为 `IN_PROCESS`；未创建 notification-service、独立数据库或 PostgreSQL。

### 2026-11-15：notification 写入所有权与 Inbox 收口

- 迁移：商城 V25 新增 notification 自有 `notification_inbox`，现有 MySQL 非破坏性升级成功。
- 契约：新增 `CustomerNotificationCommandV1`，固定版本、事件/通知类型、发生时间、客户/订单、
  correlation/causation ID 等字段，并提供可反序列化 fixture。
- 所有权：InventoryMapper 不再写 `customer_notifications`；notification Handler 在同一事务中先登记 Inbox，
  再由 CustomerNotificationMapper 写通知，重复事件无重复副作用。
- 验证：商城 28 项测试、正式镜像、Windows/Bash 订单取消通知链路、双平台质量/脚本契约通过；
  当前库 V25 成功，5 条实测命令一一对应通知且无重复 Inbox 键。
- 边界：当前传输仍为单体内应用调用；未创建 notification-service、独立数据库或 PostgreSQL。

### 2026-11-15：M3.1 数据所有权与分布式契约基线审计

- 数据：盘点商城 37 张表并声明当前责任模块；识别 `products` 库存列、共享订单事实和通用 Kafka Inbox
  等混合所有权。
- 依赖：盘点 40 组跨模块方向、117 个 import 引用、39 个源码文件和 21 个跨模块基础设施引用。
- 契约：确认 5 个 Kafka topic、Outbox、Inbox 等价去重和 MQ 可靠性机制已存在；版本、兼容性 fixture、
  每服务独立 Inbox 与 trace context 尚未完成。
- 门禁：新增 2 项 Maven 架构基线测试；允许债务减少，阻止新表无所有者或跨模块依赖未经评审增长。
- 结论：M3.2 尚不可启动；首个治理目标是 notification 写入所有权和版本化消费者契约。

### 2026-11-14：M3.1 前置代码结构基线治理

- 结构：物流模拟器形成 `api/application/domain/infrastructure` 四层及基础设施子目录，持久化投影独立拆分。
- 格式：为 173 个既有 Java 文件补齐 175 个类声明后空行，并以 Windows/Bash 门禁覆盖全部 256 个 Java 源文件。
- 验证：248 个主源码 Java、20 个 Mapper XML、189 个映射语句和 3 个 POM 的双平台质量审计通过；
  物流镜像 Maven 打包、主链路和 Outbox 管理专项通过。
- 边界：保留三个独立 MySQL 和现有数据卷；未启动微服务拆分或 PostgreSQL 迁移。

### 2026-11-14：M2 工程质量总验收与基线冻结

- 质量门禁：Windows 与容器化 Bash 均通过 244 个 Java 文件、20 个 Mapper XML、189 个映射语句、
  3 个 Maven POM 和 37 对验收脚本的只读审计；两个前端的 Prettier 门禁均由正式 Dockerfile 强制执行。
- 全量验收：Windows 默认 `verify-quick`、默认 `verify-all` 和最终 `-SkipBuild` 全业务复跑通过；
  容器化 Bash 默认 `verify-quick`、默认 `verify-all` 通过。Bash 使用 Docker CLI 容器、host 网络和
  Docker Desktop 可见仓库路径运行同一原生脚本，因为当前 Windows 主机没有可用的原生 Bash/WSL。
- 收口修复：支付 Web Nginx 使用 Docker DNS 动态解析上游；支付结果和完整验收脚本通过商户 API
  准备可重复库存；本地单节点 Alertmanager 关闭集群监听并以 tmpfs 保存通知日志，Watchdog 使用稳定
  `instance/job` 标签，Windows/Bash 在监控栈全新重建后均完成真实 webhook 投递。
- 运行态：默认及全 Profile Compose 配置有效，五个正式镜像存在，默认 11 个核心服务及监控服务健康，
  8 个主要健康端点返回 `200`；脚本默认不删除命名卷，本轮未删除或重建数据卷。
- 边界：结论限于本地单机 Docker 学习环境，不代表多节点高可用、多日容量、真实通知到人或生产合规验收；
  微服务拆分与 PostgreSQL 迁移均未启动。
- 冻结：13.2 独立提交 `ebaa761 style(payment-web): enforce frontend formatting` 已先推送；
  本节最终提交推送后以注释标签 `m2-quality-baseline-2026-11-14` 冻结，标签指向该最终提交。

### 2026-11-14：M2.5 Angular 商城门户格式门禁

- 范围：`web/commerce-portal/angular/` 的 TypeScript、HTML、CSS/SCSS 和根配置文件。
- 实现：固定 Prettier 3.10.1，新增 `format:check`/`format:write`，正式 Dockerfile 在生产构建前执行只读门禁。
- 验证：127 个源码/配置文件、29 项 ChromeHeadless 测试、本地和 Docker 生产构建、`/healthz`、i18n 及三角色权限通过。
- 数据边界：仅重建 Angular 容器，未删除或重建数据卷。
- 提交：`c26d90e style(web): govern Angular portal formatting`，已推送到 `origin/main`。

### 2026-11-12：通用定时任务底座阶段 1

- 迁移：`V15__generic_scheduled_tasks.sql`、`V16__scheduled_task_policy_audit.sql`。
- 配置：`commerceflow.scheduling` 默认策略、10 类任务策略、Database 扫描、对账和租约恢复。
- 实现：策略解析/快照、Handler 注册表、统一执行器、Database 扫描、对账、租约恢复、管理员治理 API 与审计。
- 验证：后端 6 个集成测试通过，Flyway 15/16 在现有 MySQL 卷升级成功，Windows/Bash 管理 API 专项脚本通过。
- 遗留：除 `PAYMENT_TIMEOUT` 外的生产业务 Handler、RabbitMQ/RocketMQ 及六组合故障实验。
- 提交：未提交。

### 2026-11-12：订单取消与 Database 超时垂直闭环

- 迁移：`V17__order_lifecycle_foundation.sql`，补充订单截止/取消/收货/完成时间及订单事件任务来源。
- 配置：`commerceflow.order-lifecycle` 三类时限；`PAYMENT_TIMEOUT` 默认使用 Database + 对账。
- 实现：客户主动取消、渠道交易关闭、本地支付关闭、幂等重试、支付/取消状态保护、超时 Handler 和提前取消任务。
- 验证：后端 6 个集成测试、MySQL 旧卷迁移、Windows 与容器化 Bash 专项脚本通过。
- 补充：取消终态同事务写入 `ORDER_CANCELLED` Outbox，订单响应新增 `availableActions`，Angular 接入未支付取消表单。
- 验证：后端集成测试校验 Outbox 和动作契约，Windows/Bash 专项脚本与全量 Docker 回归通过。
- 遗留：Outbox 下游消费者、真实并发压力测试、RabbitMQ/RocketMQ。
- 提交：未提交。

### 2026-11-12：独立模拟物流最小完整闭环

- 迁移：商城 `V18__logistics_projection.sql`；物流平台 `V1__logistics_platform.sql`。
- 组件：新增 `logistics-mysql` 和 `logistics-simulator-service`，物流平台不直连商城数据库。
- 实现：幂等建单、运单更正、六段轨迹状态机、请求 HMAC/时间戳/Nonce 防重放、签名回调、回调去重/防倒退、客户归属查询和 Angular 展开轨迹。
- 验证：物流服务构建通过；后端 6 项集成测试通过；Angular 生产构建通过；Docker 旧卷升级后全服务健康；Windows 专项验证签名建单、重放 `409`、伪造签名 `401`、`CREATED -> DELIVERED` 及商城投影；Windows `verify-all -SkipBuild` 全量回归通过。
- 跨平台边界：Bash 同语义脚本已提供；当前 Git Bash 缺少 `jq`，本轮未实机执行 Bash 专项。
- 当时遗留：回调重试 Outbox、主动对账与丢失/乱序回调故障实验；已由后续“物流回调 Outbox 与主动对账”阶段完成主要闭环。
- 提交：未提交。

### 2026-11-12：物流自动轨迹与异常恢复

- 迁移：物流平台 `V2__automatic_progress_and_exceptions.sql`，增加异常前恢复状态、自动扫描索引和轨迹事件类型；商城 `V19__logistics_event_types.sql` 保留事件类型投影。
- 实现：默认关闭的自动轨迹扫描；人工 `advance`、自动推进、异常和恢复均使用行锁与条件更新。
- 异常语义：`EXCEPTION` 暂停自动推进，不创建 `AUTO_RECEIVE`；`RECOVERY` 回到记录的异常前状态，迟到的普通 `PROGRESS` 回调不能误恢复异常件。
- 验证：物流手动专项通过 `EXCEPTION -> RECOVERY -> DELIVERED`；自动专项将每步压缩为 1 秒，通过 `CREATED -> DELIVERED` 并创建 `AUTO_RECEIVE`；后端 11 个集成测试通过。
- 配置恢复：专项脚本结束时必须重建物流服务并恢复默认关闭，避免污染后续手动实验。
- 当时遗留：回调 Outbox/重试、主动对账、丢失与乱序回调实验；已由后续阶段完成暂时/永久丢失与迟到字段场景。
- 提交：未提交。

### 2026-11-12：物流回调 Outbox 与主动对账

- 迁移：物流平台 `V3__callback_outbox.sql`；商城 `V20__logistics_reconciliation.sql`。
- 主路径：轨迹与不可变回调正文同事务落库；投递器使用 `NEW/PROCESSING/RETRY/PUBLISHED/DEAD`、领取租约、连接/读取超时、最大次数和稳定事件 ID。
- 兜底：商城定期签名查询权威运单，缺失事件复用同一投影入口，通知审计记为 `RECONCILED（已对账恢复）`。
- 乱序修正：承运商和快递单号是可更正字段，迟到回调不再用旧快照拒绝；签名、商城运单号和不变平台运单号负责身份校验。
- 验证：真实停止 commerce-service 后看到 `RETRY -> PUBLISHED` 且尝试次数增加；关闭投递器制造永久丢失后，主动对账补齐 `IN_TRANSIT`；专项结束恢复默认配置。
- 跨平台边界：Windows Docker 专项已运行；Bash 同语义脚本已提供，待具备 `jq` 的 Bash 环境实机执行。
- 当时遗留：Prometheus 告警规则、外部告警接收端和长时间并发乱序压力；后续已补齐告警管道与双平台乱序浸泡。

### 2026-11-12：物流回调 `DEAD` 治理

- 迁移：物流平台 `V4__callback_outbox_audit.sql`，为回调创建独立审计轨迹。
- 后端：新增管理员回调查询、审计和人工重放 API；非管理员拒绝，原因必填，只允许 `DEAD -> RETRY`。
- 物流平台：使用条件更新和行锁保证单次重放，保留原始回调正文和事件 ID，投递成功后进入 `PUBLISHED（已发布）`。
- 前端：新增 `/admin/logistics-callbacks` 治理页，支持状态筛选、审计展开和 `DEAD` 原因重放，中英文同步。
- 观测：暴露 Outbox 状态 Gauge（仪表）、投递/重放/对账 Counter（计数器）；后续运维阶段已接入 Prometheus Rule、Alertmanager 和可替换 Webhook。
- 验证：Windows Docker 专项已通过 `DEAD -> RETRY -> PUBLISHED`、客户 `403`、空原因 `400`、重复重放 `409`、审计人/原因、商城幂等和 Prometheus 指标。
- 提交：未提交。

### 2026-11-12：自动确认收货与自动完成

- 配置：`ORDER_AUTO_RECEIVE_AFTER_DELIVERY`（送达后自动收货观察期）和 `ORDER_AUTO_COMPLETE_AFTER_RECEIPT`（收货后自动完成观察期），默认均为 7 天。
- 实现：`DELIVERED（已送达）` 创建 `AUTO_RECEIVE（自动确认收货）`；手动收货取消该任务；手动或自动收货后创建 `AUTO_COMPLETE（自动完成订单）`；最终执行 `RECEIVED（已收货） -> COMPLETED（已完成）`。
- 一致性：所有路径共用行锁、条件更新、稳定任务键和统一 Handler；任务执行时重读订单状态，迟到或重复触发安全结束。
- 验证：后端 10 个集成测试通过，含手动/自动收货的两线程竞态与过期租约重试；Angular 生产构建通过；Windows Docker 专项脚本完整验证手动取消任务、自动收货、自动完成、支付/物流保留和完成后评价。
- 故障验收：将已执行的 `AUTO_COMPLETE` 伪造为工作进程在确认前崩溃，停止并重启 commerce-service；`LEASE_RECOVERY（租约恢复）` 将其重新领取并成功结束，`ORDER_AUTO_COMPLETED（订单自动完成）` 事件仍只有一条。
- 跨平台边界：Bash 同语义脚本已提供并通过语法检查；当前 Git Bash 缺少 `jq`，未执行真实 Bash 业务链路。
- 当时遗留：长时间高并发压力测试、RabbitMQ/RocketMQ 主触发适配器及 MQ 级故障实验；RabbitMQ 主路径与数据库兜底已由后续阶段完成。
- 提交：未提交。

### 2026-11-12：RabbitMQ 定时任务主触发

- 迁移：`V21__rabbitmq_scheduled_task_trigger.sql`，记录发布时间、尝试次数和最后发布错误，并为待发布扫描建立索引。
- 配置：新增可选 `rabbitmq` Compose Profile；连接/心跳、Publisher Confirm、发布扫描、交换机/队列/路由键、TTL 分桶、Database 扫描、租约恢复和对账周期均可由环境变量覆盖。
- 实现：持久化 TTL 分桶 + DLX、Confirm/Return、未确认任务补发、Ready/DLQ、有限退避消费重试、早到重新分桶、数据库重读和统一执行器原子抢占。
- 策略：任务保存 `RABBITMQ` 快照；管理员后续修改只影响新任务。开启 Database 对账时只改变本次实际来源，不修改主策略快照。
- 验证：后端 12 个集成测试、默认/Profile Compose 配置检查及 Windows `verify-all -SkipBuild` 全量 Docker 回归通过；正常链路验证任务 176；Broker 停机产生 4 次失败并在恢复后由任务 178 补发成功；Broker 停机且开启对账时任务 179 由 `DATABASE_RECONCILIATION` 成功兜底。
- 跨平台边界：Windows 专项脚本已执行；Bash 同语义脚本已提供，但当前 Git Bash 缺少 `jq`，尚未实机执行。
- 当时遗留：RabbitMQ 重复/毒消息专项验收、RocketMQ 两种组合、长时间压力测试；前两项已由后续 RocketMQ 与 MQ 幂等专项完成。
- 提交：未提交。

### 2026-11-12：RocketMQ 5.x 定时任务主触发

- 依赖：Apache RocketMQ Java gRPC Client `5.2.2`；Docker Profile 使用当前可获取的官方镜像 `apache/rocketmq:5.4.0`。
- 配置：新增可选 `rocketmq` Profile，启动 NameServer 和带 Proxy 的 Broker，持久化日志/存储卷，并初始化 `message.type=DELAY` Topic。
- 实现：将 `dueAt` 转为绝对投递时间；发布成功记录 message ID，失败保留未发布状态以待补发；消费端重读数据库并复用统一执行器，早到消息重新投递，异常由 Broker 重试/死信。
- 验证：后端容器构建通过；默认与 RocketMQ Profile Compose 配置通过；Windows 和容器化 Bash 均完成真实下单、Broker 回执、原生延时投递、原子领取、超时关单和审计链验收（任务 226/227）；Broker 停机时发布失败可观测且 Database 对账成功兜底（任务 228/229）。
- 验证补充：Windows 与容器化 Bash 的 `verify-mq-scheduling-idempotency` 已完成 RabbitMQ/RocketMQ 重复消息、迟到旧消息和毒消息实验（任务 233～236）。
- 验证补充：Windows Docker 与容器化 Bash 的 `verify-mq-scheduling-redelivery` 均已在 RabbitMQ/RocketMQ 消费窗口中强制终止 commerce-service，任务 `239/240`、`241/242` 恢复后均只领取/完成一次。
- 遗留：长时间故障压力测试。
- 提交：待本批次提交。

### 2026-11-12：支付尝试过期与整单截止时间

- 实现：创建新支付时使用订单 `payment_due_at` 做领域门禁；已有幂等请求先返回原结果，截止时间后拒绝产生新渠道交易。
- 语义：单次 `expires_at` 只终止当前渠道尝试；整单 `payment_due_at` 到期后，`PAYMENT_TIMEOUT` 统一执行器先关闭尚在 `PENDING` 的重试交易，再将订单转为 `CANCELLED`。
- 自动验证：后端 17 个测试通过，新集成测试同时覆盖截止时间前换渠道和截止时间后拒绝新支付。
- 实机验证：Windows 订单 `ORD-46AF0F1B96954117`/任务 `244`，容器化 Bash 订单 `ORD-370124308FED4BC0`/任务 `245`，均通过 `ALIPAY EXPIRED -> WECHAT_PAY PENDING -> CLOSED -> order CANCELLED`，任务领取与完成各一次。
- 环境恢复：两个脚本都会恢复 `PAYMENT_TIMEOUT` 策略和原环境变量，并重建商城/支付页代理以避免 Nginx 缓存旧容器地址。
- 提交：待本批次提交。

### 2026-11-12：Flyway 空库、旧库升级与非法状态

- 隔离：脚本为每次运行创建随机命名的 Docker 网络和两个 MySQL 8.4 容器，结束时只删除这些临时资源，不挂载或清空 Compose 开发数据卷。
- 空库：固定 `redgate/flyway:12.4.0` 与后端运行时一致，24 个迁移从空 Schema 全部成功，并确认定时任务、物流投影、库存预占表和核心数据字典注释存在。
- 旧库：先以 `target=16` 建立旧 Schema 并插入历史待支付订单，再升级到 V24；V17 将 `payment_due_at` 精确回填为 `created_at + 30m`，V24 补充核心数据字典注释。
- 非法状态：MySQL 实机拒绝 `scheduled_tasks.status=CORRUPTED`；新集成测试确认 `PAID` 订单不能重新支付、普通取消或确认收货，自动收货/完成对非目标状态也不会改写事实。
- 验证：Windows 与容器化 Bash 两套脚本实机通过；本批复验商城 25 项测试通过。
- 边界：本项不勾选“空数据卷全链路”或“旧卷服务/MQ 重启恢复”，那两项仍需独立完成。
- 提交：待本批次提交。

### 2026-11-13：支付/取消随机化竞态浸泡压测

- 工具：扩展快速竞态脚本，支持最少轮数、持续时间、固定随机种子、轮间节流和本轮 Docker MySQL 审计；新增 Windows/Bash 同名 stress 入口。
- 时序：每 3 轮固定覆盖支付优先、取消优先和随机近同时，每轮都轮询到非中间态后再判定订单/支付/退款组合。
- 限流：首轮无节流实验在第 81 轮真实命中支付平台 `120 requests/minute` 签名限流；保留限流后增加 900ms 轮间节流和仅针对该 `429` 的 5 秒有限退避。
- 库存：stress 入口通过商家 API 将演示商品可售库存补到安全水位，保留人工调整流水，不直改库存表或删除压测订单。
- 结果：Windows `120.6s/95` 轮，`PAID/SUCCESS=55`、`CANCELLED/CLOSED=40`；容器化 Bash `121s/97` 轮，分别为 `58/39`。两次 MySQL 审计的订单数=支付记录数=本轮轮数，矛盾记录为 0。
- 边界：这是单机 Docker 两分钟浸泡验收，不等同于多小时/多日容量规划、多实例压力或网络分区验收；脚本可将时长调至 86400 秒继续扩展。
- 提交：待本批次提交。

### 2026-11-13：支付/取消锁等待超时专项

- 配置：commerce-service MySQL 连接使用可配置的 `innodb_lock_wait_timeout`，Compose 默认 5 秒；专项脚本临时压缩为 2 秒并在结束时恢复 commerce-service，不删除数据卷。
- 契约：Spring 短暂数据访问异常统一返回 HTTP `503`、`DATABASE_CONCURRENCY_RETRY` 与 `Retry-After: 1`；支付回调不再把这类异常包装成不可区分的通用异常。
- 验证：Windows 和容器化 Bash 同名脚本分别持有订单行与支付行锁；首次取消/回调超时后订单、支付、通知和事件仍为原始事实，释放锁后重试分别达到 `CANCELLED` 与 `PAID/SUCCESS`。
- 自动测试：Docker Maven 构建通过 19 个测试，其中新增错误契约单元测试。
- 边界：本项不代表进程在关键事务窗口崩溃的恢复已完成；该项仍是 M1 下一门禁。
- 提交：本阶段独立提交（见 Git 历史）。

### 2026-11-13：支付/取消关键事务窗口进程崩溃恢复

- 故障注入：新增默认关闭、必须同时指定订单 ID 和阶段的实验暂停点；脚本观测到精确窗口后对 commerce-service 发送 SIGKILL，结束后恢复默认关闭。
- 取消窗口：已验证 `PENDING_PAYMENT -> CANCELLING` 提交后崩溃可重试完成；渠道已持久化 `CLOSED`、本地仍为 `CANCELLING/PENDING` 时崩溃，重试不重复业务事实且只写一次 `ORDER_CANCELLED`。
- 回调窗口：支付记录已改为 `SUCCESS` 但回调事务未提交时崩溃，MySQL 完整回滚订单、支付、通知和事件；重放相同回调后只提交一份 `PAID/SUCCESS` 事实。
- 迟到回调：订单留在 `CANCELLING`、渠道成功回调稍后到达时，资金事实优先，订单恢复为 `PAID`。
- 验证：Windows 与容器化 Bash 同名脚本均通过；Docker Maven 19 项测试通过。
- 边界：这是单机容器的精确事务窗口实验，不代表多节点容灾或网络分区验收。
- 提交：本阶段独立提交（见 Git 历史）。

### 2026-11-13：物流高并发乱序浸泡压测

- 语义修正：不同事件 ID 的同级状态仍保留完整轨迹，但只有严格前进的状态才更新当前投影、写订单状态事件或创建自动收货任务；避免多个 `DELIVERED` 事件重复触发副作用。
- 自动验证：Docker Maven 20 项测试通过；新测试固定“两个 `DELIVERED` + 一个迟到 `CREATED`”仍只有一次投影副作用和一条 `AUTO_RECEIVE`。
- 浸泡输入：每条回调使用唯一事件 ID，随机打乱 `CREATED -> DELIVERED` 状态和 `occurredAt`，并故意携带过时承运商/单号字段。
- 结果：Windows 两分钟、12 并发、1320 条全部 `PROCESSED`，投影实际前进 2 次；容器化 Bash 两分钟、12 并发、2652 条全部 `PROCESSED`，投影实际前进 1 次。两轮的轨迹数=通知审计数=发送数，最终状态均为 `DELIVERED`，`AUTO_RECEIVE` 均只有一条，平台运单号/承运商/快递单号不变。
- 边界：这是单机 Docker 两分钟乱序浸泡，不等同于多实例、多小时/多日容量或网络分区测试。
- 提交：本阶段独立提交（见 Git 历史）。

### 2026-11-13：`AUTO_RECEIVE/AUTO_COMPLETE` MQ 故障实验

- 环境：保留现有数据卷，临时开启 RabbitMQ/RocketMQ 适配器和 1 秒发布扫描；任务关闭 Database 对账，确保故障恢复由原 MQ 完成。
- 场景：对 RabbitMQ/RocketMQ 各自验证 `AUTO_RECEIVE` 和 `AUTO_COMPLETE`；Broker 停机后创建任务，确认发布次数/错误落库，将到期时间压缩后恢复 Broker。
- 结果：Windows 任务 `588/592/594/598` 和容器化 Bash 任务 `601/605/607/611` 均按 `primary_trigger_snapshot` 由对应 MQ 执行，`TASK_SUCCEEDED` 与订单转换均只有一次。
- 恢复：两套脚本都在 `finally/trap` 中恢复策略、Broker、环境变量和默认 services/commerce-service/web，不删除或清空任何数据卷。
- 边界：本阶段是单机 Docker Broker 停机/恢复实验，不等同于 MQ 集群多节点灾备。
- 提交：本阶段独立提交（见 Git 历史）。

### 2026-11-13：收货/完成长时间高并发竞态浸泡

- 设计：每轮通过完整支付、商家履约和物流签收链路创建新订单，然后并发客户收货请求与 RabbitMQ 同任务重投；收货后再对 `AUTO_COMPLETE` 重复同等压力。
- 竞态：奇偶轮交替 100ms 偏置，确保 `ORDER_RECEIVED` 与 `ORDER_AUTO_RECEIVED` 两种合法赢家都被覆盖；HTTP `503` 只作为可重试并发结果，最终业务不变量必须全部成立。
- Windows：120 秒、9 轮、12 并发、864 次 HTTP/MQ 操作，手动赢 5 轮、自动赢 4 轮。
- 容器化 Bash：120 秒、11 轮、12 并发、1056 次 HTTP/MQ 操作，手动赢 6 轮、自动赢 5 轮。
- 不变量：每轮收货转换仅一次，`AUTO_RECEIVE/AUTO_COMPLETE` 业务键各一条，完成事件、任务领取和任务成功各一次，且无 `FAILED` 任务。
- 边界：这是单机 Docker 中的长时间业务竞态浸泡，不等同于多日容量测试或多区域灾备。
- 提交：本阶段独立提交（见 Git 历史）。

### 2026-11-13：全新空数据卷完整业务链路

- 环境：使用随机 Compose 项目名和独立新卷；暂停后恢复默认项目，不删除或清空默认 `fairy-commerceflow_*` 卷。
- 发现与修复：空卷首次启动暴露 Spring Boot 4 下支付/物流服务仅引入 `flyway-core` 未激活自动迁移；改用 `spring-boot-starter-flyway` 后完成 V1–V3/V1–V4。
- 验证：商城/支付/物流 Flyway 分别为 V24/V4/V5，无失败迁移；初始数据为 6 用户、6 商品、0 订单。
- 链路：Windows 和容器化 Bash 均跑完登录、下单、签名支付回调、商家履约、物流、手动/自动收货、自动完成、commerce-service 重启租约恢复和评价，业务审计为 `1|1|2|1|1`。
- 破坏性边界：默认保留临时卷以便排查；只有显式 `-RemoveTemporaryVolumes` 才删除本次临时项目卷。
- 提交：本阶段独立提交（见 Git 历史）。

### 2026-11-13：旧库升级、全栈重启恢复与历史一致性

- 升级边界：在隔离 MySQL 中重放 V1–V16，插入历史订单后升级到 V24，验证 30 分钟支付截止时间回填、核心注释和当前约束；这是可重放旧库演练，不冒充生产备份升级。
- 持久卷：现有商城/支付/物流数据已非破坏性升级至 V24/V4/V5，无失败迁移；本阶段未删除任何现有卷。
- 重启范围：三个 MySQL、Redis、Kafka、支付/物流服务、services/commerce-service/web/UI、RabbitMQ 和 RocketMQ 均实际重启并恢复健康。
- 未完成任务：注入已领取但租约过期的 `AUTO_COMPLETE` 任务；Windows 任务 `1391/1392`、容器化 Bash 任务 `1393/1394` 分别由 RabbitMQ/RocketMQ 恢复，`TASK_LEASE_EXPIRED/TASK_CLAIMED/TASK_SUCCEEDED` 均各一次。
- 历史一致性：终态订单、支付、已送达物流和评价的计数+行 CRC 聚合指纹在重启前后均为 `300:3562683193|559:1055010103|83:1695107719|21:1066884382`。
- 提交：本阶段独立提交（见 Git 历史）。

### 2026-11-13：Windows/Bash 脚本契约与默认非破坏性审计

- 文件名：35 组 `verify-*` PowerShell/Bash 入口基名一一对应；Windows-only 的视频生成器不属于业务验收入口。
- 参数修正：对齐物流服务 URL、支付服务 URL、RabbitMQ 用户名/密码/等待时间，以及支付/取消竞态的有序参数。
- 安全语义：不存在默认 `down -v`、`docker volume rm/prune`；空卷专项的临时卷删除只能显式开启，Flyway 临时 MySQL 改用 tmpfs 避免匿名卷泄漏。
- 自动审计：PowerShell 与容器化 Bash 入口均返回通过，契约覆盖 35 组脚本；全部 PowerShell AST 和 Bash `-n` 语法检查通过。
- 轻量实链路：Windows 支付通知的新支付地址参数通过；容器化 Bash 按新顺序运行 1 轮支付/取消竞态，以 `PAID/SUCCESS` 且数据库审计通过结束。
- 提交：本阶段独立提交（见 Git 历史）。

### 2026-11-13：快速回归入口与 M1 基线冻结

- 入口：新增 `verify-quick.ps1`，默认构建当前源码镜像，支持 `-SkipBuild`；不删除或清空数据卷。
- 覆盖：36 组脚本契约、Compose 配置/健康、权限/i18n、订单取消、支付/取消竞态、全额退款、物流、手动/自动收货、自动完成、评价、commerce-service 租约恢复和 Redis 降级。
- 验证：Windows `-SkipBuild`、容器化 Bash `--skip-build` 和 Windows 默认构建模式均通过；默认构建模式使用当前源码重建五个应用镜像。
- 边界：快速回归不代替两分钟浸泡、Broker 停机、空卷全链路或旧卷重启专项；这些已在前置阶段独立验收。
- 冻结：本阶段提交以注释标签 `m1-baseline-2026-11-13` 冻结并推送。M1 后停止新增业务代码；验证体系文档已在 M1 后单独建立。

后续记录继续使用以下模板：

```text
日期：YYYY-MM-DD
阶段：1～6
完成代办：对应复选项
迁移：Flyway 版本和表结构
配置：主策略、对账开关和 Docker Profile
验证：测试、脚本、故障实验和结果
遗留：未完成项、风险和人工验收
提交：Git 提交号；未提交时写“未提交”
```

# CommerceFlow Lab 项目历史

本页合并保存截至 2026-11-22 的旧版业务待办、项目状态和 M2 治理记录。文中的版本、批次顺序、提案、运行状态与验收结论对应其记载的时间点。当前安排见 [项目进度与业务待办](../TODO.md) 和 [架构进度与待办](../architecture/README.md)。

- [旧版待办与实施记录](#旧版待办与实施记录)
- [旧版项目状态与里程碑记录](#旧版项目状态与里程碑记录)
- [M2 代码质量治理记录](#m2-代码质量治理记录)

## 旧版待办与实施记录

### 1. 文档职责

| 项目 | 内容 |
| --- | --- |
| 性质 | 业务进度与阶段导航，架构收尾的勾选和验收标准由对应专题维护 |
| 主线 | 原 `temp.md` 中准备实施的订单闭环方案 |
| 合并内容 | 订单超时策略、Database/RabbitMQ/RocketMQ 讨论、截图阶段 4～6 和旧实施计划 |
| 当前状态 | P0–P4、计价与运费、按行售后、单商家满减券、退货跟踪，以及商家入驻与归属、应结账本、模拟打款均已验收并发布。 |
| UI 边界 | 客户订单中心已改为摘要、当前主操作与可展开详情，支付、退款、物流和时间线保持独立事实展示 |
| 最后更新 | 2026-11-20 |

本文档保留已完成勾选和未完成项，用于表示实施顺序和阶段门禁。已实现能力的详细证据见 [IMPLEMENTED.md](../IMPLEMENTED.md)，当前快照见 [PROJECT-STATUS.md](#旧版项目状态与里程碑记录)，详细策略见 [通用定时任务策略](../reference/SCHEDULED-TASK-STRATEGIES.md)。第 13 节各批次和第 14 节实施记录的脚本数量、准入结论与拓扑均对应当时版本。旧草案和旧实施计划移入 `archive/`。

### 2. 当前基线与本轮边界

当前按真实入口验收基础业务，发现阻断即修复。先解决全新演示环境能登录、选商品、加购、下单和支付，再验证商家接单发货、客户看物流和收货，以及取消、退款与库存结果。

- [x] 全新隔离环境通过显式演示初始化跑通上述基础 API。支付宝模拟实付、接单发货、物流签收、手动收货、取消释放和全额退款回补已核对。[本轮验收记录](../IMPLEMENTED.md#21-基础业务隔离复验)
- [x] 在隔离环境用实际浏览器走查客户和商家页面。修复商家履约、客户取消和退款的异步状态停滞，并验证异常提示。[页面验收记录](../IMPLEMENTED.md#22-基础业务页面走查)
- [x] 支付失败后的同渠道重试、响应丢失后的幂等重试及微信转银联已通过隔离浏览器验收。前端修复已更新到日常 Docker 门户。[支付重试验收](../IMPLEMENTED.md#23-支付失败与重试)
- [x] 日常商城和支付编排已发布银联契约修复。以 V51 运行基线构建，仅更新两处支付渠道枚举，使用同一批镜像完成隔离支付和库存核对。[发布记录](../IMPLEMENTED.md#23-支付失败与重试)

#### 基础功能实施顺序

1. [x] 商品列表排序。支持价格升降序和评分优先，与现有搜索一起使用。
2. [x] 客户订单查询。按订单号或商品搜索，按订单状态筛选，显示匹配数及无结果提示。
3. [x] 商家订单查询。复用订单筛选，并支持按收件人或运单号定位履约订单。

三项已通过隔离浏览器验收并更新到日常 Docker 门户。[验收记录](../IMPLEMENTED.md#24-商品排序与订单查询)

#### 基础业务第二批

1. [x] 结算前确认。直接选择收货地址，核对商品、数量和应付金额，校验失败后保留购物车。
2. [x] 商家商品管理。可维护自有商品资料、价格和上下架状态，保留历史订单快照。
3. [x] 库存不足和下架提示。顾客可查看库存、定位受影响的购物车条目，并在预占拒绝后调整购物车。
4. [x] 商家待处理订单。提供待接单和待发货队列，履约操作完成后刷新笔数。
5. [x] 订单服务端查询与分页。订单服务按角色、关键字、状态和下单日期查询，门户展示匹配总数和分页。

五项均已通过隔离 Docker 和 MySQL 验收。[第二批验收记录](../IMPLEMENTED.md#25-基础业务第二批验收)

- [x] 发布到日常 Docker，实测结算、支付、接单、发货、履约笔数和历史订单查询。[日常发布记录](../IMPLEMENTED.md#26-基础业务第二批日常发布)
- [x] 按选中商品结算。购物车支持单选和全选，订单与库存仅处理选中条目，未选商品继续保留。已发布到日常 Docker。[验收记录](../IMPLEMENTED.md#27-购物车按选中商品结算)
- [x] 历史订单再次购买。已收货、已完成订单可按当前售价和库存加购，逐项展示结果。同一请求重试只加购一次。已发布到日常 Docker。[验收和发布记录](../IMPLEMENTED.md#28-历史订单再次购买)
- [x] 订单详情直达与通知跳转。支持独立订单链接、相关区域定位和列表筛选恢复。已通过隔离验收并发布到日常 Docker。[验收记录](../IMPLEMENTED.md#29-订单详情直达与通知跳转)

#### 2.1 直接复用的现有能力

- [x] `PENDING_PAYMENT -> PAID -> CONFIRMED -> SHIPPED -> RECEIVED` 基础流转。
- [x] 三渠道模拟支付、签名通知、幂等、Outbox、Kafka 和支付对账。
- [x] 未发货已支付订单首版整单全额退款。
- [x] 商家确认、手工录入承运商/单号、发货和物流信息更正。
- [x] 客户手动确认收货和一次性商品评价。
- [x] ShedLock 与跨平台验收脚本。Windows、Linux 和 WSL 统一使用 PowerShell 7 运行 `scripts/` 中的同一份入口。

#### 2.2 本轮必须补齐

- [x] `CANCELLING`、`CANCELLED`、`COMPLETED` 订单状态。
- [x] 客户主动取消未支付订单，Angular 客户页面已按 `availableActions` 接入。
- [x] 整单支付截止时间、Database 自动关单及与支付成功的并发状态处理，Windows/Bash 真实竞态浸泡压测已通过。
- [x] Database、RabbitMQ、RocketMQ 可插拔主触发和可独立开关的 Database 对账补偿。
- [x] 任务策略快照，保证同一任务不随全局配置中途换策略。
- [x] 独立模拟物流服务、签名回调、轨迹投影与客户查询。
- [x] 物流签收后由 `AUTO_RECEIVE（自动确认收货）` 推进收货，再由 `AUTO_COMPLETE（自动完成订单）` 执行 `RECEIVED -> COMPLETED`。
- [x] 订单响应新增 `availableActions`，客户页面已接入取消、支付、退款、物流、收货和评价操作，历史支付/退款/物流信息保留。

#### 2.3 基础业务批次的范围边界

- 退货、换货、部分退款和售后工单列入第 2.5 节的后续实施批次。
- 真实支付机构或真实物流渠道接入。

#### 2.4 后续项目计划与批次验收

截至 2026-11-19，日常 Docker 已运行购物车勾选结算、历史订单再次购买和订单详情直达。
商城库为 V53。再次购买和订单详情直达均已有对应发布提交。订单、目录、通知监控及取消跨库恢复有隔离验收记录，
日常部署状态在对应发布批次重新核对。计划按下列顺序推进，每批可连续完成实现、验收、记录和提交。

##### P0 对齐日常发布版本与源码

- [x] 推送已验收的两笔提交，核对 `origin/main`、日常容器镜像 ID、前端资产和商城 Flyway 版本。
- [x] 用隔离工作树基于该提交构建，只吸收本批需要的改动。记录其余架构改动对应的专题和验证状态，避免混入基础业务镜像。
- [x] 按第 2.5 节确定金额快照、订单行、后续服务所有权与权限边界，让 P1、P2 的验收一起验证兼容性。
- [x] 用现有 PowerShell 7 验收入口建立快速回归基线，记录失败项和可重现步骤。确认上线版本可由 Git 重建，
  以及五笔历史库存命令仍为 `CLOSED`、对应库存流水为零。

##### P1 顾客从选购到支付

- [x] 一起验收商品搜索排序、购物车勾选、地址选择和结算确认。覆盖下架、库存变动、价格变化、地址失效和双击提交；
  修复发现的阻断。价格变化以服务端实际应付金额为准，确认页能让顾客重新核对，失败后保留可继续编辑的购物车。
- [x] 统一购物车逐项异常提示和调整入口。覆盖再次购买的部分成功、同款合并、请求回执重试和继续结算；
  对照结算明细、回执表、购物车余项和库存预占核对。
- [x] 用日常拓扑对应的候选镜像覆盖三种模拟支付渠道、失败重试、切换渠道、超时和回调重放。
  门槛为同一订单最多一笔成功扣款，库存状态与实际预占或扣减流水一致。

##### P2 商家履约与顾客订单闭环

- [x] 完成商家改价、上下架、库存调整、待接单和待发货的同场景验收。商家只处理自有商品和订单，
  历史订单快照保持原值，刷新后队列数量与订单库一致。
- [x] 验收多商品订单发货、运单更正、物流轨迹、手动或自动收货、订单完成和评价。
  核对重复操作时出库和运单各只有一条有效业务记录。
- [x] 验收未支付主动取消和超时取消，以及未发货的整单全额退款。以取消释放、退款回补及渠道资金流水核对结果；
  从顾客订单详情检查状态、可用操作和通知跳转。按可用角色覆盖越权访问和错误提示。
- [x] 汇总 P1、P2 的修复，在日常 Docker 逐服务发布候选镜像。验收保留代表成功、缺货、取消、退款的订单证据，
  按日常实际产生的通知核对跳转与已读回执。基础业务发布完成后冻结可复测基线。

P0–P2 已通过隔离验收和日常 Docker 抽检，冻结基础商城源码及镜像基线。[验收与发布记录](../IMPLEMENTED.md#210-基础商城-p0p2-验收)

##### P3 运行监控与订单取消恢复

- [x] 单独整理库存、订单、目录、通知四条链路的指标和告警，逐一确定已提交、隔离验收和日常运行状态。
  复用现有专项脚本，先验证正常、故障触发和恢复，再检查日常抓取及告警规则加载。
  商城库存命令监控与库存服务出库事件监控分别记录。
- [x] 单独发布取消跨库恢复。基于当前 V53 核对 V52 表在最小权限下的运行账号读写，
  用发布候选镜像隔离验证终态已提交、Outbox 尚未提交时重启补齐，库存释放和通知各完成一次。
  日常以新建验收订单抽查，五笔归档不重放。
- [x] 用同一份 PowerShell 7 入口复用现有模块测试、快速回归和专项。打通从订单号、命令 ID、事件 ID
  到相应服务日志和积压监控的查询方式。按 [现有验证体系](../VERIFICATION-SYSTEM.md) 留存结果。

P3 已隔离验收并完成日常发布。[验收与发布记录](../IMPLEMENTED.md#211-p3-运行监控与订单取消恢复)

##### P4 M3 收尾与旧源清理

- [x] 隔离验收履约候选版和带新增发货的版本化回退，随后将同一候选镜像发布到日常 Docker。
- [x] 定向归档并恢复验证旧源，确认无运行依赖后清理。日常九库与五笔历史归档已复核。见[P4 验收记录](../architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#29-2026-11-20-p4-日常发布和旧源清理)。

##### 范围和推进规则

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

#### 2.5 缺失能力与服务拆分顺序

日常 Docker 已运行商城及六个内部服务：目录、库存、订单、支付编排、履约和通知。
支付、物流模拟器代表外部平台。登录、收货地址、购物车、评价、调度以及物流桥接和轨迹投影仍由商城模块持有。
新增能力先明确状态归属、读写入口和测试边界，再根据业务负载和部署需求决定是否独立。

| 能力 | 当前范围 | 提前准备与建设顺序 |
| --- | --- | --- |
| 售后申请、退货和部分退款 | 未发货整单退款、按订单行部分退款、验收后入库和首版售后异常工单已交付。同店同 SKU 无差价换货后端及三类页面均已验收并日常发布。[换货页面发布](../IMPLEMENTED.md#2313-换货页面日常发布) | 独立售后服务按业务需求评估。 |
| 运费与仓配规则 | 最小运费模板、结算快照、单仓多包裹和同商家两仓拆履约首版均已发布 | 继续按订单行核对数量、运费、库存、运单和退款。更复杂的仓库选路或拆单场景按需求评审。 |
| 定价、促销和优惠券 | 目录售价、下单价格快照和单商家订单满减券已发布 | 沿用按订单行冻结优惠分摊的规则。扩展促销时继续核对并发核销、取消返还和退款上限。 |
| 物流接入 | 已交付正向物流和首版退货运单跟踪。运单桥接、回调治理和轨迹投影由商城持有，渠道运单使用现有物流模拟器。 | 需要第二个承运商或独立管理回调容量时，把桥接、回调 Inbox、运单和轨迹作为首个独立服务候选。 |
| 商家入驻与结算 | 店铺入驻、订单行商家归属、应结账本、佣金和模拟打款均已发布 | 商家账单和打款继续按支付、退款和结算事实逐笔核对。根据业务负载评估是否独立。 |
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
[微服务演进计划](../architecture/README.md#6-微服务演进准入)，一次切换一个数据所有权。

##### 后续实施批次

**基础主线。** P0 先确定金额快照、订单行、商家归属及历史订单的数据迁移规则。
旧数据迁移后走统一业务路径，过渡兼容按[代码规范](../CODE-STYLE.md#7-临时兼容与最终验收)退出。P1、P2 在现有商城闭环中核对这些规则。
P2 日常验收通过后，按下表交付；各阶段独立验收和发布。

| 顺序 | 计划交付 | 依赖与验收 |
| --- | --- | --- |
| 1. 结算计价与运费（已发布） | 建立统一计价和订单金额快照，再提供最小运费模板。记录商品金额、优惠分摊、运费和实付，首版普通订单优惠为零。见[发布验收](../IMPLEMENTED.md#213-统一结算计价与最小运费模板)。 | 地址或购物车变动后重新计算，价格变动要求顾客重新确认。新单能支付，历史单仍按原金额查询和退款。 |
| 2. 按订单行售后与部分退款（已发布） | 先做申请、审核、部分退款与资金对账；需要退货时由签收或验收结果触发后续动作，核对库存回补和退款。首版跑通后再排换货与售后工单。[发布验收](../IMPLEMENTED.md#214-按订单行售后与部分退款)。 | 读取下单金额快照，覆盖部分数量、累计退款上限、运费处理、越权、重复退款和重复入库。用有优惠分摊的隔离订单提前验证退款计算。 |
| 3. 促销与优惠券（已发布） | 顾客领券、单商家固定金额满减、结算试算、按行分摊、并发锁券和付款核销。[功能说明](../features/CHECKOUT-COUPONS.md) | 同一候选版本通过并发领券与锁券、取消返还、超时、跨库中断、支付竞态、部分退款和页面验收。[发布验收](../IMPLEMENTED.md#215-单商家订单满减券) |

**可并行和按条件启动的能力。**

| 能力 | 启动条件与验收 |
| --- | --- |
| 物流接入（首版退货跟踪已发布） | 复用模拟器，绑定、回调重放、乱序、跨库中断、存量运单查询及验收后退款回补已核对。[验收记录](../IMPLEMENTED.md#216-首版退货运单跟踪) 接入第二家承运商或出现独立扩容需求后再评审拆分。 |
| 多包裹、多仓与拆单 | 单仓多包裹和同商家两仓拆履约首版已发布。扩展时继续校验订单行数量、运费、库存预占、运单和退款。 |
| 商家入驻与结算 | 店铺归属、应结账本、佣金和模拟打款已逐笔核对并发布。 |
| 商品搜索与推荐 | 全文检索、组合筛选和索引重建已验收。后续按数据量和吞吐评估扩容，推荐独立评估。 |

P3 的监控和取消恢复以及 P4 的履约专项、旧源归档和回退验收均已完成。

##### 商家经营与后续功能五章

五章均已完成隔离验收与日常 Docker 发布，证据见各章记录。

| 章节 | 首版与实施顺序 | 隔离与日常验收 |
| --- | --- | --- |
| 1. 最小商家入驻、店铺与归属（已发布） | 一账号一店铺、存量回填、资料和审核、公开店铺、商品草稿与库存快照，订单沿用单商家边界。 | 隔离验证交易归属、越权、跨店结算及停用后存量退款。日常验收订单 712。[验收](../IMPLEMENTED.md#217-商家入驻与店铺归属) |
| 2. 应结账本、账单与佣金（已发布） | 成交行实付入账、冻结佣金、暂缓活跃售后、跨期退款冲正与账单明细。 | 隔离和日常账单均逐笔核对，覆盖并发出账和中断重跑。[验收](../IMPLEMENTED.md#218-商家应结账本与佣金) |
| 3. 模拟商家打款（已发布） | 冻结账单驱动、独立出款 API 和账本、后台双菜单、签名回调及主动查询。 | 隔离验证并发、失败重试、渠道提交后中断及重复回调。日常专用账单出款一次并核对金额。[验收](../IMPLEMENTED.md#219-模拟商家打款) |
| 4. 单仓多包裹（已发布） | 单商家单仓、按订单行分配包裹，顾客与商家查看独立运单和轨迹，支持未发数量退款与收货后部分退货。 | 隔离和日常通过同一脚本。日常订单 716 完成 1+2 发货。[功能说明](../features/ORDERS-FULFILLMENT.md#单仓多包裹) · [验收记录](../architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#30-单仓多包裹首版验收) |
| 5. 商品全文搜索与可重建索引（已发布） | 中文多词匹配、店铺与价格区间筛选、目录事件增量和代次重建，顾客入口已切换。 | 隔离覆盖暂停消费、旧事件重放和空索引重建。日常订单 719 核对成交单价 95.00 元。[验收记录](../architecture/CATALOG-SERVICE-EVOLUTION-PLAN.md#6-商品全文搜索与索引重建验收) |

第 4 章两仓拆履约首版已发布。日常订单 720 同一订单行由两仓各发 1 件，订单 721 验证第二仓未发取消、
默认仓已发退货均回补原仓并按快照退款。[功能说明](../features/ORDERS-FULFILLMENT.md#两仓拆履约) ·
[验收记录](../architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#31-两仓拆履约首版验收)

仓库独立实发、短发、退货实收与质检和售后异常工单均已发布。工单承接仓库短收、拒收和质检不通过，
已验证补证、按合格件数退款回补及拒绝结案。[工单验收](../architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#34-售后异常工单验收)

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

#### 2.6 外部系统模拟器计划

现有支付和物流模拟器继续复用。下列项目随对应业务启动，排序按业务依赖确定。
模拟器只实现外部系统的协议、响应和回执，用于开发和隔离验收。业务规则和权威数据由项目内对应模块或服务维护。

| 待办 | 启动条件 | 首版交付与验收 |
| --- | --- | --- |
| [x] 短信与邮件模拟供应商 | 共用一个模拟服务和双菜单后台。 | 完成签名受理与查询、投递账本、限流、超时和送达失败。[验收记录](../IMPLEMENTED.md#223-短信与邮件模拟平台) |
| [x] 退款成功和售后工单站外通知 | 退款成功通知顾客，新建异常工单通知商家。 | 隔离验证事件去重、渠道独立送达和超时查回，日常 Docker 验证两类通知双渠道送达。[验收记录](../IMPLEMENTED.md#224-退款与工单站外通知) |
| [x] 仓库系统（WMS）出库实发与短发 | 两仓履约发布后接入独立仓库出库回执。 | 两仓各实发 1 件、短发后取消退款、拒绝出库、重复回执和重启恢复已验收。[发布记录](../architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#32-wms-出库实发与短发验收) |
| [x] 仓库系统（WMS）退货实收与质检 | 出库回执、幂等和查询对账已通过日常验收。 | 已验收原包裹与原仓绑定、质检后商家验收、原仓回补和差异待复核。[发布记录](../architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#33-wms-退货实收与质检验收) |
| [x] 商家打款渠道 | 现有支付模拟平台已交付独立模拟打款。 | 完成受理、成功、失败、查询和对账。隔离和日常均核对一笔成功及原账单金额。[验收](../IMPLEMENTED.md#219-模拟商家打款) |
| [x] 电子发票平台 | 有电子发票业务需求时，结合订单实付和退款事实实施。 | 支持开票受理、异步结果、失败重试及退款后的红冲。核对发票与订单、退款记录，验证重复请求和回执不会重复开票或红冲。[分批方案](#电子发票模拟平台与商城开票) |

模拟平台按需预留 `service/`（服务端）和 `web/`（管理后台 UI），沿用现有支付模拟器的目录结构。

| 平台 | 服务端 | 管理后台 UI |
| --- | --- | --- |
| 物流 | [logistics-platform/service](../../simulators/logistics-platform/service) | [logistics-platform/web](../../simulators/logistics-platform/web)（已发布，[回调恢复与页面回归](../IMPLEMENTED.md#233-模拟管理台回调恢复与页面回归)） |
| 短信与邮件 | [messaging-platform/service](../../simulators/messaging-platform/service) | [messaging-platform/web](../../simulators/messaging-platform/web) |
| 仓库系统 | [warehouse-platform/service](../../simulators/warehouse-platform/service) | [warehouse-platform/web](../../simulators/warehouse-platform/web) |
| 电子发票 | [invoice-platform/service](../../simulators/invoice-platform/service) | [invoice-platform/web](../../simulators/invoice-platform/web) |
| 商家打款 | 复用 [payment-platform/service](../../simulators/payment-platform/service) | 复用 [payment-platform/web](../../simulators/payment-platform/web) |

##### 电子发票模拟平台与商城开票

首版服务于单商家订单。顾客可为已支付订单申请一张模拟正票，已开票订单发生成功退款时支持多次部分红冲。
订单服务提供订单行商家归属和下单金额快照，支付编排提供实付与退款事实。商城发票模块保存开票申请、权限和处理进度，
发票模拟平台独立保存受理记录、模拟票据和回执。

**开票金额**

- 首版票面包括订单行净额和订单实际收取的运费。商品金额以订单行原价扣除下单时冻结的优惠分摊为准，运费取原订单快照。
  商品净额和运费合计须与支付编排确认的成功实付金额、币种一致。金额或商家归属不一致时，进入待核对状态。
- 提交正票时冻结票面金额、币种、原支付单号和已经扣减的成功退款单号。开票金额等于成功实付减去本次快照已纳入的成功退款。
  退款金额取原支付单对应的成功退款账本，含该笔实际退还的运费。待处理或失败退款暂不扣减，余额为零时不发起开票。
  金额用定点十进制，同一订单按原支付币种核算。退款与运费沿用[既有业务口径](../features/PAYMENTS-AFTER-SALES.md#实付运费与库存)。

**退款与红冲**

| 退款发生时点 | 处理规则 |
| --- | --- |
| 开票请求冻结前 | 成功退款纳入开票快照，扣减正票金额。已纳入的退款单后续重复送达时，继续使用已扣减的结果。 |
| 请求已提交，结果仍在处理或超时 | 保留原请求号、原金额和已扣减退款清单，记录快照外的成功退款。先按原请求号查回结果，确认已开票后补红冲。渠道确认终态失败且未出票时，重新核算当前可开金额，再以新的提交尝试处理同一申请。 |
| 正票已确认 | 每笔快照外的成功退款按原退款单号关联正票发起红冲，支持多次部分红冲。全额退清时原票减去累计成功红冲为零。 |

- 同一订单并发申请共用一个开票申请号，每个申请只允许一张成功正票。红冲以原正票和退款单号唯一。相同提交尝试重放沿用原请求号和相同请求内容。
  重复、迟到和乱序回执须查回原结果，已确认的票据状态保持稳定。
- 单笔红冲以该笔成功退款金额为准。并发受理时，在途和已成功红冲共同占用可红额度，合计不得超过原票金额。
  金额、币种或原单关系不符时保留差异并待核对。红冲结果写入独立记录，原票及历史退款事实留存。
- 对账样例为实付 300.00 CNY，开票前成功退款 50.00，正票为 250.00。开票冻结后另有一笔 80.00 的成功退款，
  正票确认后补一笔 80.00 红冲，有效票面为 170.00。重复投递两笔退款仍得到相同结果。

**分批实施与验收**

| 批次 | 交付和进入下一批的条件 |
| --- | --- |
| 1. 开票口径与模拟平台（已通过隔离验收） | 冻结金额和状态约定，完成签名受理、结果查询、异步回执、模拟票据账本和红冲接口。在隔离 MySQL 验证正常出正票、部分红冲、明确失败、超额拒绝、重启恢复。模拟已受理后响应超时、重复请求和回执，逐笔核对同一请求只有一个出票或红冲结果。 |
| 2. 商城开票与退款联动（已通过隔离验收） | 通过现有订单和支付契约读取权威事实，保存申请快照，接入成功退款并完成红冲恢复。在隔离 MySQL 核对有优惠和运费的订单，覆盖冻结前退款、受理中退款、开票后多次部分退款、全退、重复与乱序回执和跨进程中断恢复。逐笔关联订单、支付单、退款单、正票和红冲，核对有效票面等于正票减累计成功红冲。 |
| 3. 页面和发布验收（隔离浏览器及日常发布均已验收） | 顾客申请并查看自己的票据，商家查看本店票据，授权平台账号查询和处理失败或待核对记录。用两家商户在隔离浏览器中验证各方入口、权限与跨店隔离。隔离通过后使用同一候选版本发布日常 Docker，用新订单验收支付、开票、退款红冲，复核历史账本与数据卷，记录版本、对账证据和回退方式。 |

第一批于 2026-11-21 通过隔离 Docker/MySQL 验收。逐笔核对 9 条终态请求、7 张票据和 9 条回执，主正票 250.00 CNY 累计红冲后余额为零。[第一批验收记录](../IMPLEMENTED.md#228-电子发票模拟平台第一批隔离验收)

第二批于 2026-11-21 通过隔离验收。实付 300.00 CNY，冻结前退款 50.00，正票 250.00；冻结后红冲 80.00、100.00 和 70.00，最终余额为零。
末笔红冲含退运费 20.00 CNY。两次强制重启均查回原请求，[第二批验收记录](../IMPLEMENTED.md#229-商城开票与退款红冲第二批隔离验收)

第三批页面于 2026-11-21 通过两家商户的隔离 Chromium 验收，覆盖顾客申请、本人票据、商家跨店隔离和授权平台复核。
同一候选版本已发布到日常 Docker。订单 736 实付、正票、退款红票均为 1,299.00 CNY，有效票面为零，3,524 条历史记录逐行核对一致。[第三批验收及回退记录](../IMPLEMENTED.md#230-电子发票第三批页面隔离验收)

##### 支付模拟平台的收款与打款

收款退款和商家打款共用一个 `payment-platform` 模拟平台，沿用同一个服务端部署与管理后台。
商家结算已接模拟出款，渠道资金结果由独立打款账本持有。

- **收款与退款**沿用顾客订单链路，维护收款和退款自己的接口、状态机及账本。
- **商家打款**按商家结算单发起，拥有独立的打款发起和结果查询接口、出款状态机及账本。
  按打款单号保证幂等，验收重复请求、回执和对账。
- 管理后台分为 **「收款与退款」** 和 **「商家打款」** 两个菜单，分别查询各自的交易、状态和账本。

##### 正式支付后台的运营统计

- [x] 首版交付只读运营报表，包含创建交易趋势、成功支付笔数、实收、退款、净收、渠道对比和状态分布。
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
| 1. 账本口径与汇总接口（隔离验收已完成） | 内部查询通过 MySQL 8.4 逐笔对账，覆盖重复回执、多次支付和部分退款、跨日负净收、多币种、时区边界和空结果。[首批隔离验收](../IMPLEMENTED.md#225-正式支付运营统计首批隔离验收) |
| 2. 商家归属与权限（隔离验收已完成） | 共用支付宝渠道账户的两商家逐项核对日、渠道和币种汇总，验证新增归属、历史回填重跑、全量及限定平台权限、商家本人、越权过滤、既有会话撤权与未归属账本隔离。[第二批隔离验收](../IMPLEMENTED.md#226-正式支付运营统计第二批商家归属与权限) |
| 3. 运营页面与发布验收（已日常发布） | 日期、时区、渠道、币种和授权商家筛选已接入。两商家浏览器核对趋势、卡片、状态分布、跨日退款、空结果与失败重试。[隔离与日常验收](../IMPLEMENTED.md#227-正式支付运营统计页面隔离验收) 日常使用同一候选镜像，完成新建订单、支付和退款账本抽检、权限校验及回退记录。 |

运维告警继续复用现有 [`alert-webhook`](../ALERTING.md)。库存预占、运费计价、优惠核销、售后审核和商家应结金额
由业务代码实现，使用隔离数据验收。

### 3. 必须先固定的业务语义

#### 3.1 目标订单状态机

```text
PENDING_PAYMENT
    ├─ 支付成功 ─> PAID
    └─ 客户取消/整单超时 ─> CANCELLING ─> CANCELLED

PAID
    ├─ 客户退款 ─> REFUNDING ─> REFUNDED
    └─ 商家确认 ─> CONFIRMED ─> SHIPPED ─> RECEIVED ─> COMPLETED
```

退款失败时恢复到退款前的 `PAID` 或 `CONFIRMED`，不能改写原支付成功事实。

#### 3.2 三个不可混淆的边界

1. **支付尝试过期 ≠ 整单超时**：单次 `expires_at` 到期只使该尝试进入 `EXPIRED`；整单 `payment_due_at` 未到时仍可换渠道重试。
2. **物流签收 ≠ 客户收货**：`DELIVERED` 只代表物流渠道认为送达；客户可手动确认，或等待观察期后自动进入 `RECEIVED`。
3. **`RECEIVED` ≠ `COMPLETED`**：已收货后仍有观察期；观察期结束才进入稳定终态。`COMPLETED` 不依赖商品已评价。

### 4. 开工前需确认的决策

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

### 5. 阶段 1：订单生命周期基础

- [x] 新增 `CANCELLING`、`CANCELLED`、`COMPLETED`。
- [x] `shop_orders` 新增 `payment_due_at`、`cancelled_at`、`cancel_reason`、`received_at`、`completed_at`、`logistics_delivered_at`。
- [x] 新订单写入 `payment_due_at = created_at + payment-timeout`；历史订单按 `created_at + 30m` 回填。
- [x] 新增 `payment-timeout`、`auto-receive-after-delivery`、`auto-complete-after-receipt` 配置。
- [x] 订单事件保留操作人、触发源、旧/新状态、原因和任务 ID。
- [x] 新增 `docs/ORDER-LIFECYCLE.md`，统一订单、支付、退款、物流和任务状态。
- [x] 订单响应新增 `availableActions`；写接口仍在后端重新验证。
- [x] Flyway 空库创建、旧数据升级和非法状态转换测试通过；Windows/容器化 Bash 已使用隔离 MySQL 验证 V1–V24 与 V16→V24，商城 25 项测试包含订单非法转换。

### 6. 阶段 2：未支付订单关闭与可插拔触发

先实现唯一正确的关单 Handler，再接三种触发器；不得各写一套业务逻辑。

#### 6.1 客户主动取消

- [x] 新增 `POST /api/purchase-orders/{orderId}/cancel`，仅订单所属客户可取消 `PENDING_PAYMENT`。
- [x] 锁定订单，执行 `PENDING_PAYMENT -> CANCELLING`，关闭所有 `PENDING` 渠道交易。
- [x] 全部关闭后执行 `CANCELLING -> CANCELLED`，写入时间、原因和订单事件。
- [x] 单体阶段曾将取消终态与 `ORDER_CANCELLED` Outbox 同事务写入。切流后订单终态和商城 Outbox 分属两库，跨服务中断恢复列入第 13.25 节关联的架构清单。
- [x] 实现订单取消 Outbox 的库存释放、客户通知等下游消费者；Kafka 独立消费组与消费记录保证重复消息不重复释放或通知。
- [x] 无支付记录或全部已 `FAILED/CLOSED/EXPIRED` 时可直接完成取消。
- [x] 存在成功支付时普通取消不会覆盖支付事实，订单保持/恢复 `PAID`；前端已通过独立退款动作处理已支付订单。
- [x] 重复请求幂等返回原结果，不重复改变已关闭渠道交易。

#### 6.2 支付/取消并发

- [x] 渠道关闭先成功时进入 `CANCELLED`。
- [x] 支付通知先落库时支付优先，订单进入 `PAID`，普通取消不覆盖。
- [x] `CANCELLED` 后意外成功通知不忽略：恢复支付事实，使用稳定幂等键创建自动退款单，转入 `REFUNDING` 并复用退款对账。
- [x] 使用两个真实线程和受控门闩复现“取消已进入 `CANCELLING`、支付成功回调随后到达”；数据库锁与条件更新保证最终为 `PAID/SUCCESS`，且不生成 `ORDER_CANCELLED`。
- [x] Docker MySQL + 真实模拟支付平台的长时间随机化竞态压测：Windows 120.6 秒/95 轮、容器化 Bash 121 秒/97 轮，两类合法终态均覆盖且 MySQL 矛盾记录为 0。

#### 6.3 统一定时任务领域

- [x] 首批任务类型：`PAYMENT_TIMEOUT/AUTO_RECEIVE/AUTO_COMPLETE/REFUND_RECONCILIATION/PAYMENT_RECONCILIATION/LOGISTICS_RECONCILIATION/NOTIFICATION_RETRY/REVIEW_REMINDER/INVENTORY_RELEASE/AFTER_SALE_DEADLINE`；类型由管理员启停和配置，Handler 由代码安全注册。
- [x] 任务状态：`CREATED/SCHEDULED/PROCESSING/SUCCEEDED/FAILED/CANCELLED`。
- [x] 建立任务表，包含唯一业务键、版本、租约、尝试次数、最后错误、下次执行时间。
- [x] 保存主触发、对账、宽限期、租约、重试和配置版本快照。
- [x] 同一任务类型+业务键只允许一条任务，调用方使用稳定业务键保证聚合幂等。
- [x] Database 主触发与 Database 对账调用同一 `ScheduledTaskExecutor` 和 Handler；后续 MQ 也必须复用该入口。
- [x] `PAYMENT_TIMEOUT` 通过同一取消服务和 Handler 关单，禁止直接批量 SQL 改状态；事件名统一为取消阶段事件并携带任务来源。

#### 6.4 三种主触发

- [x] **Database**：ShedLock + 分批领取，支持周期、批次、原子领取、租约恢复和最大尝试次数。
- [x] **RabbitMQ**：持久化分桶 TTL + DLX、Publisher Confirm/Return、有限退避重试、DLQ、早到重新分桶、数据库原子抢占和重启补发。
- [x] **RocketMQ**：5.x gRPC 客户端、原生绝对投递时间、Broker 回执、发布失败重试、消费端重试/死信、早到重投和重启后未发布任务恢复。
- [x] RabbitMQ/RocketMQ 消息只携带 `taskId/taskType/dueAt/strategySnapshot`，消费时从数据库重读任务和业务事实。

#### 6.5 Database 对账和管理配置

- [x] `reconciliation.enabled` 是独立补偿开关，不是第四种主策略。
- [x] 只处理超过 `dueAt + gracePeriod` 仍未完成的任务，保留原策略快照和补偿来源。
- [x] 通过 `@ConfigurationProperties` 完整绑定并校验全部通用策略、RabbitMQ 和 RocketMQ 适配器字段，并支持环境变量覆盖。
- [x] 管理员改全局默认只影响新任务；旧任务继续使用快照。
- [x] 管理员 API 与 Angular 管理页可新增/覆盖/恢复策略，查询、重试、取消任务，并查看策略审计与任务事件。
- [x] 验证三种主触发 × 对账关/开的六种组合；RocketMQ 开启对账时已实测 Broker 停机、发布失败记录和 `DATABASE_RECONCILIATION` 兜底。

#### 6.6 阶段门禁

- [x] 主动取消无支付、有待支付、重复请求均已通过 Windows 与容器化 Bash 专项验收。
- [x] 单次支付过期后，整单未超时时仍可重试。
- [x] 整单超时时必须先关闭待支付交易，再进入 `CANCELLED`。
- [x] 支付/取消真实竞态与长时间随机化浸泡压测不造成错账。
- [x] RabbitMQ/RocketMQ 重复、迟到旧消息与未确认重投不重复执行业务副作用。
- [x] 支付/取消锁等待超时专项：Windows 与容器化 Bash 均验证超时返回 `503/DATABASE_CONCURRENCY_RETRY` 和 `Retry-After`，事务回滚无中间事实，释放锁后重试成功。
- [x] 支付/取消关键事务窗口进程崩溃恢复专项：双平台 SIGKILL 覆盖 `CANCELLING` 恢复、渠道已关/本地未提交、回调事务回滚和迟到成功回调。

### 7. 阶段 3：独立模拟物流

#### 7.1 服务、数据与状态

- [x] 新建独立 `logistics-simulator-service`、数据库和 Docker 服务；首版不做物流管理 UI。
- [x] 物流平台保存渠道运单号、商城请求号、承运商、单号、状态、轨迹、节点时间、回调和幂等信息。
- [x] 商城保存 `shipment_records`、`shipment_events`、当前投影、发货/签收时间、通知 ID 和回调摘要。
- [x] 模拟物流不得直接连接商城数据库。
- [x] 正常流为 `CREATED -> PICKED_UP -> IN_TRANSIT -> AT_STATION -> OUT_FOR_DELIVERY -> DELIVERED`；非终态可进入 `EXCEPTION（异常件）`，只能通过明确 `RECOVERY（异常恢复）` 回到异常前状态。
- [x] 自动轨迹可启停，扫描周期、每步间隔和批次可配置；默认关闭，测试环境可压缩为秒级，每节点有稳定事件 ID。

#### 7.2 签名接口、回调与对账

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

#### 7.3 商城投影与客户查询

- [x] 商家发货时创建运单，成功后再完成商城发货投影。
- [x] 新增 `GET /api/purchase-orders/{orderId}/logistics`，严格校验客户归属。
- [x] `SHIPPED/RECEIVED/COMPLETED` 均返回承运商、单号、当前状态和完整轨迹。
- [x] 已通过建单、手动/自动六节点推进、`EXCEPTION -> RECOVERY`、签名/重放拒绝、回调投影、客户查询、Outbox 重试和对账恢复。

### 8. 阶段 4：收货与完成

- [x] 客户手动 `SHIPPED（已发货） -> RECEIVED（已收货）` 时写入 `received_at`，取消 `AUTO_RECEIVE（自动确认收货）`，幂等创建 `AUTO_COMPLETE（自动完成订单）`。
- [x] 首次收到 `DELIVERED（已送达）` 时写入 `logistics_delivered_at`，创建延时 `AUTO_RECEIVE`。
- [x] `AUTO_RECEIVE` 执行时重新锁定订单并检查仍为 `SHIPPED`，再写入 `received_at`。
- [x] 手动/自动路径使用行锁、条件更新和唯一任务键；两线程同时抢占已验证只产生一次收货转换和一个后续任务。
- [x] 创建 `dueAt = received_at + auto-complete-after-receipt` 的 `AUTO_COMPLETE`。
- [x] 执行时只允许 `RECEIVED -> COMPLETED（已完成）`，写入 `completed_at`。
- [x] `COMPLETED` 后仍可查订单、支付、退款、物流，且未评价商品仍可一次性评价。
- [x] `COMPLETED` 后不提供取消、修改物流、确认收货和“未发货退款”动作，写接口仍会独立校验状态。
- [x] 已通过手动收货、任务重放、受控并发抢占、Docker 自动收货/完成，以及“业务已提交但任务未确认”窗口中的后端重启/租约恢复验收。

### 9. 阶段 5：客户页面必要功能

信息顺序统一为：订单头部 → 商品 → 收货地址 → 当前操作 → 物流 → 支付 → 退款 → 时间线 → 评价。

#### 9.1 `availableActions`

- [x] 初始动作包含 `PAY`、`REFRESH_PAYMENT`、`CANCEL_ORDER`、`REQUEST_REFUND`、`VIEW_PAYMENT`、`VIEW_REFUND`、`VIEW_LOGISTICS`、`COPY_TRACKING_NUMBER`、`CONFIRM_RECEIPT`、`REVIEW_ITEM`。
- [x] Angular 的主要订单操作按 `availableActions` 展示；后端写接口仍独立验证权限和状态。

#### 9.2 状态与展示

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

#### 9.3 展示要求

- [x] 物流始终展开，显示承运商、单号、复制、状态和按时间排序的轨迹。
- [x] `RECEIVED/COMPLETED` 不能隐藏物流或支付。
- [x] 退款不覆盖支付卡片，作为独立资金事实展示。
- [x] 按状态展示支付截止、取消、签收、收货和完成时间。
- [x] 语言切换不闪回旧订单/空数据，不直接显示后端枚举英文值。
- [x] 移动端无水平滚动；已在 Edge Pixel 9 Pro 427×952 设备模式验收。

### 10. 阶段 6：验收、故障实验和文档

#### 10.1 业务与故障验收

- [x] 主动取消无支付/有待支付订单、重复取消、支付/取消并发；Windows 与容器化 Bash 脚本已覆盖 `PAID/SUCCESS` 和 `CANCELLED/CLOSED` 两种竞态终态，并完成 95/97 轮两分钟浸泡实验。
- [x] 单次支付过期仍可重试，整单超时自动关闭；Windows 与容器化 Bash 已验证换渠道重试、渠道关闭和 Database 任务单次执行。
- [x] 六种主触发/对账组合。
- [x] RabbitMQ/RocketMQ 未投递与 Database 补偿。
- [x] RabbitMQ/RocketMQ 重复消息、迟到旧消息和毒消息专项：重放已完成任务消息只增加 ignored 指标，任务领取/完成仍各一次；非法 JSON 分别进入 RabbitMQ Dead Queue 和 RocketMQ `%DLQ%ConsumerGroup`。
- [x] 消费者已取得消息但尚未确认时进程退出、恢复后的重新投递专项：Windows Docker 与容器化 Bash 已分别验证 RabbitMQ/RocketMQ 任务 `239/240` 和 `241/242`，崩溃前后无新增发布，且领取/完成各一次。
- [x] 物流推进、回调丢失、重放、迟到可变字段、对账和越权查询已验收。长时间并发乱序压力验收结果见第 14 节的 2026-11-13 记录。
- [x] Windows Docker 已验证手动/自动收货、自动 `COMPLETED`，完成后仍可查支付/物流并评价；退款记录保留由现有响应契约覆盖。

#### 10.2 双平台脚本和 Docker

- [x] M1 冻结时验收了 36 组双平台入口。当前使用一份 PowerShell 7 脚本和 `scripts/script-contracts.tsv` 参数清单。
- [x] 三种主策略可独立运行；RabbitMQ 和 RocketMQ 提供可选 Compose Profile，Database 默认模式不强制启动 MQ。
- [x] 空数据卷从 Flyway 到全链路验收通过。
- [x] 隔离旧库升级演练、当前持久卷服务/MQ 重启、任务恢复和历史一致性通过。
- [x] 脚本默认不清空现有数据卷；全新卷专项只在显式参数下删除其自身随机临时项目卷。

#### 10.3 文档交付与完成门禁

- [x] `docs/ORDER-LIFECYCLE.md`、`docs/MOCK-LOGISTICS.md`、`IMPLEMENTED.md` 和 README 已同步自动收货/完成的实现边界与验收脚本。
- [x] 已写清六种配置、策略快照、切换边界、回滚、已有监控与待实现告警边界。
- [x] 订单、支付、退款、物流和任务已经快速回归、专项不变量和历史指纹审计，未发现矛盾状态。
- [x] 收货/完成已验证重试、重放、受控并发、Database 重启/租约恢复、RabbitMQ/RocketMQ 主触发故障恢复和双平台 120 秒/12 并发竞态浸泡。
- [x] 客户、商家、管理员和服务回调权限边界通过；快速入口含全角色矩阵，支付/物流专项含签名、伪造回调和越权拒绝。
- [x] Windows 与容器化 Bash 的 M1 专项和快速回归结果已对齐。

### 11. M1：可复测基线冻结与验证体系启动门禁

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

- [x] 已建立 [全开发生命周期可复测验证体系](../VERIFICATION-SYSTEM.md)，统一 L0–L6 分层门禁、改动矩阵、数据安全边界、证据格式、失败处理和基线冻结清单。

### 12. M1 和验证体系文档之后再做的视觉与运维代办

- [x] 订单卡片改为“状态化紧凑摘要 + 当前主操作 + 可展开详情”；大屏固定对齐状态、金额与操作列，不重复显示可由订单状态推导的支付成功/已关闭；展开态支付信息扁平化、商品图失败回退且空历史区不渲染；桌面与 390 px 窄屏验收通过。
- [x] 客户通知面板改为中性紧凑菜单，正文独立滚动并使用细滚动条；支持关闭按钮、页面外点击、`Esc` 和选择通知后关闭，语言切换收敛为无障碍图标按钮。
- [x] 重做退款申请/回执、已收货/已完成状态和逐商品评价表单；Angular 25 项测试、生产构建、真实数据桌面与 390 px 窄屏验收通过。
- [x] 统一支付、退款、物流和订单时间线视觉。
- [x] 补齐骨架屏、空状态、错误状态和响应式布局。
- [x] 集成 Prometheus Alert Rule、Alertmanager 和可替换 Webhook 通知管道；10 条规则、三服务抓取、规则单测及 Windows/容器化 Bash Watchdog 实时投递均通过。

视觉改造和本地生产式告警管道均已完成。真实邮箱、短信、电话或值班平台凭据不提交到学习仓库，替换接收端和生产高可用边界见 [ALERTING.md](../ALERTING.md)。本轮固定序列到此结束，停止新增业务代码并先汇报结果。

### 13. M2：代码质量与模块化单体治理

本节只跟踪跨模块工程质量，不混入新增业务功能，也不替代 `docs/architecture/` 下的架构待办。
详细审计基线和实施门禁见 [CODE-QUALITY-PLAN.md](#m2-代码质量治理记录)。

#### 13.1 已完成批次

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

#### 13.2 已完成：支付模拟器 Web 格式与自动门禁

本批只治理 `simulators/payment-platform/web/`，不重做 UI、不改支付业务语义，不与 Angular 已完成批次混合。

- [x] 先做只读基线：统计 Vue/TypeScript/CSS/JSON 文件、压缩行、制表符和过长行，确认当前测试、构建与镜像入口。
- [x] 固定项目本地 Prettier 版本与配置，提供 `format:check` 只读入口和显式 `format:write` 写入入口。
- [x] 仅对本模块执行一次机械格式化，审查依赖锁文件及核心支付/收银台差异，不夹带业务重构。
- [x] 将 `format:check` 接入支付 Web 正式 Dockerfile，保证默认 Compose 构建可阻止未格式化代码。
- [x] 通过格式检查、Vitest、TypeScript/Vite 生产构建、正式 Docker 镜像构建、`14201/healthz` 和支付 Web 相关运行态验证。
- [x] 更新 `IMPLEMENTED.md`、`PROJECT-STATUS.md`、`CODE-QUALITY-PLAN.md` 和格式规范，独立提交并推送；不删除或重建数据卷。

#### 13.3 M2 工程质量总验收与基线冻结

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

#### 13.4 M2 冻结后的架构阶段顺序

以下是 M2 冻结后的架构层演进顺序，不属于当前业务待办，不得在实际验收前写入 `IMPLEMENTED.md`。
详细边界以 [微服务演进计划](../architecture/README.md#6-微服务演进准入) 和
[数据库演进计划](../architecture/DATABASE-EVOLUTION-PLAN.md) 为准。

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

#### 13.5 M3.1 前置审计首批：代码结构基线治理

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

#### 13.6 M3.2 准入批次：跨进程追踪基线

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

#### 13.7 M3.2 首批：notification-service 独立影子进程

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

#### 13.8 M3.2 下一批：notification 单一所有权与公开入口切换

详细边界和分阶段切换顺序见
[notification-service 单一所有权切换计划](../architecture/NOTIFICATION-SERVICE-CUTOVER-PLAN.md)。
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

#### 13.9 M3.3 首批：catalog-service 边界准入治理

详细边界见 [catalog-service 单一所有权演进计划](../architecture/CATALOG-SERVICE-EVOLUTION-PLAN.md)。本批只在
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

#### 13.10 M3.3 第二批：catalog/inventory 数据所有权冻结

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

#### 13.11 M3.3 第三批：catalog V1 契约与失败语义

详细契约见 [catalog V1 API 与商品事件契约](../architecture/CATALOG-V1-CONTRACT.md)。本批仍只维护一套
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

#### 13.12 M3.3 第四批：catalog 单一所有权切换

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

#### 13.13 M3.4 首批：inventory-service 准入评审与边界治理

详细边界见 [inventory-service 准入与单一所有权演进计划](../architecture/INVENTORY-SERVICE-EVOLUTION-PLAN.md)。
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

#### 13.14 M3.4 第二批：inventory V1 命令、Inbox 与跨边界外键清理

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

#### 13.15 M3.4 第三批：catalog 事件驱动的 inventory 商品快照

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

#### 13.16 M3.4 第四批：inventory 命令投递、结果与补偿协议

详细协议见 [inventory V1 命令投递、结果与补偿协议](../architecture/INVENTORY-COMMAND-DELIVERY-PROTOCOL.md)。
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

#### 13.17 M3.4 第五批：inventory 业务等待态与人工恢复门禁

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

#### 13.18 M3.4 第六批：inventory-service 最终创建准入评审

本批只授予创建独立服务和 MySQL Schema 的资格，不创建服务、不搬迁数据、不切换流量。详细步骤见
[inventory-service 创建准入与单一所有权切换运行手册](../architecture/INVENTORY-SERVICE-CUTOVER-RUNBOOK.md)。

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

#### 13.19 M3.4 第七批：inventory-service 影子运行时与可重复快照

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

#### 13.20 M3.4 第八批：停写、最终追平与远程传输预演

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

#### 13.21 M3.4 第九批：库存命令生产切换与故障恢复

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

#### 13.22 M3.4 第十批：商家库存管理迁出与完整单一所有权

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
- [x] 新增 [payment-orchestration 一次性切换运行手册](../architecture/PAYMENT-ORCHESTRATION-CUTOVER-RUNBOOK.md)，
  冻结停写、最终追平、唯一远程适配器、回调切换、源表撤权、开门、烟雾验收和不可直接回旧库的原子顺序。
- [x] 完整快速回归发现并关闭支付成功与库存预留结果并发到达的竞态：Order V1 新增原子
  `START_INVENTORY_COMMIT`，该批次兼容 `RESERVED/WAITING_RESERVATION`。此处保留当时结果，现行实现只接受 `RESERVED`。当时支付已成功但库存仍停留在预留态时，
  重放同一签名回调会补发幂等 COMMIT。两笔历史测试样本均恢复为 `PAID/COMMITTED`，最终完整快速回归通过。
- [x] M3.6 第九批补齐原子切换前的 commerce 停写闸门：`PAYMENT_WRITE_FROZEN=true` 通过稳定
  `PaymentWriteAdmissionV1` 在订单进入 `CANCELLING` 前拒绝创建支付、关闭支付和发起退款，查询及签名回调继续排空。
  Windows 真实演练确认三类新写均为 503、业务事实不变、已有回调仍收敛为 `PAID/SUCCESS`，退出后恢复默认 false；
  Ubuntu 26.04 WSL 后续补齐 jq，并修复 Windows Docker CLI 下 Compose 路径/环境透传后完成同等运行态验收。
- [x] M3.6 第十批完成[最终 GO/NO-GO 评审](../architecture/PAYMENT-CUTOVER-GO-NO-GO-REVIEW.md)：generation 15、
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

#### 13.23 M3.7 第一批：履约创建准入审计与边界治理

- [x] 冻结履约编排与订单、资金、物流投影的权威边界，记录发货竞态与恢复门禁。
- [x] 商家入口向履约传递 merchantId，物流投影写入归 logistics 应用层，移除履约对 ShipmentMapper 的直接访问。
- [x] 交付 Fulfillment V1 草案、发货命令样例与 Windows/WSL 只读审计。CREATE 结论为 `REJECTED`。

#### 13.24 M3.7 第二批：商城内的持久履约流程

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

详见[履约服务演进计划](../architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md)。

#### 13.25 M3 各服务单一所有权复核与收尾

六个服务已完成运行路径切换，库存旧迁移入口已封住，35 笔历史投递已逐笔处理，其中 5 笔缺证测试数据保留归档。付款核对真实预占、空扣减拒绝和正常出库均已在日常环境复验。[订单 695／696 验收记录](../architecture/INVENTORY-RECONCILIATION-REVIEW.md#2026-11-19-日常环境库存门禁验收)

商城已补齐库存命令待对账、在途积压和最老等待时间的指标及独立告警。隔离 MySQL 已核对正常零值、投递滞留、待人工对账、处理后恢复及终态排除，并验证库存服务的出库事件告警独立触发和恢复。[库存命令监控验收](../architecture/INVENTORY-RECONCILIATION-REVIEW.md#2026-11-19-库存命令监控验收)

订单服务的发货意图滞留、取消协议待恢复及采集失败告警已完成。隔离 MySQL 验证了指标和告警，日常库已只读核对所有者、旧表授权、存量订单身份及取消投递记录。[订单监控与只读核对](../architecture/ORDER-SERVICE-EVOLUTION-PLAN.md#10-订单监控与只读核对)

目录和通知的业务链路监控已通过隔离验收，覆盖目录到库存的投递、商城到 Kafka 的通知发送、通知消费 lag 和消费死信。日常库完成所有权、旧表授权、事件及账本只读核对。[目录与通知监控验收](../architecture/M3-SINGLE-OWNERSHIP-REVIEW.md#10-目录与通知监控验收)

取消订单跨库恢复已通过隔离验收。模拟订单终态已提交、商城两份 Outbox 尚未提交时的进程退出，商城重启自动补齐，库存释放和取消通知各完成一次。[恢复验收记录](../architecture/M3-SINGLE-OWNERSHIP-REVIEW.md#11-取消订单跨库中断恢复验收)

履约候选版本已通过隔离存量恢复、真实发货、重复发货和带新写入的六表回退。[履约新版本验收](../architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#23-2026-11-19-履约新版本恢复与回退验收)

商城旧履约分支和 Mapper 的退役评审已完成。独立回退包验证了旧商城镜像能够接管候选版本的新写入。[独立回退包验收](../architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#25-2026-11-19-履约独立回退包验收)

商城本地履约执行代码及专用 Mapper 已退役，通过隔离空库、存量部署、商家发货及管理员恢复验证。[退役记录](../architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#26-2026-11-19-商城本地履约代码退役)

旧履约六表及捕获对象已通过隔离归档恢复。正式启动、审计及回退入口已通过无旧源部署、归档恢复和旧镜像接管验收。[入口改造验收](../architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#28-2026-11-19-履约归档后启动审计与回退)

P4 日常履约发布及归档清理已通过。见[P4 验收记录](../architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#29-2026-11-20-p4-日常发布和旧源清理)。

2026-11-21 日常发布、旧入口退役和完整验收通过，M3 正式关闭。
[日常发布记录](../architecture/M3-SINGLE-OWNERSHIP-REVIEW.md#14-2026-11-21-日常发布与-m3-验收)

- [x] 将门户和验收脚本的商家接单、发货和改运单调用迁到统一履约命令，验证幂等、处理中、查询恢复和旧入口 404。
- [x] 将商城履约对物流包裹的查询收口到契约，按实际包裹及平台运单匹配回执，覆盖同单多包裹。
  明确 `fk_storefront_return_case` 的同库跨模块边界，对保留或退役形成有回归测试的决定。完整准入脚本须通过。
- [x] 核对支付旧捕获过程和 42 个触发器的退役条件。当前备份可恢复旧定义，隔离移除后支付与退款回归通过。
- [x] 固定当前镜像和 12 库备份，在隔离环境恢复并核对多包裹、售后和发票。模拟正票和红票本地回执缺失后均按原请求号恢复。
- [x] 完成日常商城、履约和门户更新。旧入口返回 404，统一命令、多包裹审计、售后和发票均通过。支付旧捕获对象已在归档恢复验证后退役，其他容器及数据卷保持完整。
- [x] 评审 M4 PostgreSQL 专项，选定通知服务作为 PostgreSQL 17 隔离试点。
- [x] 完成通知服务的 MySQL 对照、PostgreSQL 导入、统一用例和备份回退验收。八表 635 行正向迁移一致，含 PostgreSQL 新写入的八表 642 行反向恢复一致。
- [x] 完成通知服务隔离 Kafka 偏移接续、数据库断连恢复、回拨重放和回退验收。60 个有效事件无丢失、无重复，两库各完成 9,000 个唯一事件的容量对照。
- [x] 完成通知服务日常切换的只读基线核对和停写、备份、回退评审。在日常 Kafka 上发现数据卷挂载和实际日志路径不一致，阻断切换。
- [x] 完成通知服务磁盘环境的峰值吞吐、未读查询延迟和 6,600 条 Kafka 积压恢复对照，复验切换、故障恢复和回退。
- [x] 完成 Kafka 持久化隔离验收。迁移、broker 重建及恢复写入后的回退均保留集群身份与主题数据，新增 12 条消息和 18 个分区提交位置核对通过，三个消费组接续后 Lag 归零。
- [x] 单独发布 Kafka 持久化修复。停写后备份，重建后逐项核对日常 12 个主题、357 条业务记录和 3 个消费组的 12 个分区提交位置，再恢复流量。
- [x] 完成 Kafka 持久化修复后的通知服务切换复审。用最新通知库备份隔离复验分区接续、断连恢复、重放、原生恢复和反向回退。
- [x] 完成商城售后与支付编排退款两路站外 Outbox 的停写、恢复和原请求号对账。隔离停写 38.22 秒未消耗重试，恢复后四条渠道回执一一对应。
- [x] 固化日常 PostgreSQL 发布和反向回退 PowerShell 入口，并在隔离环境核对分区偏移、候选新写入和历史供应方请求号。
- [x] 日常仅发布商城、支付编排和通知维护门控，挂载共享维护卷。两路 Outbox 停写 38.89 秒后按原请求号恢复，通知写接口暂停时返回 503，三个门控指标已经恢复为 0。
- [x] 日常通知服务已切换至 PostgreSQL 17.11。用维护门控停写后备份 MySQL，核对八表 670 行、首次已读和 32 条历史站外回执。Kafka 从原偏移接续、Lag 归零，放行后新测试通知的两条渠道回执通过。保留原 MySQL 容器与全部原项目卷。

试点范围、复跑命令与恢复边界见[数据库演进计划](../architecture/DATABASE-EVOLUTION-PLAN.md#m4-通知服务-postgresql-17-隔离试点)。

### 14. 实施记录模板

#### 2026-11-15：M3.2 第二批——notification 单一所有权切换

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

#### 2026-11-15：M3.2 首批——notification-service 独立影子进程

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

#### 2026-11-15：M3.2 准入——跨进程追踪基线首批

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

#### 2026-11-15：M3.1 最终门禁——notification 独立运行 Schema 与可逆切换

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

#### 2026-11-15：notification 影子投影核对与独立 Schema 演练

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

#### 2026-11-15：notification 可替换异步传输与恢复门禁

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

#### 2026-11-15：notification 写入所有权与 Inbox 收口

- 迁移：商城 V25 新增 notification 自有 `notification_inbox`，现有 MySQL 非破坏性升级成功。
- 契约：新增 `CustomerNotificationCommandV1`，固定版本、事件/通知类型、发生时间、客户/订单、
  correlation/causation ID 等字段，并提供可反序列化 fixture。
- 所有权：InventoryMapper 不再写 `customer_notifications`；notification Handler 在同一事务中先登记 Inbox，
  再由 CustomerNotificationMapper 写通知，重复事件无重复副作用。
- 验证：商城 28 项测试、正式镜像、Windows/Bash 订单取消通知链路、双平台质量/脚本契约通过；
  当前库 V25 成功，5 条实测命令一一对应通知且无重复 Inbox 键。
- 边界：当前传输仍为单体内应用调用；未创建 notification-service、独立数据库或 PostgreSQL。

#### 2026-11-15：M3.1 数据所有权与分布式契约基线审计

- 数据：盘点商城 37 张表并声明当前责任模块；识别 `products` 库存列、共享订单事实和通用 Kafka Inbox
  等混合所有权。
- 依赖：盘点 40 组跨模块方向、117 个 import 引用、39 个源码文件和 21 个跨模块基础设施引用。
- 契约：确认 5 个 Kafka topic、Outbox、Inbox 等价去重和 MQ 可靠性机制已存在；版本、兼容性 fixture、
  每服务独立 Inbox 与 trace context 尚未完成。
- 门禁：新增 2 项 Maven 架构基线测试；允许债务减少，阻止新表无所有者或跨模块依赖未经评审增长。
- 结论：M3.2 尚不可启动；首个治理目标是 notification 写入所有权和版本化消费者契约。

#### 2026-11-14：M3.1 前置代码结构基线治理

- 结构：物流模拟器形成 `api/application/domain/infrastructure` 四层及基础设施子目录，持久化投影独立拆分。
- 格式：为 173 个既有 Java 文件补齐 175 个类声明后空行，并以 Windows/Bash 门禁覆盖全部 256 个 Java 源文件。
- 验证：248 个主源码 Java、20 个 Mapper XML、189 个映射语句和 3 个 POM 的双平台质量审计通过；
  物流镜像 Maven 打包、主链路和 Outbox 管理专项通过。
- 边界：保留三个独立 MySQL 和现有数据卷；未启动微服务拆分或 PostgreSQL 迁移。

#### 2026-11-14：M2 工程质量总验收与基线冻结

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

#### 2026-11-14：M2.5 Angular 商城门户格式门禁

- 范围：`web/commerce-portal/angular/` 的 TypeScript、HTML、CSS/SCSS 和根配置文件。
- 实现：固定 Prettier 3.10.1，新增 `format:check`/`format:write`，正式 Dockerfile 在生产构建前执行只读门禁。
- 验证：127 个源码/配置文件、29 项 ChromeHeadless 测试、本地和 Docker 生产构建、`/healthz`、i18n 及三角色权限通过。
- 数据边界：仅重建 Angular 容器，未删除或重建数据卷。
- 提交：`c26d90e style(web): govern Angular portal formatting`，已推送到 `origin/main`。

#### 2026-11-12：通用定时任务底座阶段 1

- 迁移：`V15__generic_scheduled_tasks.sql`、`V16__scheduled_task_policy_audit.sql`。
- 配置：`commerceflow.scheduling` 默认策略、10 类任务策略、Database 扫描、对账和租约恢复。
- 实现：策略解析/快照、Handler 注册表、统一执行器、Database 扫描、对账、租约恢复、管理员治理 API 与审计。
- 验证：后端 6 个集成测试通过，Flyway 15/16 在现有 MySQL 卷升级成功，Windows/Bash 管理 API 专项脚本通过。
- 遗留：除 `PAYMENT_TIMEOUT` 外的生产业务 Handler、RabbitMQ/RocketMQ 及六组合故障实验。
- 提交：未提交。

#### 2026-11-12：订单取消与 Database 超时垂直闭环

- 迁移：`V17__order_lifecycle_foundation.sql`，补充订单截止/取消/收货/完成时间及订单事件任务来源。
- 配置：`commerceflow.order-lifecycle` 三类时限；`PAYMENT_TIMEOUT` 默认使用 Database + 对账。
- 实现：客户主动取消、渠道交易关闭、本地支付关闭、幂等重试、支付/取消状态保护、超时 Handler 和提前取消任务。
- 验证：后端 6 个集成测试、MySQL 旧卷迁移、Windows 与容器化 Bash 专项脚本通过。
- 补充：取消终态同事务写入 `ORDER_CANCELLED` Outbox，订单响应新增 `availableActions`，Angular 接入未支付取消表单。
- 验证：后端集成测试校验 Outbox 和动作契约，Windows/Bash 专项脚本与全量 Docker 回归通过。
- 遗留：Outbox 下游消费者、真实并发压力测试、RabbitMQ/RocketMQ。
- 提交：未提交。

#### 2026-11-12：独立模拟物流最小完整闭环

- 迁移：商城 `V18__logistics_projection.sql`；物流平台 `V1__logistics_platform.sql`。
- 组件：新增 `logistics-mysql` 和 `logistics-simulator-service`，物流平台不直连商城数据库。
- 实现：幂等建单、运单更正、六段轨迹状态机、请求 HMAC/时间戳/Nonce 防重放、签名回调、回调去重/防倒退、客户归属查询和 Angular 展开轨迹。
- 验证：物流服务构建通过；后端 6 项集成测试通过；Angular 生产构建通过；Docker 旧卷升级后全服务健康；Windows 专项验证签名建单、重放 `409`、伪造签名 `401`、`CREATED -> DELIVERED` 及商城投影；Windows `verify-all -SkipBuild` 全量回归通过。
- 跨平台边界：Bash 同语义脚本已提供；当前 Git Bash 缺少 `jq`，本轮未实机执行 Bash 专项。
- 当时遗留：回调重试 Outbox、主动对账与丢失/乱序回调故障实验；已由后续“物流回调 Outbox 与主动对账”阶段完成主要闭环。
- 提交：未提交。

#### 2026-11-12：物流自动轨迹与异常恢复

- 迁移：物流平台 `V2__automatic_progress_and_exceptions.sql`，增加异常前恢复状态、自动扫描索引和轨迹事件类型；商城 `V19__logistics_event_types.sql` 保留事件类型投影。
- 实现：默认关闭的自动轨迹扫描；人工 `advance`、自动推进、异常和恢复均使用行锁与条件更新。
- 异常语义：`EXCEPTION` 暂停自动推进，不创建 `AUTO_RECEIVE`；`RECOVERY` 回到记录的异常前状态，迟到的普通 `PROGRESS` 回调不能误恢复异常件。
- 验证：物流手动专项通过 `EXCEPTION -> RECOVERY -> DELIVERED`；自动专项将每步压缩为 1 秒，通过 `CREATED -> DELIVERED` 并创建 `AUTO_RECEIVE`；后端 11 个集成测试通过。
- 配置恢复：专项脚本结束时必须重建物流服务并恢复默认关闭，避免污染后续手动实验。
- 当时遗留：回调 Outbox/重试、主动对账、丢失与乱序回调实验；已由后续阶段完成暂时/永久丢失与迟到字段场景。
- 提交：未提交。

#### 2026-11-12：物流回调 Outbox 与主动对账

- 迁移：物流平台 `V3__callback_outbox.sql`；商城 `V20__logistics_reconciliation.sql`。
- 主路径：轨迹与不可变回调正文同事务落库；投递器使用 `NEW/PROCESSING/RETRY/PUBLISHED/DEAD`、领取租约、连接/读取超时、最大次数和稳定事件 ID。
- 兜底：商城定期签名查询权威运单，缺失事件复用同一投影入口，通知审计记为 `RECONCILED（已对账恢复）`。
- 乱序修正：承运商和快递单号是可更正字段，迟到回调不再用旧快照拒绝；签名、商城运单号和不变平台运单号负责身份校验。
- 验证：真实停止 commerce-service 后看到 `RETRY -> PUBLISHED` 且尝试次数增加；关闭投递器制造永久丢失后，主动对账补齐 `IN_TRANSIT`；专项结束恢复默认配置。
- 跨平台边界：Windows Docker 专项已运行；Bash 同语义脚本已提供，待具备 `jq` 的 Bash 环境实机执行。
- 当时遗留：Prometheus 告警规则、外部告警接收端和长时间并发乱序压力；后续已补齐告警管道与双平台乱序浸泡。

#### 2026-11-12：物流回调 `DEAD` 治理

- 迁移：物流平台 `V4__callback_outbox_audit.sql`，为回调创建独立审计轨迹。
- 后端：新增管理员回调查询、审计和人工重放 API；非管理员拒绝，原因必填，只允许 `DEAD -> RETRY`。
- 物流平台：使用条件更新和行锁保证单次重放，保留原始回调正文和事件 ID，投递成功后进入 `PUBLISHED（已发布）`。
- 前端：新增 `/admin/logistics-callbacks` 治理页，支持状态筛选、审计展开和 `DEAD` 原因重放，中英文同步。
- 观测：暴露 Outbox 状态 Gauge（仪表）、投递/重放/对账 Counter（计数器）；后续运维阶段已接入 Prometheus Rule、Alertmanager 和可替换 Webhook。
- 验证：Windows Docker 专项已通过 `DEAD -> RETRY -> PUBLISHED`、客户 `403`、空原因 `400`、重复重放 `409`、审计人/原因、商城幂等和 Prometheus 指标。
- 提交：未提交。

#### 2026-11-12：自动确认收货与自动完成

- 配置：`ORDER_AUTO_RECEIVE_AFTER_DELIVERY`（送达后自动收货观察期）和 `ORDER_AUTO_COMPLETE_AFTER_RECEIPT`（收货后自动完成观察期），默认均为 7 天。
- 实现：`DELIVERED（已送达）` 创建 `AUTO_RECEIVE（自动确认收货）`；手动收货取消该任务；手动或自动收货后创建 `AUTO_COMPLETE（自动完成订单）`；最终执行 `RECEIVED（已收货） -> COMPLETED（已完成）`。
- 一致性：所有路径共用行锁、条件更新、稳定任务键和统一 Handler；任务执行时重读订单状态，迟到或重复触发安全结束。
- 验证：后端 10 个集成测试通过，含手动/自动收货的两线程竞态与过期租约重试；Angular 生产构建通过；Windows Docker 专项脚本完整验证手动取消任务、自动收货、自动完成、支付/物流保留和完成后评价。
- 故障验收：将已执行的 `AUTO_COMPLETE` 伪造为工作进程在确认前崩溃，停止并重启 commerce-service；`LEASE_RECOVERY（租约恢复）` 将其重新领取并成功结束，`ORDER_AUTO_COMPLETED（订单自动完成）` 事件仍只有一条。
- 跨平台边界：Bash 同语义脚本已提供并通过语法检查；当前 Git Bash 缺少 `jq`，未执行真实 Bash 业务链路。
- 当时遗留：长时间高并发压力测试、RabbitMQ/RocketMQ 主触发适配器及 MQ 级故障实验；RabbitMQ 主路径与数据库兜底已由后续阶段完成。
- 提交：未提交。

#### 2026-11-12：RabbitMQ 定时任务主触发

- 迁移：`V21__rabbitmq_scheduled_task_trigger.sql`，记录发布时间、尝试次数和最后发布错误，并为待发布扫描建立索引。
- 配置：新增可选 `rabbitmq` Compose Profile；连接/心跳、Publisher Confirm、发布扫描、交换机/队列/路由键、TTL 分桶、Database 扫描、租约恢复和对账周期均可由环境变量覆盖。
- 实现：持久化 TTL 分桶 + DLX、Confirm/Return、未确认任务补发、Ready/DLQ、有限退避消费重试、早到重新分桶、数据库重读和统一执行器原子抢占。
- 策略：任务保存 `RABBITMQ` 快照；管理员后续修改只影响新任务。开启 Database 对账时只改变本次实际来源，不修改主策略快照。
- 验证：后端 12 个集成测试、默认/Profile Compose 配置检查及 Windows `verify-all -SkipBuild` 全量 Docker 回归通过；正常链路验证任务 176；Broker 停机产生 4 次失败并在恢复后由任务 178 补发成功；Broker 停机且开启对账时任务 179 由 `DATABASE_RECONCILIATION` 成功兜底。
- 跨平台边界：Windows 专项脚本已执行；Bash 同语义脚本已提供，但当前 Git Bash 缺少 `jq`，尚未实机执行。
- 当时遗留：RabbitMQ 重复/毒消息专项验收、RocketMQ 两种组合、长时间压力测试；前两项已由后续 RocketMQ 与 MQ 幂等专项完成。
- 提交：未提交。

#### 2026-11-12：RocketMQ 5.x 定时任务主触发

- 依赖：Apache RocketMQ Java gRPC Client `5.2.2`；Docker Profile 使用当前可获取的官方镜像 `apache/rocketmq:5.4.0`。
- 配置：新增可选 `rocketmq` Profile，启动 NameServer 和带 Proxy 的 Broker，持久化日志/存储卷，并初始化 `message.type=DELAY` Topic。
- 实现：将 `dueAt` 转为绝对投递时间；发布成功记录 message ID，失败保留未发布状态以待补发；消费端重读数据库并复用统一执行器，早到消息重新投递，异常由 Broker 重试/死信。
- 验证：后端容器构建通过；默认与 RocketMQ Profile Compose 配置通过；Windows 和容器化 Bash 均完成真实下单、Broker 回执、原生延时投递、原子领取、超时关单和审计链验收（任务 226/227）；Broker 停机时发布失败可观测且 Database 对账成功兜底（任务 228/229）。
- 验证补充：Windows 与容器化 Bash 的 `verify-mq-scheduling-idempotency` 已完成 RabbitMQ/RocketMQ 重复消息、迟到旧消息和毒消息实验（任务 233～236）。
- 验证补充：Windows Docker 与容器化 Bash 的 `verify-mq-scheduling-redelivery` 均已在 RabbitMQ/RocketMQ 消费窗口中强制终止 commerce-service，任务 `239/240`、`241/242` 恢复后均只领取/完成一次。
- 遗留：长时间故障压力测试。
- 提交：待本批次提交。

#### 2026-11-12：支付尝试过期与整单截止时间

- 实现：创建新支付时使用订单 `payment_due_at` 做领域门禁；已有幂等请求先返回原结果，截止时间后拒绝产生新渠道交易。
- 语义：单次 `expires_at` 只终止当前渠道尝试；整单 `payment_due_at` 到期后，`PAYMENT_TIMEOUT` 统一执行器先关闭尚在 `PENDING` 的重试交易，再将订单转为 `CANCELLED`。
- 自动验证：后端 17 个测试通过，新集成测试同时覆盖截止时间前换渠道和截止时间后拒绝新支付。
- 实机验证：Windows 订单 `ORD-46AF0F1B96954117`/任务 `244`，容器化 Bash 订单 `ORD-370124308FED4BC0`/任务 `245`，均通过 `ALIPAY EXPIRED -> WECHAT_PAY PENDING -> CLOSED -> order CANCELLED`，任务领取与完成各一次。
- 环境恢复：两个脚本都会恢复 `PAYMENT_TIMEOUT` 策略和原环境变量，并重建商城/支付页代理以避免 Nginx 缓存旧容器地址。
- 提交：待本批次提交。

#### 2026-11-12：Flyway 空库、旧库升级与非法状态

- 隔离：脚本为每次运行创建随机命名的 Docker 网络和两个 MySQL 8.4 容器，结束时只删除这些临时资源，不挂载或清空 Compose 开发数据卷。
- 空库：固定 `redgate/flyway:12.4.0` 与后端运行时一致，24 个迁移从空 Schema 全部成功，并确认定时任务、物流投影、库存预占表和核心数据字典注释存在。
- 旧库：先以 `target=16` 建立旧 Schema 并插入历史待支付订单，再升级到 V24；V17 将 `payment_due_at` 精确回填为 `created_at + 30m`，V24 补充核心数据字典注释。
- 非法状态：MySQL 实机拒绝 `scheduled_tasks.status=CORRUPTED`；新集成测试确认 `PAID` 订单不能重新支付、普通取消或确认收货，自动收货/完成对非目标状态也不会改写事实。
- 验证：Windows 与容器化 Bash 两套脚本实机通过；本批复验商城 25 项测试通过。
- 边界：本项不勾选“空数据卷全链路”或“旧卷服务/MQ 重启恢复”，那两项仍需独立完成。
- 提交：待本批次提交。

#### 2026-11-13：支付/取消随机化竞态浸泡压测

- 工具：扩展快速竞态脚本，支持最少轮数、持续时间、固定随机种子、轮间节流和本轮 Docker MySQL 审计；新增 Windows/Bash 同名 stress 入口。
- 时序：每 3 轮固定覆盖支付优先、取消优先和随机近同时，每轮都轮询到非中间态后再判定订单/支付/退款组合。
- 限流：首轮无节流实验在第 81 轮真实命中支付平台 `120 requests/minute` 签名限流；保留限流后增加 900ms 轮间节流和仅针对该 `429` 的 5 秒有限退避。
- 库存：stress 入口通过商家 API 将演示商品可售库存补到安全水位，保留人工调整流水，不直改库存表或删除压测订单。
- 结果：Windows `120.6s/95` 轮，`PAID/SUCCESS=55`、`CANCELLED/CLOSED=40`；容器化 Bash `121s/97` 轮，分别为 `58/39`。两次 MySQL 审计的订单数=支付记录数=本轮轮数，矛盾记录为 0。
- 边界：这是单机 Docker 两分钟浸泡验收，不等同于多小时/多日容量规划、多实例压力或网络分区验收；脚本可将时长调至 86400 秒继续扩展。
- 提交：待本批次提交。

#### 2026-11-13：支付/取消锁等待超时专项

- 配置：commerce-service MySQL 连接使用可配置的 `innodb_lock_wait_timeout`，Compose 默认 5 秒；专项脚本临时压缩为 2 秒并在结束时恢复 commerce-service，不删除数据卷。
- 契约：Spring 短暂数据访问异常统一返回 HTTP `503`、`DATABASE_CONCURRENCY_RETRY` 与 `Retry-After: 1`；支付回调不再把这类异常包装成不可区分的通用异常。
- 验证：Windows 和容器化 Bash 同名脚本分别持有订单行与支付行锁；首次取消/回调超时后订单、支付、通知和事件仍为原始事实，释放锁后重试分别达到 `CANCELLED` 与 `PAID/SUCCESS`。
- 自动测试：Docker Maven 构建通过 19 个测试，其中新增错误契约单元测试。
- 边界：本项不代表进程在关键事务窗口崩溃的恢复已完成；该项仍是 M1 下一门禁。
- 提交：本阶段独立提交（见 Git 历史）。

#### 2026-11-13：支付/取消关键事务窗口进程崩溃恢复

- 故障注入：新增默认关闭、必须同时指定订单 ID 和阶段的实验暂停点；脚本观测到精确窗口后对 commerce-service 发送 SIGKILL，结束后恢复默认关闭。
- 取消窗口：已验证 `PENDING_PAYMENT -> CANCELLING` 提交后崩溃可重试完成；渠道已持久化 `CLOSED`、本地仍为 `CANCELLING/PENDING` 时崩溃，重试不重复业务事实且只写一次 `ORDER_CANCELLED`。
- 回调窗口：支付记录已改为 `SUCCESS` 但回调事务未提交时崩溃，MySQL 完整回滚订单、支付、通知和事件；重放相同回调后只提交一份 `PAID/SUCCESS` 事实。
- 迟到回调：订单留在 `CANCELLING`、渠道成功回调稍后到达时，资金事实优先，订单恢复为 `PAID`。
- 验证：Windows 与容器化 Bash 同名脚本均通过；Docker Maven 19 项测试通过。
- 边界：这是单机容器的精确事务窗口实验，不代表多节点容灾或网络分区验收。
- 提交：本阶段独立提交（见 Git 历史）。

#### 2026-11-13：物流高并发乱序浸泡压测

- 语义修正：不同事件 ID 的同级状态仍保留完整轨迹，但只有严格前进的状态才更新当前投影、写订单状态事件或创建自动收货任务；避免多个 `DELIVERED` 事件重复触发副作用。
- 自动验证：Docker Maven 20 项测试通过；新测试固定“两个 `DELIVERED` + 一个迟到 `CREATED`”仍只有一次投影副作用和一条 `AUTO_RECEIVE`。
- 浸泡输入：每条回调使用唯一事件 ID，随机打乱 `CREATED -> DELIVERED` 状态和 `occurredAt`，并故意携带过时承运商/单号字段。
- 结果：Windows 两分钟、12 并发、1320 条全部 `PROCESSED`，投影实际前进 2 次；容器化 Bash 两分钟、12 并发、2652 条全部 `PROCESSED`，投影实际前进 1 次。两轮的轨迹数=通知审计数=发送数，最终状态均为 `DELIVERED`，`AUTO_RECEIVE` 均只有一条，平台运单号/承运商/快递单号不变。
- 边界：这是单机 Docker 两分钟乱序浸泡，不等同于多实例、多小时/多日容量或网络分区测试。
- 提交：本阶段独立提交（见 Git 历史）。

#### 2026-11-13：`AUTO_RECEIVE/AUTO_COMPLETE` MQ 故障实验

- 环境：保留现有数据卷，临时开启 RabbitMQ/RocketMQ 适配器和 1 秒发布扫描；任务关闭 Database 对账，确保故障恢复由原 MQ 完成。
- 场景：对 RabbitMQ/RocketMQ 各自验证 `AUTO_RECEIVE` 和 `AUTO_COMPLETE`；Broker 停机后创建任务，确认发布次数/错误落库，将到期时间压缩后恢复 Broker。
- 结果：Windows 任务 `588/592/594/598` 和容器化 Bash 任务 `601/605/607/611` 均按 `primary_trigger_snapshot` 由对应 MQ 执行，`TASK_SUCCEEDED` 与订单转换均只有一次。
- 恢复：两套脚本都在 `finally/trap` 中恢复策略、Broker、环境变量和默认 services/commerce-service/web，不删除或清空任何数据卷。
- 边界：本阶段是单机 Docker Broker 停机/恢复实验，不等同于 MQ 集群多节点灾备。
- 提交：本阶段独立提交（见 Git 历史）。

#### 2026-11-13：收货/完成长时间高并发竞态浸泡

- 设计：每轮通过完整支付、商家履约和物流签收链路创建新订单，然后并发客户收货请求与 RabbitMQ 同任务重投；收货后再对 `AUTO_COMPLETE` 重复同等压力。
- 竞态：奇偶轮交替 100ms 偏置，确保 `ORDER_RECEIVED` 与 `ORDER_AUTO_RECEIVED` 两种合法赢家都被覆盖；HTTP `503` 只作为可重试并发结果，最终业务不变量必须全部成立。
- Windows：120 秒、9 轮、12 并发、864 次 HTTP/MQ 操作，手动赢 5 轮、自动赢 4 轮。
- 容器化 Bash：120 秒、11 轮、12 并发、1056 次 HTTP/MQ 操作，手动赢 6 轮、自动赢 5 轮。
- 不变量：每轮收货转换仅一次，`AUTO_RECEIVE/AUTO_COMPLETE` 业务键各一条，完成事件、任务领取和任务成功各一次，且无 `FAILED` 任务。
- 边界：这是单机 Docker 中的长时间业务竞态浸泡，不等同于多日容量测试或多区域灾备。
- 提交：本阶段独立提交（见 Git 历史）。

#### 2026-11-13：全新空数据卷完整业务链路

- 环境：使用随机 Compose 项目名和独立新卷；暂停后恢复默认项目，不删除或清空默认 `fairy-commerceflow_*` 卷。
- 发现与修复：空卷首次启动暴露 Spring Boot 4 下支付/物流服务仅引入 `flyway-core` 未激活自动迁移；改用 `spring-boot-starter-flyway` 后完成 V1–V3/V1–V4。
- 验证：商城/支付/物流 Flyway 分别为 V24/V4/V5，无失败迁移；初始数据为 6 用户、6 商品、0 订单。
- 链路：Windows 和容器化 Bash 均跑完登录、下单、签名支付回调、商家履约、物流、手动/自动收货、自动完成、commerce-service 重启租约恢复和评价，业务审计为 `1|1|2|1|1`。
- 破坏性边界：默认保留临时卷以便排查；只有显式 `-RemoveTemporaryVolumes` 才删除本次临时项目卷。
- 提交：本阶段独立提交（见 Git 历史）。

#### 2026-11-13：旧库升级、全栈重启恢复与历史一致性

- 升级边界：在隔离 MySQL 中重放 V1–V16，插入历史订单后升级到 V24，验证 30 分钟支付截止时间回填、核心注释和当前约束；这是可重放旧库演练，不冒充生产备份升级。
- 持久卷：现有商城/支付/物流数据已非破坏性升级至 V24/V4/V5，无失败迁移；本阶段未删除任何现有卷。
- 重启范围：三个 MySQL、Redis、Kafka、支付/物流服务、services/commerce-service/web/UI、RabbitMQ 和 RocketMQ 均实际重启并恢复健康。
- 未完成任务：注入已领取但租约过期的 `AUTO_COMPLETE` 任务；Windows 任务 `1391/1392`、容器化 Bash 任务 `1393/1394` 分别由 RabbitMQ/RocketMQ 恢复，`TASK_LEASE_EXPIRED/TASK_CLAIMED/TASK_SUCCEEDED` 均各一次。
- 历史一致性：终态订单、支付、已送达物流和评价的计数+行 CRC 聚合指纹在重启前后均为 `300:3562683193|559:1055010103|83:1695107719|21:1066884382`。
- 提交：本阶段独立提交（见 Git 历史）。

#### 2026-11-13：Windows/Bash 脚本契约与默认非破坏性审计

- 文件名：35 组 `verify-*` PowerShell/Bash 入口基名一一对应；Windows-only 的视频生成器不属于业务验收入口。
- 参数修正：对齐物流服务 URL、支付服务 URL、RabbitMQ 用户名/密码/等待时间，以及支付/取消竞态的有序参数。
- 安全语义：不存在默认 `down -v`、`docker volume rm/prune`；空卷专项的临时卷删除只能显式开启，Flyway 临时 MySQL 改用 tmpfs 避免匿名卷泄漏。
- 自动审计：PowerShell 与容器化 Bash 入口均返回通过，契约覆盖 35 组脚本；全部 PowerShell AST 和 Bash `-n` 语法检查通过。
- 轻量实链路：Windows 支付通知的新支付地址参数通过；容器化 Bash 按新顺序运行 1 轮支付/取消竞态，以 `PAID/SUCCESS` 且数据库审计通过结束。
- 提交：本阶段独立提交（见 Git 历史）。

#### 2026-11-13：快速回归入口与 M1 基线冻结

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

## 旧版项目状态与里程碑记录

### 1. 快照与事实来源

| 项目 | 当前值 |
| --- | --- |
| 快照日期 | 2026-11-21 |
| M1 阶段起点基线 | `7ee3854` |
| M1 冻结基线 | Git 标签 `m1-baseline-2026-11-13` |
| M2 冻结基线 | Git 标签 `m2-quality-baseline-2026-11-14` |
| 当前阶段 | M3 已正式关闭。六个内部服务各自拥有独立数据库，履约旧入口和支付旧捕获对象已在日常环境退役，完整准入及业务回归通过。[M3 日常发布验收](../architecture/M3-SINGLE-OWNERSHIP-REVIEW.md#14-2026-11-21-日常发布与-m3-验收) |
| 当前优先级 | 保持 MySQL 日常基线。M4 PostgreSQL 专项按独立评审条件推进。[数据库演进计划](../architecture/DATABASE-EVOLUTION-PLAN.md) |
| 近期日常发布 | 单仓多包裹、两仓拆履约、商品全文搜索、站外通知和正式支付运营统计均已发布。 |
| 范围边界 | 商城持有身份、购物车、调度、部分投递账本和物流投影。独立履约服务执行，旧源归档和回退包在仓库外保存。数据库继续使用 MySQL。 |
| 电子发票 | 顾客、商家和平台页面已通过隔离验收，`invoice-20261121` 已部署到日常 Docker。订单 736 的支付、开票、退款红冲及历史账本复核通过。[发布记录](../IMPLEMENTED.md#2301-电子发票日常发布与验收) |

事实优先级为：当前源码/迁移/脚本和实际验收结果 > [IMPLEMENTED.md](../IMPLEMENTED.md) > [TODO.md](#旧版待办与实施记录) 勾选状态 > `archive/` 和各专题中标明的历史记录。发现冲突时必须修改文档，不得为了勾选代办而改写实际验收结论。

### 2. 已完成功能域

| 功能域 | 当前状态 | 主要边界 |
| --- | --- | --- |
| 基础商城与权限 | 已实现 | 用户/角色/Session/CSRF、商品、购物车、地址、下单和资源权限 |
| 商家入驻与店铺归属 | 已验收并日常发布 | 一商家一店铺、审核启停、草稿商品、库存归属和存量订单继续履约与售后 |
| 应结账本与佣金 | 已验收并日常发布 | 成交快照、成功退款、待售后暂缓、账单冻结和跨期冲正 |
| 模拟商家打款 | 已验收并日常发布 | 冻结账单、独立渠道账本、失败重试、超时查询、签名回调和重复出款防护 |
| 统一结算与运费 | 已验收并日常发布 | 服务端报价、地区运费、订单金额快照、历史迁移与整单退费 |
| 单商家满减券 | 已验收并日常发布 | 活动与券包、领券并发、行分摊、锁券、付款核销、取消返还、中断恢复及优惠订单部分退款。[功能说明](../features/CHECKOUT-COUPONS.md) |
| 三渠道模拟支付 | 已实现 | HMAC/RSA、防重放、幂等、异步通知、Outbox/Kafka、对账和安全后台 |
| 正式支付运营统计 | 已验收并日常发布 | 正式支付与退款账本汇总、商家归属与授权、五项筛选和页面逐项对账。[验收记录](../IMPLEMENTED.md#227-正式支付运营统计页面隔离验收) |
| 订单行售后与部分退款 | 已通过隔离与日常验收 | 未发货部分退款、已收货退货、按行回补与原运费核对 |
| 首版全额退款 | 已实现并双平台验收 | 只支持未发货整单全额退款 |
| 未支付订单取消 | 已实现并验收 | 无支付、有待支付、重复取消、库存释放和通知。订单终态与商城 Outbox 的跨库中断恢复已通过隔离验收。 |
| 支付尝试/整单截止时间 | 已实现并验收 | 尝试过期可换渠道；整单到期先关渠道再取消 |
| 支付/取消真实竞态 | 已实现并完成浸泡、锁超时和崩溃专项 | 双平台验证锁超时可重试；SIGKILL 覆盖取消中间态、已关渠道窗口、回调事务回滚和迟到成功回调 |
| 通用定时任务 | 已实现核心 | Database/RabbitMQ/RocketMQ、Database 对账、策略快照、租约恢复和管理页 |
| MQ 幂等与重投 | 已实现并验收 | 重复/迟到旧消息、毒消息、DLQ、未 ACK 进程退出与 Broker 重投 |
| 独立模拟物流 | 已实现主链路并完成乱序浸泡 | 双平台 120 秒/12 并发，Windows 1320 条、Bash 2652 条唯一回调；状态不倒退且不变字段未被旧消息覆盖 |
| 退货运单跟踪 | 已隔离验收并日常发布 | 退货绑定、双角色轨迹、回调恢复、实物验收后退款与入库。[验收记录](../IMPLEMENTED.md#216-首版退货运单跟踪) |
| 收货、自动收货和自动完成 | M1 可靠性门禁已通过 | 重放、重启/租约恢复、RabbitMQ/RocketMQ 停机恢复和双平台 120 秒/12 并发竞态压测均通过 |
| 库存与商家库存中心 | 已实现 | 可售/预占/已售、转正/释放/退款回补、调整流水 |
| Angular/Vue 界面 | Angular 主流程和订单页视觉收尾已实现 | 订单详情与支付信息已扁平化，商品图有失败回退；通知菜单使用中性列表和细滚动条，支持外部点击、`Esc`、关闭按钮和选择通知后关闭；语言切换使用图标；导航与 1480 px 正文容器对齐，桌面与 390 px 窄屏已验收 |
| Flyway 数据库生命周期 | 空卷和迁移层已验收 | 日常商城 V68、订单 V12、库存 V10、履约 V4、支付编排 V5、支付模拟平台 V7、目录 V4、通知 V4。 |
| notification 独立服务 | 单一所有权切换已实现 | 默认拓扑启动独立 MySQL/Kafka 服务；commerce 仅签名代理，故障稳定返回 503，无本地数据库回退 |
| catalog 拆分准入 | 单一所有权已切换 | catalog-service/catalog-db 独占资料、翻译、版本和 Outbox；commerce 只保留鉴权、签名代理与 review 组合 |
| inventory 拆分准入 | 完整单一所有权已切换 | 库存服务持有余额、预占、流水、出库单及出库事件；商城持有库存命令投递和结果账本。历史五笔缺证测试订单已归档 |
| 订单、支付编排与履约 | 均已切流 | Order V1 持有正式订单，Payment V1 持有支付与退款，履约服务以 `EXECUTION` 模式持有流程状态。日常商城库旧订单源表已清理并保留离线备份。 |
| 告警与监控 | P3 及模拟服务告警已日常发布 | Prometheus 加载 41 条规则，11 个目标健康。发票开具和红冲未决、物流回调终止、短信邮件窗口失败均已单独验收。[模拟服务告警日常发布](../IMPLEMENTED.md#2361-模拟服务告警日常发布) |

### 3. M1 冻结结论

M1 按既定 1→9 顺序完成：核心可靠性、空卷/旧库环境生命周期、双平台脚本审计和快速回归入口均有实测证据。基线以注释 Git 标签 `m1-baseline-2026-11-13` 冻结。

### 4. M1 门禁结果

1. 全新空数据卷全链路已通过 Windows 和容器化 Bash 验收。
2. 隔离 V16→V24 旧库升级演练，以及当前持久卷的服务/MQ 重启、未完成任务恢复和历史一致性已通过。
3. Windows/Bash 36 组主要脚本的文件名、参数、语义和默认非破坏性审计已通过。
4. Windows/Bash 快速回归入口已在当前持久卷通过；Windows 默认构建模式也已通过。

### 5. M1 之后的固定顺序

```text
核心可靠性收尾
    -> 完整环境生命周期
    -> 冻结 M1 可复测基线
    -> 编写整个开发生命周期的可复测验证体系文档
    -> 退款区域/已收货/评价表单视觉重做（已完成）
    -> Prometheus Alert Rule、Alertmanager/可替换 Webhook（已完成）
```

M1 期间只维护当前实现证据和专项学习文档，未提前创建或标记“全开发生命周期可复测验证体系”为已开始。

### 6. 当前明确不做或未验收的边界

- 真实支付机构和真实物流渠道仍待实施。首版售后异常工单已日常发布，覆盖仓库短收、拒收和质检不通过。[工单验收](../IMPLEMENTED.md#222-售后异常工单)
- 同店同 SKU 无差价换货后端及顾客、商家、平台页面均已在日常 Docker 发布。订单 738 的换货和两条物流已验收。[换货页面发布](../IMPLEMENTED.md#2313-换货页面日常发布)
- 多节点 MySQL/Redis/Kafka/MQ 高可用、跨可用区容灾、多日容量压测和生产合规认证。
- Prometheus Rule、Alertmanager 与本地 Webhook 已实现并完成 Windows/容器化 Bash 实时投递；真实通知供应商凭据、通知到人和值班升级流程未配置。
- [全开发生命周期可复测验证体系](../VERIFICATION-SYSTEM.md) 已建立；当前为本地单机 Docker 学习环境门禁，不代表多节点容灾、多日容量或生产合规验收。
- Java/Maven 已统一为 `vip.fairy.commerceflow`，Compose 已统一为 `fairy-commerceflow`，目录已按 `services/web/simulators/infrastructure` 责任分组。旧学习卷按用户明确授权精确清理，新空卷 Flyway V24/V4/V5、完整业务链路和快速回归均已通过；M1 标签保留迁移前证据。

### 7. M2 工程质量起点

第 7、8 节按批次保留当时的源码规模、脚本形式和准入判断，当前运行状态见第 1、2 节。

M2 只读基线与分批门禁见 [M2 代码质量与模块化单体治理计划](#m2-代码质量治理记录)。
当前继续修复数据映射与格式问题，并已建立首批模块边界门禁；微服务拆分和 PostgreSQL 迁移仍是独立架构待办。
首批已修正支付退款实体主键映射，并将支付模拟服务通配符导入与
超过 120 字符的 Java 行均清零。支付服务 9 项测试及 Windows/容器化 Bash 真实 MySQL
退款全链路专项均已复验通过。物流模拟服务也已完成强类型持久化投影与格式治理，
并通过回调恢复、DEAD 人工重投、固定种子乱序并发和容器化 Bash 基础链路验收。
商城服务已建立 4 条 ArchUnit 模块边界门禁，移除用户 API 直接访问 Mapper 的越层依赖，
并完成 `identity` 首批格式治理。第二批完成 `catalog`、`cart` 格式与数据访问治理，
商品翻译改用 MyBatis-Plus 单表查询，并修复旧订单投影遗漏商品 ID；25 项 Maven 测试、
Windows L2 快速回归、容器化 Bash i18n/权限回归及现有 MySQL 卷的订单投影实测均通过。
第三批完成 `customer`、`review`、`receipt` 格式与数据访问治理：地址查询改用 Lambda Wrapper，
评价写入改用带字段注释的实体和 `BaseMapper`，复杂联表、聚合与批量更新仍保留在 Mapper XML。
目标三个包的通配符导入和超过 120 字符的 Java 行均为 0；25 项 Maven 测试、Windows 快速回归、
容器化 Bash 收货/评价专项和 37 对脚本契约审计均通过，未删除或重建数据卷。
第四批完成 `fulfillment` 格式治理，商家归属联表、订单行锁和条件状态更新仍保留在 Mapper XML，
事务分段与远程物流调用顺序未改变。目标包的通配符导入和超过 120 字符的 Java 行均为 0；
正式镜像构建、25 项 Maven 测试、Windows/Bash 履约专项、Windows 快速回归及脚本契约审计均通过，
未删除或重建数据卷。第五批完成 `ordering` 格式、数据库投影注释和 Mapper XML 可读性治理；
购物车行锁、订单/支付幂等锁和条件状态迁移仍保留原事务边界。目标包的通配符导入、超过 120 字符的
Java 行和 Mapper XML 超长行均为 0；正式镜像构建、25 项测试、Windows 快速回归、Bash 订单取消、
支付/取消竞态与商家履约专项以及脚本契约审计均通过，未删除或重建数据卷。下一批从 `payment`
继续按业务包治理商城服务，不做全仓一次性机械重写。第六批已完成 `payment` 格式治理，
目标包的通配符导入和超过 120 字符的 Java 行均为 0，未改动支付 SQL、事务、锁、幂等或签名语义。
正式镜像构建、25 项测试、Windows 快速回归、容器化 Bash 支付安全链路和 37 对脚本契约审计均通过，
未删除或重建数据卷。第七批已完成 `refund` 格式、持久化投影注释和 Mapper XML 可读性治理；
成功支付行锁、退款幂等、条件状态迁移、金额上限与通知去重语义保持不变。目标包的通配符导入、
超过 120 字符的 Java 行和 Mapper XML 超长行均为 0；正式镜像构建、25 项测试、Windows/Bash 退款专项、
Windows 快速回归和 37 对脚本契约审计均通过，未删除或重建数据卷。下一批从 `logistics` 继续。
第八批已完成 `logistics` 格式、持久化投影注释和 Mapper XML 可读性治理；运单 upsert、回调行锁、
事件幂等和乱序状态保护语义保持不变。目标包的通配符导入、超过 120 字符的 Java 行和 Mapper XML 超长行
均为 0；正式镜像构建、25 项测试、Windows 物流主链路/回调恢复/人工重放治理、容器化 Bash 物流主链路、
Windows 快速回归和 37 对脚本契约审计均通过，未删除或重建数据卷。
第九批已完成 `scheduling` 格式与 Mapper XML 可读性治理，任务领取、租约、重试、对账和 MQ 消费语义
保持不变。商城业务源码的通配符导入和超过 120 字符的 Java 行均已清零；正式镜像构建、25 项测试、
Windows 快速回归及 Windows/容器化 Bash 的 RabbitMQ/RocketMQ 幂等专项通过，未删除或重建数据卷。
M2.3 已完成，下一阶段为 M2.4 数据库注释与自动门禁。

M2.4 第一批已新增商城 V24、支付模拟器 V4 和物流模拟器 V5，为 16 张核心业务表及
133 个核心标识、状态、金额和时间列补充数据库注释。现有三库已非破坏性升级到 V24/V4/V5；
Windows/容器化 Bash 隔离 Flyway 演练和 Windows 三库空卷完整业务链路通过。

M2.4 第二批已建立 Windows/Bash 后端只读质量门禁，覆盖 244 个 Java 文件、20 个 Mapper XML、
189 个映射语句和 3 个 Maven POM，并接入 `verify-quick`/`verify-all`。商城 25 项（含 4 条 ArchUnit）、
支付 9 项测试、支付安全专项及 Windows 快速回归通过；容器化 Bash 质量门禁实测通过。M2.4 后端门禁完成。

M2.5 第一批已完成 Angular 商城门户的 Prettier 3.10.1 格式治理，`format:check`、29 项
ChromeHeadless 测试、本地与正式 Docker 生产构建均通过。镜像构建已强制只读格式检查；
运行态 `/healthz`、中英文及三角色权限专项通过，未删除或重建数据卷。

M2.5 第二批已完成支付模拟器 Web 的 Prettier 3.10.1 格式治理。基线 24 个目标文件无制表符和压缩行，
20 条超过 120 字符的行经机械格式化收口；`format:check`、Vitest（当前无测试文件）、本地生产构建、
正式 Docker 镜像构建和 `14201/healthz`/Vue 入口运行态通过。正式镜像已强制只读格式检查，未改支付业务语义，
未删除或重建数据卷。M2.5 完成，下一阶段为 M2 工程质量总验收和基线冻结。

M2 总验收已完成：Windows 默认快速/完整验收、容器化 Bash 默认快速/完整验收、双平台质量与
37 对脚本契约审计均通过；默认及全 Profile Compose、五个正式镜像、11 个默认核心服务和 8 个主要
健康端点已复验。支付 Web 上游动态解析、连续验收库存准备及 Alertmanager 冷启动通知日志边界已收口，
全新重建监控栈后的 Watchdog 在 Windows/Bash 均真实送达本地 webhook。整个验收保留现有数据卷；
微服务与 PostgreSQL 迁移未启动。M2 以注释标签 `m2-quality-baseline-2026-11-14` 冻结。

### 8. M3.1 前置审计当前进展

首批先收紧可审计的代码结构基线：物流模拟器已由根包平铺调整为
`api/application/domain/infrastructure`，基础设施继续按客户端、配置、指标、持久化和安全职责分目录，
Mapper 投影独立放入 `infrastructure/persistence/row`。三个服务全部 256 个 Java 主源码/测试源均要求
每个 `class` 声明后保留空行，Windows/Bash 质量门禁同时约束物流根包只保留启动类。

248 个主源码 Java、20 个 Mapper XML、189 个映射语句和 3 个 POM 的双平台质量审计通过；物流服务
正式镜像完成 Maven 打包，签名建单、重放/伪造签名负例、异常恢复、完整轨迹回调以及
`DEAD -> 管理员重放 -> PUBLISHED` Outbox 治理专项通过。现有数据卷保留，微服务拆分和 PostgreSQL
迁移均未启动；该批没有提前实施架构拆分。

第二批完成了完整审计并形成 [M3.1 微服务拆分前置条件审计](../architecture/M3-PRE-SPLIT-AUDIT.md)：
商城当前 37 张表均已声明责任模块；40 组跨模块方向包含 117 个 import 引用，分布在 39 个源码文件，
其中 21 个引用直接指向其他模块的 `infrastructure`。新增 2 项 Maven 架构基线测试，允许依赖债务减少，
但禁止新表无所有者、出现新依赖方向或增加跨模块基础设施引用。

Kafka 已有 5 个 topic、事务 Outbox 和由唯一键保护的 Inbox 等价去重，RabbitMQ/RocketMQ 也具备重复、
迟到、毒消息和重投证据；但事件信封没有 schema version、发生时间、因果/关联 ID 或 trace context，
消费者契约 fixture、每服务独立 Inbox 和真正的分布式追踪仍缺失。因此 M3.2 尚不可启动；下一批先治理
notification 表由 InventoryMapper 直接写入的所有权倒置，不创建新服务或数据库。

第三批完成 notification 写入责任收口：商城 V25 新增 notification 自有 Inbox；
`CustomerNotificationCommandV1` 固定 schema version、事件标识/类型、发生时间、业务字段及
correlation/causation ID。InventoryMapper 已不再写通知表，notification Handler 在原业务事务中先以
`(consumer_name,event_id)` 领取命令，再通过自己的 Mapper 写通知。消费者 fixture、重复消费和
Windows/Bash 真实取消通知链路通过，商城测试增至 28 项。

第三批审计基线曾更新为 38 张表、41 组方向和 119 个跨模块 import；21 个跨模块基础设施引用没有增加。
该批没有创建新服务或数据库，传输仍为单体内应用调用；当时遗留的异步适配器、恢复、身份解耦、
追踪传播和回退开关已由下述第四批完成。

第四批完成 notification 拆分前传输门禁：商城 V26 新增 notification 自有投递 Outbox，并为 Inbox 增加
correlation/causation/traceparent。`CustomerNotificationCommandPublisher` 支持默认进程内与 Kafka 两种适配器；
Kafka 发布失败可持久重试、进入本地 DEAD 后重放，消费毒消息有限重试后进入专用 DLT，达到阈值也可按开关
回退进程内 Handler。Kafka payload/Header 双通道校验 W3C traceparent，Inbox 保留最终证据。

真实 Broker 停机时验证了 `RETRY:1 -> PUBLISHED:2`，恢复后 Inbox/通知各一条；持续故障且回退开启时验证
`FALLBACK:1`。共享调度池扩为 4，Producer 阻塞/请求/交付超时为 5/3/5 秒，避免其他 Outbox 饿死
notification 恢复任务。notification application 已不再接收 `AppUser`，只使用 customerId。当前基线为
39 张表、41 组方向、118 个跨模块 import、38 个涉及文件和 21 个基础设施引用。当时 M3.2 仍被
投影核对窗口、停止条件与独立 Schema 迁移演练阻塞；该批未创建服务或数据库，默认仍为 `IN_PROCESS`。

第五批完成 notification 影子投影验证窗和独立 Schema 迁移演练：商城 V27 增加窗口、影子投影与核对审计，
独立 Kafka consumer group 按窗口起始水位核对主投影。真实窗口共匹配 5 条通知，missing/orphan/mismatch 和
阻塞 Outbox 均为 0，连续 3 次检查通过后才由 `READY` 显式停止为 `STOPPED`。默认配置随后恢复为
`IN_PROCESS` 且投影关闭。

独立 notification Schema V1 只包含通知、Inbox 和投递 Outbox 三张表且无外键；Windows 与容器化 Bash
已在随机网络和 tmpfs MySQL 中验证 Flyway、快照字段和 Inbox 幂等。商城空库 V1→V27 与旧库 V16→V27
隔离升级演练均通过。当前商城所有权基线为 42 张表；依赖基线仍为 41 组方向、118 个 import、38 个文件和
21 个基础设施引用，HTTP 基线为 18 个 Controller、60 个操作。独立 Schema 尚未成为实际运行目标，
notification-service、独立运行数据库和 PostgreSQL 均未启动，M3.2 仍需单独授权。

第六批完成 M3.1 最终门禁：Compose 增加可选 `notification-storage` Profile 的独立 `notification-db`；commerce-service 通过专用连接池、Flyway、
MyBatis Session 与事务管理器访问 notification Schema，商城命令 Outbox 仍由原业务事务持有。V2/V3 增加
历史回填水位、核对审计和孤儿隔离证据，不使用跨 Schema 外键、JOIN 或事务。

真实历史回填从 159 条开始追平。新 consumer group 重放时发现 2 条故障专项旧命令，核对以 orphan=2
阻止切换；完整归档后活动读模型恢复一致。Windows 与容器化 Bash 随后分别完成实时 Kafka 通知、连续三次
零差异、INDEPENDENT 读切换、按 eventId 镜像已读状态、notification-db 停机和 COMMERCE 回滚。
最终商城/独立通知均为 167 条，默认恢复 `IN_PROCESS + COMMERCE`，两个数据库均健康。

当前质量基线为 283 个主源码 Java、21 个 Mapper XML、235 条映射、3 个 POM 和全部 292 个 Java 源文件；
脚本契约为 41 对，HTTP 基线为 19 个 Controller、63 个操作。商城所有权及跨模块依赖基线仍为
42 张表和 41/118/38/21。M3.1 已完成并具备 M3.2 启动条件，但 notification-service 与 PostgreSQL 均未启动。

第七批完成 M3.2 独立进程准入所需的首个跨进程追踪基线。三个 Java 服务接入 OpenTelemetry，统一
W3C Trace Context、日志 `traceId/spanId`、采样和 OTLP 导出开关；Compose 增加可选 Tempo，Grafana 增加
Tempo 数据源。默认关闭导出，因此追踪后端不是默认业务依赖，Prometheus 指标链也不改由 OTLP 承担。

Windows 与容器化 Bash 的配对专项均查询到 commerce-service 与 logistics-simulator-service 的同一 Trace，
并确认 Tempo 停机时业务服务继续健康。Kafka `send/process` Span 已实测；notification V1 同时保留业务来源
traceparent 和传输 Span，上述兼容修复又通过带 tracing 的独立 Schema 实时切换全演练。RabbitMQ 通过
Observation 与显式 W3C 上下文跨越 TTL/DLX，RocketMQ 通过消息 properties 注入/提取上下文。
Windows 任务 410/411 和容器化 Bash 任务 414/415 均在 Tempo 查询到同一 Trace 的 publish/process Span，
两平台也验证 Tempo 停机不影响商城健康。脚本契约现为 41 对。四种边界的本地追踪准入门禁已经补齐，
下一步可单独评审 notification-service 影子进程；本批未创建服务或迁移 PostgreSQL。

第八批在单独授权后实现 `notification-service` 影子进程。服务按
`api/application/domain/infrastructure` 分层并继续拆分 `messaging/persistence`，只消费冻结的 notification
V1 命令，使用稳定消费者组、独立 MySQL Schema、Inbox 和本地事务，不访问商城 Mapper 或 Schema。
Compose 的 `notification-service` Profile 默认关闭，健康与只读影子状态端点使用 `18086`。

Windows 与容器化 Bash 均完成实时写入、进程停机期间消息积压、重启追平、commerce/notification 同一
Trace 和 `IN_PROCESS` 单体回退。当前质量基线为 293/22/239/4、全部 303 个 Java 源文件和 42 对脚本契约。
验收后 notification-service 已关闭，商城恢复默认配置；客户通知 API 尚未切到独立服务，单体表和回退路径
保留，PostgreSQL 未启动。

第九批实施 notification 单一所有权切换：notification-service 补齐四个内部 V1 API、客户隔离和 HMAC
调用方校验并进入默认 Compose 拓扑；commerce 原四个公开接口只做 Session/RBAC、签名与限时 HTTP 转发。
Angular 改用 eventId 已读并在 503 时显示重试。commerce 内本地通知 Mapper、消费者、独立数据源、投影、
回填核对和运行模式均删除，只保留事务 Outbox、V1 生产契约与历史 Flyway。Windows 专项已验证真实 Kafka
通知、已读落库、独立服务停机 503、核心商城健康与恢复；交接时 commerce 历史通知为 0 行，Kafka 三分区
lag 均为 0。Windows/Bash 通知专项、快速回归、质量审计和 41 对脚本契约均已通过；Windows 完整验收通过。
Bash 等价全链路通过全部业务阶段，告警尾段在 Git Bash 排除容器路径的 MSYS 参数转换后补验，结果为 3 个
健康采集目标、10 条规则和 1 次 Watchdog 投递。本批已以 `88d22c6` 独立提交并推送。

第十批开始 M3.3 catalog 拆分准入。首批建立 `CatalogProductLookup`、`CatalogCacheInvalidator` 和
`ProductReviewCatalogQuery` 应用边界，cart/order/catalog/review 不再跨模块注入 ProductMapper/ReviewMapper，
依赖基线由 41/118/38/21 收紧为 41/113/37/18，并以 `587a75d` 独立提交推送。

第二批以商城 Flyway V28 完成数据所有权冻结：catalog 的 `products` 只保存商品资料，inventory 自有
`product_inventory` 独占可售、预占、已售数量并保存履行快照；库存预占/流水外键已改接新表。cart_items、
customer_orders 固化名称、图片和单价，购物车、轻量订单、结算锁定和商家库存不再 join `products`；同时
移除 products→identity 及 cart/order/review→catalog 的跨边界外键。现有库 6 条库存记录和 642/1/65 数量
总和保持不变，Windows/容器化 Bash 的空库 V1→V28、旧库 V16→V28 隔离升级均通过。当前所有权基线为
43 张表，依赖基线保持 41/113/37/18；catalog-service、inventory-service、独立数据库及 PostgreSQL 均未启动。

第三批以商城 Flyway V29 冻结 catalog V1 边界。认证查询提供 ACTIVE 列表、详情和最多 100 个 ID 的批量
快照；商家 API 按 `api/merchant` 子目录分层，支持本人商品资料、价格、状态和翻译维护。每次写入携带
expectedVersion，成功后 businessVersion 加一，并在同一事务写 catalog 自有 Outbox；并发旧版本稳定返回
`409/CATALOG_VERSION_CONFLICT`。profile/price 更新提交前同步 inventory 展示快照，失败则商品与事件一起回滚。

五类事件 V1 已固定 schema/event/业务版本、发生时间、correlation/causation 与商品快照，fixture 和版本门禁
覆盖 APPLY、STALE 与版本缺口重试。商品创建事件已冻结，但创建 API 等 inventory 初始化命令后才开放。
当前商城所有权基线为 44 张表，依赖基线仍为 41/113/37/18；HTTP 为 20 个 Controller、66 个操作。
Java 25 正式镜像 32 项测试、双平台 V1→V29/V16→V29 生命周期及快速回归通过；真实 Docker V1 中文详情、
批量快照和 6 条商家目录已验证。catalog-service、独立数据库、inventory-service 与 PostgreSQL 均未启动。

第四批已创建独立 `catalog-service` 和 `catalog-db`，按 api/application/domain/infrastructure 分层承接商品
资料、翻译、业务版本和 catalog Outbox。一次性迁移以 commerce V29 为源门槛，复制 6 条商品、6 条翻译和
既有 Outbox，计数与内容指纹一致后写入 `catalog-v29-single-owner=COMPLETE`；重复运行只读取 COMPLETE，
不会用陈旧 commerce 表覆盖独立库。首次真实演练的字符集差异被 FAILED 门禁阻止，补齐 utf8mb4 后完成，
未删除命名卷。commerce 已删除 catalog Mapper/XML/本地写路径与 shared 过渡投影，只通过 HMAC HTTP 访问；
catalog 停机稳定返回 503，commerce 核心健康保持 UP，跨进程 Trace 同时出现 commerce-service 与
catalog-service。inventory-service 与 PostgreSQL 均未启动。

第五批进入 M3.4 inventory-service 准入评审。ordering、payment、refund 已改为只依赖
`inventory/application/port/InventoryCommandPort`，预占输入不再泄露 cart DTO，商家库存应用服务不再接收
identity 的 `AppUser`。新增 ArchUnit 门禁阻止外部模块依赖库存 API、domain、infrastructure 或具体实现；
当前跨模块实际依赖收紧为 39 个方向、109 个 import、36 个源码文件和 18 个 infrastructure 引用。评审同时
确认 order 外键、取消通知副作用、本地事务、独立 Inbox 和 catalog 事件消费者仍是拆分阻塞项，因此本批没有
创建 inventory-service/inventory-db、迁移数据或启动 PostgreSQL。Java 25 的 30 项测试、Windows 新运行 JAR
快速回归及容器化 Bash 质量/脚本审计通过；从工具容器调用宿主 Compose 因路径身份不同导致迁移脚本 126，
已用 Windows 原生 Compose 恢复全部健康，未把该次 Bash 全链路记为通过，也未删除命名卷。

第六批完成 M3.4 inventory V1 命令边界。`application/contract/v1` 固定 RESERVE、COMMIT、RELEASE、REFUND
统一信封和四份 fixture；V30 新增 `inventory_command_inbox`，同一事务完成幂等领取、库存变化和 SUCCEEDED，
重复同载荷不重复扣减，同 commandId 异载荷拒绝。两条 inventory→shop_orders 外键和 customerId 跨表查询已
删除，取消通知改由订单取消事务写 notification Outbox，inventory→notification 依赖归零。当前跨模块实际为
39/109/35/18。Java 25 共 32 项测试，Windows 与容器化 Bash 的 V1→V30、V16→V30 隔离升级及 Windows
快速回归均通过；默认 Docker 持久库已升级到 V30，17 条真实 inventory 命令全部 SUCCEEDED，非成功命令和
两条跨边界订单外键均为 0，未删除或重建命名卷。本批仍未创建 inventory-service/inventory-db，也未启动
PostgreSQL。

第七批完成 catalog→inventory 单向事件投影。catalog-service Outbox 通过 HMAC 服务身份和已签名载荷摘要向
inventory 内部端点投递，最多 5 次后进入 DEAD；V31 新增 inventory 自有 catalog-event Inbox 及商品快照
生命周期/businessVersion。重复 eventId 幂等、异载荷拒绝、旧版本 STALE、版本缺口重试，翻译事件不会覆盖
库存主名称，事件不改变任何库存数量。Java 25 的 catalog 5 项、commerce 35 项测试通过；真实 Docker 完成
版本 1→2 投递，以及 commerce 停机 RETRY、恢复后第 4 次尝试 PUBLISHED、inventory 单次 APPLIED 并推进到
版本 3。双平台 V1→V31/V16→V31、Windows 快速回归和双平台质量/脚本审计通过。未删除或重建命名卷，
inventory-service/inventory-db 和 PostgreSQL 仍未启动。

第八批冻结 inventory 命令投递与结果协议。四类命令统一经过 `InventoryCommandGateway`；V32 新增生产端
delivery Outbox 和结果 Inbox，当前本地适配器在同一事务留下 SUCCEEDED/ACKNOWLEDGED 证据并调用唯一
Handler，不维护双实现。`InventoryCommandResultV1`、成功/拒绝 fixture、共享语义指纹和超时状态机已落地；
结果未知达到上限进入 RECONCILIATION_REQUIRED，不伪造拒绝或盲目补偿。commerce Java 25 共 37 项测试
通过；双平台 V1→V32/V16→V32 生命周期、Windows 快速回归和双平台质量/脚本审计均通过。默认持久库已升级
到 V32，32 条新协议命令均为 SUCCEEDED/ACKNOWLEDGED，未决投递为 0。当前尚未启用远程传输；订单等待库存、
履约阻断、退款库存待对账和管理恢复入口仍是独立服务前门禁，inventory-service/inventory-db 与 PostgreSQL
均未启动。

第九批补齐生产侧业务等待态和人工恢复门禁。V33 将订单主状态与 inventoryStatus 分离，checkout、支付提交、
取消释放和退款归还均留下明确状态；只有 COMMITTED 才允许发货。退款财务结果与库存归还结果独立表达，未知
结果进入 RECONCILIATION_REQUIRED。ADMIN 恢复入口只用原 commandId/原 payload 重试，成功后同步恢复
ACKNOWLEDGED 和业务状态；V34 记录操作者、原因和时间。Java 25 正式镜像 38 项测试通过；
inventory-service/inventory-db 与 PostgreSQL 仍未启动。双平台 V34 生命周期和 Windows 快速回归通过；默认
持久库 124 条订单 COMMITTED、118 条 RELEASED、21 条 REFUNDED，21 条退款库存状态均已收口，53 条命令
全部 ACKNOWLEDGED；该项的后续最终准入评审已由第十批完成。

第十批完成 inventory-service 最终创建准入评审。运行手册明确五张 inventory 所有权表迁入独立库，生产端
delivery Outbox 和结果 Inbox 留在 commerce；冻结快照、增量追平、短暂停写、最终指纹核对、整体切换、
跨进程 Trace 与独立库新写后的受控回退规则。Windows/Bash 只读审计均通过 V34、七张边界表、零跨域外键、
余额/预占/已售汇总、Inbox/Outbox 闭环、零业务等待态和唯一 Gateway。当前基线为库存聚合 6 条、预占
263 条、流水 548 条、命令 Inbox 87 条、catalog Inbox 2 条，未决与待对账均为 0。本批仅授予 CREATE
资格；没有创建 inventory-service/inventory-db 或切换流量，CUTOVER 与 PostgreSQL 继续冻结。

第十一批创建分层 inventory-service、独立 MySQL 和 `inventory-shadow` Profile。V1 只持有五张 inventory
所有权表与迁移状态；唯一命令 HTTP 适配器和 catalog 事件适配器复用 V1 Inbox、幂等指纹、余额状态机、
HMAC 与 Trace 信封。默认流量门禁关闭，健康检查为 UP，未签名请求 401、已签名请求 503，commerce/catalog
均未改指向。可重复迁移连续至少 20 代 READY，快速回归后的源/目标完整字段指纹一致。Java 25 的 2 项测试、Windows/Git Bash
影子验收、368 个 Java/30 个 XML/252 个语句/6 个 POM 的质量门禁及 43 组脚本契约通过。未切流、未接收独立
业务新写、未删除 commerce 本地实现；CUTOVER 与 PostgreSQL 继续冻结。

第十二批补齐可配置停写门禁和唯一 HMAC HTTP 远程客户端，但只开放默认关闭的管理员预演入口，不把业务
Gateway 分成 LOCAL/COMPARE 两套。Windows/Git Bash 均完成停写、最终追平、历史 COMMIT 原命令幂等重放、
inventory-service 停机 503 与恢复；预演前后独立库余额/流水/Inbox 未变化。Windows 快速回归通过后，迁移器
generation 23/24 再次得到相同五表指纹：`6|575|1|132|1824707114`、`279|279|2231803732`、
`582|688|2202142859`、`121|2961863654`、`2|2798068318`。Java 25 正式镜像 40 项测试、372 个 Java、
385 个类声明空行和 44 对脚本门禁通过；三个运行开关已恢复 false。真实新写仍走 commerce 本地路径，四类
Tempo Trace、超时/结果丢失及整体 CUTOVER 仍待下一批，PostgreSQL 继续冻结。

第十三批完成库存命令生产切换。commerce 业务事务只写 delivery Outbox，唯一异步派发器远程发送四类命令，
Result V1 确认后再推进订单/退款库存状态；inventory-service/inventory-db 已进入默认拓扑并独占新库存事实写入。
返回结果丢失、服务停机、数据库停机均以原 commandId 恢复且只记一条流水；跨进程 Trace
逐类证明 RESERVE/COMMIT/RELEASE/REFUND 均同时包含 commerce/inventory。完整 Windows 验收后 commerce
源流水仍为 582，独立库增至 627；42 项 Java 25 测试、374/30/253/6 质量门禁、388 个类声明空行及 45 对脚本契约通过。
本批只完成四类命令所有权切换：商家库存查询/调整仍使用 commerce Mapper，所以下一批必须先迁出该管理边界，
再删除本地 Handler/Mapper/XML 和源表权限。PostgreSQL 继续冻结。

第十四批完成 inventory 完整单一所有权。商家库存列表、调账与流水查询迁入 inventory-service，commerce
公开 API 只保留 Session/CSRF/MERCHANT RBAC 和 HMAC 薄代理；独立服务停机稳定返回 503，不回退本地表。
commerce 本地库存 Handler、领域余额对象、四个 Mapper/XML、旧 catalog 内部入口及专用签名代码已删除。
Compose 先以迁移账号执行 Flyway，再为 `commerce_runtime` 逐表授权；该账号可访问 commerce 自有表，但五张
库存源表均被 MySQL 1142 拒绝。Windows 实机完成 `72 -> 73 -> 72` 调账恢复、未签名 401、停机 503 和恢复，
源流水保持 582，目标流水增至 641。Java 25 共 44 项测试，368/27/231/6 质量门禁、383 个 Java 源文件类声明
空行及 46 对脚本契约通过；Bash 对等脚本已通过语法审计。本批未启动 PostgreSQL，下一步是 order-service
边界准入治理，不直接创建服务或数据库。

M3.5 第一批完成 order-service 边界审计与自动门禁。order 最终拥有 `shop_orders`、`order_items`、
`order_shipping_addresses`、`order_events` 四表；当前 4 条出站和 5 条入站跨边界外键、7 个外部
`ShopOrderMapper` 消费者仍阻止 CREATE。当批默认持久卷为 Flyway V34，订单/商品项/地址均为 313 条、事件
1477 条、订单与商品项金额均为 406587.00；重复、孤儿、金额错配、非法状态和待恢复流程均为 0。Windows
与 Git Bash 审计结果一致，Java 25 正式镜像 42 项测试通过。分布式事务固定为本地事务 + Outbox/Inbox +
持久化 Saga/Process Manager + 补偿/对账，不引入 Seata、DTM、XA 或全局事务协调器。本批未创建
order-service/order-db，未并行拆 payment-orchestration/fulfillment，也未启动 PostgreSQL。

M3.5 第二批新增 `ordering/application/port` 和 `ordering/application/contract/v1` 子目录。fulfillment、
logistics、payment、receipt、refund 只通过五类意图端口访问订单，7 个外部 `ShopOrderMapper` 消费者归零；
支付回调的锁定、通知、支付状态和事件 SQL 迁入 payment 自有 Mapper。OrderCommandV1/OrderResultV1 固定稳定
ID、前置状态、失败码、correlation/causation/W3C Trace，以及“超时不是失败”的结果语义，两个兼容 fixture
通过测试。Windows/Git Bash 审计均保持 V34、9 条跨边界外键和数据指纹一致；Java 25 正式镜像 44 项测试、
380/28/232/6 质量门禁与全部 396 个 Java 源文件类声明空行通过。CREATE 仍因跨边界外键、Mapper 剩余
cart/address/payment 表职责及持久化 Saga 尚未完成而冻结；未创建服务/数据库，PostgreSQL 未启动。
本批 Windows 快速回归完整通过后，最新一致性审计为订单/商品项/地址各 321 条、事件 1521 条、金额
416979.00，指纹 `orders|321|416979.00|832328281`；所有错误计数仍为 0。

M3.5 第三批完成最终 CREATE 准入。cart/address/payment SQL 从 `ShopOrderMapper` 迁回各自边界端口和 Mapper，
订单 Mapper 只访问四张核心表；Flyway V35 删除 4 条出站、5 条入站跨边界外键，并新增 order Process Manager、
命令 Outbox 和结果 Inbox 三张表。流程基线覆盖同事务启动、命令/结果 payload 指纹幂等、冲突拒绝、超时未知
进入待对账、拒绝进入待补偿及带操作者/原因/时间的人工恢复。空库 V1→V35、遗留 V16→V35、Java 25 正式
镜像 45 项测试、394/31/243/6 质量门禁、410 个 Java 源文件 class 字段空行、Windows 快速回归和 Windows/
Git Bash 准入审计均通过。最终持久卷为订单/商品项/地址各 329 条、事件 1565 条、金额 427371.00，流程/
命令 Outbox/结果 Inbox 为 0，双向跨边界外键、外部 Mapper 消费者、Mapper 外部表职责和所有一致性错误均为 0。
CREATE 已授权，CUTOVER 仍冻结；该结论是第三批结束时的历史边界，当批未启动 PostgreSQL。

M3.5 第四批创建独立 `order-service/order-db`，但 commerce 不依赖该服务，Order V1 业务流量门固定关闭。
V36 用单例提交顺序时钟和 21 个触发器记录 7 张所有权表增量；order-service 以 REPEATABLE READ 一致性快照、
批次增量、水位、逐字段 SHA-256 审计和孤儿隔离/重放维护影子库。双平台专项复跑后 generation 8、水位 120，
337 笔订单、337 条商品项、337 份地址、1609 条事件、金额 437763.00 与三张空协议表均 MATCH；一次目标父记录
缺失演练已隔离并恢复为
RESOLVED，OPEN 0。order-service 和 order-db 分别停机期间产生的增量均在恢复后追平。当前 407/31/243/7
质量门禁与 424 个 Java 源文件类声明空行通过；PostgreSQL、payment-orchestration/fulfillment 拆分和 CUTOVER
均未启动。

M3.5 第五批把远程 Order V1 从只校验推进为可恢复协议适配器。内部请求使用 HMAC-SHA256、正文 SHA-256、
5 分钟时间窗和持久 requestId 防重放；命令以 messageId/payload 指纹 Inbox 去重，条件状态更新与事件、取消
Process Manager 及两条下游命令 Outbox 在 order-db 本地事务提交。结果 Inbox 拒绝同 ID 异载荷和乱序结果，
丢失结果到期只进入 `WAITING_RECONCILIATION`，人工 RETRY 记录操作者、原因和时间。Windows 合成订单演练在
Tempo 验证真实 trace，覆盖重复、冲突、乱序、超时、丢失、两阶段取消结果和恢复；结束自动清理并恢复默认
503 门。最终快速回归后 generation 20、水位 236，345/345/345/1653、金额 448155.00 及三张空协议表全部
MATCH；临时账号验证
源端权限冻结和恢复、order-service 停机及源端唯一所有者回退。实际 commerce 账号未冻结，未 CUTOVER，
PostgreSQL 与后续两个服务拆分均未启动。

M3.5 第六批在 commerce 停写后完成 generation 21 / 水位 236 的最终快照和 7 表 MATCH，再一次性切换订单
读写。commerce 删除订单 Mapper、本地 Process Manager 与源表 SQL，生产运行只经远程 Order Store V1；
Compose 默认打开业务门、关闭影子复制，order-service 不再持有源库连接。`commerce_runtime` 对库存和订单
源表权限均被撤销。专项验收确认新订单只写 order-db，源端保持 345/448155.00；order-service 停机时返回
503，恢复后健康。完整快速回归后 order-db 为 359/359/359/1710、金额 466341.00，协议表与 OPEN 孤儿均为
0；413/28/204/7 质量门禁、431 个 Java 源文件空行和 50 对脚本契约通过。本批未拆后续服务，未启动 PostgreSQL。

M3.6 第一批把商城支付编排与退款统一评审为一个候选资金边界，冻结 11 张候选所有权表。持久卷为 299 笔支付、
总额 388401.00、累计退款 41568.00，32 笔退款总额同为 41568.00；437 条 Outbox 全部 PUBLISHED，金额、
状态、幂等、渠道证据、退款投影和事件不变量均通过。CREATE 尚未准入：仍有 1 条 refund→identity 外键、
5 个外部 infrastructure 消费者文件和 1 条 OPEN 对账记录。首批只增加源码/数据库只读门禁与双平台审计，
Windows/Linux 审计、45 个 commerce-service 测试、50 对脚本契约与快速回归通过；未创建
payment-orchestration-service/payment-db，未切流、未并行拆 fulfillment，也未启动 PostgreSQL。

M3.6 第二批冻结创建支付、按订单查询支付/退款、关闭待支付记录和支付/退款结果的 Payment V1 契约。ordering
与 fulfillment 对 payment/refund 的 application、domain、infrastructure 和 Mapper 直接导入归零，只保留稳定
V1 数据结构及订单侧端口；当前由唯一 `LocalPaymentOrchestrationAdapter` 承接原实现，不存在 LOCAL/COMPARE。
创建/关闭命令、关闭结果和支付成功结果共 4 个 JSON 兼容样例覆盖版本、幂等键、关联/因果 ID、W3C Trace、
payload 指纹、“超时结果未知”和支付成功优先语义。CREATE 仍因 refund→identity 外键、1 条 OPEN 对账记录和
尚未收口的 payment-backed 订单事件 Outbox/Inbox 适配器、Process Manager/结果 Inbox/人工恢复门禁而冻结。
回归同时修复迟到 RESERVE 结果把已 COMMITTED 库存倒退为 RESERVED 的竞态，人工核对后的恢复重试仍可推进终态。最终持久卷为 324 笔支付、36 笔退款，外部
内部实现导入与数据不变量错误均为 0；48 项测试、431/28/204/7 质量门禁、451 个 Java 源文件类声明空行、
Windows/Linux 审计、50 对脚本契约和完整快速回归通过。本批没有创建服务/数据库或启动 PostgreSQL。

M3.6 第三批删除 refund→identity 外键，并把唯一 OPEN 超时对账依据已为 `CLOSED` 的支付事实审计式关闭。
179 条历史订单副作用 Outbox/Inbox 已迁入 ordering 自有表，payment 表不再承载 `order-events`。新增支付/退款
Process Manager、命令 Outbox、结果 Inbox，以及同 ID 异 payload 冲突、未知结果、超时转人工核对和带操作人、
原因、时间的人工重试门禁。持久卷 Flyway V37 审计为 14 张 payment/refund 所有权表、双向跨边界外键 0、
OPEN 对账 0、payment 中订单事件 0，179/179 条订单 Outbox/Inbox 完整保留；55 项测试通过。CREATE 已准入，
CUTOVER 仍冻结；本批没有创建独立服务/数据库、没有并行拆 fulfillment，也没有启动 PostgreSQL。

M3.6 第四批创建分层 `payment-orchestration-service` 和独立 MySQL `payment-db`，但 commerce 仍只使用唯一
`LocalPaymentOrchestrationAdapter`，独立服务的 `PAYMENT_ACCEPTING_TRAFFIC=false`，只开放影子管理端点。
Flyway V38 以 42 个触发器和提交顺序水位捕获 14 张所有权表变更；独立服务完成 REPEATABLE READ 可重复快照、
增量追平、逐字段 SHA-256 核对和外键孤儿隔离/重放。Windows 与 Ubuntu 26.04 WSL 专项及最终快速回归后
generation 3、水位 83，支付 353、商户 3、通知 248、支付事件 706、幂等锁 353、对账 2、
Outbox/Kafka Inbox 各 326、退款 40、退款事件/通知各 80，三张恢复协议表为空，14 张表全部 MATCH；两次孤儿演练均 RESOLVED，OPEN 0。
本批没有业务流量、CUTOVER、LOCAL/COMPARE、fulfillment 拆分或 PostgreSQL。

M3.6 第五批在独立服务实现受流量门保护的 Payment V1 创建、查询、关闭、结果和恢复入口，以及支付/退款渠道
回调；创建与关闭会用原签名协议真实调用 payment-simulator-service。Flyway V2 持久化命令 Inbox 和内部请求防重放；HMAC、正文摘要、时间窗、requestId、payload 指纹与 W3C
Trace 均有专项门禁。同命令重放返回原结果，同 ID 异正文拒绝；支付成功先到或关闭后迟到成功都保持资金成功优先。
结果丢失会进入人工核对，恢复重试保存操作人、原因和时间。Windows/Ubuntu 26.04 WSL 均实跑服务/数据库停机、
退款、重复通知、临时源权限冻结拒绝和整体回退，结束后删除合成数据并恢复关闭流量。

完整快速回归并最终追平后 payment-db Flyway V2 无失败，generation 13、水位 160；支付/商户/通知/事件/幂等锁/
对账/Outbox/Kafka Inbox/退款/退款事件/退款通知为 360/3/253/720/360/2/333/333/41/82/82，三张恢复协议表为空，
14 张表全部 MATCH，OPEN 孤儿和待恢复流程均为 0。payment 服务 2 项 Java 25 测试、461/30/220/8 质量门禁、
484 个 Java 源文件空行和 53 对脚本契约通过。commerce 仍只有本地正式实现，未 CUTOVER、未拆 fulfillment，
PostgreSQL 容器为 0。

M3.6 第六批完成一次性 CUTOVER 评审，结论为不通过。独立服务默认流量门仍关闭且未配置 PostgreSQL，但尚无
远程 Order V1/Inventory 命令客户端、支付 Outbox 投递器和退款发起入口；支付/退款回调仍指向 commerce，
commerce 本地实现与源表权限仍是唯一正式路径。新增 Windows 与 WSL/Linux 对等审计，成功结果表示这些安全门禁
和阻塞基线未漂移，最终结论固定为 `REJECTED`。本批未切流、未删除本地路径、未撤销支付源表权限、未拆
fulfillment，也未启动 PostgreSQL。第七批随后只在关闭流量门下补齐跨边界副作用与退款恢复闭环。

M3.6 第七批在独立服务补齐远程 Order V1、Inventory COMMIT/REFUND、可靠 payment Outbox 投递器、退款发起和
退款渠道命令闭环。Flyway V3 在真实 payment-db 增加 Outbox 失败证据；稳定命令 ID、条件状态推进、租约、退避
重试和退款持久 Inbox/Outbox 防止重复副作用。正式副作用受流量门与副作用门共同保护，且影子复制开启时强制
拒绝；默认仍为流量关、副作用关、影子开。Windows 与 Ubuntu 26.04 WSL 缺失订单演练均得到可恢复 RETRY、
库存零写入，随后精确清理并恢复默认门禁。commerce 本地路径、渠道回调和源表权限未切换，CUTOVER 审计继续
`REJECTED`。payment 服务 6 项 Java 25 测试、468/30/220/8 质量门禁、493 个 Java 源文件空行、54 对脚本契约
和完整快速回归通过；未维护 LOCAL/COMPARE、未拆 fulfillment、未启动 PostgreSQL。

M3.6 第八批把客户退款 POST 收口到 `RefundCreateCommandV1 -> PaymentOrchestrationV1`，但
`LocalPaymentOrchestrationAdapter` 仍是唯一 Bean，继续复用原退款事务，因此没有远程切流或双实现。commerce
56 项测试和真实全额退款、重放、冲突、签名拒绝专项通过。评审更正 `RefundController` 为切流后应保留的身份薄
入口，把本地 `RefundService/RefundMapper` 明确为删除对象；一次性运行手册冻结停写、最终追平、唯一远程适配器、
回调切换、源表撤权和开门顺序。由于本地实现、旧回调及源权限仍在，CUTOVER 继续 `REJECTED`。

本批最终完整快速回归发现支付成功与库存预留结果并发到达时，两次远程条件更新之间可能发生状态漂移。现已用
Order V1 原子 `START_INVENTORY_COMMIT` 同时接受 `RESERVED/WAITING_RESERVATION`，并允许同一成功回调重放修复
`PAID` 但库存尚未提交的部分结果。两笔历史测试样本均恢复为 `PAID/COMMITTED`；order 2 项、commerce 56 项、
payment 6 项 Java 25 测试，以及 469/30/220/8 质量门禁、495 个 Java 源文件空行、54 对脚本契约和完整快速回归通过。

M3.6 第九批新增默认关闭的 `PAYMENT_WRITE_FROZEN` 和稳定 `PaymentWriteAdmissionV1`，在创建支付、订单取消进入
`CANCELLING`、发起退款之前统一拒绝新资金写，签名回调继续排空。Windows 真实演练确认三类写均返回 503、拒绝
不改变业务事实、已有支付仍由回调收敛到 `PAID/SUCCESS`，退出后恢复 false。Ubuntu 26.04 WSL 补齐 jq 并修复
Windows Docker CLI 的 Compose 路径/环境透传后完成同等运行态验收。commerce 58 项测试、55 对脚本契约通过。

M3.6 第十批完成最终 GO/NO-GO：generation 15、水位 603、14 表 MATCH，OPEN 孤儿、待投递 Payment/Command
Outbox、未完成 Process Manager 均为 0；权限冻结恢复和目标停机恢复通过。由于本地实现、旧回调、源权限和唯一
远程适配器原子替换仍待同一窗口完成，结论为 NO-GO，CUTOVER 审计继续 `REJECTED`。

M3.6 第十一批在明确授权的受控窗口完成一次性 CUTOVER。商城停止后完成 generation 16、水位 610 的最终快照，
14 表全部 MATCH，OPEN 孤儿、Payment/Command Outbox 和 Process Manager 未决项均为 0。commerce 现在只有
`RemotePaymentOrchestrationAdapter`，本地支付/退款 Service、Mapper/XML、渠道 Gateway、旧回调和对账路径已删除；
支付平台回调只指向 payment-orchestration-service，影子默认关闭且流量/副作用默认开启，`commerce_runtime` 对
14 张旧源表授权为 0。受控订单 486 的支付、退款及两类幂等重放通过，最终为
`REFUNDED/SUCCEEDED/REFUNDED`，独立库未决项归零，旧源库没有该订单支付记录。未拆 fulfillment，未启动 PostgreSQL。

M3.6 第十二批补齐切流后运行观察。payment-orchestration-service 新增支付/命令 Outbox、Process Manager、人工恢复、
OPEN 孤儿、处理中退款、指标刷新和 `true/true/false` 切流模式共 10 个 Prometheus 指标；监控增加独立采集目标，
14 条告警规则通过 promtool。Windows 订单 517/520、WSL 订单 527/530 在 payment 服务、payment-db 和支付模拟器
重启前后均完成支付/退款，待支付取消同时完成 Process Manager 与 Command Outbox。两端最终源水位仍为 610，
14 张源表授权、Payment/Command Outbox、未完成 Process Manager 和 OPEN 孤儿均为 0。下一步只评审 fulfillment
CREATE 准入；本批未创建 fulfillment-service，未启动 PostgreSQL。

M3.7 第一批决定让履约拥有执行流程与恢复记录。订单、资金和物流投影继续由原边界负责。移除履约对物流 Mapper
和身份领域对象的直接依赖，完成契约草案。Windows 与 WSL 只读审计记录 3 张物流表、103 条运单、0 条未绑定运单、
0 条跨边界外键与 0 条通知断链。

M3.7 第二批在 commerce 内实现发货编排、发货与退款原子互斥、发货人工恢复与改运单版本保护。商城 V42 将
接单和改运单也纳入持久化执行，受理后可查询原命令，后台从渠道或订单检查点继续。真实 MySQL 样本 601
在渠道先提交、商城后重启的场景使用原命令完成，三处运单版本一致，订单接单和改运单事件各 1 条。
商城 48 项测试、Angular 构建、MySQL 空库与旧库升级、Windows 快速回归以及 Windows/WSL 审计通过。

商城 V43 补齐接单和改运单的管理员恢复与审计。订单 609 模拟 Order V1 已提交、商城尚未确认，两路认领只有
一路成功，接单租约过期后自动完成，改运单经管理员恢复完成，两个 Inbox 成功结果与运单版本保持一致。接单、
改运单事件及恢复审计各 1 条。商城 49 项测试、MySQL 空库与旧库升级、商家履约专项和 Windows/WSL 准入审计通过。

V43 后完成 CREATE 复核，结论仍为 `REJECTED`。MySQL 样本 611 复现接单、发货或改运单已在下游提交、商城任务
仍待核对时，新履约操作可进入。Windows/WSL 复测记录 33 条任务，未决任务、到期租约、重叠订单与发货意图
均为 0。已修订 Fulfillment V1 草案。

V44 在商城内落地同订单事务准入和 Fulfillment V1 提交、结果查询。真实 MySQL 样本 612 的三个跨操作场景均受
待核对任务阻塞，管理员恢复原命令后继续。商城 51 项测试、V44 空库安装及旧库升级验证通过。独立履约服务仍处于
CREATE 复核阶段。

已完成 V44 拆分前评审。MySQL 一致性快照中的六张履约表在隔离库还原两次，行数、摘要、表定义和自增游标一致，
17 组订单与商家引用均匹配。样本 614 证实渠道重放返回当前资源或版本冲突。下一步在商城内落实物流命令回执端口，
并为无副作用的已知拒绝保留历史、允许重新准入。当时结论为 `REJECTED`。

V45 已通过独立履约服务 CREATE 准入，范围为关闭业务流量的影子运行。订单 615 验证发货已知拒绝后只允许一个
新命令执行，已有同参数发货意图仍被 Order V1 拒绝。运单升到版本 2 后，原改运单命令仍能恢复版本 1 的回执，
订单事件无重复。商城 53 项测试、订单 2 项测试、MySQL 空库与旧库升级、六表两次还原及 Windows 快速回归通过。
Windows/WSL 最终审计记录 59 条任务、9 条物流回执，未决任务、待完成回执、回执不一致及未清发货意图均为 0。

V46 已启动独立 `fulfillment-service` 和 MySQL `fulfillment-db` 影子环境，商城继续承担履约业务。真实 MySQL
验收通过重复快照、回滚、增量写入与删除、孤儿隔离、重启续跑和签名读写门禁。最终源与目标水位均为 52，六表
摘要匹配，67 条任务的命令登记一致，待隔离记录为 0。新服务 2 项、商城 53 项测试，真实 MySQL 空库与旧库升级、
完整 Windows 快速回归及 58 对 Windows/WSL 脚本契约通过。

V47 增加商城履约新命令冻结，完成影子进程断线、源只读授权恢复、商城冻结排空和撤回演练。六次冻结写请求
均为 503，已受理改运单完成，解除冻结后三个原命令 ID 各成功受理一次。目标写入提交失败时，行和水位原子回滚。
最终源目标水位 153，六表摘要一致，隔离 0，冻结已解除。商城 54 项测试、V47 迁移与完整快速回归通过。
当时切流准入为 `REJECTED`。

履约库 V2 增加数据库模式绑定。独立服务具备默认关闭的签名写执行，订单侧增加受限履约身份，商城侧增加默认关闭
的签名物流桥。隔离真实 Order V1 提交后超时、物流夹具回执保存后断连、六表转移与回退均通过，最终运单版本 2。
默认商城继续承接业务，影子库为 `SHADOW`，源目标水位均为 201，隔离为 0。商城 55 项、订单 2 项、履约 2 项
测试及完整 Windows 快速回归通过。切流准入仍为 `REJECTED`，下一批验证真实物流桥和独立人工恢复。

独立履约已实现默认关闭的管理员签名查询和恢复。隔离订单 101 使用真实商城物流桥和物流模拟器，回执提交后
模拟断连，确认原命令进入人工核对。签名恢复与审计在同一事务提交，原命令完成后没有重复建单。隔离源冻结水位
为 5，六表逐行一致，旧写授权降为 0 后仍返回冻结错误。回退后原商家路由沿用旧回执，运单推进到版本 2，
管理员审计保留。默认部署仍由商城承接商家履约。下一批处理商家和管理员远程路由与现有影子库所有权转换，
切流准入仍为 `REJECTED`。

M3.7 已接通商城商家与管理员的签名远程路由。隔离演练在 generation 1、水位 5 原地转换影子库，撤销旧六表
写权限后验证历史命令、兼容接单发货和改运单、管理员恢复及停机 503。回传六表后恢复原商城路由，运单推进到
版本 2，管理员审计保留。默认环境保持 `SHADOW`，源目标水位 297，隔离与未决任务为 0。后续需将六表权限
交接和回退配置固化到部署脚本并通过重启验收。

V48 与独立执行覆盖配置已完成权限交接的重启验收。两套隔离 MySQL 和应用重启、授权任务重跑后，旧账号对
六表的 18 次增删改均被拒绝。回退恢复原商城权限，退役目标拒绝旧执行配置重启。默认环境仍为
`COMMERCE`/`SHADOW`，源目标水位 345、隔离与未决任务为 0。正式履约切流待明确授权和窗口内最终核对。

2026-11-18 履约正式切流并通过端到端验收。最终交接点为 generation 41、水位 393，六表一致。独立履约库
为 `EXECUTION`，商城仅代理履约请求，源六表授权为 0，源水位仍为 393。验收订单 675 的运单版本为 2，
历史命令和管理员恢复重放通过。首次验收脚本列表超时后的回退记录见演进计划第 17 节。

独立履约已纳入 Prometheus 指标与告警。订单 676 的发货任务在 `PENDING/RESERVE` 停机后沿用原命令完成，
发货和改运单各产生一次事件。商城源水位保持 393，执行与路由指标均为 1，待处理与人工核对均为 0。

2026-11-18 初次复核时，M3 六个服务已切至独立库。当时库存迁移为 `READY`，发现 35 条库存投递待对账、
1 个缺少投递记录的等待预占订单、4 条支付对账 OPEN，以及目录、通知及已停用影子表的遗留权限。
收尾优先顺序与验收标准见 [M3 单一所有权复核](../architecture/M3-SINGLE-OWNERSHIP-REVIEW.md)。

库存旧迁移入口已通过隔离空库导入、已切流和非空库拒绝、并发新增保护及指纹不符回滚验证。现有库存库登记
`CUTOVER_COMPLETE`（generation 25），五表 3012 行及商城库存投递 Outbox 的登记前后摘要一致。

2026-11-19 复核结果为库存历史 35 条中 30 条业务结案、5 条测试数据归档，订单 485 已取消，支付库 4 条历史对账已结案。旧账号撤权和迁移工具隔离均已验收。日常环境本次升级保留 9 个数据库原容器与卷，历史订单、库存流水、支付记录和运单摘要一致。[日常库存验收](../architecture/INVENTORY-RECONCILIATION-REVIEW.md#2026-11-19-日常环境库存门禁验收)

## M2 代码质量治理记录

### 1. 定位与边界

本计划是 M1 之后的工程质量待办，不新增商城业务能力，也不启动微服务拆分或 PostgreSQL 迁移。
微服务和数据库演进继续分别维护在
[微服务演进计划](../architecture/README.md#6-微服务演进准入) 与
[数据库演进计划](../architecture/DATABASE-EVOLUTION-PLAN.md)，不得在本计划中标记为已实现。

事实优先级仍为当前源码、迁移、脚本和实际验证结果高于文档。数据访问重构不得改变锁顺序、
事务边界、幂等键、条件更新或 Outbox/任务租约语义。

### 2. 2026-11-13 只读审计基线

| 模块 | Java 文件 | Mapper XML | 迁移文件 | 通配符导入 | 超过 120 字符的行 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `commerce-service` | 162 | 16 | 38 | 36 | 129 |
| `payment-simulator-service` | 65 | 4 | 5 | 11 | 32 |
| `logistics-simulator-service` | 15 | 1 | 4 | 1 | 4 |

审计结论：

- 12 个 MyBatis-Plus 持久化实体已有类和字段 JavaDoc，仍需持续检查注释与真实列语义一致。
- 未发现 Controller、Service、Job 或 Dispatcher 内联业务 SQL；物流复杂 SQL已集中到
  `LogisticsDataMapper.xml`，商城和支付复杂 SQL 也已放在 Mapper XML。
- `SchedulerConfiguration` 中的 `JdbcTemplate` 只用于 ShedLock 官方适配器配置，不属于业务 SQL。
- 未发现多个字段或方法压缩在同一行的现存 Java 声明。
- 基线时尚未配置自动格式检查；ArchUnit 模块依赖边界测试已在 M2.3 首批治理中建立。
- `ChannelRefund` 错把外部退款编号标为 `@TableId(AUTO)`，与数据库 `id BIGINT AUTO_INCREMENT`
  不一致；该项作为第一批功能性修复处理。

### 3. 分批实施顺序

#### M2.1 支付退款映射与格式基线

- [x] 修正 `ChannelRefund.id` 的 MyBatis-Plus 自增主键映射并增加防回归测试。
- [x] 整理支付模块通配符导入、过长构造器和 Wrapper 链，不改变退款事务和通知补偿语义。
- [x] 使用真实 MySQL 重新执行模拟支付测试和退款专项。

首批验证证据：支付服务 9 项 Maven 测试通过；Windows 与容器化 Bash 真实退款专项均完成
签名支付、全额退款、账本核对、重复请求幂等、重复退款 `409`、伪造回调 `401` 和通知重投幂等。
验收过程中修正了 `verify-refunds` 向下游支付通知脚本遗漏支付平台 URL 的双平台参数透传。
正式 Dockerfile
首次重建受 Docker Hub 获取 `eclipse-temurin:25-jre-alpine` 元数据时连续 `EOF` 阻断；本轮使用
已缓存的同版本 Maven/JDK 25 Alpine 镜像构建测试运行镜像，不修改仓库 Dockerfile。

格式收口验收：支付模拟服务的通配符导入从审计基线 11 个降为 0，
超过 120 字符的 Java 行从 32 行降为 0。支付服务 9 项 Maven 测试再次通过，
Windows 与容器化 Bash 真实退款专项再次通过，相关容器重建后均为 `healthy`。
Windows `verify-quick -SkipBuild` 也已使用本次构建的支付服务镜像通过完整 L2 快速回归。

#### M2.2 物流模拟服务类型与格式治理

- [x] 清理剩余通配符导入和过长 Java 行。
- [x] 将运单、轨迹和 Outbox 的 `Map<String, Object>` 查询结果替换为含字段语义的持久化投影；行锁、条件更新、
  批量领取和 Outbox SQL 继续保留在 Mapper XML。
- [x] 运行物流回调、乱序和 Outbox 恢复专项，证明当前投影和幂等语义不变。

验收证据：物流服务构建通过，通配符导入与超过 120 字符的 Java 行均归零；
Windows 回调可靠性专项通过 `RETRY -> PUBLISHED` 和丢失回调对账恢复，回调治理专项通过
`DEAD -> 人工重投 -> PUBLISHED`、RBAC、审计、幂等和指标验证。固定种子 `20261113`
的 10 秒乱序并发产生 322 个回调，最终状态为 `DELIVERED`、有界字段更新计数为 1、
不可变身份字段保持不变。容器化 Bash 基础物流链路也通过签名创建、重放 `409`、
伪造回调 `401`、异常恢复、`CREATED -> DELIVERED` 和商城投影验证。

#### M2.3 商城服务分包治理

- [x] 分业务包清理通配符导入和过长行，不做全仓一次性机械重写；`identity`、`catalog`、`cart`、
  `customer`、`review`、`receipt`、`fulfillment`、`ordering`、`payment`、`refund`、`logistics`、`scheduling` 已完成。
- 简单单表 CRUD 继续使用 `BaseMapper` 和 Lambda Wrapper；复杂 SQL 保留在 Mapper XML。
- [x] 建立 ArchUnit 测试，阻止 API 访问基础设施、领域层反向依赖外层，并约束
  `@RestController` 与 Mapper 接口的包位置。

首批治理移除了 `UserController -> AppUserMapper` 的越层访问，查询改由
`UserQueryService` 使用 Lambda Wrapper 完成；`identity` 与 `shared` 包的通配符导入和
超过 120 字符的 Java 行均为 0。商城服务 24 项 Maven 测试通过，其中包含 4 条 ArchUnit
门禁；使用重建后的镜像执行 Windows L2 快速回归和容器化 Bash 权限回归均通过，现有数据卷未删除。
其余商城业务包的格式治理仍按包分批进行，不能据此标记为全服务完成。

第二批治理将 `catalog` 与 `cart` 的通配符导入和超过 120 字符的 Java 行均清零。
商品翻译单表查询由手写 `SELECT *` 改为 `BaseMapper + LambdaQueryWrapper`，复合业务键字段补齐语义注释；
购物车 upsert、联表投影和按用户归属条件更新仍保留在 Mapper XML。审计同时发现旧订单投影遗漏
`product_id`，修复后补充接口级回归测试，并将测试 Schema 与 V1 真实迁移对齐。商城服务 25 项测试、
Windows L2 快速回归、容器化 Bash i18n 与权限回归均通过。Bash i18n 脚本还修复了
`pipefail` 下 `curl | grep -q` 提前断管导致的页面可用性误判。最终镜像还在现有 MySQL 卷完成
旧订单创建与数量更新，响应保留正确的 `productId`。本批未删除或重建数据卷。

第三批治理将 `customer`、`review` 与 `receipt` 的通配符导入和超过 120 字符的 Java 行均清零。
收货地址单表查询改用 Lambda Wrapper，并通过 `@TableField("is_default")` 显式映射默认地址字段；
评价写入改用带完整字段注释的 `ProductReview` 实体和 `BaseMapper`。评价联表查询、聚合统计以及地址默认项
批量切换等复杂 SQL 继续保留在 Mapper XML。商城服务 25 项测试、Windows 快速回归和容器化 Bash
收货/评价专项均通过；快速回归还覆盖服务重启后的租约恢复和 Redis 故障降级。脚本契约审计确认
37 对 Windows/Bash 验证脚本参数有序对应，默认路径不会删除命名卷。本批未删除或重建数据卷。

第四批治理将 `fulfillment` 的 5 个通配符导入和 7 条超过 120 字符的 Java 行均清零。
商家订单归属联表、订单行锁以及确认、发货和物流信息更正的条件更新仍保留在 Mapper XML，
原有事务分段、远程物流调用顺序和状态条件未改变。商城服务正式 Dockerfile 构建及 25 项测试通过，
Windows/Bash 履约专项均通过支付回调、商家确认、发货、物流信息更正、时间线和客户越权 `403`；
Windows 快速回归继续通过取消竞态、退款、物流、收货/完成/评价、服务重启租约恢复和 Redis 降级。
37 对脚本契约审计再次通过，现有数据卷未删除或重建。商城服务剩余 19 个通配符导入和
104 条超过 120 字符的 Java 行，M2.3 仍需按包继续治理。

第五批治理将 `ordering` 的 4 个通配符导入、19 条超过 120 字符的 Java 行以及
`ShopOrderMapper.xml` 的 23 条超长 SQL 行均清零。订单、商品明细、事件和收货地址四个数据库投影
补齐类与字段业务语义注释；购物车行锁、订单/支付幂等锁、条件状态迁移、事件写入和支付通知写入
继续保留在 Mapper XML，原有锁顺序和事务边界未改变。商城服务正式 Dockerfile 构建及 25 项测试通过；
Windows 快速回归覆盖取消竞态、退款、履约、物流、收货/完成/评价、服务重启租约恢复和 Redis 降级，
Bash 订单取消、支付/取消竞态与商家履约专项也通过。37 对脚本契约审计再次通过，现有数据卷未删除
或重建。商城服务剩余 15 个通配符导入和 85 条超过 120 字符的 Java 行，M2.3 仍未完成。

第六批治理将 `payment` 的 9 个通配符导入和 12 条超过 120 字符的 Java 行均清零。
本批仅整理导入、参数与表达式换行，未改动支付 SQL、事务边界、锁顺序、幂等或签名语义。
商城服务正式 Dockerfile 构建及 25 项测试通过；Windows 快速回归覆盖取消竞态、退款、履约、
物流、收货/完成/评价、服务重启租约恢复和 Redis 降级；容器化 Bash 支付安全链路通过 HMAC/RSA、
签名负例、MFA、一次性提权令牌和审计链验证。37 对脚本契约审计再次通过，未删除或重建数据卷。
商城服务剩余 6 个通配符导入和 73 条超过 120 字符的 Java 行，下一批从 `refund` 继续。

第七批治理将 `refund` 的 4 个通配符导入、27 条超过 120 字符的 Java 行和
`RefundMapper.xml` 的 11 条超长 SQL 行均清零。`RefundView` 与 `RefundEventView` 补齐类和字段业务
语义注释；成功支付行锁、退款幂等查询、状态条件更新、金额上限保护和通知去重 SQL 继续保留在
Mapper XML，未改动事务、锁顺序、幂等、库存回补或通知语义。商城服务正式 Dockerfile 构建及 25 项测试通过；
Windows 与容器化 Bash 退款专项均通过签名支付、全额退款、双端账本一致、幂等重放、重复退款 `409`、
伪造回调 `401` 和通知重投；Windows 快速回归与 37 对脚本契约审计同时通过。本批未删除或重建数据卷。
商城服务当前剩余 2 个通配符导入和 47 条超过 120 字符的 Java 行，下一批从 `logistics` 继续。

第八批治理将 `logistics` 的 2 个通配符导入、38 条超过 120 字符的 Java 行和
`ShipmentMapper.xml` 的 8 条超长 SQL 行均清零。`ShipmentRecordView` 与 `ShipmentEventView` 补齐类和字段业务
语义注释；运单 upsert、回调行锁、事件幂等、状态投影和对账候选 SQL 继续保留在 Mapper XML，未改动
签名、事务、乱序保护、送达时间或自动收货任务语义。商城服务正式 Dockerfile 构建及 25 项测试通过；
Windows 物流主链路、回调丢失恢复与 `DEAD -> 管理员重放 -> PUBLISHED` 治理专项通过，容器化 Bash 主链路通过
签名创建、重放 `409`、伪造签名 `401`、异常恢复和完整轨迹投影验证。Windows 快速回归与 37 对脚本契约审计
同时通过，未删除或重建数据卷。商城服务通配符导入已全部清零，剩余 9 条 Java 超长行均位于 `scheduling`；
下一批完成 `scheduling` 后再判定 M2.3 整体门禁。

第九批治理将 `scheduling` 的 9 条超过 120 字符的 Java 行和
`ScheduledTaskMapper.xml` 的 1 条超长 SQL 行清零，并拆分了多计数器单行声明。过期租约查询仍保留在
Mapper XML，未改动任务领取、租约、重试、对账或 MQ 消费语义。商城服务正式 Dockerfile 构建及
25 项测试通过；Windows 快速回归覆盖数据库调度、服务重启与过期租约恢复，Windows 和容器化 Bash
MQ 幂等专项均通过 RabbitMQ/RocketMQ 重复与迟到消息单次执行及毒消息 DLQ 验证；37 对脚本契约
审计通过，未删除或重建数据卷。商城业务源码通配符导入和超过 120 字符的 Java 行均为 0，
M2.3 整体门禁完成；下一阶段为 M2.4 数据库注释与自动门禁。

#### M2.4 数据库注释与自动门禁

- [x] 通过新增 Flyway 迁移补充关键表和列注释，不修改已经执行的历史迁移。
- [x] 为三个后端服务接入与 IntelliJ IDEA 规则兼容的只检查门禁，不自动改写源码。
- [x] 将后端格式、Mapper 契约、模块边界和相关测试加入快速/完整验证矩阵。

第一批新增商城 V24、支付模拟器 V4 和物流模拟器 V5，为 7/5/4 张核心业务表与
67/39/27 个核心标识、状态、金额和时间列补充业务语义注释。本批只修改数据字典元数据，
未改表结构、约束、默认值或业务数据。三服务正式镜像构建通过，商城 25 项和支付 9 项测试通过；
Windows 与容器化 Bash 隔离演练均通过空库 V1→V24 及旧库 V16→V24，Windows 独立临时卷还完成
V24/V4/V5 三库空卷到评价的完整业务链路。验收后仅删除明确命名的临时卷，现有数据卷保留并升级成功。
Windows 快速回归与 37 对 Windows/Bash 脚本契约审计也已通过。第一批完成后进入只检查的
自动格式与 Mapper 契约门禁建设。

第二批新增 Windows/Bash `audit-code-quality`，全量检查 244 个主源码 Java 文件、20 个 Mapper XML、
189 个映射语句和 3 个 Maven POM。门禁覆盖文件末尾换行、制表符/行尾空格、Java/Mapper XML
120 字符、通配符导入、单行多成员、MyBatis 内联 SQL、直接 Spring JDBC、Mapper namespace、
重复 statement id 和接口方法对应关系；只报告问题，不自动格式化文件。整理 3 个 Mapper XML 的
8 条历史超长 SQL 行后，Windows 与容器化 Bash 门禁均通过。三个服务正式镜像构建通过，商城
25 项（含 4 条 ArchUnit）和支付 9 项测试通过；Windows `verify-quick -SkipBuild` 完整通过且
始终执行新质量门禁；支付安全专项也通过 HMAC/RSA、签名负例、MFA、一次性提权、角色/商户授权
和审计链验证。现有数据卷未删除或重建。

M2.4 后端门禁完成。

#### M2.5 前端格式门禁

##### M2.5.1 Angular 商城门户（已完成）

- [x] 分模块治理 Angular 商城门户的 TypeScript、HTML、CSS 和配置文件。
- [x] 使用项目本地 Prettier 3.10.1 提供 `format:check` 只读检查和显式 `format:write` 写入入口。
- [x] Angular 正式 Dockerfile 在生产构建前执行格式检查。

Angular 批次纳入 127 个源码/配置文件（包含新增的两个 Prettier 配置文件），已通过
`format:check`、29 项 ChromeHeadless 单元测试、本地生产构建和正式 Docker 镜像构建。重建且仅重建
Angular 容器后，`/healthz`、中英文与三角色权限专项通过，未删除或重建数据卷。
`verify-quick`/`verify-all` 默认构建会通过 Angular Dockerfile 执行该门禁；显式跳过构建时不会重新检查。
支付模拟器 Web 由下一独立批次治理。

##### M2.5.2 支付模拟器 Web（已完成）

- [x] 以 `simulators/payment-platform/web/` 当前源码、`package-lock.json`、Dockerfile 和现有测试为基线。
- [x] 接入模块本地 Prettier 及只读/写入分离的 npm 命令，不顺带重做 UI 或支付流程。
- [x] 格式化后通过 Vitest、TypeScript/Vite 生产构建和核心差异审查。
- [x] 将只读门禁接入正式 Dockerfile，验证镜像、`14201/healthz` 和支付 Web 运行态。
- [x] 同步状态文档、独立提交并推送，不删除或重建数据卷。

本批基线包含 24 个 Vue/TypeScript/CSS/JSON/HTML 文件，无制表符或超过 200 字符的压缩行，
共有 20 条超过 120 字符的行。固定 Prettier 3.10.1 后只对本模块执行机械格式化；依赖锁仅增加
Prettier 包，支付签名、收银台和管理视图没有业务重构。`format:check`、Vitest（当前无测试文件，
按既有 `--passWithNoTests` 契约通过）、TypeScript/Vite 生产构建和正式 Docker 镜像构建均通过；
镜像构建已强制格式检查，`14201/healthz`、Vue 入口和正式服务名运行态验证通过。未删除或重建数据卷。

M2.5 前端格式门禁完成；总验收清单见 [TODO.md 第 13.3 节](#133-m2-工程质量总验收与基线冻结)。

#### M2.6 工程质量总验收与冻结（已完成）

- [x] 三个 Java 服务的格式、内联 SQL/JDBC 边界、Mapper 契约和商城 ArchUnit 模块边界门禁通过。
- [x] Angular 与支付模拟器 Web 的项目本地 Prettier、生产构建和正式镜像门禁通过。
- [x] Windows 与容器化 Bash 的质量审计、37 对脚本契约、快速/完整验收通过。
- [x] Compose 默认/全 Profile、五个正式镜像、运行态服务名和主要健康端点通过复验。
- [x] 文档、最终提交、远端 `main` 与注释标签 `m2-quality-baseline-2026-11-14` 完成收口。

最终验收保持现有数据卷，连续运行所需库存通过商户 API 准备。支付 Web 使用 Docker DNS 动态解析
支付后端；本地单节点 Alertmanager 关闭集群监听并使用 tmpfs 通知日志，Watchdog 具有稳定分组标签，
监控栈全新重建后的 Windows/Bash webhook 投递均通过。容器化 Bash 是因为当前主机没有可用的原生
Bash/WSL，使用 Docker CLI 容器、host 网络和 Docker Desktop 可见路径执行同一原生脚本。
M2 结论不包含微服务拆分、PostgreSQL 迁移、多节点高可用、多日容量或生产通知验收。

M2 标签冻结后的 M3.1 首批治理扩展了当前门禁，但不改写历史标签结论：物流模拟器根包只允许保留
启动类，业务代码必须存在 `api/application/domain/infrastructure` 四层；三个服务全部 256 个 Java
主源码/测试源要求每个 `class` 声明后保留空行。当前双平台门禁覆盖 248 个主源码 Java、20 个 Mapper XML、
189 个映射语句和 3 个 POM，并已通过物流正式镜像打包、主链路和 Outbox 管理专项。

M3.1 第二批新增 2 项商城 Maven 架构基线测试：37 张 Flyway 表必须声明当前责任模块；40 组既有跨模块
依赖允许减少，但不允许未经评审新增方向、增加 117 个 import 基线或增加其中 21 个跨模块基础设施引用。
新增后商城 27 项测试和正式镜像构建通过，质量门禁的类声明空行范围同步扩大到 257 个 Java 源文件。
该基线记录债务而不豁免债务，完整拆分阻塞项见
[M3.1 微服务拆分前置条件审计](../architecture/M3-PRE-SPLIT-AUDIT.md)。

notification 所有权治理经评审将基线更新为 38 张表、41 组方向和 119 个 import：新增的
`inventory -> notification` 仅依赖 V1 应用命令，跨模块基础设施引用仍为 21。InventoryMapper 删除通知表
写入，notification 模块以 V25 自有 Inbox 和 Mapper 在同一事务中幂等处理。商城 28 项测试、正式镜像、
Windows/Bash 取消通知链路与双平台门禁通过；Java/Mapper/语句计数更新为 250/20/190，全部 Java 源文件为 259。

notification 传输治理新增 V26 自有 Outbox、进程内/Kafka 适配器、发布 RETRY/DEAD/重放、消费 DLT、
Broker 恢复、W3C traceparent 传播和单体 FALLBACK 门禁。notification application 不再传递 `AppUser`，
使跨模块基线降为 41 组方向、118 个 import、38 个涉及文件，基础设施引用仍为 21；商城表增至 39 张。
商城 29 项测试及正式镜像通过，双平台质量计数更新为 258 个主源码 Java、20 个 Mapper XML、197 个映射语句、
3 个 POM 和全部 267 个 Java 源文件。

notification 投影核对治理新增 V27 窗口、影子投影与审计，以及独立 Schema V1 隔离演练。商城表增至
42 张，跨模块基线保持 41 组方向、118 个 import、38 个文件和 21 个基础设施引用；商城 30 项测试及
正式镜像通过。双平台门禁当前覆盖 265 个主源码 Java、20 个 Mapper XML、212 个映射语句、3 个 POM 和
全部 274 个 Java 源文件；38 对 Windows/Bash 脚本契约一致。实际运行 Schema 未切换，微服务与 PostgreSQL
均未启动。

M3.1 最终门禁新增 notification 独立 DataSource/Flyway/MyBatis/事务配置、回填/核对应用服务、Kafka
独立消费者、运行时管理 API 和 Windows/Bash 可逆切换脚本。Mapper 质量门禁改为递归覆盖分层子目录，
仅允许独立持久化配置类创建事务管理器，不放宽业务代码的 Spring JDBC/内联 SQL 禁令。当前双平台计数为
280 个主源码 Java、21 个 Mapper XML、235 条映射、3 个 POM 和全部 289 个 Java 源文件；商城 31 项测试、
正式镜像与 39 对脚本契约通过。M3.2 和 PostgreSQL 尚未启动。

### 4. 每批完成门禁

1. 修改前后 `git diff --check` 通过，格式化变更与业务语义变更可以清晰审查。
2. 相关 Maven 测试和 Docker 镜像构建通过。
3. 涉及数据访问时执行真实 MySQL 专项；涉及并发 SQL 时执行对应竞态或恢复脚本。
4. Windows/Bash 脚本契约保持一致，默认不删除现有数据卷。
5. 每批独立提交并推送，验证结果同步到本计划和 `PROJECT-STATUS.md`。

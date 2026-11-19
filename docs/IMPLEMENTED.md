# 已实现能力与验证边界

## 1. 文档规则

本文档是当前源码树的已实现摘要。“已实现”表示已有代码和数据契约；实机或跨平台尚未验收的内容必须单独标注。未实现内容一律放入 [TODO.md](TODO.md)。

## 2. 基础商城

- 用户、角色、Session 登录、CSRF、登录限流和 Remember-Me。
- 商品、购物车、收货地址、下单和订单地址/商品快照。
- 客户、商家和管理员的资源权限边界。
- Angular 运行时中英文切换。
- 客户订单中心使用“状态色轨 + 订单摘要 + 当前主操作 + 可展开详情”；大屏折叠态使用固定列对齐状态、金额和操作，商品数量与收件人收纳到订单基本信息。支付成功/已关闭由订单状态表达，摘要仅在支付处理中、失败或过期等需要关注时显示支付状态；展开态的商品图片提供目录地址修复与失败占位，支付详情采用单层信息布局，空订单历史不再渲染。
- 订单页已提供骨架屏、空状态、错误重试、时间线去噪和响应式布局；本轮生产镜像重建后已在 Edge 完成默认桌面视口与 390×844 窄屏实机验收。
- 全局导航在大屏使用与页面主体一致的 1480 px 居中容器，不再将品牌与账户工具贴到屏幕两端。

### 2.1 基础业务隔离复验

2026-11-19 从全新隔离卷启动，显式初始化演示商品、库存和支付渠道。独立支付库通过 `payment-demo-init` 建立三个演示渠道，
重复执行保持商户配置摘要。Portal API 验证登录、选商品、加购、下单、支付宝模拟支付、商家接单发货及运单更新。
订单 `2` 经物流签收后手动收货，库存为 `COMMITTED`。未支付和渠道待支付订单各一笔取消并释放库存。
全额退款验收等待异步退款、订单状态和库存回补完成，再核对退款重试、模拟平台台账和通知补发。
本次覆盖 Portal API 和模拟平台接口，页面验收见下节。日常环境继续运行。

### 2.2 基础业务页面走查

2026-11-19 在隔离环境用 Chromium 操作客户门户、支付宝模拟收银台和商家订单页。
商家确认、发货及运单更新收到 HTTP 202 时，页面沿操作状态地址等待终态，再读取正式订单。客户取消、退款同样在当前页面查询终态。
商家确认和发货、客户收货遇到冲突时可见错误提示，提交过程中禁止重复操作。

| 浏览器操作 | 数据核对 |
| --- | --- |
| 登录、选择收货地址、加购下单、收银台实付、商家确认发货并修改运单，客户查看签收后确认收货 | `ORD-7EA75E3DD7694486` 为 `RECEIVED/COMMITTED`，显示更新后的运单 `UI-5-B` |
| 客户提交取消，页面直接更新终态 | `ORD-0175C5A2407B431C` 为 `CANCELLED/RELEASED` |
| 客户在 390 px 窄屏提交已付款订单的全额退款，页面直接更新终态 | `ORD-6F1B3E3E65994009` 为 `REFUNDED/REFUNDED` |

物流签收由签名模拟器请求推进。浏览器注入一次 HTTP 409 分别校验确认、发货和收货提示，然后完成正常操作。订单页窄屏无横向溢出。
Angular 生产构建、Prettier 和差异检查通过。隔离容器已关闭，日常环境继续运行。

### 2.3 支付失败与重试

2026-11-19 日常 Docker 门户先更新页面走查修复，再更新支付重试前端，最终加载 `main-LSW44Y4Z.js`，
门户健康检查返回 200。其余 28 个日常容器 ID 保持原值。

- 页面收到明确失败时显示可重试提示，并为下次普通支付准备新幂等键。模拟响应丢失时保留原键并重用原支付单。
  支付签名结果成功但订单仍在更新时，页面等待订单终态。
- 隔离浏览器走查支付宝失败后重试，以及微信失败后切换银联。对应订单最终为 `PAID/COMMITTED`，
  每单有一条失败支付、一条成功支付和一条库存扣减流水。支付宝案例还模拟了服务端已建单但浏览器丢失响应。
- 商城和支付编排补齐 `UNION_PAY` 接口契约，52 项商城测试和 9 项支付编排测试通过。
  日常后端以 `67955d5` 基线单独构建并发布。对比运行 JAR 与发布 JAR，统一文本换行后，每个服务仅支付渠道枚举不同。
  商城库发布前后均为 V51，两个服务健康，其他 27 个日常容器 ID 一致。使用同一批发布镜像在隔离环境复验，
  订单 14 支付宝重试成功，订单 15 微信失败后转银联成功，两个订单的库存各扣减一次。日常未创建测试订单。

### 2.4 商品排序与订单查询

2026-11-19 使用连接隔离 MySQL 的 Docker 环境进行浏览器验收。

| 页面 | 验收结果 |
| --- | --- |
| 商品列表 | 6 件商品通过价格升序、价格降序和评分降序校验。排序后打开对应商品详情。顶部搜索保留排序，刷新保留组合条件，清除搜索恢复全部商品。 |
| 客户订单 | 15 笔订单中按订单号定位 1 笔。验证商品名搜索、匹配数、叠加不匹配状态后的空结果提示及清除后恢复。 |
| 商家订单 | 15 笔订单中按运单号定位 1 笔。按收件人和已接单状态组合筛选，发货入口仍可用。390 像素宽视口可操作筛选且无横向溢出。 |

本节验收时，订单查询针对接口已返回给当前角色的订单，在浏览器内完成。生产构建和前端格式检查通过。
日常 Docker 仅重建门户，加载 `main-2D2UBN3Y.js`，健康检查和 JS 资源均返回 HTTP 200，其余 28 个日常容器 ID 保持原值。

### 2.5 基础业务第二批验收

2026-11-19 在隔离 Docker 环境使用真实浏览器、商城、库存、订单服务和各自的 MySQL 数据库验收。

| 功能 | 验收结果 |
| --- | --- |
| 结算前确认 | 浏览器切换收货地址并提交，应付金额 95.90 元及所选收货人写入订单快照。金额不一致和提交他人地址均返回 409，缺地址时页面禁用提交。 |
| 商家商品管理 | 修改资料，价格从 95.90 元调为 98.90 元，原订单的单价和金额仍为 95.90 元。旧版本写入返回 409，其他商家越权改价返回 404。上下架均验证顾客侧变化。 |
| 库存提示 | 商品详情显示可售数量和售罄，购物车标出下架或需减少的数量。预检发现库存不足时返回 409 并保留购物车。在 MySQL 事务中暂锁库存，验证预检通过后竞争导致预占拒绝，提交返回 409，购物车恢复原条目。 |
| 待处理订单 | 接单后待接单 5→4、待发货 2→3。发货后待发货 3→2，可按新运单号定位订单。 |
| 服务端查询 | 顾客 21 笔订单每页 10 笔，共 3 页。验证跨页订单号查找、状态和按浏览器时区的下单日期组合、空结果、收件人与运单号查找。其他用户和商家无法读取目标订单，角色不符返回 403。390 像素宽页面可操作且无横向溢出。 |

新增分页查询保留原列表接口。订单服务在 MySQL 中先应用数据权限和筛选条件，再分页。当前商品译名和历史订单商品快照均可用于搜索。库存服务继续负责最终预占，收到明确拒绝后恢复缺失的购物车条目。

商城 53 项、库存 7 项、订单 2 项和 Angular 32 项测试通过。前端生产构建、格式检查和 PowerShell 脚本契约审计通过。本节记录隔离镜像验收。

### 2.6 基础业务第二批日常发布

2026-11-19 基于已运行的 V51 商城代码打包本批功能，保留银联契约和原履约路径。兼容包通过 57 项商城测试。
使用相同四个发布镜像在独立 V51 MySQL 环境走通结算、支付、接单、发货和取消释放，并复测库存不足及并发预占拒绝。两种库存拒绝均返回 409，购物车保留或恢复。

- 日常环境依序更新库存、订单、商城和门户。四个容器均健康，门户 HTTP 健康检查返回 200，加载 `main-ZDR5Q6Q6.js`。其余 25 个运行容器 ID 相同，商城库保持 V51。
- 日常订单 697 使用支付宝模拟支付 89.90 元，已发货，运单号为 `BASIC-FIVE-697`。MySQL 核对预占、扣减、出库各一笔，数量均为 1。
- 接单后待接单 118→117，待发货 2→3，发货后待发货恢复为 2。浏览器可打开商家商品管理，按新运单号查询订单。
- 顾客可分页浏览 697 笔订单，并通过订单号查到历史订单 696，金额仍为 179.80 元。五笔历史归档仍为 `CLOSED`，尝试数各为 5，未产生库存流水。

### 2.7 购物车按选中商品结算

购物车默认全选，支持单独勾选，按选中商品更新件数和应付金额。提交包含购物车条目 ID 和确认金额。
商城在购物车锁内检查条目归属和存在性，对选中条目核价、预占和下单，只移除本次选中的条目。
旧客户端省略 `itemIds` 时沿用整车结算。空数组、重复条目或缺确认金额返回 400，越权或失效条目返回 409。

2026-11-19 隔离 Docker 浏览器和 MySQL 验收记录如下。

| 用例 | 结果 |
| --- | --- |
| 部分结算 | 两条购物车记录中仅选商品 2 的两件，未选商品 3 当时可售量为零。订单 22 金额 197.80 元，仅有商品 2 的两件明细和两件预占。库存流水对应一条预占，未选条目仍在购物车。 |
| 重复与并发提交 | 已消费条目再次提交返回 409。相同选择同时提交两次得到 200 和 409，只生成订单 23，且只预占一件。 |
| 并发售罄 | MySQL 行锁制造预检通过后的库存变化，订单 24 的预占被明确拒绝。购物车恢复选中条目，未选条目的内容保持原值，数据库无该订单的预占流水。 |
| 旧客户端 | 省略 `itemIds` 可提交购物车中剩余的两条商品记录，生成订单 25。 |

商城当前工作区 54 项、Angular 32 项测试通过。生产构建、前端格式、代码质量及 PowerShell 脚本契约检查通过。

日常发布包基于 V51 商城运行基线，58 项商城测试通过。用相同的商城、门户镜像在隔离 V51 再次验收部分结算、并发提交和缺货恢复。日常仅更新商城和门户，两个容器均健康，门户加载 `main-B456AZKX.js`，其余 27 个容器保持原值。

日常测试订单 698 只提交商品 1 的一件，金额 1299.00 元，商品 2 保留在购物车。重提返回 409。取消测试订单后，MySQL 核对商品 1 预占和释放各一次，随后清除本次测试添加的剩余条目。商城库保持 V51，五笔历史归档保持关闭。

### 2.8 历史订单再次购买

客户可从自己的已收货或已完成订单发起再次购买。商城读取历史商品 ID 和原购数量，再查当前商品及库存。
已在购物车中的同款合并数量并刷新售价。下架、售罄、无法按原数量加入及超过购物车数量上限的条目逐项提示。
V53 增加 `cart_repurchase_requests`，加购和结果回执在同一商城事务内提交。同一顾客使用相同请求重试时返回原结果。

2026-11-19 在隔离 Docker 和 MySQL 验收。

- 原订单 26 含六种商品。两种商品成功加入，四种分别因下架、售罄、库存不足和购物车数量上限被跳过。
- 模拟服务端已成功但浏览器丢失响应。页面重试复用同一请求 ID，两次并发重放也返回相同结果。MySQL 仅有一条回执，机械键盘从已有 1 件增至 3 件，单价更新为 103.90 元。
- 其他顾客操作返回 404，未达到可再次购买状态返回 409，复用请求 ID 操作另一张订单返回 409。
- 从购物车只勾选机械键盘结算，订单 29 金额为 311.70 元。MySQL 核对仅有该商品的 3 件订单明细和预占，购物车保留另外三条。历史订单仍为已完成，金额 100.00 元。

商城 55 项、Angular 32 项测试通过，前端生产构建、格式、代码质量和 PowerShell 脚本契约审计通过。
Flyway 验证空库、V16 存量升级和该隔离库 V52→V53。

#### 再次购买日常发布

- 以当前日常商城版本构建再次购买发布包，59 项商城测试通过。先用同一批发布镜像演练 V51→V53。旧商城在升级后的库上创建隔离订单 8，取消后库存释放。新商城和门户通过混合商品、响应丢失重试及勾选结算复验。
- 日常 Flyway 顺序应用 V52 和 V53。仅为商城运行账号新增再次购买回执表的 `SELECT`、`INSERT`、`UPDATE` 权限。商城和门户均健康，门户加载 `main-SKCPKLHM.js`，其余 27 个容器保持原值。 V52 取消恢复表已创建，本次发布沿用原取消路径，商城运行账号只验证了 V53 回执表的读写。
- 日常从历史订单 692 再次购买，购物车同款从 1 件增至 2 件，同一请求重复提交后仍是 2 件，MySQL 仅有一条回执。勾选结算生成订单 699，金额 2598.00 元。取消验收订单后，库存预占、释放各一笔，每笔 2 件。
- 五笔历史归档均为 `CLOSED`，尝试数各为 5，库存流水为零。

### 2.9 订单详情直达与通知跳转

顾客可从列表订单号进入 `/orders/:id`。页面直接请求现有单笔订单接口，刷新和登录后均可恢复详情地址。
从列表返回时恢复关键词、状态、日期和页码。无权查看、不存在或无效的订单地址显示统一的不可用提示。

通知通过订单 ID 打开详情，支付、退款、物流、收货和取消类型定位到对应区域。没有订单 ID 的通知可在面板内继续查看。

- 使用 23 笔隔离订单验证第三页直达、刷新和恢复四项查询条件。浏览器确认打开详情时请求单笔订单接口，并从详情完成再次购买。
- 使用隔离 MySQL 通知夹具验证五类定位和无订单通知。支付通知的已读接口模拟 503，仍可打开订单并定位到支付区。数据库中该通知保持未读，其余五条成功标记已读。
- 验证未登录打开订单链接后的登录恢复，以及其他顾客访问、无效订单 ID 和不存在订单的提示。

Angular 33 项测试、生产构建及格式检查通过。本批改动在隔离门户验收。

#### 订单详情与通知跳转日常发布

- 基于日常再次购买门户构建并仅更新 Angular 容器。健康端点和订单深链均返回 200，浏览器加载
  `main-ROJ6NRSD.js`，其余 28 个日常容器 ID 保持原值。
- 顾客从第 2 页打开订单 606，刷新和同会话新标签页均可查看详情。返回后恢复关键词、状态、日期和页码。
  未登录打开订单 692 的收货链接，登录后回到对应区域。该订单仍显示物流与再次购买入口。
- 日常商家访问订单 692 的接口返回 403，门户也拦截访问。不同顾客之间的订单所有权校验已在隔离验收通过。
- 点击日常订单 699 的取消通知，打开并定位到取消区。通知 API 和 MySQL 均显示由未读变为已读。
  五笔历史库存命令仍为 `CLOSED`，各尝试 5 次，对应库存流水为 0。

### 2.10 基础商城 P0–P2 验收

- P0 以 `9080e6f` 为源码基线，并记录日常镜像、前端资源和商城库 V53。从独立工作树构建。
  商品和当前售价由目录负责，订单保存成交时的商品、商家、数量和金额快照。付款、退款和库存按各自的权威账本核对。
  当前履约以整单为单位，结算一次只处理同一商家的选中商品。跨商家商品仍可选中各自结算。
- P1 隔离旧版本按 98.90 元下单，目录已改到 101.90 元（订单 99）。修复后旧金额返回 409，
  门户自动刷新购物车价格，顾客再次确认后生成订单 100，成交金额和订单明细均为 101.90 元。
  未选商品保留，重复提交和无效收货地址均被拒绝。独立角色与中英文回归通过。
- P2 隔离订单 110 主动取消并释放库存，111 支付宝整单退款，112 微信失败后切换银联并整单退款，
  113 微信支付后两件商品由同一商家发货，重复发货只有一张出库单、两条出库明细和一张运单。
  运单更正成功，顾客确认收货并评价，重复评价被拒绝。加速隔离任务后，订单 102 超时取消并释放库存，
  订单 113 自动进入完成状态。两条调度任务均为 `SUCCEEDED`。
- MySQL 核对了上述订单明细、资金账本、退款回补、出库及运单，成功付款重复计数为零。
  商城 59 项测试、Angular 33 项测试及生产构建通过。代码质量和 59 项脚本契约检查通过。
- 候选版本复验了商品价格升降序及评分排序、下架复上架、库存预检与并发预占拒绝、订单筛选分页及商家队列刷新。
  缺货拒绝后购物车恢复。再次购买隔离订单 117 按现价 103.90 元预占三件，同一个请求只有一条回执。
  隔离实测混合商家结算返回 409，两个购物车条目均保留。商家库存调整 100 → 101 → 100，其他商家操作返回 404。
- 订单 113 的重复签名支付回调返回 `ACKNOWLEDGED`，同一通知 ID 只入库一次，扣减流水仍为两条。
  Flyway 空库 V1–V53 和存量 V16→V53 均通过隔离脚本。

#### P0–P2 日常发布

- 日常基线从源码提交 `ded190e` 构建，镜像标签均为 `p0-p2-20261119`。先更新商城，再更新门户，
  两个容器健康，门户加载 `main-TKLHXCBZ.js`。日常容器内的 JAR 和前端资源 SHA-256 与候选构建一致，
  其余 27 个日常容器 ID 在发布阶段保持原值。
- 日常订单 700 主动取消，库存预占和释放各一件。订单 701 支付宝实付及退款均为 1299.00 元，
  库存按原商品回补一件。订单 702 以 1388.90 元微信支付，两条出库明细各一件，同一出库单和同一运单，
  顾客完成收货和评价。验收订单的成功扣款重复计数为零。
- 从日常门户点击订单 700 的取消通知，跳转至取消区。MySQL 核对已读及已读时间。
  商城库仍为 V53。五笔历史命令均为 `CLOSED`、各尝试 5 次，历史库存流水仍为 0。

### 2.11 P3 运行监控与订单取消恢复

- 使用日常候选镜像及 PowerShell 7 在隔离 MySQL、Kafka 和 Prometheus 逐项核对 SQL、指标和告警。
  商城库存命令积压、待人工对账与库存服务出库积压、发布失败分别触发和恢复。
  订单发货意图滞留及取消协议待恢复、目录与通知发送积压和死信、通知消费滞后和死信均通过触发及恢复复验。
  Promtool 规则用例通过，覆盖指标刷新失败的判断和恢复。
- 隔离 V53 使用仅有 V52 表 `SELECT`、`INSERT`、`UPDATE` 的商城运行账号。订单 101 已提交取消终态，
  商城两份 Outbox 提交前以 SIGKILL 中断。重启后恢复台账完成，库存释放和通知各一笔。
  两次重复取消保持原审计和各一笔副作用；历史已取消夹具 102 产生零条新效果。
- 日常 Docker 更新商城、订单、目录及通知镜像至 `p3-20261119`，四个容器健康。重新加载 39 条 Prometheus 规则，
  五条业务采集目标均为 `up`。目录与通知目标原有的不可抓取告警恢复。
  日常 SQL 与 Prometheus 的九项计数一致，通知消费 lag 和取消恢复未完成数均为零。
- 日常新订单 703（ORD-36064752E6F04A48）完成预占、取消、库存释放和通知展示。两次重复请求后，
  完成台账、取消效果、已确认释放命令及已发布通知仍各一笔，库存库与通知库逐笔匹配。
  通知事件为 `78d1b828-2a19-4ee8-af59-1123e9778136:notification`。
- 日常库仍为 V53，V52 表授予 `SELECT`、`INSERT`、`UPDATE`。五笔历史库存命令仍是 `CLOSED`、尝试数各为 5，
  历史库存流水为 0。其余 25 个日常容器 ID 保持原值。
  订单号、命令 ID、事件 ID 的定位方式见[告警配置](ALERTING.md#用订单定位投递与监控)。

### 2.12 P4 履约发布与旧源清理

- 固定 `p4-20261120` 候选镜像，隔离验证新发货、重复发货、旧运单、管理员恢复和带新增履约数据的回退。
- 发布到日常 Docker，归档恢复和依赖核对通过后清理商城旧履约源。清理后再次发货，运单、出库单及出库流水均只有一条。
- 原九库容器和五笔历史库存归档已复核。见[P4 验收记录](architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#29-2026-11-20-p4-日常发布和旧源清理)。

### 2.13 统一结算计价与最小运费模板

商城 V54 新增商家运费规则和结算回执。商家在商品管理中配置一份模板，按国家和省州匹配固定运费，
未命中时使用默认运费，未配置模板时运费为零。模板变更影响新结算。首版沿用单商家订单，优惠金额为零。

顾客勾选商品和地址后先试算，再用报价 ID 确认。报价绑定顾客、地址、已选条目、当前商品事实和运费。
金额或选中事实变化时返回 409 和新报价，顾客重新确认。同一报价的提交重试返回同一订单。
订单库 V6 保存商品小计、订单及行优惠、运费、应付和原有订单行快照。商品小计由数据库根据应付、优惠和运费计算并存储，
订单服务写入时用订单行复核。支付用下单快照，未发货整单退款按原实付并退回已收运费。

| 验收项 | 结果 |
| --- | --- |
| 存量与迁移 | 隔离 MySQL 复制九库。订单 V5 → V6 核对 705 张历史订单的总额与订单行小计一致，运费及优惠均为零。商城从空库、V16 存量升级至 V54 均通过。 |
| 报价与权限 | 修改运费、数量、商品当前售价或地址均使原报价失效，未生成订单。省州规则 8 元，未命中时默认 3 元。越权读取商家模板、无效地址和无报价直接提交均被拒绝。未勾选商品留在购物车。 |
| 资金与幂等 | 隔离新订单 706（ORD-35CE1F33CA660139200867F6974936A0）商品小计 2600.50 元、运费 8 元、应付 2608.50 元。订单库、支付编排和渠道账本一致，成功支付及全额退款各一次。重复确认仅一张订单、一笔预占命令和一条回执。历史订单 599 按原实付 1299 元整单退款。 |
| 浏览器与构建 | Chromium 验证顾客试算、取消勾选使报价失效、再次试算、确认下单和详情金额拆分，并在 412px 宽的商家页面验证默认运费保存和恢复。商城 55 项、订单 2 项 Maven 测试通过，Angular 生产构建和 63 项 PowerShell 7 脚本契约检查通过。 |
| 日常 Docker | 订单库 V6、商城库 V54。订单、商城和门户容器运行候选镜像 `checkout-candidate-20261120`，门户入口资源为 `main-J5X5FBQA.js`，Prometheus 9 个目标正常。原 705 张订单金额一致，九库容器 ID 前后一致。日常订单 706（ORD-B12A1255BF2522C7D68BD240694FD691）1299 元完成支付和整单退款，重复确认同一订单。订单 702 仍显示原金额 1388.90 元。 |
| 五笔库存历史归档 | 465、479、480、497、498 仍为 `TEST_DATA_ARCHIVED`，原预占投递 `CLOSED`、各尝试 5 次，审计摘要一致，库存流水为 0，旧预占重放 0 笔。 |

专用验收入口为 `scripts/verify-checkout-pricing.ps1`。需修改模板、价格及测试历史退款时，在隔离门户使用 `-ExercisePricing`。
日常抽检使用现有模板和历史只读查询。九库迁移前快照、候选镜像摘要、SQL 对账与页面结果保存在
`%LOCALAPPDATA%/CommerceFlow/releases/checkout-pricing-20261120`。

### 2.14 按订单行售后与部分退款

顾客从订单详情选择订单行和件数，试算后提交。商家审核未发货退款，或先审核退货、再确认实收件数及可售状态。
每单同时处理一张售后单，结案后可继续申请。异常实收数量和商品状态进入人工核对。顾客登记的退回运费单列，
计入退款的运费沿用原订单快照。

商品退款以订单行净实付的累计分摊差额计算，分批退款保留分级尾差。部分退未发货订单保留原运费，
整单从未出库且退清时在最后一张售后单返还原运费。已收货退货保留原发货运费。
支付编排与模拟渠道按原交易锁定额度，处理中和成功退款都占额度，使用稳定售后和退款编号。
订单服务串行仲裁发货意图和未发货退款，库存服务为每张售后单写入一份回补凭证。
未发货的剩余应发件数从原订单行减去已结算数量。已收货退货按原出库行、实收件数和验收结果回补。

| 验收项 | 结果 |
| --- | --- |
| 隔离累计退款 | 订单 708 分三笔退款 1295.66、1295.67、1303.67 元，合计 3895.00 元。前两笔原运费为零，最后一笔退 8 元。支付编排、模拟渠道和库存流水对账一致。 |
| 隔离竞争和中断 | 暂停模拟支付渠道时，已锁定的售后阻止同一订单发货。商城重启、渠道恢复后结案，同一申请重试只有一张售后和一份回补凭证。 |
| 隔离退货与出库 | 订单 709 付款三件，未出库先退一件，实际出库两件。收货后再退一件，实收件数不符时未回补，验收通过后仅入库一件，另记退回运费 9.50 元。 |
| 页面与回归 | 5 个 Java 模块 83 项测试通过，Angular 生产构建及 64 项 PowerShell 脚本契约核对通过。Chromium 走通顾客试算、申请和登记运单，商家审核及收货验收。原未发货整单全额退款与回调重投通过。 |
| 日常 Docker | 商城 V55、订单 V7、支付编排 V4、库存 V6。日常订单 707 两笔按行退款合计 2598.00 元，对应库存回补两件。九个数据库容器及发布前订单金额、退款记录均已核对。门户入口为 `main-HDA3OKAT.js`，Prometheus 9 个目标正常。 |
| 五笔历史归档 | 465、479、480、497、498 仍为 `TEST_DATA_ARCHIVED`，对应旧预占为 `CLOSED`，各尝试 5 次，关联库存流水为 0，旧预占重放 0 笔。 |

PowerShell 7 验收入口为 `scripts/verify-line-after-sales.ps1`。带优惠、发货竞争、进程重启与退货均使用隔离 Compose 项目。
九库快照、镜像摘要和验收结果保存在 `%LOCALAPPDATA%/CommerceFlow/releases/line-after-sales-20261120`。

### 2.15 单商家订单满减券

2026-11-20 完成隔离验收并发布到日常 Docker。商家可创建、查看和停止领券活动。顾客可领取、查券包、选券试算、确认下单及查看行优惠。首版每单限一张同商家的固定金额满减券。金额、期限和发行量自活动创建后固定，停用后已有订单继续按快照处理。[操作与计价规则](features/CHECKOUT-COUPONS.md)

商城 V56 新增活动、已领取优惠券和结算恢复记录。订单 V8 保存券 ID，沿用既有行优惠与订单金额快照。报价按结算商品金额判断门槛，运费独立计价。优惠按金额比例计算，以分为单位按最大余数补齐。支付按原实付创建，售后按行优惠后的净额计算。

| 验收项 | 隔离结果 |
| --- | --- |
| 名额、重复领券、门槛、锁券 | 两个顾客竞争最后一个名额，仅一方成功。同顾客重试取得原券。商品金额差 0.01 时拒绝，满足门槛时通过。两份有效报价并发使用同一张券，仅一单成功。 |
| 下单中断恢复 | 订单 712 写入订单库后，故意阻断商城回执写入并重启商城。结算记录从 `PREPARED` 恢复为 `BOUND`，原券保持锁定，重试返回同一订单。 |
| 主动取消、超时、支付竞态 | 订单 711 重复取消，原券返还一次且可重新下单。订单 713 超时取消，活动已到期的券进入 `EXPIRED`。订单 714 取消获胜，支付关闭，券返还。订单 715 支付获胜，券核销。 |
| 计价、核销和退款 | 订单 712 原额 1388.90、优惠 30.00、运费 8.00。两行分摊 28.06 和 1.94。实付 1366.90，分别退款 87.96 和 1278.94，第二笔包含全部 8.00 运费。支付账本累计退款等于实付，库存回补两行共两件，券保持 `USED`。 |
| 页面与既有业务 | Chromium 使用商家和顾客两个会话验证活动创建、停用、领券、券包、报价、确认和详情行优惠，订单 717，无页面脚本错误。无券结算、历史订单 702 原金额、同报价重试回归通过。 |
| 迁移 | 隔离环境以商城 V55、订单 V7 数据启动候选版本，通过新版权限实际领券和下单。空库和 V16 历史库迁移到商城 V56 验收通过。 |

日常同批镜像发布顺序为订单服务、商城迁移和运行权限、商城服务、门户。商城 V56、订单 V8、支付编排 V4、库存 V6。订单 708 取消后原券返还，订单 709 实付 1358.90，两笔原路退款 87.96 和 1270.94，已使用的券保持核销。门户资源为 `main-SO7KTGXD.js`。九个 Prometheus 目标均为 UP。发布前后的九个数据库容器、存量订单金额及退款记录核对一致。五笔历史归档保持 `TEST_DATA_ARCHIVED`，旧预占命令保持 `CLOSED` 且未重放。

[PowerShell 7 验收入口](../scripts/verify-coupons.ps1)。故障、竞态和到期注入在隔离 Compose 项目执行。日常回归仅创建新的验收订单和活动，完成后停止这些活动。

## 3. 支付

- 商城通过 Payment V1 访问独立支付编排服务，支付和退款记录归 `payment-db`。模拟支付平台独立持有渠道交易与回执。
- 支付宝、微信、银联三种模拟渠道与 Vue 收银台。
- 支付单、渠道交易、请求幂等、金额/币种校验。
- HMAC/RSA 签名、防重放、回调白名单、TOTP/恢复码、Step-up 与审计链。
- 异步通知、Transactional Outbox、Kafka 幂等消费、定时补偿和对账。

详见 [高级模拟支付](reference/ADVANCED-PAYMENT.md)。

## 4. 退款首版

- 只支持 `PAID/CONFIRMED` 且未发货订单的原路整单全额退款。
- 订单使用 `REFUNDING/REFUNDED`，原 `payment_record=SUCCESS` 事实不改写。
- 支付编排服务和模拟支付平台分别持有商户侧退款单与渠道侧退款记录，通过签名通知和对账同步结果。
- 客户申请、商家只读摘要、支付后台台账/通知补发与双前端国际化已实现。
- 切流后的整单退款和库存回补已在 Windows／WSL 验收。订单行部分退款及退货入库见第 2.14 节，换货另列。
- 退款首版已随 `d963e7b` 提交并推送。

详见 [退款实现说明](reference/REFUND.md)。

## 5. 商家履约、收货与评价

- 商家通过商城鉴权后访问独立履约服务。履约库保存命令、执行任务和恢复记录，发货通过库存出库单入账后再建运单。
- 发货和物流更正都经过独立模拟物流平台；平台使用独立 MySQL，不直连商城库。
- 物流请求支持 HMAC、时间窗口、Nonce 防重放和幂等建单；回调使用独立 HMAC 密钥和通知 ID 去重。
- 已实现 `CREATED -> PICKED_UP -> IN_TRANSIT -> AT_STATION -> OUT_FOR_DELIVERY -> DELIVERED` 轨迹投影；可人工推进，也可启用可配置的自动推进。
- 非终态运单可进入 `EXCEPTION（异常件）`，自动推进暂停；明确 `RECOVERY（异常恢复）` 后回到异常前状态继续运输。
- 每个轨迹事件与 Callback Outbox 在同一本地事务提交；后台投递支持领取租约、HTTP 超时、失败重试、最大次数和进程重启接管。
- 商城主动签名查询物流平台并补齐永久丢失事件；回调与对账复用事件 ID 和同一投影入口，分别记录 `PROCESSED（回调处理）` 与 `RECONCILED（对账恢复）`。
- 超过最大投递次数的回调进入 `DEAD（停止自动重试）`；管理员可查询、查看审计并填写原因重放，重放使用原始事件 ID 保持幂等。
- 物流回调已暴露积压、投递结果、`DEAD`、人工重放和主动对账 Prometheus 指标。
- 物流状态投影只在严格前进时触发副作用，同级和迟到旧事件仍保留轨迹但不重复调度。Windows 和容器化 Bash 两分钟/12 并发乱序浸泡分别通过 1320/2652 条唯一回调，最终保持 `DELIVERED`、审计精确对齐且过时回调未覆盖承运商或单号。
- 客户可查询本人订单的完整物流轨迹；Angular 订单详情展开时，后续状态始终保留完整物流区域。
- 同值物流重复保存不会无限生成时间线事件。
- 客户可手动确认收货并对订单商品一次性评价；手动收货会取消未执行的 `AUTO_RECEIVE（自动确认收货）` 任务。
- 物流首次进入 `DELIVERED（已送达）` 后创建 `AUTO_RECEIVE`；观察期到期且订单仍为 `SHIPPED（已发货）` 时自动进入 `RECEIVED（已收货）`。
- 手动或自动收货都会幂等创建 `AUTO_COMPLETE（自动完成订单）`；观察期到期后从 `RECEIVED` 进入 `COMPLETED（已完成）`。
- `COMPLETED` 仍保留支付记录、物流轨迹和未评商品的评价入口，同时不再提供取消、确认收货或未发货退款动作。
- 已用两线程同时抢占验证手动收货与 `AUTO_RECEIVE` 只会形成一次有效转换；过期租约可在 commerce-service 重启后恢复，幂等重放不会重复完成订单。
- `AUTO_RECEIVE/AUTO_COMPLETE` 的 RabbitMQ/RocketMQ 故障实验已通过 Windows 与容器化 Bash 实测：Broker 停机时发布尝试和错误可观测，恢复后由原策略快照执行，`TASK_SUCCEEDED` 与订单状态副作用均只有一次。
- 收货/完成竞态浸泡已通过 Windows 120 秒/9 轮/12 并发/864 次操作和容器化 Bash 120 秒/11 轮/12 并发/1056 次操作；交替偏置覆盖手动收货和自动收货两种赢家，每轮只有一次收货转换、一条 `AUTO_COMPLETE` 任务和一次完成事件。

自动轨迹默认关闭，避免干扰手动学习实验；回调 Outbox/重试和物流主动对账默认开启。详见 [独立模拟物流](MOCK-LOGISTICS.md)。

## 6. 基础设施和交付

- MySQL + Flyway、Redis/Spring Session、Kafka/Outbox、RabbitMQ 分桶延迟任务、RocketMQ 5.x 原生延时任务 Profile、ShedLock。
- Angular 商城门户、Vue 支付收银台，以及 9 个 Spring Boot 应用（商城、6 个内部业务服务、2 个模拟平台）。日常 Compose 使用 9 个独立 MySQL 库。
- 验收脚本统一位于 `scripts/`，使用 PowerShell 7。
- `verify-flyway-lifecycle` 使用与后端一致的 Flyway `12.4.0` 和隔离 MySQL 8.4，检查空库 V1–V51、
  V16→V51 升级、V17 历史订单截止时间回填、V24 核心注释、V28 catalog/inventory 所有权分离、V29 catalog
  版本/Outbox 元数据、V30 inventory 命令 Inbox/跨边界外键清理、V31 catalog-event Inbox/版本化快照、
  V32 命令投递/结果协议表、V33 业务恢复状态、V34 人工恢复审计、V35 order Process Manager/跨边界外键清理
  及后续变更，包括 V50 退役轻量订单表、V51 独立审批的库存实盘复核表。脚本只删除自己的随机容器/网络，不使用或清空开发数据卷。

## 7. 通用定时任务底座（阶段 1）

- 建立 `scheduled_tasks`、`scheduled_task_events`、`scheduled_task_policies` 和策略审计表。
- 内置 10 类任务目录，并允许管理员为新的任务类型保存策略；只有 Java 中注册了 Handler 的类型才能真正创建和执行任务。
- 配置文件提供系统默认与各任务初始策略，数据库覆盖优先；创建任务时固化主触发、对账、宽限期、租约、重试和策略版本快照。
- 已实现 Database、RabbitMQ 与 RocketMQ 三种主触发、独立 Database 对账、原子领取、执行租约、失败重试、过期租约恢复和 ShedLock 多实例互斥。
- RabbitMQ 适配器使用持久化分桶 TTL 队列和 DLX：发布端启用 Confirm/Return，失败保留未发布任务并重试；消费端重读数据库并复用统一执行器，格式错误进入 DLQ，基础设施异常有限退避后进入 DLQ。
- RabbitMQ 消息只携带 `taskId/taskType/dueAt/strategySnapshot`；数据库任务快照仍是唯一执行依据，重复消息和 Database 对账通过同一条件更新竞争，只有一方能够领取。
- RabbitMQ 是可选 Compose Profile，默认 Database 模式不启动 Broker；连接、心跳、发布扫描、拓扑、延迟分桶、Database 扫描、租约恢复和对账周期都支持环境变量覆盖。
- RocketMQ 适配器使用 Java gRPC 客户端和绝对投递时间；发布成功后记录 Broker message ID，消费端重读数据库并复用统一执行器，早到消息会清除发布标记重新投递。
- RocketMQ 也是可选 Compose Profile，提供 NameServer、Broker/Proxy、持久化数据卷和 `message.type=DELAY` Topic 初始化；默认 Database 模式不启动它。
- 管理员 API 支持策略查询/覆盖/恢复、策略审计、任务查询/重试/取消和任务事件查询；客户角色被拒绝访问。
- 早期 Windows 专项和后端集成测试已通过，现行脚本入口统一使用 PowerShell 7。

当前已接入 `PAYMENT_TIMEOUT（支付超时）`、`AUTO_RECEIVE（自动确认收货）` 和 `AUTO_COMPLETE（自动完成订单）` 三个业务 Handler；其他任务类型 Handler 仍属于待办。

RabbitMQ 实机验收已覆盖：正常延迟关单、Broker 停机时不误执行、发布失败可观测、恢复后补发，以及开启 Database 对账时由 `DATABASE_RECONCILIATION` 兜底。此段记录早期 RabbitMQ 专项，后续 MQ 幂等及故障演练的实机结果见下文。

RocketMQ 实机验收已在当时的 Windows 与容器化 Bash 两套脚本上通过。验收确认 Broker 回执、原生延时投递、策略快照、原子领取、支付超时关单及审计链。Broker 停机专项也已验证发布失败可观测、`DATABASE_RECONCILIATION` 宽限期兜底和恢复 Broker。

MQ 幂等专项已通过 Windows 和容器化 Bash 实机验收：RabbitMQ/RocketMQ 分别重放两条已完成任务的旧消息，ignored 指标按预期增加，而 `TASK_CLAIMED`、`TASK_SUCCEEDED` 和订单取消副作用仍各只有一次；非法 JSON 分别进入 RabbitMQ Dead Queue 与 RocketMQ `%DLQ%commerceflow-scheduled-task-consumer`。RocketMQ 教学 Consumer Group 使用 2 次、1 秒/2 秒的有限重试，避免毒消息无限占用资源。

MQ 未确认重新投递专项已通过 Windows Docker 与容器化 Bash 实机验收：故障注入点在 Broker 已投递、统一执行器尚未领取且消费者未确认时暂停；零秒宽限停止 commerce-service 后，RabbitMQ/RocketMQ 任务 `239/240`、`241/242` 均由原 Broker 消息重新投递，崩溃前后没有新增发布事件，`TASK_CLAIMED`、`TASK_SUCCEEDED` 和订单取消均只有一次。

Angular 管理后台已增加“定时任务”入口，可新增/覆盖/恢复策略，查看 Handler 可用性、策略来源和版本，并按类型/状态查询任务、查看事件、重试失败任务或取消待执行任务。页面维护的是数据策略，不能上传可执行代码。

## 7. 架构命名与代码治理

下文保留 M2、M3 各批次验证时的规模、准入结论与脚本形式。当前部署和待办分别见第 6、9、10 节及[项目快照](PROJECT-STATUS.md)。

- 核心商城保持模块化单体，按 `identity/catalog/cart/ordering/inventory/payment/refund/fulfillment/logistics/receipt/review/notification/scheduling/shared` 业务包分界。
- 目录已分为 `services/`、`web/`、`simulators/`和 `infrastructure/`；Angular 商城门户与未来 Vue 3 门户使用明确的并列边界。
- Java/Maven 使用 `vip.fairy.commerceflow`；Compose 使用 `fairy-commerceflow` 项目名和“业务归属 + 角色”服务名；默认单副本环境使用固定 `container_name` 去除冗余的 `-1`，HA 多副本服务保留 Compose 副本序号。
- 商城、通知、目录、库存、订单、支付编排、履约及两个模拟平台各持有独立 MySQL。商城和支付模拟器各自使用 Redis。
- M2 批次为当时的 12 个 MyBatis-Plus 持久化实体增加类/字段业务 JavaDoc；简单 CRUD 优先使用 `BaseMapper`/Wrapper，复杂投影、联表、行锁和原子更新放入 Mapper XML。
- 手写业务 SQL 统一放在 Mapper XML。`scripts/audit-code-quality.ps1` 对 9 个 Maven 项目的格式、内联 SQL 边界及 Mapper 契约执行只读审计。
- 物流模拟器根包只保留启动类，业务实现已按 `api/application/domain/infrastructure` 分层；
  基础设施继续按客户端、配置、指标、持久化和安全分目录，Mapper 投影独立放入持久化 `row` 子目录。
- Angular 商城门户和支付模拟器 Web 均已纳入模块本地 Prettier 3.10.1 只读门禁，两个正式 Dockerfile
  都会在生产构建前强制检查。Angular 的 127 个源码/配置文件、29 项单元测试、本地/Docker 生产构建和
  运行态 i18n/权限专项已验证；支付 Web 的 24 个基线文件完成机械格式化，格式检查、无用例 Vitest、
  TypeScript/Vite 生产构建、正式镜像和 `14201/healthz`/Vue 入口运行态已验证。
- 新空卷验收已通过：Flyway 商城/支付/物流为 V24/V4/V5，完成登录→下单→支付→履约→物流→收货→完成→评价；Windows 快速回归同时通过。
- notification-service、catalog-service、inventory-service 与 order-service 已完成独立边界切换；
  payment-orchestration 已完成远程协议和切流前演练但尚未 CUTOVER，fulfillment 尚未拆分。PostgreSQL 专项必须等
  M3 清单中的全部服务完成单一所有权切换并验收后才允许
  启动；后续新增待拆服务也必须先完成。M3.1 初始审计盘点了 37 张表、40 组跨模块依赖方向、
  Outbox/Inbox、消费者契约和追踪缺口，并以 2 项 Maven 基线测试阻止所有权和依赖债务继续增长。
- M3.3 catalog 准入首批已建立 `CatalogProductLookup`、`CatalogCacheInvalidator` 和
  `ProductReviewCatalogQuery` 应用边界；cart/order 不再注入 `ProductMapper`，catalog 不再注入
  `ReviewMapper`。跨模块基线由 41/118/38/21 降至 41/113/37/18，相关 infrastructure 引用上限收紧为 0。
  Java 25 正式镜像 29 项测试和 Windows/Bash 快速回归通过；尚未创建 catalog-service、独立 Schema 或迁移数据。
- M3.3 第二批以 V28 新建 inventory 自有 `product_inventory`，将数量和必要商品快照从 `products` 分离；
  cart/customer order 固化创建时商品快照，库存、购物车、结算和订单读取不再跨边界 join `products`。
  现有库 6 条库存记录及 642/1/65 数量总和保持不变，空库/旧库双平台升级门禁通过；仍未创建 catalog-service、
  inventory-service 或独立数据库，PostgreSQL 继续冻结。
- M3.3 第三批以 V29 和分层 `api/v1`、`api/merchant` 固定列表、详情、批量快照、商家资料/价格/状态/翻译
  契约；expectedVersion 冲突返回稳定错误，写事务同时保存 catalog Outbox 并同步 inventory 展示快照。
  五类事件 V1、fixture、eventId 去重与 businessVersion 乱序/缺口规则已冻结。Java 25 正式镜像 32 项测试、
  双平台生命周期/快速回归和真实 Docker V1 查询通过；创建 API、独立 catalog-service/Schema 尚未启动。
- M3.3 第四批新增独立 catalog-service/catalog-db、HMAC 内部 API、2 秒连接/3 秒读取的 commerce HTTP
  代理和一次性 V29 数据交接。6 条商品与 6 条翻译通过计数/内容指纹后标记 COMPLETE，重复迁移拒绝覆盖；
  commerce 已删除 catalog Mapper/XML/Outbox 写入和过渡投影。catalog 停机返回稳定 503 且 commerce
  健康隔离，commerce→catalog 跨进程 Trace 已验证；inventory-service 与 PostgreSQL 继续冻结。
- M3.4 inventory 准入首批新增分层 `inventory/application/port`：ordering、payment、refund 只依赖
  `InventoryCommandPort`，预占命令使用 inventory 自有最小商品项，商家库存应用层只接收 merchantId。
  ArchUnit 禁止库存外模块重新依赖库存 API、domain、infrastructure 或具体实现。当前依赖为
  39/109/36/18；order 外键、取消通知副作用、独立 Inbox 和 catalog 事件消费仍待下一批治理，未创建
  inventory-service/inventory-db，也未启动 PostgreSQL。Java 25 的 30 项测试、Windows 新运行 JAR 快速
  回归及容器化 Bash 质量/脚本审计已通过。
- M3.4 第二批以 `application/contract/v1` 冻结四类 inventory 命令和 fixture，V30 增加命令 Inbox 并在
  库存本地事务中完成去重、余额变化和成功状态；重复载荷幂等、同 ID 异载荷拒绝。V30 同时移除两条
  inventory→shop_orders 外键，删除 customerId 跨表查询；取消通知由 ordering 事务写 notification Outbox，
  inventory→notification 依赖清零。Java 25 的 32 项测试、双平台 V1→V30/V16→V30 演练及 Windows 快速回归
  通过；默认 Docker 持久库的 17 条真实命令全部 SUCCEEDED，非成功命令和跨边界订单外键均为 0。独立服务、
  独立数据库与 PostgreSQL 仍未启动。
- M3.4 第三批打通 catalog-service Outbox 到 inventory 商品快照的 HMAC HTTP 投递，签名包含请求体
  SHA-256；V31 增加 catalog-event Inbox、生命周期和 businessVersion。重复/异载荷、STALE、版本缺口、
  翻译事件和 INACTIVE 预占门禁均有测试；catalog 最多 5 次后 DEAD。真实 Docker 已验证正常 PUBLISHED，
  以及 commerce 停机 RETRY、恢复后第 4 次尝试成功且 inventory 单次 APPLIED。catalog 5 项、commerce
  35 项测试、双平台 V31 迁移和 Windows 快速回归通过；inventory-service/inventory-db 与 PostgreSQL 仍未启动。
- M3.4 第四批新增统一 `InventoryCommandGateway`、V32 delivery Outbox/结果 Inbox 和
  `InventoryCommandResultV1`。当前本地适配器在原业务事务内调用唯一 Handler 并留下 SUCCEEDED/
  ACKNOWLEDGED 证据；远程切换使用 NEW/IN_FLIGHT/RETRY/RECONCILIATION_REQUIRED 状态机，未知结果不伪造
  拒绝或自动反向补偿。成功/拒绝 fixture、语义指纹、重复命令和超时上限测试已通过，commerce 共 37 项测试；
  双平台 V32 生命周期、Windows 快速回归和双平台质量/脚本审计通过。默认持久库 32 条协议命令全部形成
  SUCCEEDED/ACKNOWLEDGED 配对证据，未决投递为 0；远程传输、独立 inventory-service/inventory-db 和
  PostgreSQL 均未启动。
- M3.4 第五批通过 V33 增加订单与退款自有 inventoryStatus。订单显式经历等待预占、等待提交、已提交、
  退款等待或待对账，发货门禁只接受 COMMITTED；退款财务状态不再替代库存归还状态。超时状态机通过端口
  投影 RECONCILIATION_REQUIRED，ADMIN 恢复入口以原 commandId/原 payload 重试并收口 ACK 与业务状态，
  V34 保留操作者、原因与时间。Java 25 正式镜像 38 项测试、双平台 V34 生命周期、Windows 快速回归和质量
  门禁通过；真实持久库 53 条命令
  全部 ACKNOWLEDGED，订单/退款无等待或待对账残留。独立 inventory-service 与 PostgreSQL 未启动。
- M3.4 第六批完成 inventory-service 最终 CREATE 准入评审。运行手册区分迁入独立库的五张所有权表与留在
  commerce 的生产端 delivery Outbox/结果 Inbox，冻结快照、增量追平、短暂停写、最终指纹、唯一远程
  适配器、跨进程 Trace 和独立新写后的受控回退规则。Windows/Bash 只读审计均通过 V34、七张边界表、
  零跨域外键、余额不变量、命令闭环、零待对账及单一 Gateway；五表基线分别为 6、263、548、87、2 行。
  本批未创建 inventory-service/inventory-db、未迁移或切流；CUTOVER 与 PostgreSQL 继续冻结。
- M3.4 第七批新增分层 inventory-service、独立 MySQL 和默认关闭流量的 `inventory-shadow` Profile。V1
  创建五张所有权表，命令/catalog HTTP 适配器复用冻结的 Inbox、指纹、余额状态机、HMAC 和 Trace 信封；
  未签名请求 401，已签名请求在关闭门禁下稳定 503。可重复迁移连续至少 20 代 READY，快速回归后的五表完整
  字段指纹一致。
  Java 25 的 2 项测试、双平台影子验收、扩展后的质量和 43 组脚本契约门禁通过。commerce/catalog 未改指向，
  独立库未接收业务新写，CUTOVER 与 PostgreSQL 继续冻结。
- M3.4 第八批新增统一库存停写门禁和 commerce 唯一 HMAC HTTP 远程客户端。默认关闭的管理预演入口只重放
  已 ACKNOWLEDGED 的原命令/原 payload，并将 Result V1 幂等确认到生产端 Inbox；连接失败稳定返回 503，
  不引入 LOCAL/COMPARE、双写或数据库回退。Windows/Git Bash 实机均通过最终追平、历史 COMMIT 重复不改账、
  inventory-service 停机/恢复；Windows 快速回归后 generation 23/24 五表指纹连续一致。Java 25 的 40 项测试、
  372 个 Java、385 个类声明空行和 44 对脚本门禁通过。真实业务切换、四类 Tempo Trace、超时/结果丢失仍未
  验收，三个开关恢复默认关闭，CUTOVER 与 PostgreSQL 继续冻结。
- M3.4 第九批把 RESERVE、COMMIT、RELEASE、REFUND 的生产入口整体切到 Outbox→独立 inventory-service。
  业务事务不再调用本地库存 Handler，只有远端 Result V1 确认后才推进订单/退款 inventoryStatus；未知结果保留
  IN_FLIGHT 并用原 commandId 有限重试，不存在 LOCAL/COMPARE、双写或本地回退。Windows 实机验证四类 ACK、
  8 秒结果丢失、服务/数据库停机恢复及四类命令各自的 commerce/inventory 同 Trace；源库存流水保持 582，
  目标增至 627。
  Java 25 干净构建 42 项测试、374/30/253/6 质量门禁、388 个类声明空行和 45 对脚本契约通过。商家库存管理
  API 仍使用 commerce Mapper，因此完整单一所有权与删除源表权限明确留到下一批；PostgreSQL 未启动。
- M3.4 第十批把商家库存列表、人工调整和流水查询迁入 inventory-service。commerce 公开路径只保留
  Session/CSRF/MERCHANT RBAC 与 HMAC 薄代理，独立服务故障返回 503；本地库存 Handler、四个 Mapper/XML、
  旧内部入口和专用签名实现已删除，不存在数据库回退。Compose 使用 Flyway 迁移账号与逐表授权的
  `commerce_runtime` 运行账号，后者对五张库存源表均被 MySQL 1142 拒绝。Windows 实机完成调账恢复、401、
  停机 503 与恢复，源流水保持 582、目标增至 641，随后 Windows 快速回归完整通过；Java 25 共 44 项测试，
  368/27/231/6 质量门禁、全部 383 个 Java 源文件类声明空行和 46 对脚本契约通过，Bash 对等脚本语法通过。
  PostgreSQL 未启动。
- M3.5 第一批完成 order-service 边界审计，不创建服务或数据库。四张 order 所有权表的数据、状态、金额和
  关联完整性通过双平台实机审计；当前仍有 4 条出站、5 条入站跨边界外键和 7 个外部
  `ShopOrderMapper` 消费者，因此当批 CREATE 准入明确不通过。新增 Java 门禁禁止扩张直接 Mapper 消费者、
  Mapper 外部表职责以及 Seata/DTM/XA 协调器；Java 25 正式镜像 42 项测试通过。后续统一使用本地事务、
  Outbox/Inbox、持久化 Saga、条件状态转换和业务补偿/对账，PostgreSQL 继续冻结。
- M3.5 第二批以五个意图型 order application port 替代 fulfillment/logistics/payment/receipt/refund 对
  `ShopOrderMapper` 的直接依赖，外部消费者由 7 归零；支付通知 SQL 回到 payment 自有 Mapper。新增
  OrderCommandV1/OrderResultV1、类型/结果枚举和两份兼容 fixture，明确前置状态、稳定 ID、失败码、Trace、
  未知结果与补偿语义。Java 25 正式镜像 44 项测试、380/28/232/6 质量门禁和 396 个 Java 源文件类声明
  空行通过，双平台 order 准入审计及 Windows 快速回归通过；回归后订单/商品项/地址各 321 条、事件 1521 条，
  金额 416979.00 且错误计数均为 0。当前仍是本地适配器，不代表独立服务或远程 Saga 已完成。
- M3.5 第三批通过 order-service 最终 CREATE 准入。cart/address/payment SQL 已从 `ShopOrderMapper` 迁回
  各自边界端口和 Mapper；Flyway V35 删除 9 条跨边界外键，新增持久化 Process Manager、命令 Outbox 和
  结果 Inbox。流程支持同事务启动、payload 指纹幂等、冲突拒绝、未知结果待对账、拒绝待补偿和审计化人工
  恢复。Java 25 正式镜像 45 项测试、394/31/243/6 质量门禁、410 个 Java 源文件 class 字段空行、双路径
  Flyway 生命周期、Windows 快速回归及 Windows/Git Bash 准入审计通过；最终 329 笔订单、1565 条事件、
  金额 427371.00，所有错误与待恢复计数均为 0。CREATE 已授权但 CUTOVER 未授权；尚未创建 order-service/
  order-db，PostgreSQL 未启动。
- M3.5 第四批创建分层 `order-service` 与独立 MySQL `order-db`，仅运行影子复制。commerce V36 记录 7 张
  order 所有权表的提交顺序变更；独立服务提供 REPEATABLE READ 可重复快照、批次增量追平、水位、7 表逐字段
  SHA-256 审计及孤儿隔离/重放。双平台专项复跑后 generation 8、水位 120，337/337/337/1609、金额 437763.00 与
  三张空协议表全部 MATCH；
  1 条孤儿演练记录已 RESOLVED、OPEN 0，服务和数据库停机恢复均最终追平。远程 Order V1 适配器复用冻结
  契约与 payload 指纹，但流量门默认 503，commerce 未连接它；未 CUTOVER、未引入 LOCAL/COMPARE、未拆后续
  服务、未启动 PostgreSQL。Java 25 镜像 2 项新增契约测试和 407/31/243/7 质量门禁通过。
- M3.5 第五批实现远程 Order V1 的 HMAC-SHA256、正文摘要、时间窗和持久 requestId 防重放；命令 Inbox、
  取消 Process Manager、两条命令 Outbox 与结果 Inbox 使用稳定 ID 和 payload 指纹处理重复、冲突及乱序。
  结果超时进入 `WAITING_RECONCILIATION`，不推断失败；人工 RETRY 保存操作者、原因和时间。合成订单的真实
  跨进程演练在 Tempo 找到 W3C trace，并覆盖结果丢失、两阶段取消完成和清理。最终快速回归后 generation 20、
  水位 236、345/345/345/1653、金额 448155.00、7 表零差异，并完成临时源权限冻结/恢复与 order-service
  停机回退。默认流量门已恢复 503，commerce
  仍不依赖 order-service；没有业务流量、CUTOVER、LOCAL/COMPARE、后续服务拆分或 PostgreSQL。
- M3.5 第六批完成一次性 CUTOVER。停写后的 generation 21 / 水位 236 最终快照在 7 张表全部 MATCH；
  commerce 删除订单 Mapper、本地 Process Manager 和源表 SQL，唯一经远程 Order Store V1 读写。Compose
  默认打开 order 业务门并关闭影子复制，order-service 不再持有源库配置；`commerce_runtime` 对库存与订单
  源表权限均已撤销。运行验收确认新订单只写 order-db、源端保持 345/448155.00，order-service 停机返回
  503 且恢复正常。快速回归后 order-db 为 359/359/359/1710、金额 466341.00，协议表和 OPEN 孤儿均为 0；
  413/28/204/7 质量门禁、431 个 Java 源文件空行和 50 对脚本契约通过。未保留 LOCAL/COMPARE，未拆
  payment-orchestration/fulfillment，未启动 PostgreSQL。
- M3.6 第一批完成 payment-orchestration CREATE 前置审计，不创建服务或数据库。payment 与 refund 统一为
  11 张候选所有权表；299 笔支付、32 笔退款的金额、状态、幂等、渠道证据、Outbox 和事件不变量通过。
  Java 门禁禁止候选表被外部 Mapper 访问、禁止 payment/refund Mapper 引入外部表，并限制 5 个外部
  infrastructure 消费者不得增长。当前仍有 1 条 refund→identity 外键和 1 条 OPEN 对账记录，CREATE 明确
  不通过；Windows/Linux 审计、45 个 commerce-service 测试、50 对脚本契约和快速回归通过。未切流、未拆
  fulfillment、未启动 PostgreSQL。
- M3.6 第二批冻结 Payment V1 创建、查询、关闭与结果契约，ordering/fulfillment 对 payment/refund 内部实现的
  直接导入归零。唯一的本地适配器复用现有业务语义，4 个 JSON 兼容样例固定版本、幂等、关联/因果 ID、Trace、
  payload 指纹、“超时结果未知”和支付成功优先规则；迟到库存预占结果不得倒退已提交状态并有专项测试。
  48 项测试、双平台审计、50 对脚本契约和快速回归通过；未创建独立服务/数据库或双实现，CREATE 继续冻结。
- M3.6 第三批取得 payment-orchestration CREATE 准入：删除 refund→identity 外键，审计式关闭唯一已确认终态的
  OPEN 对账；179 条订单事件 Outbox/Inbox 迁入 ordering 自有表，payment 不再承载订单主题。新增 Process
  Manager、命令 Outbox、结果 Inbox、未知结果/超时人工核对和带审计信息的人工重试门禁。真实 MySQL V37
  迁移与 55 项测试通过；未创建独立服务/数据库、未 CUTOVER、未并行拆 fulfillment、未启动 PostgreSQL。
- M3.6 第四批创建分层 `payment-orchestration-service` 与独立 `payment-db`，但业务流量门保持关闭，commerce
  仍只使用唯一的本地 Payment V1 适配器。V38 用提交顺序水位和 42 个触发器捕获 14 张表变更；独立服务完成
  一致性可重复快照、增量追平、逐字段 SHA-256 核对和孤儿隔离/重放。Windows/WSL 实机专项及快速回归后
  generation 3、水位 83，14 张表全部 MATCH，两次孤儿演练 RESOLVED、OPEN 0；未 CUTOVER、未拆 fulfillment、未启动 PostgreSQL。
- M3.6 第五批实现受流量门保护的远程 Payment V1、真实签名渠道创建/关闭、支付/退款回调、
  HMAC/摘要/持久防重放、Trace、结果未知和
  人工恢复。Windows/WSL 合成专项覆盖重复/冲突、支付关闭竞态、退款、结果丢失、服务/数据库停机和源权限冻结/
  整体回退；结束后清理合成数据并恢复关闭流量。最终 generation 13、水位 160、14 表 MATCH、OPEN 孤儿/恢复 0；
  payment-db Flyway V2、2 项支付服务测试、461/30/220/8 质量门禁、484 个 Java 源文件空行、53 对脚本契约及
  快速回归通过。commerce 仍只有本地正式路径，未 CUTOVER、未拆 fulfillment、未启动 PostgreSQL。
- M3.6 第六批只进行 payment-orchestration 一次性 CUTOVER 评审，没有切流。评审确认独立服务缺少远程
  Order V1/Inventory 副作用适配器、支付 Outbox 投递器和退款发起入口；渠道回调、本地支付/退款运行路径及
  源表权限仍归 commerce。Windows 与 WSL/Linux 审计已把关闭流量门、无 PostgreSQL 和上述阻塞冻结为可重复
  证据；审计通过的含义是正确拒绝 CUTOVER，而不是允许切换。
- M3.6 第七批在关闭流量门下补齐远程 Order V1、Inventory COMMIT/REFUND、带租约和退避的 payment Outbox
  投递器、退款发起及退款渠道命令闭环。Flyway V3 保存 `last_error`，所有远程副作用使用稳定命令 ID；流量门、
  副作用门和影子门互斥保护正式写入。Windows/WSL 缺失订单演练均进入 RETRY 且库存零写入，清理后恢复
  `false/false/true`。commerce 本地路径、渠道回调和源权限仍未切换，故 CUTOVER 继续拒绝；未拆 fulfillment、
  未启动 PostgreSQL。payment 服务 6 项测试、468/30/220/8 质量门禁、493 个 Java 源文件空行、54 对脚本契约
  和完整快速回归通过。
- M3.6 第八批将客户退款 POST 收口为 `RefundCreateCommandV1 -> PaymentOrchestrationV1`，当前仍只有 Local
  适配器，56 项 commerce 测试及真实退款专项通过。新增一次性切换运行手册并更正边界：保留
  `RefundController` 身份薄入口，切流窗口删除本地 Refund Service/Mapper、支付适配器和旧回调。源权限和回调
  尚未切换，故仍未 CUTOVER、未拆 fulfillment、未启动 PostgreSQL。最终回归另以 Order V1 原子
  `START_INVENTORY_COMMIT` 关闭支付回调/库存预留并发状态漂移，并支持重放成功回调补齐 `PAID` 部分结果；两笔
  历史样本恢复为 `PAID/COMMITTED`，order 2 项、commerce 56 项、payment 6 项测试及完整快速回归通过。
- M3.6 第九批增加默认关闭的 `PAYMENT_WRITE_FROZEN` 和 `PaymentWriteAdmissionV1`：创建支付、关闭支付、发起退款
  在业务状态变更前统一停写，签名回调不受影响。Windows 真实演练确认三类新写均为 503、业务事实不变、已有
  回调收敛到 `PAID/SUCCESS` 并恢复默认 false；Ubuntu WSL 补齐 jq 和 Compose 兼容后通过同等验收。commerce
  58 项测试、55 对跨平台脚本契约通过。该能力不代表
  CUTOVER，唯一远程实现、回调切换、本地路径删除和源表撤权仍待同一受控窗口完成。
- M3.6 第十批最终预切换核对为 generation 15、水位 603、14 表 MATCH，OPEN 孤儿、待投递 Payment/Command
  Outbox 和未完成 Process Manager 均为 0；权限冻结恢复与目标停机恢复通过。最终 GO/NO-GO 仍为 NO-GO，因为
  本地实现、旧回调和源权限只能在获准的原子窗口内一起替换。
- M3.6 第十一批经明确授权完成 payment-orchestration 一次性 CUTOVER。最终 generation 16、水位 610、14 表
  MATCH，四类未决项均为 0；commerce 删除本地资金实现并只保留签名远程 Payment V1 适配器，支付/退款回调
  改投独立服务，运行账号对 14 张旧源表授权为 0。订单 486 的支付、退款、两类幂等重放和库存退款闭环通过，
  独立库未决项归零且旧源库无新支付记录。未维护双实现、未拆 fulfillment、未启动 PostgreSQL。
- M3.6 第十二批补齐 payment-orchestration 切流后观察：独立服务暴露 10 个运行态指标，Prometheus 新增第四个
  采集目标，14 条告警覆盖资金 Outbox、人工恢复、孤儿、退款、切流模式和指标刷新。Windows/WSL 在支付服务、
  payment-db、支付模拟器重启前后完成支付/退款与待支付关闭，源水位 610 不变，源授权和未决项持续为 0。
- 以下记录 M3.7 创建准入阶段。当时收口履约对物流投影和商家身份的直接依赖，增加双平台只读审计。履约运行在 commerce 内，CREATE
  评审结果为 `REJECTED`。持久履约和远程契约的后续批次见[履约演进计划](architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md)。
- notification 写入已从 InventoryMapper 收回：V25 建立模块自有 Inbox，V1 通知命令包含版本、发生时间、
  correlation/causation ID，Handler 在同一事务中完成 Inbox 领取和通知写入；契约 fixture、重复消费及
  Windows/Bash 真实取消通知链路已通过。当前审计基线为 38 张表、41 组方向和 119 个 import。
- V26 增加 notification 自有投递 Outbox；进程内/Kafka 适配器可配置切换，具备持久重试、本地 DEAD/重放、
  Kafka DLT、Broker 恢复和受控单体回退。V1 payload/Header 传播 correlation/causation/W3C traceparent，
  Inbox 保存证据；notification application 查询只接收 customerId，不再传递 `AppUser`。29 项测试及正式镜像、
  真实 Kafka 取消、毒消息 DLT、停机恢复和 FALLBACK 通过。当前基线为 39 张表、41 组方向、118 个 import。
- V27 增加 notification 投影验证窗、窗口级影子投影和核对审计；停止要求最少 5 条匹配、零差异、零阻塞
  Outbox 且连续 3 次通过。真实 Kafka 窗口达到 `READY` 后显式停止，三次审计全部通过。独立 notification
  Schema V1 的三张自有表、零外键、快照和 Inbox 幂等已在 Windows/容器化 Bash 隔离 MySQL 中通过。
  商城 30 项测试、正式镜像和双平台门禁通过；当前为 42 张商城表，依赖基线仍为 41/118/38/21。
  默认已恢复 `IN_PROCESS` 且投影关闭；实际运行仍使用商城 Schema，未创建微服务、独立运行数据库或 PostgreSQL。
- M3.1 最终门禁已让 notification 在 commerce-service 进程内实际使用独立 MySQL：V2/V3 提供回填水位、
  核对审计和孤儿隔离，专用 MyBatis/Flyway/事务边界不替代商城主数据源。Windows/容器化 Bash 均完成
  历史追平、Kafka 实时镜像、连续三次零差异、独立读、已读状态 eventId 镜像、独立库停机和商城回滚。
  当前两库通知均为 167 条；默认恢复 `IN_PROCESS + COMMERCE`。31 项测试、正式镜像和 39 对脚本门禁通过。
  notification-service 与 PostgreSQL 仍未启动，M3.1 完成后仅具备 M3.2 的后续启动条件。
- M3.2 准入首批为三个 Java 服务接入 OpenTelemetry，并增加默认关闭的 OTLP 导出、可选 Tempo 和
  Grafana Trace 数据源。Windows/容器化 Bash 已查询到 commerce-service → logistics-simulator-service
  的同一 HTTP Trace，并验证 Tempo 停机不影响业务健康；Kafka `send/process` Span 及 notification
  来源/传输 traceparent 分离已实测。RabbitMQ 通过 Observation 与显式 W3C 上下文跨越 TTL/DLX，
  RocketMQ 通过消息 properties 传播；Windows 与容器化 Bash 均查询到 publish/process 同 Trace，并验证
  Tempo 停机降级。四种边界的本地追踪准入门禁已补齐，但 notification-service 与 PostgreSQL 仍未启动。
- M3.2 影子首批已新增独立 `notification-service`：分层包、独立 Flyway/MyBatis/MySQL、稳定 Kafka
  consumer group、Inbox/eventId 幂等、有限重试 DLT、健康与影子状态端点均已实现。Windows 与容器化
  Bash 实测实时消费、停机积压恢复、commerce/notification 同 Trace 和切回 `IN_PROCESS`；质量门禁为
  293/22/239/4、全部 303 个 Java 源文件及 42 对脚本。该 Profile 默认关闭，客户 API 仍在单体，
  notification 表和回退路径未删除，PostgreSQL 未启动。
- M3.2 单一所有权批次已将 notification-service 纳入默认 Compose 拓扑，并补齐列表、未读数、eventId
  单条已读和全部已读内部 V1 API。commerce 的公开路径保持不变，但只负责 Session/RBAC、HMAC 签名和
  有超时的 HTTP 转发；独立服务故障返回 `503/NOTIFICATION_SERVICE_UNAVAILABLE`，Angular 显示可重试状态。
  commerce 内通知读写 Mapper、进程内消费者、独立数据源、投影比较、回填核对和旧模式配置已删除，
  只保留订单事务侧 Outbox、V1 生产契约与历史 Flyway。Windows/容器化 Bash 运行专项和快速回归已验证
  Kafka 投递、eventId 已读、服务停机 503、恢复和核心商城健康；当前质量基线为 277/21/198/4、全部 287 个
  Java 源文件与 41 对脚本。Windows 完整验收和 Bash 等价全链路业务阶段通过；Git Bash 告警尾段补验
  3 个采集目标、10 条规则和 Watchdog 真实投递。切换时 commerce 历史通知为 0 行，消费者三分区 lag
  均为 0。M3 清单中的全部服务完成单一所有权切换并验收前继续使用 MySQL，不启动 PostgreSQL 专项或
  数据库方言分支。
- 已建立 [全开发生命周期可复测验证体系](VERIFICATION-SYSTEM.md)，将现有构建、快速/完整回归、空卷/升级恢复、MQ/崩溃专项和浸泡压测组织为 L0–L6 门禁，并固定证据、破坏性操作和基线冻结规则。
- Angular 客户订单页已将退款申请/回执、`RECEIVED/COMPLETED` 履约状态和逐商品评价改为独立响应式面板；评价增加评分、必填内容、防重复提交和成功/失败反馈，未修改后端状态机或接口契约。
- 上述界面已通过 25 项 Angular 单元测试、生产构建、Docker 实际页面与现有 `PAID/REFUNDED/RECEIVED/COMPLETED` 数据验收；浏览器控制台无错误，390 px 窄屏无横向溢出。
- `observability` Profile 已接入 Prometheus、10 条可单测告警规则、Alertmanager、Grafana 的 Prometheus/Tempo
  数据源和只读本地 Webhook 接收端；商城、支付模拟器、物流模拟器三个 Spring Boot Target 均可抓取。
- `verify-alerting` 已在 Windows 与容器化 Bash 通过：Prometheus/Alertmanager 配置有效，服务宕机/退款积压/物流 `DEAD` 规则单测通过，Watchdog 经真实 Alertmanager 路由到 Webhook。
- M2 工程质量总验收已完成：Windows 与容器化 Bash 的默认完整验收、质量审计和 37 对脚本契约审计通过；
  五个正式镜像、默认/全 Profile Compose、默认 11 个核心服务和 8 个主要健康端点已复验。连续执行会在
  全量验收开场通过商户 API 准备测试库存；支付 Web 代理动态解析容器上游；本地单节点 Alertmanager
  使用可重建的 tmpfs 通知日志，监控栈冷启动后 Watchdog 可真实送达 webhook。M2 冻结标签为
  `m2-quality-baseline-2026-11-14`，微服务拆分和 PostgreSQL 迁移尚未启动。

## 8. 订单取消与 Database 超时首个垂直闭环

- 新订单保存 `payment_due_at`，同时创建带策略快照的 `PAYMENT_TIMEOUT` 任务。
- 客户可通过 `POST /api/purchase-orders/{id}/cancel` 取消自己的待支付订单；重复取消幂等返回 `CANCELLED`。
- 订单使用 `PENDING_PAYMENT -> CANCELLING -> CANCELLED`，并保存取消原因、取消时间、触发来源和关联任务 ID。
- 有待支付交易时，商城通过 Payment V1 请求支付编排关闭交易，再由 Order V1 完成取消。中断后可从 `CANCELLING` 重试。
- 支付回调与取消并发时，成功支付可从 `CANCELLING/CANCELLED` 恢复 `PAID`；已取消后到达的成功回调由支付编排记录，按稳定幂等键申请自动退款，再通过 Order V1 推进订单。
- Database 扫描使用同一个 `PaymentTimeoutTaskHandler` 自动关单；客户提前取消会取消尚未执行的超时任务。
- 订单服务保存取消终态。商城写入取消效果和通知 Outbox，再由下游幂等处理库存释放及客户通知。订单库与商城库各有本地事务。两次写入之间发生进程中断的恢复仍待隔离验证。
- 客户订单响应包含后端统一计算的 `availableActions`；Angular 已使用它展示支付、取消、退款、收货和评价操作。
- 客户页面已接入未支付订单取消表单，取消后保留原因、时间和历史支付信息；导航通知中心展示未读数量并支持单条或全部已读，使用中性列表、细滚动条和独立正文滚动，面板可通过关闭按钮、页面外点击、`Esc` 或选择通知关闭；语言切换使用紧凑翻译图标并保留无障碍文本。
- Windows 与容器化 Bash 专项验收覆盖动作契约、无支付取消、重复取消、任务快照/取消和待支付渠道关闭。
- 受控双线程集成测试已固定复现支付回调赢得取消竞态的时序，验证资金事实优先、单一订单事件且不误写取消终态。
- Windows PowerShell 与容器化 Bash 的 `verify-payment-cancellation-race` 使用交替偏置和随机抖动并发调用真实商城/支付平台，已验证 `PAID/SUCCESS` 与 `CANCELLED/CLOSED` 都不会产生矛盾账务状态。
- `verify-payment-cancellation-stress` 在不关闭支付平台每分钟签名请求限流的前提下增加节流与有限退避，并按本轮订单 ID 直接审计 Docker MySQL。Windows 120.6 秒/95 轮（`55 PAID` + `40 CANCELLED`）、容器化 Bash 121 秒/97 轮（`58 PAID` + `39 CANCELLED`）均为 0 矛盾记录。
- `verify-payment-cancellation-lock-timeout` 在真实 MySQL 中分别持有订单行和支付行锁；Windows 与容器化 Bash 均验证首次请求返回 `503/DATABASE_CONCURRENCY_RETRY` 与 `Retry-After`，超时事务无中间状态，释放锁后取消和支付回调都可成功重试。
- `verify-payment-cancellation-crash-recovery` 用默认关闭的定点暂停钩子和 commerce-service SIGKILL 覆盖四个窗口：`CANCELLING` 中间态恢复、渠道已关但本地未完成、支付回调事务回滚/重放、迟到成功回调恢复资金事实；Windows 与容器化 Bash 均已通过。
- 单次渠道交易进入 `EXPIRED` 后不关闭订单；`payment_due_at` 未到时可换渠道发起新支付，到期后创建新支付会被后端拒绝。
- `verify-payment-attempt-deadline` 的 Windows 与容器化 Bash 真实链路均已通过：整单截止时间到达后，Database 任务先关闭当前待支付渠道，再取消订单，且领取/完成事件各一次。
- 全新独立数据卷已跑完完整链路：商城/支付/物流 Flyway 分别达到 V24/V4/V5，从 0 订单开始完成登录、下单、支付、履约、物流、收货、完成、重启恢复和评价。
- V16→V24 隔离旧库升级演练已通过；在不删现有卷的前提下，Windows 和容器化 Bash 均已完成数据层、业务服务和两种 MQ 重启，RabbitMQ/RocketMQ 租约过期任务各恢复一次，终态业务历史指纹不变。
- `verify-quick` 已作为快速回归入口在 Windows 和容器化 Bash 通过；M2 冻结前两平台默认完整验收也已通过。
  M1 历史标签为 `m1-baseline-2026-11-13`，M2 质量基线标签为 `m2-quality-baseline-2026-11-14`。

## 9. 库存预占与商家库存中心

- inventory 自有 `product_inventory` 保存可售、预占和已售三个余额；结算原子扣减可售并增加预占，支付成功转为已售，取消事件幂等释放预占。
- 每次预占、转正、释放和人工调整均写库存流水及调整后余额；人工调整不能将可售库存降为负数。
- 商家库存 API 只允许查询和调整自己名下的商品，人工调整必须填写原因。
- Angular 商家端展示库存汇总、商品余额、调整表单及最近流水。管理端支持按仓库凭证关联已有流水、补记出库和实盘复核。
- 商城创建支付前向库存服务核对订单全部商品与数量。缺货使整单预占回滚。库存拒绝没有预占的提交及其重试，履约根据真实扣减和出库结果发货。历史五笔缺证测试记录单独归档。日常环境已用新订单验证缺货拒付拒发、空扣减拒绝、正常扣减与运单唯一性。[验收证据](architecture/INVENTORY-RECONCILIATION-REVIEW.md#2026-11-19-日常环境库存门禁验收)
- 商城的库存命令投递账本已接入 Prometheus，分别监控待人工对账、在途积压和最老等待时长。已确认和已关闭记录排除在外。隔离 MySQL 已验证商城命令告警与库存服务出库事件告警独立触发和恢复。[验收证据](architecture/INVENTORY-RECONCILIATION-REVIEW.md#2026-11-19-库存命令监控验收)

## 10. 尚未实现的总边界

P4 履约发布与旧源清理已完成。见[P4 验收记录](architecture/FULFILLMENT-SERVICE-EVOLUTION-PLAN.md#29-2026-11-20-p4-日常发布和旧源清理)。

其他边界如下。

- 真实邮箱、短信、电话或企业值班平台接入，以及 Prometheus/Alertmanager 多副本和通知到人升级流程；当前只验证本地可替换 Webhook 管道。

详细顺序和验收门禁只见 [TODO.md](TODO.md)。

ALTER TABLE shop_orders
  COMMENT = '商城订单主表，保存支付、履约、取消与收货状态',
  MODIFY COLUMN order_number VARCHAR(40) NOT NULL COMMENT '对客户和商家展示的商城订单号',
  MODIFY COLUMN customer_id BIGINT NOT NULL COMMENT '下单客户在 app_users 中的内部主键',
  MODIFY COLUMN status VARCHAR(40) NOT NULL COMMENT '订单状态机当前状态',
  MODIFY COLUMN total_amount DECIMAL(12,2) NOT NULL COMMENT '订单应付总金额，币种见 currency',
  MODIFY COLUMN currency VARCHAR(3) NOT NULL COMMENT 'ISO 4217 订单币种代码',
  MODIFY COLUMN payment_due_at TIMESTAMP(6) NOT NULL COMMENT '整单允许完成支付的最终时刻',
  MODIFY COLUMN cancelled_at TIMESTAMP(6) NULL COMMENT '订单进入 CANCELLED 的时刻',
  MODIFY COLUMN logistics_delivered_at TIMESTAMP(6) NULL COMMENT '物流渠道首次确认送达的时刻',
  MODIFY COLUMN received_at TIMESTAMP(6) NULL COMMENT '客户或自动任务确认收货的时刻',
  MODIFY COLUMN completed_at TIMESTAMP(6) NULL COMMENT '订单进入 COMPLETED 履约终态的时刻';

ALTER TABLE order_items
  COMMENT = '订单商品快照，保留下单时的商品、商家和价格事实',
  MODIFY COLUMN order_id BIGINT NOT NULL COMMENT '所属商城订单内部主键',
  MODIFY COLUMN product_id BIGINT NOT NULL COMMENT '下单时对应的商品内部主键',
  MODIFY COLUMN merchant_id BIGINT NOT NULL COMMENT '下单时商品所属商家的用户主键',
  MODIFY COLUMN unit_price DECIMAL(12,2) NOT NULL COMMENT '下单时的商品单价，币种继承订单',
  MODIFY COLUMN quantity INT NOT NULL COMMENT '订单行购买数量',
  MODIFY COLUMN subtotal DECIMAL(12,2) NOT NULL COMMENT '订单行小计金额';

ALTER TABLE payment_records
  COMMENT = '商城侧支付尝试及渠道交易投影',
  MODIFY COLUMN order_id BIGINT NOT NULL COMMENT '支付尝试所属订单内部主键',
  MODIFY COLUMN payment_number VARCHAR(40) NOT NULL COMMENT '商城生成的支付单号',
  MODIFY COLUMN idempotency_key VARCHAR(80) NOT NULL COMMENT '客户端创建支付时的幂等键',
  MODIFY COLUMN amount DECIMAL(12,2) NOT NULL COMMENT '本次支付尝试金额，币种见 currency',
  MODIFY COLUMN status VARCHAR(30) NOT NULL COMMENT '商城侧支付尝试状态',
  MODIFY COLUMN channel VARCHAR(30) NOT NULL DEFAULT 'LEGACY_SIMULATED' COMMENT '支付渠道代码',
  MODIFY COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'CNY' COMMENT 'ISO 4217 支付币种代码',
  MODIFY COLUMN channel_transaction_no VARCHAR(80) NULL COMMENT '支付渠道返回的交易号',
  MODIFY COLUMN paid_at TIMESTAMP(6) NULL COMMENT '渠道确认支付成功的时刻',
  MODIFY COLUMN expires_at TIMESTAMP(6) NULL COMMENT '单次渠道支付尝试的过期时刻',
  MODIFY COLUMN refunded_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '已成功退回的累计金额';

ALTER TABLE refund_records
  COMMENT = '商城侧整单全额退款单及渠道退款投影',
  MODIFY COLUMN refund_number VARCHAR(40) NOT NULL COMMENT '商城生成的退款单号',
  MODIFY COLUMN order_id BIGINT NOT NULL COMMENT '退款所属订单内部主键',
  MODIFY COLUMN payment_id BIGINT NOT NULL COMMENT '被退款的成功支付记录主键',
  MODIFY COLUMN customer_id BIGINT NOT NULL COMMENT '申请退款的客户用户主键',
  MODIFY COLUMN idempotency_key VARCHAR(80) NOT NULL COMMENT '退款请求幂等键',
  MODIFY COLUMN amount DECIMAL(12,2) NOT NULL COMMENT '申请退回金额，币种见 currency',
  MODIFY COLUMN currency VARCHAR(3) NOT NULL COMMENT 'ISO 4217 退款币种代码',
  MODIFY COLUMN status VARCHAR(30) NOT NULL COMMENT '退款状态机当前状态',
  MODIFY COLUMN order_status_before_refund VARCHAR(40) NOT NULL COMMENT '退款开始前的订单状态，失败时用于恢复',
  MODIFY COLUMN channel_refund_no VARCHAR(100) NULL COMMENT '支付渠道返回的退款号',
  MODIFY COLUMN succeeded_at TIMESTAMP(6) NULL COMMENT '渠道确认退款成功的时刻';

ALTER TABLE shipment_records
  COMMENT = '商城侧物流运单投影及对账状态',
  MODIFY COLUMN order_id BIGINT NOT NULL COMMENT '运单所属商城订单内部主键',
  MODIFY COLUMN merchant_shipment_no VARCHAR(100) NOT NULL COMMENT '商城生成并发送给物流平台的运单号',
  MODIFY COLUMN platform_shipment_no VARCHAR(100) NULL COMMENT '物流平台返回的运单号',
  MODIFY COLUMN tracking_number VARCHAR(120) NOT NULL COMMENT '承运商查件单号',
  MODIFY COLUMN status VARCHAR(40) NOT NULL COMMENT '商城投影的物流状态',
  MODIFY COLUMN delivered_at TIMESTAMP(6) NULL COMMENT '物流平台确认送达的时刻',
  MODIFY COLUMN last_reconciled_at TIMESTAMP(6) NULL COMMENT '最近一次主动对账成功时刻';

ALTER TABLE inventory_reservations
  COMMENT = '订单结算建立的库存预占与转正或释放事实',
  MODIFY COLUMN order_id BIGINT NOT NULL COMMENT '触发库存预占的订单主键',
  MODIFY COLUMN product_id BIGINT NOT NULL COMMENT '被预占的商品主键',
  MODIFY COLUMN quantity INT NOT NULL COMMENT '预占商品数量',
  MODIFY COLUMN status VARCHAR(24) NOT NULL COMMENT '预占状态：RESERVED、COMMITTED 或 RELEASED',
  MODIFY COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';

ALTER TABLE scheduled_tasks
  COMMENT = '通用业务定时任务，保存策略快照、租约与重试状态',
  MODIFY COLUMN task_key VARCHAR(128) NOT NULL COMMENT '业务稳定幂等键',
  MODIFY COLUMN task_type VARCHAR(64) NOT NULL COMMENT '任务类型，用于选择统一执行 Handler',
  MODIFY COLUMN aggregate_type VARCHAR(64) NOT NULL COMMENT '关联业务聚合类型',
  MODIFY COLUMN aggregate_id VARCHAR(128) NOT NULL COMMENT '关联业务聚合标识',
  MODIFY COLUMN payload_json JSON NOT NULL COMMENT '执行 Handler 需要的业务快照',
  MODIFY COLUMN status VARCHAR(24) NOT NULL COMMENT '任务状态机当前状态',
  MODIFY COLUMN due_at TIMESTAMP(6) NOT NULL COMMENT '任务首次允许执行的时刻',
  MODIFY COLUMN primary_trigger_snapshot VARCHAR(20) NOT NULL COMMENT '创建任务时固化的主触发策略',
  MODIFY COLUMN reconciliation_enabled_snapshot TINYINT(1) NOT NULL COMMENT '创建任务时固化的数据库对账开关',
  MODIFY COLUMN claim_lease_seconds_snapshot BIGINT NOT NULL COMMENT '创建任务时固化的领取租约秒数',
  MODIFY COLUMN max_attempts INT NOT NULL COMMENT '任务允许的最大执行次数',
  MODIFY COLUMN retry_delay_seconds_snapshot BIGINT NOT NULL COMMENT '创建任务时固化的重试间隔秒数',
  MODIFY COLUMN lease_until TIMESTAMP(6) NULL COMMENT '当前执行者的租约到期时刻',
  MODIFY COLUMN claimed_by VARCHAR(190) NULL COMMENT '当前领取任务的实例标识',
  MODIFY COLUMN last_trigger_source VARCHAR(32) NULL COMMENT '最近一次尝试执行的实际触发来源',
  MODIFY COLUMN completed_at TIMESTAMP(6) NULL COMMENT '任务执行成功的时刻',
  MODIFY COLUMN cancelled_at TIMESTAMP(6) NULL COMMENT '任务被业务流程取消的时刻';

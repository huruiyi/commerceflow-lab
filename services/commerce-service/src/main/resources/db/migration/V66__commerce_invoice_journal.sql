CREATE TABLE invoice_applications (
  order_id BIGINT NOT NULL PRIMARY KEY COMMENT '订单服务的订单主键',
  application_no VARCHAR(64) NOT NULL UNIQUE COMMENT '同一订单的固定开票申请号',
  customer_id BIGINT NOT NULL COMMENT '申请人和订单所有者',
  status VARCHAR(24) NOT NULL COMMENT 'PENDING, ISSUED, EXHAUSTED, REVIEW_REQUIRED',
  review_reason VARCHAR(80) NULL COMMENT '需人工核对的错误码',
  review_detail VARCHAR(1024) NULL COMMENT '金额、币种及关系差异的核对依据',
  next_check_at DATETIME(6) NULL COMMENT 'UTC，NULL 表示已停止自动处理',
  KEY idx_invoice_due (next_check_at, order_id)
) ENGINE=InnoDB;

CREATE TABLE invoice_attempts (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  request_id VARCHAR(64) NOT NULL UNIQUE COMMENT '持久化后传给发票平台的签名请求号',
  kind VARCHAR(8) NOT NULL COMMENT 'ISSUE 正票或 RED 红票',
  reference_no VARCHAR(64) NOT NULL COMMENT '开票申请号或成功退款单号',
  payment_no VARCHAR(64) NOT NULL COMMENT '正式支付账本的原支付单号',
  original_invoice_no VARCHAR(64) NULL COMMENT '红票关联的正票号',
  amount DECIMAL(18,2) NOT NULL COMMENT '发送到发票平台的冻结金额',
  currency CHAR(3) NOT NULL,
  merchant_id BIGINT NULL COMMENT '正票冻结时的单商家归属',
  gross_amount DECIMAL(18,2) NULL COMMENT '正票冻结时订单行原价总额',
  discount_amount DECIMAL(18,2) NULL COMMENT '正票冻结时订单行优惠总额',
  freight_amount DECIMAL(18,2) NULL COMMENT '正票冻结时实收运费',
  paid_amount DECIMAL(18,2) NULL COMMENT '正票冻结时的实付金额',
  status VARCHAR(12) NOT NULL COMMENT 'PENDING, SUCCEEDED, FAILED',
  invoice_no VARCHAR(64) NULL UNIQUE COMMENT '模拟平台确认的票号',
  failure_code VARCHAR(80) NULL COMMENT '模拟平台确认终态失败的原因',
  frozen_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  KEY idx_invoice_attempt_order (order_id, kind, id),
  CONSTRAINT fk_invoice_attempt_app FOREIGN KEY (order_id) REFERENCES invoice_applications (order_id),
  CHECK (amount > 0)
) ENGINE=InnoDB;

CREATE TABLE invoice_refund_facts (
  order_id BIGINT NOT NULL,
  refund_no VARCHAR(64) NOT NULL,
  payment_no VARCHAR(64) NOT NULL,
  amount DECIMAL(18,2) NOT NULL COMMENT '成功退款账本金额，含实际退还运费',
  currency CHAR(3) NOT NULL,
  succeeded_at DATETIME(6) NOT NULL COMMENT '支付编排确认退款成功的 UTC 时间',
  PRIMARY KEY (order_id, refund_no),
  CONSTRAINT fk_invoice_facts_app FOREIGN KEY (order_id) REFERENCES invoice_applications (order_id),
  CHECK (amount > 0)
) ENGINE=InnoDB;

CREATE TABLE invoice_frozen_refunds (
  request_id VARCHAR(64) NOT NULL,
  order_id BIGINT NOT NULL,
  refund_no VARCHAR(64) NOT NULL COMMENT '已计入该次正票快照的成功退款',
  PRIMARY KEY (request_id, refund_no),
  CONSTRAINT fk_invoice_frozen_attempt FOREIGN KEY (request_id) REFERENCES invoice_attempts (request_id),
  CONSTRAINT fk_invoice_frozen_fact FOREIGN KEY (order_id, refund_no)
    REFERENCES invoice_refund_facts (order_id, refund_no)
) ENGINE=InnoDB;

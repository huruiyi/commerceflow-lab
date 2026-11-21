ALTER TABLE invoice_applications
  ADD COLUMN order_number VARCHAR(64) NULL COMMENT '申请时读取的订单展示号，存量申请可为空',
  ADD COLUMN retry_after_id BIGINT NOT NULL DEFAULT 0 COMMENT '平台授权重试前最后一次已记录的尝试主键',
  ADD KEY idx_invoice_customer (customer_id, order_id),
  ADD KEY idx_invoice_order_number (order_number);

CREATE TABLE invoice_review_actions (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL COMMENT '执行复核的已授权平台账号',
  prior_reason VARCHAR(80) NOT NULL COMMENT '复核前的异常码',
  prior_detail VARCHAR(1024) NULL COMMENT '复核前保存的差异依据',
  note VARCHAR(300) NOT NULL COMMENT '平台操作员填写的复核说明',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_invoice_review_order (order_id, id),
  CONSTRAINT fk_invoice_review_app FOREIGN KEY (order_id) REFERENCES invoice_applications (order_id)
) ENGINE=InnoDB;

INSERT INTO roles (code, description)
VALUES ('INVOICE_OPERATIONS', 'Manage invoice operations across commerce merchants');

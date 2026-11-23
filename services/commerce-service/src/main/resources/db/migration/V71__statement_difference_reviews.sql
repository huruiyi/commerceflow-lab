CREATE TABLE merchant_statement_difference_reviews (
  request_id CHAR(36) NOT NULL PRIMARY KEY COMMENT '复核请求号，同一请求重试复用原记录',
  statement_id CHAR(36) NOT NULL COMMENT '被复核的冻结账单',
  merchant_id BIGINT NOT NULL COMMENT '冻结账单所属店铺',
  order_id BIGINT NULL COMMENT '订单级差异所属订单',
  order_line_id BIGINT NULL COMMENT '库存或仓库数量差异所属订单行',
  difference_kind VARCHAR(64) NOT NULL COMMENT '对账报告中的差异类型',
  evidence_reference VARCHAR(128) NOT NULL COMMENT '原账单、账本行或回执编号',
  expected_value DECIMAL(24,2) NULL COMMENT '复核时报告的预期金额或件数',
  observed_value DECIMAL(24,2) NULL COMMENT '复核时报告的实际金额或件数',
  decision VARCHAR(16) NOT NULL COMMENT 'CONFIRMED 确认差异，NO_ACTION 无需处置',
  reason VARCHAR(300) NOT NULL COMMENT '复核人填写的依据',
  reviewed_by BIGINT NOT NULL COMMENT '复核管理员账号',
  reviewed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '登记复核的时间',
  CONSTRAINT fk_statement_difference_review FOREIGN KEY (statement_id) REFERENCES merchant_settlement_statements(id),
  CONSTRAINT chk_statement_difference_decision CHECK (decision IN ('CONFIRMED','NO_ACTION')),
  KEY idx_statement_difference_review_history (merchant_id,statement_id,reviewed_at,request_id)
);

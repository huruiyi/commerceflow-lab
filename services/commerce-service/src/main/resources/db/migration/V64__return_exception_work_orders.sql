ALTER TABLE line_after_sales
  ADD COLUMN approved_quantity INT NULL,
  ADD COLUMN approved_merchandise_amount DECIMAL(12,2) NULL,
  ADD CONSTRAINT ck_line_after_sale_resolution CHECK (
    (approved_quantity IS NULL AND approved_merchandise_amount IS NULL)
    OR (kind='RETURN' AND approved_quantity IS NOT NULL AND approved_quantity BETWEEN 1 AND quantity
      AND approved_merchandise_amount IS NOT NULL AND approved_merchandise_amount >= 0
      AND approved_merchandise_amount <= merchandise_amount AND shipping_amount=0)
  );

CREATE TABLE return_exception_work_orders (
  case_id CHAR(36) PRIMARY KEY,
  warehouse_status VARCHAR(24) NOT NULL,
  phase VARCHAR(24) NOT NULL DEFAULT 'OPEN',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_return_work_case FOREIGN KEY (case_id) REFERENCES line_after_sales(id),
  CONSTRAINT ck_return_work_phase CHECK (phase IN ('OPEN','WAITING_EVIDENCE','APPROVED','REJECTED'))
);

CREATE TABLE return_exception_actions (
  request_id CHAR(36) PRIMARY KEY,
  case_id CHAR(36) NOT NULL,
  actor_id BIGINT NOT NULL,
  action VARCHAR(24) NOT NULL,
  note VARCHAR(300) NOT NULL,
  quantity INT NULL,
  evidence_id CHAR(36) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_return_work_actions (case_id,created_at,request_id),
  CONSTRAINT fk_return_work_action FOREIGN KEY (case_id) REFERENCES return_exception_work_orders(case_id)
);

INSERT INTO return_exception_work_orders(case_id,warehouse_status)
SELECT id,warehouse_return_status FROM line_after_sales
WHERE kind='RETURN' AND status='REVIEW_REQUIRED' AND refund_number IS NULL
  AND warehouse_return_status IN ('SHORT_RECEIVED','REJECTED','FAILED');

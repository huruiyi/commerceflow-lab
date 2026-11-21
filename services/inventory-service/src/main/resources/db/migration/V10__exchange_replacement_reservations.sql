CREATE TABLE exchange_replacement_reservations (
  case_id CHAR(36) NOT NULL COMMENT '已验收入库的同一换货单',
  status VARCHAR(16) NOT NULL COMMENT 'RESERVED 或 SHIPPED',
  PRIMARY KEY (case_id),
  CONSTRAINT fk_exchange_credit FOREIGN KEY (case_id) REFERENCES inventory_line_credits(case_id),
  CONSTRAINT ck_exchange_replacement_status CHECK (status IN ('RESERVED','SHIPPED'))
);

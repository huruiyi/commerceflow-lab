CREATE TABLE merchant_settlement_accounts (
  merchant_id BIGINT PRIMARY KEY,
  commission_bps INT NOT NULL COMMENT 'Frozen commission basis points on net merchandise',
  enabled_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_settlement_store FOREIGN KEY(merchant_id) REFERENCES merchant_stores(merchant_id),
  CONSTRAINT ck_settlement_commission CHECK (commission_bps BETWEEN 0 AND 10000)
);

CREATE TABLE merchant_settlement_statements (
  id CHAR(36) PRIMARY KEY COMMENT 'The freeze request UUID and statement reference',
  merchant_id BIGINT NOT NULL,
  starts_at TIMESTAMP(6) NOT NULL,
  cutoff_at TIMESTAMP(6) NOT NULL,
  merchandise DECIMAL(16,2) NOT NULL,
  freight DECIMAL(16,2) NOT NULL,
  commission DECIMAL(16,2) NOT NULL,
  payable DECIMAL(16,2) NOT NULL,
  frozen_by BIGINT NOT NULL,
  CONSTRAINT fk_statement_account FOREIGN KEY(merchant_id) REFERENCES merchant_settlement_accounts(merchant_id),
  CONSTRAINT ck_statement_payable CHECK (payable > 0 AND payable = merchandise + freight - commission),
  KEY ix_statement_merchant(merchant_id,cutoff_at)
);

CREATE TABLE merchant_settlement_entries (
  source_key VARCHAR(100) PRIMARY KEY COMMENT 'Deterministic order line, freight or refund source identifier',
  merchant_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  order_line_id BIGINT NULL,
  kind VARCHAR(20) NOT NULL COMMENT 'MERCHANDISE, FREIGHT or REFUND',
  merchandise DECIMAL(14,2) NOT NULL,
  freight DECIMAL(14,2) NOT NULL,
  commission DECIMAL(14,2) NOT NULL,
  occurred_at TIMESTAMP(6) NOT NULL,
  statement_id CHAR(36) NULL,
  CONSTRAINT fk_entry_account FOREIGN KEY(merchant_id) REFERENCES merchant_settlement_accounts(merchant_id),
  CONSTRAINT fk_entry_statement FOREIGN KEY(statement_id) REFERENCES merchant_settlement_statements(id),
  KEY ix_settlement_pending(merchant_id,statement_id),
  KEY ix_settlement_order(order_id)
);

CREATE TABLE merchant_payout_attempts (
  id CHAR(36) PRIMARY KEY COMMENT 'Stable signed channel attempt id',
  statement_id CHAR(36) NOT NULL,
  merchant_id BIGINT NOT NULL COMMENT 'Merchant approved for the statement',
  amount DECIMAL(16,2) NOT NULL,
  currency CHAR(3) NOT NULL,
  status VARCHAR(16) NOT NULL COMMENT 'REQUESTED, PENDING, SUCCEEDED or FAILED',
  simulate_failure BOOLEAN NOT NULL,
  requested_by BIGINT NOT NULL,
  channel_payout_no VARCHAR(50) NULL UNIQUE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  resolved_at TIMESTAMP(6) NULL,
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  active_statement_id CHAR(36) GENERATED ALWAYS AS (
    CASE WHEN status IN ('REQUESTED','PENDING','SUCCEEDED') THEN statement_id END
  ) STORED,
  CONSTRAINT fk_payout_frozen_statement FOREIGN KEY(statement_id) REFERENCES merchant_settlement_statements(id),
  CONSTRAINT ck_payout_amount CHECK (amount > 0 AND currency='CNY'),
  CONSTRAINT ck_payout_status CHECK (status IN ('REQUESTED','PENDING','SUCCEEDED','FAILED')),
  UNIQUE KEY uk_payout_active_statement(active_statement_id),
  KEY ix_payout_statement(statement_id,created_at),
  KEY ix_payout_dispatch(status,next_attempt_at)
);

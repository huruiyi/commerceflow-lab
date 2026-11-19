CREATE TABLE channel_payouts (
  attempt_id CHAR(36) PRIMARY KEY COMMENT 'Merchant attempt idempotency key',
  statement_id CHAR(36) NOT NULL COMMENT 'Original frozen settlement statement',
  beneficiary_id BIGINT NOT NULL COMMENT 'Merchant store entitled to payment',
  amount DECIMAL(16,2) NOT NULL,
  currency CHAR(3) NOT NULL,
  channel_payout_no VARCHAR(50) NOT NULL UNIQUE,
  status VARCHAR(16) NOT NULL COMMENT 'PENDING, SUCCEEDED or FAILED',
  simulate_failure BOOLEAN NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  resolved_at TIMESTAMP(6) NULL,
  notified_at TIMESTAMP(6) NULL,
  next_notify_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  active_statement_id CHAR(36) GENERATED ALWAYS AS (
    CASE WHEN status IN ('PENDING','SUCCEEDED') THEN statement_id END
  ) STORED,
  UNIQUE KEY uk_channel_payout_active_statement(active_statement_id),
  KEY ix_payout_notify(status,notified_at,next_notify_at),
  CONSTRAINT ck_channel_payout_amount CHECK (amount > 0 AND currency = 'CNY'),
  CONSTRAINT ck_channel_payout_status CHECK (status IN ('PENDING','SUCCEEDED','FAILED'))
);

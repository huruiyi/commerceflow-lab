CREATE TABLE payment_idempotency_locks (
  idempotency_key VARCHAR(80) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (idempotency_key)
);

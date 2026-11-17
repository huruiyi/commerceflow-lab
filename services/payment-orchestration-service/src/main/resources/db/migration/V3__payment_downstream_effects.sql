ALTER TABLE payment_outbox
  ADD COLUMN last_error VARCHAR(500) NULL AFTER next_attempt_at;

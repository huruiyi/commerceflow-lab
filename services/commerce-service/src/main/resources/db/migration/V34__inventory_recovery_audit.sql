ALTER TABLE inventory_command_delivery_outbox
  ADD COLUMN recovered_by VARCHAR(100) NULL AFTER last_error,
  ADD COLUMN recovery_reason VARCHAR(300) NULL AFTER recovered_by,
  ADD COLUMN recovered_at TIMESTAMP(6) NULL AFTER recovery_reason;

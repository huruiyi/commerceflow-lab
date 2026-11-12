ALTER TABLE shipment_records
  ADD COLUMN last_reconciled_at TIMESTAMP(6) NULL AFTER delivered_at,
  ADD COLUMN reconciliation_error VARCHAR(1000) NULL AFTER last_reconciled_at,
  ADD INDEX idx_shipment_reconciliation(status,last_reconciled_at,updated_at,id);

ALTER TABLE shipment_records
  ADD COLUMN resume_status VARCHAR(40) NULL AFTER status,
  ADD INDEX idx_shipment_auto_progress(status,updated_at,id);

ALTER TABLE shipment_events
  ADD COLUMN event_type VARCHAR(30) NOT NULL DEFAULT 'PROGRESS' AFTER event_id;

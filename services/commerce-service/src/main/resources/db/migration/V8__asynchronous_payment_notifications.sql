ALTER TABLE payment_records ADD COLUMN checkout_url VARCHAR(500) NULL;
ALTER TABLE payment_records ADD COLUMN action_type VARCHAR(30) NULL;
ALTER TABLE payment_records ADD COLUMN qr_payload VARCHAR(500) NULL;
ALTER TABLE payment_records ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE payment_notifications ADD COLUMN raw_status VARCHAR(30) NULL;
ALTER TABLE payment_notifications ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE payment_notifications ADD COLUMN next_retry_at TIMESTAMP(6) NULL;

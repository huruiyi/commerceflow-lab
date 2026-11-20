CREATE TABLE messaging_deliveries (
  request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  provider_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  channel VARCHAR(12) NOT NULL,
  recipient_mask VARCHAR(96) NOT NULL,
  reference VARCHAR(120) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  outcome VARCHAR(24) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACCEPTED',
  accepted_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  ready_at TIMESTAMP(6) NOT NULL,
  finished_at TIMESTAMP(6) NULL,
  CONSTRAINT ck_messaging_channel CHECK (channel IN ('SMS','EMAIL')),
  CONSTRAINT ck_messaging_status CHECK (status IN ('ACCEPTED','DELIVERED','FAILED')),
  INDEX idx_messaging_due (status,ready_at),
  INDEX idx_messaging_channel_history (channel,accepted_at,request_id),
  INDEX idx_messaging_reference (channel,reference)
);

CREATE TABLE messaging_faults (
  request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  behavior VARCHAR(24) NOT NULL,
  CONSTRAINT ck_messaging_fault CHECK (behavior IN ('THROTTLE_ONCE','TIMEOUT_AFTER_ACCEPT','DELIVERY_FAILED'))
);

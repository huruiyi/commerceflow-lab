CREATE TABLE offsite_notification_inbox (
  event_id VARCHAR(120) PRIMARY KEY,
  event_type VARCHAR(40) NOT NULL,
  recipient_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  reference VARCHAR(100) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE offsite_notification_deliveries (
  request_id CHAR(36) PRIMARY KEY,
  event_id VARCHAR(120) NOT NULL,
  channel VARCHAR(10) NOT NULL,
  recipient VARCHAR(120) NOT NULL,
  subject VARCHAR(160) NULL,
  message VARCHAR(400) NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  provider_id CHAR(36) NULL,
  last_error VARCHAR(100) NULL,
  lease CHAR(36) NULL,
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_offsite_channel(event_id, channel),
  KEY idx_offsite_due(status, next_attempt_at, request_id),
  CONSTRAINT fk_offsite_delivery_event FOREIGN KEY (event_id) REFERENCES offsite_notification_inbox(event_id),
  CONSTRAINT chk_offsite_delivery_channel CHECK (channel IN ('SMS','EMAIL')),
  CONSTRAINT chk_offsite_delivery_status CHECK (status IN ('PENDING','RETRY','ACCEPTED','DELIVERED','FAILED','SKIPPED'))
);

CREATE COLLATION messaging_reference_ci (provider = icu, locale = 'und-u-ks-level1', deterministic = false);

CREATE TABLE messaging_deliveries (
  request_id VARCHAR(36) COLLATE "C" PRIMARY KEY,
  provider_id VARCHAR(36) COLLATE "C" NOT NULL UNIQUE,
  channel VARCHAR(12) NOT NULL,
  recipient_mask VARCHAR(96) NOT NULL,
  reference VARCHAR(120) COLLATE messaging_reference_ci NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  outcome VARCHAR(24) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACCEPTED',
  accepted_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ready_at TIMESTAMPTZ(6) NOT NULL,
  finished_at TIMESTAMPTZ(6),
  CONSTRAINT ck_messaging_channel CHECK (channel IN ('SMS','EMAIL')),
  CONSTRAINT ck_messaging_status CHECK (status IN ('ACCEPTED','DELIVERED','FAILED'))
);
CREATE INDEX idx_messaging_due ON messaging_deliveries (status,ready_at);
CREATE INDEX idx_messaging_channel_history ON messaging_deliveries (channel,accepted_at,request_id);
CREATE INDEX idx_messaging_reference ON messaging_deliveries (channel,reference);
CREATE INDEX idx_messaging_recent_failure ON messaging_deliveries (channel,status,finished_at);

CREATE TABLE messaging_faults (
  request_id VARCHAR(36) COLLATE "C" PRIMARY KEY,
  behavior VARCHAR(24) NOT NULL,
  CONSTRAINT ck_messaging_fault CHECK (behavior IN ('THROTTLE_ONCE','TIMEOUT_AFTER_ACCEPT','DELIVERY_FAILED'))
);

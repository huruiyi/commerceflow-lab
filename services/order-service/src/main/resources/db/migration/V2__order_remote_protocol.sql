ALTER TABLE order_events MODIFY id BIGINT NOT NULL AUTO_INCREMENT;

CREATE TABLE order_remote_message_inbox (
  message_id VARCHAR(100) NOT NULL,
  message_kind VARCHAR(20) NOT NULL,
  payload_fingerprint CHAR(64) NOT NULL,
  processing_status VARCHAR(30) NOT NULL,
  response_payload LONGTEXT NULL,
  error_detail VARCHAR(500) NULL,
  received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  processed_at TIMESTAMP(6) NULL,
  PRIMARY KEY(message_id)
);

CREATE TABLE order_internal_request_replay (
  request_id VARCHAR(100) NOT NULL,
  source_service VARCHAR(50) NOT NULL,
  content_fingerprint CHAR(64) NOT NULL,
  received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY(request_id),
  KEY idx_order_internal_replay_expiry(expires_at)
);

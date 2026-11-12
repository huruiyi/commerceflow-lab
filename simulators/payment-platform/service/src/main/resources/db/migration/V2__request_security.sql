ALTER TABLE payment_merchant
  ADD COLUMN signing_algorithm VARCHAR(32) NOT NULL DEFAULT 'HMAC_SHA256' AFTER credential_version;

CREATE TABLE signed_request_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  merchant_id BIGINT NOT NULL,
  request_id VARCHAR(80) NOT NULL,
  nonce VARCHAR(100) NOT NULL,
  request_timestamp BIGINT NOT NULL,
  body_sha256 VARCHAR(64) NOT NULL,
  signing_algorithm VARCHAR(32) NOT NULL,
  request_path VARCHAR(300) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_signed_request_id UNIQUE (merchant_id, request_id),
  CONSTRAINT uk_signed_request_nonce UNIQUE (merchant_id, nonce),
  CONSTRAINT fk_signed_request_merchant FOREIGN KEY (merchant_id) REFERENCES payment_merchant(id),
  KEY ix_signed_request_created_at (created_at)
);

CREATE TABLE merchant_callback_endpoint (
  id BIGINT NOT NULL AUTO_INCREMENT,
  merchant_id BIGINT NOT NULL,
  callback_url VARCHAR(500) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_merchant_callback UNIQUE (merchant_id, callback_url),
  CONSTRAINT fk_callback_merchant FOREIGN KEY (merchant_id) REFERENCES payment_merchant(id) ON DELETE CASCADE
);

INSERT INTO merchant_callback_endpoint (merchant_id, callback_url)
SELECT id, notify_url FROM payment_merchant WHERE notify_url IS NOT NULL AND notify_url <> '';

CREATE TABLE payment_admin_mfa_recovery_code (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  code_hash VARCHAR(255) NOT NULL,
  used_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT fk_mfa_recovery_user FOREIGN KEY (user_id) REFERENCES payment_admin_user(id) ON DELETE CASCADE
);

ALTER TABLE payment_admin_audit_log
  ADD COLUMN previous_hash VARCHAR(64) NULL AFTER source_ip,
  ADD COLUMN event_hash VARCHAR(64) NULL AFTER previous_hash;

CREATE TABLE payment_audit_chain_head (
  id BIGINT NOT NULL,
  last_hash VARCHAR(64) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id)
);

INSERT INTO payment_audit_chain_head (id, last_hash) VALUES (1, REPEAT('0', 64));

DELIMITER //
CREATE TRIGGER payment_audit_no_update BEFORE UPDATE ON payment_admin_audit_log
FOR EACH ROW BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit log is append-only';
END//
CREATE TRIGGER payment_audit_no_delete BEFORE DELETE ON payment_admin_audit_log
FOR EACH ROW BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit log is append-only';
END//
DELIMITER ;

CREATE TABLE payment_admin_user (
  id BIGINT NOT NULL AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  email VARCHAR(128) NOT NULL,
  mobile VARCHAR(32) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  account_locked BOOLEAN NOT NULL DEFAULT FALSE,
  login_failure_count INT NOT NULL DEFAULT 0,
  locked_until TIMESTAMP(6) NULL,
  password_change_required BOOLEAN NOT NULL DEFAULT FALSE,
  mfa_required BOOLEAN NOT NULL DEFAULT FALSE,
  mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  last_login_at TIMESTAMP(6) NULL,
  created_by BIGINT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_by BIGINT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_payment_admin_username UNIQUE (username),
  CONSTRAINT uk_payment_admin_email UNIQUE (email)
);

CREATE TABLE payment_admin_role (
  id BIGINT NOT NULL AUTO_INCREMENT,
  role_code VARCHAR(64) NOT NULL,
  role_name VARCHAR(100) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_payment_admin_role_code UNIQUE (role_code)
);

CREATE TABLE payment_admin_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id),
  CONSTRAINT fk_payment_admin_user_role_user FOREIGN KEY (user_id) REFERENCES payment_admin_user(id) ON DELETE CASCADE,
  CONSTRAINT fk_payment_admin_user_role_role FOREIGN KEY (role_id) REFERENCES payment_admin_role(id)
);

CREATE TABLE payment_merchant (
  id BIGINT NOT NULL AUTO_INCREMENT,
  merchant_no VARCHAR(64) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  app_id VARCHAR(64) NOT NULL,
  secret_cipher VARCHAR(512) NOT NULL,
  secret_last_four VARCHAR(4) NOT NULL,
  credential_version INT NOT NULL DEFAULT 1,
  merchant_public_key TEXT NULL,
  notify_url VARCHAR(500) NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_payment_merchant_no UNIQUE (merchant_no),
  CONSTRAINT uk_payment_merchant_app_id UNIQUE (app_id),
  CONSTRAINT fk_payment_merchant_creator FOREIGN KEY (created_by) REFERENCES payment_admin_user(id)
);

CREATE TABLE payment_admin_user_merchant (
  user_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL,
  permission_scope VARCHAR(32) NOT NULL,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (user_id, merchant_id),
  CONSTRAINT fk_admin_merchant_user FOREIGN KEY (user_id) REFERENCES payment_admin_user(id) ON DELETE CASCADE,
  CONSTRAINT fk_admin_merchant_merchant FOREIGN KEY (merchant_id) REFERENCES payment_merchant(id) ON DELETE CASCADE
);

CREATE TABLE payment_admin_mfa_credential (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  mfa_type VARCHAR(32) NOT NULL,
  secret_cipher VARCHAR(512) NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  verified_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_admin_mfa_type UNIQUE (user_id, mfa_type),
  CONSTRAINT fk_admin_mfa_user FOREIGN KEY (user_id) REFERENCES payment_admin_user(id) ON DELETE CASCADE
);

CREATE TABLE channel_transaction (
  id BIGINT NOT NULL AUTO_INCREMENT,
  channel_transaction_no VARCHAR(80) NOT NULL,
  merchant_id BIGINT NOT NULL,
  merchant_request_no VARCHAR(100) NOT NULL,
  channel VARCHAR(30) NOT NULL,
  subject VARCHAR(200) NOT NULL,
  amount DECIMAL(19,2) NOT NULL,
  currency VARCHAR(3) NOT NULL,
  status VARCHAR(30) NOT NULL,
  fault_scenario VARCHAR(40) NOT NULL DEFAULT 'NONE',
  checkout_token VARCHAR(64) NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  paid_at TIMESTAMP(6) NULL,
  closed_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_channel_transaction_no UNIQUE (channel_transaction_no),
  CONSTRAINT uk_merchant_request UNIQUE (merchant_id, merchant_request_no),
  CONSTRAINT uk_checkout_token UNIQUE (checkout_token),
  CONSTRAINT fk_channel_transaction_merchant FOREIGN KEY (merchant_id) REFERENCES payment_merchant(id)
);

CREATE TABLE payment_platform_key (
  id BIGINT NOT NULL,
  key_version INT NOT NULL,
  public_key TEXT NOT NULL,
  private_key_cipher TEXT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id)
);

CREATE TABLE payment_admin_audit_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  actor_user_id BIGINT NULL,
  actor_username VARCHAR(64) NOT NULL,
  action VARCHAR(80) NOT NULL,
  target_type VARCHAR(50) NOT NULL,
  target_id VARCHAR(100) NULL,
  result VARCHAR(20) NOT NULL,
  detail VARCHAR(500) NOT NULL,
  source_ip VARCHAR(64) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY ix_payment_audit_created_at (created_at),
  KEY ix_payment_audit_actor (actor_user_id)
);

INSERT INTO payment_admin_role (role_code, role_name) VALUES
  ('PAYMENT_ADMIN', '支付平台管理员'),
  ('PAYMENT_OPERATOR', '支付运营员'),
  ('PAYMENT_AUDITOR', '支付审计员');

INSERT INTO payment_admin_user
  (id, username, password_hash, display_name, email, status, mfa_required, created_by, updated_by)
VALUES
  (1, 'payment-admin', '$2a$10$.vOuqtr8OlOAWivaL/d9te.Y3JkyUNC.T1cBG3eHPG/U8zv0e1Gg6', '支付平台管理员', 'payment-admin@example.com', 'ACTIVE', TRUE, NULL, NULL),
  (2, 'payment-operator', '$2a$10$.vOuqtr8OlOAWivaL/d9te.Y3JkyUNC.T1cBG3eHPG/U8zv0e1Gg6', '支付运营员', 'payment-operator@example.com', 'ACTIVE', FALSE, 1, 1),
  (3, 'payment-auditor', '$2a$10$.vOuqtr8OlOAWivaL/d9te.Y3JkyUNC.T1cBG3eHPG/U8zv0e1Gg6', '支付审计员', 'payment-auditor@example.com', 'ACTIVE', FALSE, 1, 1);

UPDATE payment_admin_user SET created_by = 1, updated_by = 1 WHERE id = 1;

INSERT INTO payment_admin_user_role (user_id, role_id)
SELECT 1, id FROM payment_admin_role WHERE role_code = 'PAYMENT_ADMIN';
INSERT INTO payment_admin_user_role (user_id, role_id)
SELECT 2, id FROM payment_admin_role WHERE role_code = 'PAYMENT_OPERATOR';
INSERT INTO payment_admin_user_role (user_id, role_id)
SELECT 3, id FROM payment_admin_role WHERE role_code = 'PAYMENT_AUDITOR';

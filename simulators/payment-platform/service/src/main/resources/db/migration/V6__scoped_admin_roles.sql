ALTER TABLE payment_admin_role
  ADD COLUMN role_scope VARCHAR(16) NOT NULL DEFAULT 'PLATFORM' COMMENT '平台角色或商户角色',
  ADD COLUMN description VARCHAR(255) NOT NULL DEFAULT '' COMMENT '角色说明',
  ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '角色是否全局生效',
  ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 COMMENT '角色权限版本',
  ADD COLUMN system_role BOOLEAN NOT NULL DEFAULT FALSE COMMENT '受保护的内置角色';

ALTER TABLE payment_admin_user_role
  ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '账号的角色绑定是否生效';

CREATE TABLE payment_admin_role_permission (
  role_id BIGINT NOT NULL,
  permission_code VARCHAR(64) NOT NULL COMMENT '后端权限项编码',
  PRIMARY KEY (role_id, permission_code),
  FOREIGN KEY (role_id) REFERENCES payment_admin_role(id) ON DELETE CASCADE
);

CREATE TABLE payment_admin_user_merchant_role (
  user_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '账号在该商户的角色绑定是否生效',
  PRIMARY KEY (user_id, merchant_id, role_id),
  FOREIGN KEY (user_id) REFERENCES payment_admin_user(id) ON DELETE CASCADE,
  FOREIGN KEY (merchant_id) REFERENCES payment_merchant(id),
  FOREIGN KEY (role_id) REFERENCES payment_admin_role(id)
);

UPDATE payment_admin_role SET system_role = TRUE
WHERE role_code IN ('PAYMENT_ADMIN', 'PAYMENT_OPERATOR', 'PAYMENT_AUDITOR');

INSERT INTO payment_admin_role (role_code, role_name, role_scope, system_role) VALUES
  ('MERCHANT_READ_ONLY', '商户只读', 'MERCHANT', TRUE),
  ('MERCHANT_OPERATE', '商户运营员', 'MERCHANT', TRUE),
  ('MERCHANT_REFUND', '商户退款员', 'MERCHANT', TRUE),
  ('MERCHANT_AUDIT', '商户审计员', 'MERCHANT', TRUE),
  ('MERCHANT_ADMIN', '商户管理员', 'MERCHANT', TRUE);

INSERT INTO payment_admin_role_permission (role_id, permission_code)
SELECT id, 'ADMIN_USER_MANAGE' FROM payment_admin_role WHERE role_code = 'PAYMENT_ADMIN'
UNION ALL SELECT id, 'ROLE_MANAGE' FROM payment_admin_role WHERE role_code = 'PAYMENT_ADMIN'
UNION ALL SELECT id, 'MERCHANT_MANAGE' FROM payment_admin_role WHERE role_code = 'PAYMENT_ADMIN'
UNION ALL SELECT id, 'PAYOUT_READ' FROM payment_admin_role WHERE role_scope = 'PLATFORM' AND system_role = TRUE
UNION ALL SELECT id, 'AUDIT_READ' FROM payment_admin_role WHERE role_code IN ('PAYMENT_ADMIN', 'PAYMENT_AUDITOR')
UNION ALL SELECT id, 'TRANSACTION_READ' FROM payment_admin_role WHERE system_role = TRUE
UNION ALL SELECT id, 'REFUND_READ' FROM payment_admin_role WHERE system_role = TRUE
UNION ALL SELECT id, 'TRANSACTION_CLOSE' FROM payment_admin_role
  WHERE role_code IN ('PAYMENT_ADMIN', 'MERCHANT_OPERATE', 'MERCHANT_ADMIN')
UNION ALL SELECT id, 'REFUND_OPERATE' FROM payment_admin_role
  WHERE role_code IN ('PAYMENT_ADMIN', 'MERCHANT_REFUND', 'MERCHANT_ADMIN');

INSERT INTO payment_admin_user_merchant_role (user_id, merchant_id, role_id)
SELECT legacy.user_id, legacy.merchant_id, r.id
FROM payment_admin_user_merchant legacy
JOIN payment_admin_role r ON r.role_code = CONCAT('MERCHANT_', legacy.permission_scope);

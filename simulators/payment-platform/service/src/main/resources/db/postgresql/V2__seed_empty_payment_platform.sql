-- Restored ledgers already contain the original users, roles and audit head.
DO $seed$
BEGIN
  IF EXISTS (SELECT 1 FROM payment_admin_user) THEN
    RETURN;
  END IF;

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

  INSERT INTO payment_audit_chain_head (id, last_hash) VALUES (1, repeat('0', 64));
  PERFORM setval(pg_get_serial_sequence('payment_admin_user', 'id'), 3);
END;
$seed$;

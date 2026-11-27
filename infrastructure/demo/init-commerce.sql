BEGIN;
SET LOCAL search_path TO commerceflow;
SELECT pg_advisory_xact_lock(hashtext('commerceflow.demo-accounts'));

INSERT INTO roles (code, description) VALUES
  ('CUSTOMER', 'Customer shopping and order permissions'),
  ('MERCHANT', 'Merchant order fulfillment permissions'),
  ('ADMIN', 'User and role administration permissions'),
  ('INVOICE_OPERATIONS', 'Invoice incident review and resolution')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app_users (id, email, password_hash, first_name, last_name, phone, role, enabled)
SELECT demo.id, demo.email, '$2a$10$.vOuqtr8OlOAWivaL/d9te.Y3JkyUNC.T1cBG3eHPG/U8zv0e1Gg6',
       demo.first_name, demo.last_name, demo.phone, demo.role, demo.enabled
FROM (VALUES
  (1, 'demo@example.com', 'Demo', 'Student', '+90 555 000 0001', 'CUSTOMER', TRUE),
  (2, 'ada@example.com', 'Ada', 'Lovelace', '+44 20 0000 0001', 'CUSTOMER', FALSE),
  (3, 'grace@example.com', 'Grace', 'Hopper', '+1 212 000 0002', 'CUSTOMER', FALSE),
  (4, 'alan@example.com', 'Alan', 'Turing', '+44 20 0000 0003', 'CUSTOMER', FALSE),
  (5, 'merchant@example.com', 'Demo', 'Merchant', '+86 138 0000 0002', 'MERCHANT', TRUE),
  (6, 'admin@example.com', 'Demo', 'Administrator', '+86 138 0000 0003', 'ADMIN', TRUE)
) AS demo(id,email,first_name,last_name,phone,role,enabled)
ON CONFLICT (email) DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM app_users u JOIN roles r ON r.code = u.role
WHERE u.id BETWEEN 1 AND 6
ON CONFLICT DO NOTHING;

INSERT INTO merchant_stores (merchant_id, name, description, status)
SELECT id, 'CommerceFlow Demo Store', 'CommerceFlow learning store', 'ACTIVE'
FROM app_users
WHERE email = 'merchant@example.com'
ON CONFLICT (merchant_id) DO NOTHING;

INSERT INTO fulfillment_write_admission (singleton_id, frozen, write_owner)
VALUES (1, TRUE, 'FULFILLMENT')
ON CONFLICT (singleton_id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('app_users','id'), MAX(id)) FROM app_users;
COMMIT;

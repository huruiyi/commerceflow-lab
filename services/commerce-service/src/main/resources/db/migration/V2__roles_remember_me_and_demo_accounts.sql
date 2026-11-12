CREATE TABLE roles (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(40) NOT NULL,
  description VARCHAR(160) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_roles_code UNIQUE (code)
);

CREATE TABLE user_roles (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id),
  CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES app_users (id),
  CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id)
);

CREATE TABLE persistent_logins (
  username VARCHAR(190) NOT NULL,
  series VARCHAR(64) NOT NULL,
  token VARCHAR(64) NOT NULL,
  last_used TIMESTAMP NOT NULL,
  PRIMARY KEY (series)
);

INSERT INTO roles (code, description) VALUES
  ('CUSTOMER', 'Customer shopping and order permissions'),
  ('MERCHANT', 'Merchant order fulfillment permissions'),
  ('ADMIN', 'User and role administration permissions');

UPDATE app_users SET role = 'CUSTOMER' WHERE email = 'demo@example.com';

INSERT INTO app_users (email, password_hash, first_name, last_name, phone, role, enabled)
SELECT 'merchant@example.com', '$2a$10$.vOuqtr8OlOAWivaL/d9te.Y3JkyUNC.T1cBG3eHPG/U8zv0e1Gg6',
       'Demo', 'Merchant', '+86 138 0000 0002', 'MERCHANT', TRUE
WHERE NOT EXISTS (SELECT 1 FROM app_users WHERE email = 'merchant@example.com');

INSERT INTO app_users (email, password_hash, first_name, last_name, phone, role, enabled)
SELECT 'admin@example.com', '$2a$10$.vOuqtr8OlOAWivaL/d9te.Y3JkyUNC.T1cBG3eHPG/U8zv0e1Gg6',
       'Demo', 'Administrator', '+86 138 0000 0003', 'ADMIN', TRUE
WHERE NOT EXISTS (SELECT 1 FROM app_users WHERE email = 'admin@example.com');

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM app_users u JOIN roles r ON r.code = 'CUSTOMER'
WHERE u.email = 'demo@example.com';

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM app_users u JOIN roles r ON r.code = 'MERCHANT'
WHERE u.email = 'merchant@example.com';

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM app_users u JOIN roles r ON r.code = 'ADMIN'
WHERE u.email = 'admin@example.com';

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM app_users u JOIN roles r ON r.code = 'CUSTOMER'
WHERE u.email IN ('ada@example.com', 'grace@example.com', 'alan@example.com');

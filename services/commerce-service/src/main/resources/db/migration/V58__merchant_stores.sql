CREATE TABLE merchant_stores (
  merchant_id BIGINT PRIMARY KEY COMMENT 'Stable merchant and one-store identifier used by order lines',
  name VARCHAR(120) NOT NULL COMMENT 'Current public store name',
  description VARCHAR(500) NOT NULL COMMENT 'Current public introduction',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING, ACTIVE, REJECTED or DISABLED',
  reviewed_by BIGINT NULL COMMENT 'Administrator responsible for the latest decision',
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_merchant_store_account FOREIGN KEY (merchant_id) REFERENCES app_users(id),
  CONSTRAINT ck_merchant_store_status CHECK (status IN ('PENDING','ACTIVE','REJECTED','DISABLED'))
);

INSERT INTO merchant_stores(merchant_id,name,description,status)
SELECT u.id, LEFT(CONCAT_WS(' ',u.first_name,u.last_name),120), '', 'ACTIVE'
FROM app_users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id
WHERE r.code='MERCHANT';

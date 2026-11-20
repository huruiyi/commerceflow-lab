INSERT INTO roles (code, description)
VALUES ('PAYMENT_REPORT_ALL', 'Read payment ledger reports for all commerce merchants');

CREATE TABLE payment_report_merchant_grants (
  account_id BIGINT NOT NULL COMMENT 'Platform administrator receiving scoped report access',
  merchant_id BIGINT NOT NULL COMMENT 'Commerce merchant whose payment reports can be read',
  PRIMARY KEY (account_id, merchant_id),
  CONSTRAINT fk_payment_report_grant_account FOREIGN KEY (account_id) REFERENCES app_users(id),
  CONSTRAINT fk_payment_report_grant_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_stores(merchant_id)
);

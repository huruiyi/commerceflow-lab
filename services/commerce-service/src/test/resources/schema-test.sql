CREATE TABLE app_users
(
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  email         VARCHAR(190) UNIQUE NOT NULL,
  password_hash VARCHAR(100)        NOT NULL,
  first_name    VARCHAR(80)         NOT NULL,
  last_name     VARCHAR(80)         NOT NULL,
  phone         VARCHAR(40)         NOT NULL,
  role          VARCHAR(30)         NOT NULL,
  enabled       BOOLEAN             NOT NULL
);
CREATE TABLE roles
(
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  code        VARCHAR(40) UNIQUE NOT NULL,
  description VARCHAR(160)       NOT NULL
);
CREATE TABLE user_roles
(
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id)
);
CREATE TABLE persistent_logins
(
  username  VARCHAR(190),
  series    VARCHAR(64) PRIMARY KEY,
  token     VARCHAR(64),
  last_used TIMESTAMP
);
CREATE TABLE products
(
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  name            VARCHAR(160),
  brief           VARCHAR(300),
  description     VARCHAR(2000),
  price           DECIMAL(12, 2),
  image_url       VARCHAR(500),
  thumbnail_url   VARCHAR(500),
  merchant_id     BIGINT,
  lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  business_version BIGINT NOT NULL DEFAULT 1,
  updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE catalog_event_outbox
(
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id         VARCHAR(100) NOT NULL UNIQUE,
  event_type       VARCHAR(50) NOT NULL,
  aggregate_id     BIGINT NOT NULL,
  schema_version   INT NOT NULL,
  business_version BIGINT NOT NULL,
  occurred_at      TIMESTAMP NOT NULL,
  correlation_id   VARCHAR(100) NOT NULL,
  causation_id     VARCHAR(100) NOT NULL,
  payload          CLOB NOT NULL,
  status           VARCHAR(20) NOT NULL DEFAULT 'NEW',
  attempts         INT NOT NULL DEFAULT 0,
  next_attempt_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error       VARCHAR(500),
  created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at     TIMESTAMP
);
CREATE TABLE product_inventory
(
  product_id      BIGINT PRIMARY KEY,
  merchant_id     BIGINT,
  product_name    VARCHAR(160) NOT NULL,
  thumbnail_url   VARCHAR(500) NOT NULL,
  unit_price      DECIMAL(12, 2) NOT NULL,
  catalog_lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  catalog_business_version BIGINT NOT NULL DEFAULT 1,
  stock_available INT NOT NULL DEFAULT 100,
  stock_reserved  INT NOT NULL DEFAULT 0,
  stock_sold      INT NOT NULL DEFAULT 0,
  updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE product_translations
(
  product_id  BIGINT,
  locale      VARCHAR(10),
  name        VARCHAR(160),
  brief       VARCHAR(300),
  description VARCHAR(2000),
  PRIMARY KEY (product_id, locale)
);
CREATE TABLE product_reviews
(
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_item_id BIGINT,
  product_id    BIGINT,
  customer_id   BIGINT,
  rating        INT,
  comment       VARCHAR(1000),
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE carts
(
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id             BIGINT UNIQUE NOT NULL,
  selected_address_id BIGINT,
  created_at          TIMESTAMP,
  updated_at          TIMESTAMP
);
CREATE TABLE cart_items
(
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  cart_id    BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  merchant_id BIGINT,
  product_name VARCHAR(160) NOT NULL,
  thumbnail_url VARCHAR(500) NOT NULL,
  unit_price DECIMAL(12, 2) NOT NULL,
  quantity   INT    NOT NULL,
  created_at TIMESTAMP,
  updated_at TIMESTAMP,
  UNIQUE (cart_id, product_id)
);
CREATE TABLE cart_repurchase_requests
(
  customer_id BIGINT NOT NULL,
  request_id CHAR(36) NOT NULL,
  order_id BIGINT NOT NULL,
  result_json CLOB NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (customer_id, request_id)
);
CREATE TABLE shipping_addresses
(
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id        BIGINT NOT NULL,
  recipient_name VARCHAR(100),
  phone          VARCHAR(40),
  country_code   VARCHAR(2),
  state_province VARCHAR(100),
  city           VARCHAR(100),
  district       VARCHAR(100),
  address_line1  VARCHAR(255),
  address_line2  VARCHAR(255),
  postal_code    VARCHAR(20),
  label          VARCHAR(40),
  is_default     BOOLEAN,
  created_at     TIMESTAMP,
  updated_at     TIMESTAMP
);
CREATE TABLE shop_orders
(
  id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_number           VARCHAR(64),
  customer_id            BIGINT    NOT NULL,
  status                 VARCHAR(40),
  inventory_status       VARCHAR(40) NOT NULL DEFAULT 'WAITING_RESERVATION',
  shipping_fee DECIMAL(12, 2) DEFAULT 0 NOT NULL,
  discount_amount DECIMAL(12, 2) DEFAULT 0 NOT NULL,
  product_subtotal DECIMAL(12, 2) GENERATED ALWAYS AS (total_amount + discount_amount - shipping_fee),
  total_amount           DECIMAL(12, 2),
  currency               VARCHAR(3),
  payment_due_at         TIMESTAMP NOT NULL,
  cancelled_at           TIMESTAMP,
  cancel_reason          VARCHAR(300),
  received_at            TIMESTAMP,
  completed_at           TIMESTAMP,
  logistics_delivered_at TIMESTAMP,
  confirmed_at           TIMESTAMP,
  shipped_at             TIMESTAMP,
  carrier                VARCHAR(100),
  tracking_number        VARCHAR(120),
  shipment_version       BIGINT DEFAULT 0 NOT NULL,
  created_at             TIMESTAMP,
  updated_at             TIMESTAMP
);
CREATE TABLE order_items
(
  discount_amount DECIMAL(12, 2) DEFAULT 0 NOT NULL,
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id      BIGINT NOT NULL,
  product_id    BIGINT,
  merchant_id   BIGINT,
  product_name  VARCHAR(160),
  thumbnail_url VARCHAR(500),
  unit_price    DECIMAL(12, 2),
  quantity      INT,
  subtotal      DECIMAL(12, 2)
);
CREATE TABLE order_shipping_addresses
(
  order_id       BIGINT PRIMARY KEY,
  recipient_name VARCHAR(100),
  phone          VARCHAR(40),
  country_code   VARCHAR(2),
  state_province VARCHAR(100),
  city           VARCHAR(100),
  district       VARCHAR(100),
  address_line1  VARCHAR(300),
  address_line2  VARCHAR(300),
  postal_code    VARCHAR(20),
  label          VARCHAR(40)
);
CREATE TABLE order_events
(
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id          BIGINT NOT NULL,
  actor_id          BIGINT,
  event_type        VARCHAR(50),
  from_status       VARCHAR(40),
  to_status         VARCHAR(40),
  detail            VARCHAR(500),
  trigger_source    VARCHAR(40) DEFAULT 'USER',
  scheduled_task_id BIGINT,
  created_at        TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE order_process_managers
(
  process_id          VARCHAR(100) PRIMARY KEY,
  order_id            BIGINT       NOT NULL,
  process_type        VARCHAR(50)  NOT NULL,
  status              VARCHAR(40)  NOT NULL,
  current_step        VARCHAR(50)  NOT NULL,
  waiting_for         VARCHAR(30)  NOT NULL,
  command_id          VARCHAR(100) NOT NULL UNIQUE,
  command_fingerprint VARCHAR(64)  NOT NULL,
  correlation_id      VARCHAR(100) NOT NULL,
  causation_id        VARCHAR(100) NOT NULL,
  traceparent         VARCHAR(100) NOT NULL,
  attempts            INT          NOT NULL DEFAULT 0,
  result_deadline_at  TIMESTAMP    NOT NULL,
  next_check_at       TIMESTAMP    NOT NULL,
  last_error          VARCHAR(500),
  recovered_by        VARCHAR(100),
  recovery_reason     VARCHAR(300),
  recovered_at        TIMESTAMP,
  version             BIGINT       NOT NULL DEFAULT 0,
  created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at        TIMESTAMP
);
CREATE TABLE order_command_outbox
(
  command_id         VARCHAR(100) PRIMARY KEY,
  process_id         VARCHAR(100) NOT NULL,
  order_id           BIGINT       NOT NULL,
  target_service     VARCHAR(50)  NOT NULL,
  command_type       VARCHAR(50)  NOT NULL,
  payload_fingerprint VARCHAR(64) NOT NULL,
  payload            CLOB         NOT NULL,
  status             VARCHAR(30)  NOT NULL DEFAULT 'NEW',
  attempts           INT          NOT NULL DEFAULT 0,
  next_attempt_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  result_deadline_at TIMESTAMP    NOT NULL,
  lease_until        TIMESTAMP,
  last_error         VARCHAR(500),
  correlation_id     VARCHAR(100) NOT NULL,
  causation_id       VARCHAR(100) NOT NULL,
  traceparent        VARCHAR(100) NOT NULL,
  created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  acknowledged_at    TIMESTAMP
);
CREATE TABLE order_result_inbox
(
  result_id           VARCHAR(100) PRIMARY KEY,
  process_id          VARCHAR(100),
  order_id            BIGINT       NOT NULL,
  result_type         VARCHAR(30)  NOT NULL,
  outcome             VARCHAR(30)  NOT NULL,
  payload_fingerprint VARCHAR(64)  NOT NULL,
  payload             CLOB         NOT NULL,
  processing_status   VARCHAR(30)  NOT NULL,
  error_detail        VARCHAR(500),
  correlation_id      VARCHAR(100) NOT NULL,
  causation_id        VARCHAR(100) NOT NULL,
  traceparent         VARCHAR(100) NOT NULL,
  received_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  processed_at        TIMESTAMP
);
CREATE TABLE inventory_reservations
(
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id     BIGINT      NOT NULL,
  product_id   BIGINT      NOT NULL,
  quantity     INT         NOT NULL,
  status       VARCHAR(24) NOT NULL,
  version      INT         NOT NULL DEFAULT 0,
  reserved_at  TIMESTAMP            DEFAULT CURRENT_TIMESTAMP,
  committed_at TIMESTAMP,
  released_at  TIMESTAMP,
  UNIQUE (order_id, product_id)
);
CREATE TABLE inventory_movements
(
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id        VARCHAR(100) NOT NULL UNIQUE,
  order_id        BIGINT,
  product_id      BIGINT       NOT NULL,
  movement_type   VARCHAR(32)  NOT NULL,
  quantity        INT          NOT NULL,
  available_after INT          NOT NULL,
  reserved_after  INT          NOT NULL,
  sold_after      INT          NOT NULL,
  detail          VARCHAR(300) NOT NULL,
  created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE inventory_command_inbox
(
  command_id         VARCHAR(100) PRIMARY KEY,
  command_type       VARCHAR(24) NOT NULL,
  order_id           BIGINT      NOT NULL,
  payload_fingerprint CHAR(64)   NOT NULL,
  status             VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
  created_at         TIMESTAMP            DEFAULT CURRENT_TIMESTAMP,
  processed_at       TIMESTAMP
);
CREATE TABLE inventory_command_delivery_outbox
(
  command_id         VARCHAR(100) PRIMARY KEY,
  command_type       VARCHAR(24)  NOT NULL,
  producer           VARCHAR(50)  NOT NULL,
  order_id           BIGINT       NOT NULL,
  payload_fingerprint CHAR(64)    NOT NULL,
  payload            CLOB         NOT NULL,
  status             VARCHAR(30)  NOT NULL DEFAULT 'NEW',
  attempts           INT          NOT NULL DEFAULT 0,
  next_attempt_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  result_deadline_at TIMESTAMP,
  last_error         VARCHAR(500),
  recovered_by       VARCHAR(100),
  recovery_reason    VARCHAR(300),
  recovered_at       TIMESTAMP,
  created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  acknowledged_at    TIMESTAMP
);
CREATE TABLE inventory_command_result_inbox
(
  result_id      VARCHAR(100) PRIMARY KEY,
  command_id     VARCHAR(100) NOT NULL UNIQUE,
  command_type   VARCHAR(24)  NOT NULL,
  outcome        VARCHAR(20)  NOT NULL,
  occurred_at    TIMESTAMP    NOT NULL,
  error_code     VARCHAR(80),
  message        VARCHAR(500) NOT NULL,
  correlation_id VARCHAR(100) NOT NULL,
  causation_id   VARCHAR(100) NOT NULL,
  traceparent    VARCHAR(55)  NOT NULL,
  received_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE inventory_catalog_event_inbox
(
  event_id            VARCHAR(100) PRIMARY KEY,
  product_id          BIGINT      NOT NULL,
  business_version    BIGINT      NOT NULL,
  payload_fingerprint CHAR(64)    NOT NULL,
  status              VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
  created_at          TIMESTAMP            DEFAULT CURRENT_TIMESTAMP,
  processed_at        TIMESTAMP
);
CREATE TABLE customer_notifications
(
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id          VARCHAR(100) NOT NULL UNIQUE,
  customer_id       BIGINT       NOT NULL,
  order_id          BIGINT,
  notification_type VARCHAR(50)  NOT NULL,
  title             VARCHAR(160) NOT NULL,
  message           VARCHAR(500) NOT NULL,
  status            VARCHAR(20)  NOT NULL DEFAULT 'UNREAD',
  read_at           TIMESTAMP,
  created_at        TIMESTAMP             DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE notification_inbox
(
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  consumer_name  VARCHAR(100) NOT NULL,
  event_id       VARCHAR(100) NOT NULL,
  event_type     VARCHAR(80)  NOT NULL,
  schema_version INT          NOT NULL,
  correlation_id VARCHAR(100),
  causation_id    VARCHAR(100),
  traceparent     VARCHAR(55),
  processed_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (consumer_name, event_id)
);
CREATE TABLE notification_delivery_outbox
(
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id        VARCHAR(100) NOT NULL UNIQUE,
  topic           VARCHAR(100) NOT NULL,
  payload         VARCHAR(4000) NOT NULL,
  status          VARCHAR(20)  NOT NULL DEFAULT 'NEW',
  attempts        INT          NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP             DEFAULT CURRENT_TIMESTAMP,
  last_error      VARCHAR(500),
  created_at      TIMESTAMP             DEFAULT CURRENT_TIMESTAMP,
  published_at    TIMESTAMP,
  processed_at    TIMESTAMP
);
CREATE TABLE notification_projection_windows
(
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  status                VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
  start_notification_id BIGINT      NOT NULL,
  started_at            TIMESTAMP            DEFAULT CURRENT_TIMESTAMP,
  ready_at              TIMESTAMP,
  stopped_at            TIMESTAMP
);
CREATE TABLE notification_projection_shadow
(
  window_id        BIGINT       NOT NULL,
  event_id         VARCHAR(100) NOT NULL,
  customer_id      BIGINT       NOT NULL,
  order_id         BIGINT,
  notification_type VARCHAR(50) NOT NULL,
  title            VARCHAR(160) NOT NULL,
  message          VARCHAR(500) NOT NULL,
  correlation_id   VARCHAR(100) NOT NULL,
  causation_id      VARCHAR(100) NOT NULL,
  traceparent       VARCHAR(55)  NOT NULL,
  projected_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (window_id, event_id)
);
CREATE TABLE notification_projection_audits
(
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  window_id             BIGINT  NOT NULL,
  matched_count         INT     NOT NULL,
  missing_shadow_count  INT     NOT NULL,
  orphan_shadow_count   INT     NOT NULL,
  mismatch_count        INT     NOT NULL,
  blocking_outbox_count INT     NOT NULL,
  passed                BOOLEAN NOT NULL,
  checked_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE payment_merchant_accounts
(
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  channel     VARCHAR(30),
  merchant_no VARCHAR(64),
  enabled     BOOLEAN
);
CREATE TABLE payment_idempotency_locks
(
  idempotency_key VARCHAR(80) PRIMARY KEY,
  created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE payment_records
(
  id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id               BIGINT NOT NULL,
  merchant_account_id    BIGINT,
  payment_number         VARCHAR(40),
  idempotency_key        VARCHAR(80),
  channel                VARCHAR(30),
  currency               VARCHAR(3),
  request_fingerprint    VARCHAR(64),
  channel_transaction_no VARCHAR(80),
  amount                 DECIMAL(12, 2),
  refunded_amount        DECIMAL(12, 2) DEFAULT 0,
  status                 VARCHAR(30),
  failure_reason         VARCHAR(300),
  paid_at                TIMESTAMP,
  expires_at             TIMESTAMP,
  checkout_url           VARCHAR(500),
  action_type            VARCHAR(30),
  qr_payload             VARCHAR(500),
  version                INT            DEFAULT 0,
  created_at             TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
  updated_at             TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE payment_events
(
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  payment_id  BIGINT,
  event_id    VARCHAR(36),
  event_type  VARCHAR(50),
  from_status VARCHAR(30),
  to_status   VARCHAR(30),
  detail      VARCHAR(500),
  created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE payment_notifications
(
  id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
  payment_id             BIGINT       NOT NULL,
  channel                VARCHAR(30)  NOT NULL,
  notification_id        VARCHAR(100) NOT NULL,
  channel_transaction_no VARCHAR(80)  NOT NULL,
  signature_valid        BOOLEAN      NOT NULL,
  processing_status      VARCHAR(30)  NOT NULL,
  payload_digest         VARCHAR(64)  NOT NULL,
  failure_reason         VARCHAR(300) NOT NULL,
  raw_status             VARCHAR(30)  NOT NULL,
  received_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  processed_at           TIMESTAMP,
  UNIQUE (channel, notification_id)
);
CREATE TABLE payment_outbox
(
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id        VARCHAR(36)  NOT NULL UNIQUE,
  aggregate_type  VARCHAR(50)  NOT NULL,
  aggregate_id    VARCHAR(80)  NOT NULL,
  event_type      VARCHAR(80)  NOT NULL,
  topic           VARCHAR(100) NOT NULL,
  payload         JSON         NOT NULL,
  status          VARCHAR(20)  NOT NULL DEFAULT 'NEW',
  attempts        INT          NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at    TIMESTAMP
);
CREATE TABLE kafka_consumed_event
(
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  consumer_group VARCHAR(100) NOT NULL,
  event_id       VARCHAR(36)  NOT NULL,
  topic          VARCHAR(100) NOT NULL,
  consumed_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (consumer_group, event_id)
);
CREATE TABLE payment_process_managers
(
  process_id          VARCHAR(100) PRIMARY KEY,
  order_id            BIGINT       NOT NULL,
  process_type        VARCHAR(50)  NOT NULL,
  status              VARCHAR(40)  NOT NULL,
  command_id          VARCHAR(100) NOT NULL UNIQUE,
  command_fingerprint VARCHAR(64)  NOT NULL,
  correlation_id      VARCHAR(100) NOT NULL,
  causation_id        VARCHAR(100) NOT NULL,
  traceparent         VARCHAR(100) NOT NULL,
  result_deadline_at  TIMESTAMP    NOT NULL,
  next_check_at       TIMESTAMP    NOT NULL,
  last_error          VARCHAR(500),
  recovered_by        VARCHAR(100),
  recovery_reason     VARCHAR(300),
  recovered_at        TIMESTAMP,
  version             BIGINT       NOT NULL DEFAULT 0,
  created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at        TIMESTAMP
);
CREATE TABLE payment_command_outbox
(
  command_id          VARCHAR(100) PRIMARY KEY,
  process_id          VARCHAR(100) NOT NULL,
  order_id            BIGINT       NOT NULL,
  command_type        VARCHAR(50)  NOT NULL,
  payload_fingerprint VARCHAR(64)  NOT NULL,
  payload             CLOB         NOT NULL,
  status              VARCHAR(30)  NOT NULL DEFAULT 'NEW',
  attempts            INT          NOT NULL DEFAULT 0,
  next_attempt_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  result_deadline_at  TIMESTAMP    NOT NULL,
  last_error          VARCHAR(500),
  correlation_id      VARCHAR(100) NOT NULL,
  causation_id        VARCHAR(100) NOT NULL,
  traceparent         VARCHAR(100) NOT NULL,
  created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  acknowledged_at     TIMESTAMP
);
CREATE TABLE payment_result_inbox
(
  result_id           VARCHAR(100) PRIMARY KEY,
  process_id          VARCHAR(100),
  order_id            BIGINT       NOT NULL,
  result_type         VARCHAR(40)  NOT NULL,
  outcome             VARCHAR(40)  NOT NULL,
  payload_fingerprint VARCHAR(64)  NOT NULL,
  payload             CLOB         NOT NULL,
  processing_status   VARCHAR(30)  NOT NULL,
  error_detail        VARCHAR(500),
  correlation_id      VARCHAR(100) NOT NULL,
  causation_id        VARCHAR(100) NOT NULL,
  traceparent         VARCHAR(100) NOT NULL,
  received_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  processed_at        TIMESTAMP
);
CREATE TABLE order_effect_outbox
(
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id        VARCHAR(36)  NOT NULL UNIQUE,
  aggregate_id    VARCHAR(80)  NOT NULL,
  event_type      VARCHAR(80)  NOT NULL,
  topic           VARCHAR(100) NOT NULL,
  payload         JSON         NOT NULL,
  status          VARCHAR(20)  NOT NULL DEFAULT 'NEW',
  attempts        INT          NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at    TIMESTAMP
);
CREATE TABLE order_effect_inbox
(
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  consumer_group VARCHAR(100) NOT NULL,
  event_id       VARCHAR(36)  NOT NULL,
  topic          VARCHAR(100) NOT NULL,
  processed_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (consumer_group, event_id)
);
CREATE TABLE refund_records
(
  id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
  refund_number              VARCHAR(40)    NOT NULL UNIQUE,
  order_id                   BIGINT         NOT NULL,
  payment_id                 BIGINT         NOT NULL UNIQUE,
  customer_id                BIGINT         NOT NULL,
  idempotency_key            VARCHAR(80)    NOT NULL,
  request_fingerprint        VARCHAR(64)    NOT NULL,
  amount                     DECIMAL(12, 2) NOT NULL,
  currency                   VARCHAR(3)     NOT NULL,
  reason_code                VARCHAR(50)    NOT NULL,
  reason_detail              VARCHAR(500)   NOT NULL,
  status                     VARCHAR(30)    NOT NULL,
  inventory_status           VARCHAR(40)    NOT NULL DEFAULT 'NOT_STARTED',
  order_status_before_refund VARCHAR(40)    NOT NULL,
  channel_refund_no          VARCHAR(100),
  failure_reason             VARCHAR(300)   NOT NULL DEFAULT '',
  requested_at               TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  succeeded_at               TIMESTAMP,
  failed_at                  TIMESTAMP,
  created_at                 TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                 TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  version                    INT            NOT NULL DEFAULT 0,
  UNIQUE (payment_id, idempotency_key)
);
CREATE TABLE refund_events
(
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  refund_id   BIGINT       NOT NULL,
  event_id    VARCHAR(36)  NOT NULL UNIQUE,
  event_type  VARCHAR(50)  NOT NULL,
  from_status VARCHAR(30)  NOT NULL,
  to_status   VARCHAR(30)  NOT NULL,
  detail      VARCHAR(500) NOT NULL,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE refund_notifications
(
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  refund_id         BIGINT,
  channel           VARCHAR(30)  NOT NULL,
  notification_id   VARCHAR(100) NOT NULL,
  channel_refund_no VARCHAR(100) NOT NULL,
  signature_valid   BOOLEAN      NOT NULL,
  processing_status VARCHAR(30)  NOT NULL,
  payload_digest    VARCHAR(64)  NOT NULL,
  failure_reason    VARCHAR(300) NOT NULL,
  received_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  processed_at      TIMESTAMP,
  UNIQUE (channel, notification_id)
);
CREATE TABLE shipment_records
(
  id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id             BIGINT       NOT NULL UNIQUE,
  merchant_shipment_no VARCHAR(100) NOT NULL UNIQUE,
  platform_shipment_no VARCHAR(100) UNIQUE,
  carrier              VARCHAR(100) NOT NULL,
  tracking_number      VARCHAR(120) NOT NULL,
  shipment_version     BIGINT DEFAULT 0 NOT NULL,
  status               VARCHAR(40)  NOT NULL,
  shipped_at           TIMESTAMP,
  delivered_at         TIMESTAMP,
  last_reconciled_at   TIMESTAMP,
  reconciliation_error VARCHAR(1000),
  created_at           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE shipment_events
(
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  shipment_id BIGINT       NOT NULL,
  event_id    VARCHAR(36)  NOT NULL UNIQUE,
  event_type  VARCHAR(30)  NOT NULL DEFAULT 'PROGRESS',
  status      VARCHAR(40)  NOT NULL,
  location    VARCHAR(160) NOT NULL,
  description VARCHAR(300) NOT NULL,
  occurred_at TIMESTAMP    NOT NULL,
  created_at  TIMESTAMP             DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE shipment_notifications
(
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  shipment_id       BIGINT,
  notification_id   VARCHAR(100) NOT NULL UNIQUE,
  signature_valid   BOOLEAN      NOT NULL,
  processing_status VARCHAR(30)  NOT NULL,
  payload_digest    VARCHAR(64)  NOT NULL,
  failure_reason    VARCHAR(300) NOT NULL,
  received_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  processed_at      TIMESTAMP
);
CREATE TABLE shedlock
(
  name       VARCHAR(64) PRIMARY KEY,
  lock_until TIMESTAMP,
  locked_at  TIMESTAMP,
  locked_by  VARCHAR(255)
);
CREATE TABLE scheduled_task_policies
(
  task_type              VARCHAR(64) PRIMARY KEY,
  enabled                BOOLEAN      NOT NULL,
  primary_trigger        VARCHAR(20)  NOT NULL,
  reconciliation_enabled BOOLEAN      NOT NULL,
  grace_period_seconds   BIGINT       NOT NULL,
  claim_lease_seconds    BIGINT       NOT NULL,
  max_attempts           INT          NOT NULL,
  retry_delay_seconds    BIGINT       NOT NULL,
  config_version         BIGINT       NOT NULL DEFAULT 1,
  updated_by             VARCHAR(190) NOT NULL,
  created_at             TIMESTAMP             DEFAULT CURRENT_TIMESTAMP,
  updated_at             TIMESTAMP             DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE scheduled_tasks
(
  id                              BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_key                        VARCHAR(128) NOT NULL,
  task_type                       VARCHAR(64)  NOT NULL,
  aggregate_type                  VARCHAR(64)  NOT NULL,
  aggregate_id                    VARCHAR(128) NOT NULL,
  payload_json                    CLOB         NOT NULL,
  status                          VARCHAR(24)  NOT NULL,
  due_at                          TIMESTAMP    NOT NULL,
  primary_trigger_snapshot        VARCHAR(20)  NOT NULL,
  reconciliation_enabled_snapshot BOOLEAN      NOT NULL,
  grace_period_seconds_snapshot   BIGINT       NOT NULL,
  claim_lease_seconds_snapshot    BIGINT       NOT NULL,
  max_attempts                    INT          NOT NULL,
  retry_delay_seconds_snapshot    BIGINT       NOT NULL,
  policy_version_snapshot         BIGINT       NOT NULL,
  attempts                        INT          NOT NULL DEFAULT 0,
  lease_until                     TIMESTAMP,
  claimed_by                      VARCHAR(190),
  last_trigger_source             VARCHAR(32),
  trigger_published_at            TIMESTAMP,
  trigger_publish_attempts        INT                   DEFAULT 0,
  trigger_last_error              VARCHAR(1000),
  last_error                      VARCHAR(1000),
  completed_at                    TIMESTAMP,
  cancelled_at                    TIMESTAMP,
  version                         INT          NOT NULL DEFAULT 0,
  created_at                      TIMESTAMP             DEFAULT CURRENT_TIMESTAMP,
  updated_at                      TIMESTAMP             DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (task_type, task_key)
);
CREATE TABLE scheduled_task_events
(
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id        BIGINT        NOT NULL,
  event_type     VARCHAR(40)   NOT NULL,
  trigger_source VARCHAR(32)   NOT NULL,
  from_status    VARCHAR(24),
  to_status      VARCHAR(24),
  detail         VARCHAR(1000) NOT NULL,
  created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE scheduled_task_policy_events
(
  id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_type              VARCHAR(64)  NOT NULL,
  action                 VARCHAR(24)  NOT NULL,
  enabled                BOOLEAN      NOT NULL,
  primary_trigger        VARCHAR(20)  NOT NULL,
  reconciliation_enabled BOOLEAN      NOT NULL,
  grace_period_seconds   BIGINT       NOT NULL,
  claim_lease_seconds    BIGINT       NOT NULL,
  max_attempts           INT          NOT NULL,
  retry_delay_seconds    BIGINT       NOT NULL,
  config_version         BIGINT       NOT NULL,
  operated_by            VARCHAR(190) NOT NULL,
  created_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO app_users(email, password_hash, first_name, last_name, phone, role, enabled)
VALUES ('demo@example.com', '$2a$10$.vOuqtr8OlOAWivaL/d9te.Y3JkyUNC.T1cBG3eHPG/U8zv0e1Gg6', 'Demo', 'Student',
        '13800000000', 'CUSTOMER', TRUE);
INSERT INTO app_users(email, password_hash, first_name, last_name, phone, role, enabled)
VALUES ('admin@example.com', '$2a$10$.vOuqtr8OlOAWivaL/d9te.Y3JkyUNC.T1cBG3eHPG/U8zv0e1Gg6', 'Admin', 'User',
        '13800000002', 'ADMIN', TRUE);
INSERT INTO app_users(email, password_hash, first_name, last_name, phone, role, enabled)
VALUES ('merchant@example.com', '$2a$10$.vOuqtr8OlOAWivaL/d9te.Y3JkyUNC.T1cBG3eHPG/U8zv0e1Gg6', 'Demo', 'Merchant',
        '13800000004', 'MERCHANT', TRUE);
INSERT INTO app_users(email, password_hash, first_name, last_name, phone, role, enabled)
VALUES ('customer2@example.com', '$2a$10$.vOuqtr8OlOAWivaL/d9te.Y3JkyUNC.T1cBG3eHPG/U8zv0e1Gg6', 'Second', 'Customer',
        '13800000003', 'CUSTOMER', TRUE);
INSERT INTO roles(code, description)
VALUES ('CUSTOMER', 'Customer'),
       ('MERCHANT', 'Merchant'),
       ('ADMIN', 'Admin');
INSERT INTO user_roles(user_id, role_id)
SELECT u.id, r.id
FROM app_users u,
     roles r
WHERE u.email = 'demo@example.com'
  AND r.code = 'CUSTOMER';
INSERT INTO user_roles(user_id, role_id)
SELECT u.id, r.id
FROM app_users u,
     roles r
WHERE u.email = 'admin@example.com'
  AND r.code = 'ADMIN';
INSERT INTO user_roles(user_id, role_id)
SELECT u.id, r.id
FROM app_users u,
     roles r
WHERE u.email = 'merchant@example.com'
  AND r.code = 'MERCHANT';
INSERT INTO user_roles(user_id, role_id)
SELECT u.id, r.id
FROM app_users u,
     roles r
WHERE u.email = 'customer2@example.com'
  AND r.code = 'CUSTOMER';
INSERT INTO products(name, brief, description, price, image_url, thumbnail_url)
VALUES ('Learning Laptop', 'Coding laptop', 'Learning workstation', 1299, 'image', 'thumb');
UPDATE products
SET merchant_id=(SELECT id FROM app_users WHERE email='merchant@example.com')
WHERE id=1;
INSERT INTO product_inventory(
  product_id,merchant_id,product_name,thumbnail_url,unit_price,
  stock_available,stock_reserved,stock_sold
)
SELECT id,merchant_id,name,thumbnail_url,price,100,0,0 FROM products;
INSERT INTO product_translations(product_id, locale, name, brief, description)
VALUES (1, 'zh-CN', '学习笔记本电脑', '编程电脑', '学习工作站');

CREATE TABLE order_cancellation_completion (
  order_id BIGINT NOT NULL PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  actor_id BIGINT NULL,
  reason VARCHAR(300) NOT NULL,
  trigger_source VARCHAR(40) NOT NULL,
  scheduled_task_id BIGINT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  completed_at TIMESTAMP(6) NULL
);

CREATE TABLE fulfillment_write_admission (
  singleton_id TINYINT NOT NULL PRIMARY KEY,
  frozen BOOLEAN NOT NULL,
  write_owner VARCHAR(12) NOT NULL DEFAULT 'COMMERCE',
  CHECK (write_owner = 'COMMERCE' OR (write_owner = 'FULFILLMENT' AND frozen = TRUE))
);

INSERT INTO fulfillment_write_admission (singleton_id, frozen, write_owner) VALUES (1, TRUE, 'FULFILLMENT');

CREATE TABLE logistics_fulfillment_commands (
  command_id CHAR(36) NOT NULL PRIMARY KEY,
  operation VARCHAR(16) NOT NULL,
  order_id BIGINT NOT NULL,
  order_number VARCHAR(64),
  carrier VARCHAR(100) NOT NULL,
  tracking_number VARCHAR(120) NOT NULL,
  expected_version BIGINT,
  platform_shipment_no VARCHAR(80),
  shipment_version BIGINT,
  owner_token CHAR(36),
  lease_until TIMESTAMP(6)
);

CREATE TABLE merchant_shipping_rates (
  merchant_id BIGINT NOT NULL,
  country_code VARCHAR(2) NOT NULL,
  state_province VARCHAR(100) NOT NULL,
  fee DECIMAL(12,2) NOT NULL,
  PRIMARY KEY (merchant_id, country_code, state_province)
);

CREATE TABLE checkout_receipts (
  customer_id BIGINT NOT NULL,
  quote_id CHAR(64) NOT NULL,
  scope_hash CHAR(64) NOT NULL,
  order_id BIGINT NOT NULL UNIQUE,
  PRIMARY KEY (customer_id, quote_id)
);

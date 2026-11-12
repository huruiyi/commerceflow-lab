CREATE TABLE shipment_records (
  id BIGINT NOT NULL AUTO_INCREMENT,
  merchant_shipment_no VARCHAR(100) NOT NULL,
  platform_shipment_no VARCHAR(100) NOT NULL,
  order_number VARCHAR(64) NOT NULL,
  carrier VARCHAR(100) NOT NULL,
  tracking_number VARCHAR(120) NOT NULL,
  status VARCHAR(40) NOT NULL,
  callback_url VARCHAR(500) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(id),
  UNIQUE KEY uk_shipment_merchant_no(merchant_shipment_no),
  UNIQUE KEY uk_shipment_platform_no(platform_shipment_no),
  UNIQUE KEY uk_shipment_tracking(carrier,tracking_number)
);
CREATE TABLE shipment_events (
  id BIGINT NOT NULL AUTO_INCREMENT,
  shipment_id BIGINT NOT NULL,
  event_id VARCHAR(36) NOT NULL,
  status VARCHAR(40) NOT NULL,
  location VARCHAR(160) NOT NULL,
  description VARCHAR(300) NOT NULL,
  occurred_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(id),
  UNIQUE KEY uk_shipment_event(event_id),
  CONSTRAINT fk_shipment_event_record FOREIGN KEY(shipment_id) REFERENCES shipment_records(id)
);
CREATE TABLE signed_request_nonces (
  id BIGINT NOT NULL AUTO_INCREMENT,
  app_id VARCHAR(100) NOT NULL,
  nonce VARCHAR(100) NOT NULL,
  request_timestamp BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(id),
  UNIQUE KEY uk_logistics_nonce(app_id,nonce)
);

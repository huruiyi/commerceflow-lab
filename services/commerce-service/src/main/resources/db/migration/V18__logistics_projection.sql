CREATE TABLE shipment_records (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  merchant_shipment_no VARCHAR(100) NOT NULL,
  platform_shipment_no VARCHAR(100) NULL,
  carrier VARCHAR(100) NOT NULL,
  tracking_number VARCHAR(120) NOT NULL,
  status VARCHAR(40) NOT NULL,
  shipped_at TIMESTAMP(6) NULL,
  delivered_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(id),
  UNIQUE KEY uk_storefront_shipment_order(order_id),
  UNIQUE KEY uk_storefront_merchant_shipment(merchant_shipment_no),
  UNIQUE KEY uk_storefront_platform_shipment(platform_shipment_no),
  CONSTRAINT fk_storefront_shipment_order FOREIGN KEY(order_id) REFERENCES shop_orders(id)
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
  UNIQUE KEY uk_storefront_shipment_event(event_id),
  CONSTRAINT fk_storefront_shipment_event_record FOREIGN KEY(shipment_id) REFERENCES shipment_records(id)
);
CREATE TABLE shipment_notifications (
  id BIGINT NOT NULL AUTO_INCREMENT,
  shipment_id BIGINT NULL,
  notification_id VARCHAR(100) NOT NULL,
  signature_valid BOOLEAN NOT NULL,
  processing_status VARCHAR(30) NOT NULL,
  payload_digest VARCHAR(64) NOT NULL,
  failure_reason VARCHAR(300) NOT NULL,
  received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  processed_at TIMESTAMP(6) NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_storefront_shipment_notification(notification_id),
  CONSTRAINT fk_storefront_shipment_notification_record FOREIGN KEY(shipment_id) REFERENCES shipment_records(id)
);

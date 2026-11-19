ALTER TABLE shipment_records
  DROP INDEX uk_storefront_shipment_order,
  ADD COLUMN after_sale_id CHAR(36) NULL COMMENT 'Return case reference. NULL identifies an outbound shipment.',
  ADD COLUMN order_number VARCHAR(100) NULL COMMENT 'Order number for replaying a return registration.',
  ADD COLUMN last_event_at TIMESTAMP(6) NULL COMMENT 'Carrier time of the latest applied return tracking event.',
  ADD COLUMN outbound_order_id BIGINT GENERATED ALWAYS AS (
    CASE WHEN after_sale_id IS NULL THEN order_id END
  ) STORED,
  ADD COLUMN return_carrier VARCHAR(100) GENERATED ALWAYS AS (
    CASE WHEN after_sale_id IS NOT NULL THEN carrier END
  ) STORED,
  ADD UNIQUE KEY uk_storefront_shipment_order(outbound_order_id),
  ADD UNIQUE KEY uk_storefront_shipment_return(after_sale_id),
  ADD UNIQUE KEY uk_storefront_return_parcel(return_carrier, tracking_number),
  ADD KEY ix_storefront_shipment_tracking(carrier, tracking_number),
  ADD CONSTRAINT fk_storefront_return_case FOREIGN KEY(after_sale_id) REFERENCES line_after_sales(id);

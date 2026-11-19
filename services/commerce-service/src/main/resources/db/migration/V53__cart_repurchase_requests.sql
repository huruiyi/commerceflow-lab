CREATE TABLE cart_repurchase_requests (
  customer_id BIGINT NOT NULL COMMENT 'Owner of the cart and source order',
  request_id CHAR(36) NOT NULL COMMENT 'Client UUID for one repurchase attempt',
  order_id BIGINT NOT NULL COMMENT 'Authoritative source order identifier',
  result_json MEDIUMTEXT NOT NULL COMMENT 'Original outcome replayed for duplicate requests',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (customer_id, request_id)
) COMMENT='Transactional receipts for retrying cart repurchase';

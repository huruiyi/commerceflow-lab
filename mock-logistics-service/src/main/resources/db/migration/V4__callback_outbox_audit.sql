CREATE TABLE callback_outbox_events (
  id BIGINT NOT NULL AUTO_INCREMENT,
  outbox_id BIGINT NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  from_status VARCHAR(20) NULL,
  to_status VARCHAR(20) NOT NULL,
  operated_by VARCHAR(160) NOT NULL,
  reason VARCHAR(500) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(id),
  KEY idx_callback_outbox_events(outbox_id,created_at,id),
  CONSTRAINT fk_callback_outbox_audit FOREIGN KEY(outbox_id) REFERENCES callback_outbox(id)
);

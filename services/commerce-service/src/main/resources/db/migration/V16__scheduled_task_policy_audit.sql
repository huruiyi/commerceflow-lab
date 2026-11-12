CREATE TABLE scheduled_task_policy_events (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_type VARCHAR(64) NOT NULL,
  action VARCHAR(24) NOT NULL,
  enabled BOOLEAN NOT NULL,
  primary_trigger VARCHAR(20) NOT NULL,
  reconciliation_enabled BOOLEAN NOT NULL,
  grace_period_seconds BIGINT NOT NULL,
  claim_lease_seconds BIGINT NOT NULL,
  max_attempts INT NOT NULL,
  retry_delay_seconds BIGINT NOT NULL,
  config_version BIGINT NOT NULL,
  operated_by VARCHAR(190) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  INDEX idx_scheduled_policy_event_type (task_type, created_at)
);

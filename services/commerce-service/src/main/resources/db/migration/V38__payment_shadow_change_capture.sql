CREATE TABLE payment_shadow_change_clock (
  singleton_id TINYINT NOT NULL,
  last_sequence BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (singleton_id),
  CONSTRAINT chk_payment_shadow_clock_singleton CHECK (singleton_id = 1)
);

INSERT INTO payment_shadow_change_clock(singleton_id,last_sequence) VALUES (1,0);

CREATE TABLE payment_shadow_change_log (
  change_sequence BIGINT NOT NULL,
  table_name VARCHAR(50) NOT NULL,
  row_key VARCHAR(100) NOT NULL,
  operation VARCHAR(10) NOT NULL,
  changed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (change_sequence),
  KEY idx_payment_shadow_change_table (table_name,row_key,change_sequence),
  CONSTRAINT chk_payment_shadow_change_table CHECK (table_name IN (
    'payment_merchant_accounts','payment_records','payment_notifications','payment_events',
    'payment_idempotency_locks','payment_reconciliation_issue','payment_outbox',
    'kafka_consumed_event','refund_records','refund_events','refund_notifications',
    'payment_process_managers','payment_command_outbox','payment_result_inbox'
  )),
  CONSTRAINT chk_payment_shadow_change_operation CHECK (operation IN ('UPSERT','DELETE'))
);

DELIMITER $$
CREATE PROCEDURE record_payment_shadow_change(
  IN changed_table VARCHAR(50), IN changed_key VARCHAR(100), IN change_operation VARCHAR(10))
BEGIN
  UPDATE payment_shadow_change_clock
  SET last_sequence = LAST_INSERT_ID(last_sequence + 1)
  WHERE singleton_id = 1;
  INSERT INTO payment_shadow_change_log(change_sequence,table_name,row_key,operation)
  VALUES (LAST_INSERT_ID(),changed_table,changed_key,change_operation);
END$$

CREATE TRIGGER trg_pay_merchant_shadow_insert AFTER INSERT ON payment_merchant_accounts FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_merchant_accounts',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_pay_merchant_shadow_update AFTER UPDATE ON payment_merchant_accounts FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_merchant_accounts',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_pay_merchant_shadow_delete AFTER DELETE ON payment_merchant_accounts FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_merchant_accounts',CAST(OLD.id AS CHAR),'DELETE'); END$$

CREATE TRIGGER trg_pay_record_shadow_insert AFTER INSERT ON payment_records FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_records',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_pay_record_shadow_update AFTER UPDATE ON payment_records FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_records',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_pay_record_shadow_delete AFTER DELETE ON payment_records FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_records',CAST(OLD.id AS CHAR),'DELETE'); END$$

CREATE TRIGGER trg_pay_notice_shadow_insert AFTER INSERT ON payment_notifications FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_notifications',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_pay_notice_shadow_update AFTER UPDATE ON payment_notifications FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_notifications',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_pay_notice_shadow_delete AFTER DELETE ON payment_notifications FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_notifications',CAST(OLD.id AS CHAR),'DELETE'); END$$

CREATE TRIGGER trg_pay_event_shadow_insert AFTER INSERT ON payment_events FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_events',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_pay_event_shadow_update AFTER UPDATE ON payment_events FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_events',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_pay_event_shadow_delete AFTER DELETE ON payment_events FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_events',CAST(OLD.id AS CHAR),'DELETE'); END$$

CREATE TRIGGER trg_pay_idem_shadow_insert AFTER INSERT ON payment_idempotency_locks FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_idempotency_locks',NEW.idempotency_key,'UPSERT'); END$$
CREATE TRIGGER trg_pay_idem_shadow_update AFTER UPDATE ON payment_idempotency_locks FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_idempotency_locks',NEW.idempotency_key,'UPSERT'); END$$
CREATE TRIGGER trg_pay_idem_shadow_delete AFTER DELETE ON payment_idempotency_locks FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_idempotency_locks',OLD.idempotency_key,'DELETE'); END$$

CREATE TRIGGER trg_pay_recon_shadow_insert AFTER INSERT ON payment_reconciliation_issue FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_reconciliation_issue',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_pay_recon_shadow_update AFTER UPDATE ON payment_reconciliation_issue FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_reconciliation_issue',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_pay_recon_shadow_delete AFTER DELETE ON payment_reconciliation_issue FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_reconciliation_issue',CAST(OLD.id AS CHAR),'DELETE'); END$$

CREATE TRIGGER trg_pay_outbox_shadow_insert AFTER INSERT ON payment_outbox FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_outbox',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_pay_outbox_shadow_update AFTER UPDATE ON payment_outbox FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_outbox',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_pay_outbox_shadow_delete AFTER DELETE ON payment_outbox FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_outbox',CAST(OLD.id AS CHAR),'DELETE'); END$$

CREATE TRIGGER trg_pay_kafka_shadow_insert AFTER INSERT ON kafka_consumed_event FOR EACH ROW
BEGIN CALL record_payment_shadow_change('kafka_consumed_event',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_pay_kafka_shadow_update AFTER UPDATE ON kafka_consumed_event FOR EACH ROW
BEGIN CALL record_payment_shadow_change('kafka_consumed_event',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_pay_kafka_shadow_delete AFTER DELETE ON kafka_consumed_event FOR EACH ROW
BEGIN CALL record_payment_shadow_change('kafka_consumed_event',CAST(OLD.id AS CHAR),'DELETE'); END$$

CREATE TRIGGER trg_refund_record_shadow_insert AFTER INSERT ON refund_records FOR EACH ROW
BEGIN CALL record_payment_shadow_change('refund_records',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_refund_record_shadow_update AFTER UPDATE ON refund_records FOR EACH ROW
BEGIN CALL record_payment_shadow_change('refund_records',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_refund_record_shadow_delete AFTER DELETE ON refund_records FOR EACH ROW
BEGIN CALL record_payment_shadow_change('refund_records',CAST(OLD.id AS CHAR),'DELETE'); END$$

CREATE TRIGGER trg_refund_event_shadow_insert AFTER INSERT ON refund_events FOR EACH ROW
BEGIN CALL record_payment_shadow_change('refund_events',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_refund_event_shadow_update AFTER UPDATE ON refund_events FOR EACH ROW
BEGIN CALL record_payment_shadow_change('refund_events',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_refund_event_shadow_delete AFTER DELETE ON refund_events FOR EACH ROW
BEGIN CALL record_payment_shadow_change('refund_events',CAST(OLD.id AS CHAR),'DELETE'); END$$

CREATE TRIGGER trg_refund_notice_shadow_insert AFTER INSERT ON refund_notifications FOR EACH ROW
BEGIN CALL record_payment_shadow_change('refund_notifications',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_refund_notice_shadow_update AFTER UPDATE ON refund_notifications FOR EACH ROW
BEGIN CALL record_payment_shadow_change('refund_notifications',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_refund_notice_shadow_delete AFTER DELETE ON refund_notifications FOR EACH ROW
BEGIN CALL record_payment_shadow_change('refund_notifications',CAST(OLD.id AS CHAR),'DELETE'); END$$

CREATE TRIGGER trg_pay_process_shadow_insert AFTER INSERT ON payment_process_managers FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_process_managers',NEW.process_id,'UPSERT'); END$$
CREATE TRIGGER trg_pay_process_shadow_update AFTER UPDATE ON payment_process_managers FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_process_managers',NEW.process_id,'UPSERT'); END$$
CREATE TRIGGER trg_pay_process_shadow_delete AFTER DELETE ON payment_process_managers FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_process_managers',OLD.process_id,'DELETE'); END$$

CREATE TRIGGER trg_pay_command_shadow_insert AFTER INSERT ON payment_command_outbox FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_command_outbox',NEW.command_id,'UPSERT'); END$$
CREATE TRIGGER trg_pay_command_shadow_update AFTER UPDATE ON payment_command_outbox FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_command_outbox',NEW.command_id,'UPSERT'); END$$
CREATE TRIGGER trg_pay_command_shadow_delete AFTER DELETE ON payment_command_outbox FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_command_outbox',OLD.command_id,'DELETE'); END$$

CREATE TRIGGER trg_pay_result_shadow_insert AFTER INSERT ON payment_result_inbox FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_result_inbox',NEW.result_id,'UPSERT'); END$$
CREATE TRIGGER trg_pay_result_shadow_update AFTER UPDATE ON payment_result_inbox FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_result_inbox',NEW.result_id,'UPSERT'); END$$
CREATE TRIGGER trg_pay_result_shadow_delete AFTER DELETE ON payment_result_inbox FOR EACH ROW
BEGIN CALL record_payment_shadow_change('payment_result_inbox',OLD.result_id,'DELETE'); END$$
DELIMITER ;

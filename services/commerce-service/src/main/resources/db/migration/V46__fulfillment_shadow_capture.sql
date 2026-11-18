CREATE TABLE fulfillment_shadow_clock (
  singleton_id TINYINT NOT NULL PRIMARY KEY,
  last_sequence BIGINT NOT NULL,
  CHECK (singleton_id = 1)
);
INSERT INTO fulfillment_shadow_clock (singleton_id, last_sequence) VALUES (1, 0);

CREATE TABLE fulfillment_shadow_changes (
  change_sequence BIGINT NOT NULL PRIMARY KEY,
  table_name VARCHAR(64) NOT NULL,
  row_key VARCHAR(64) NOT NULL
);

DELIMITER $$
CREATE PROCEDURE record_fulfillment_shadow_change(IN changed_table VARCHAR(64), IN changed_key VARCHAR(64))
BEGIN
  UPDATE fulfillment_shadow_clock SET last_sequence = LAST_INSERT_ID(last_sequence + 1) WHERE singleton_id = 1;
  INSERT INTO fulfillment_shadow_changes VALUES (LAST_INSERT_ID(), changed_table, changed_key);
END$$

CREATE TRIGGER trg_fulfillment_shadow_1_insert AFTER INSERT ON fulfillment_order_locks FOR EACH ROW
BEGIN
  CALL record_fulfillment_shadow_change('fulfillment_order_locks', CAST(NEW.order_id AS CHAR));
END$$
CREATE TRIGGER trg_fulfillment_shadow_1_update AFTER UPDATE ON fulfillment_order_locks FOR EACH ROW
BEGIN
  IF NOT (OLD.order_id <=> NEW.order_id) THEN
    CALL record_fulfillment_shadow_change('fulfillment_order_locks', CAST(OLD.order_id AS CHAR));
  END IF;
  CALL record_fulfillment_shadow_change('fulfillment_order_locks', CAST(NEW.order_id AS CHAR));
END$$
CREATE TRIGGER trg_fulfillment_shadow_1_delete AFTER DELETE ON fulfillment_order_locks FOR EACH ROW
BEGIN
  CALL record_fulfillment_shadow_change('fulfillment_order_locks', CAST(OLD.order_id AS CHAR));
END$$

CREATE TRIGGER trg_fulfillment_shadow_2_insert AFTER INSERT ON fulfillment_command_ids FOR EACH ROW
BEGIN
  CALL record_fulfillment_shadow_change('fulfillment_command_ids', CAST(NEW.command_id AS CHAR));
END$$
CREATE TRIGGER trg_fulfillment_shadow_2_update AFTER UPDATE ON fulfillment_command_ids FOR EACH ROW
BEGIN
  IF NOT (OLD.command_id <=> NEW.command_id) THEN
    CALL record_fulfillment_shadow_change('fulfillment_command_ids', CAST(OLD.command_id AS CHAR));
  END IF;
  CALL record_fulfillment_shadow_change('fulfillment_command_ids', CAST(NEW.command_id AS CHAR));
END$$
CREATE TRIGGER trg_fulfillment_shadow_2_delete AFTER DELETE ON fulfillment_command_ids FOR EACH ROW
BEGIN
  CALL record_fulfillment_shadow_change('fulfillment_command_ids', CAST(OLD.command_id AS CHAR));
END$$

CREATE TRIGGER trg_fulfillment_shadow_3_insert AFTER INSERT ON fulfillment_shipment_jobs FOR EACH ROW
BEGIN
  CALL record_fulfillment_shadow_change('fulfillment_shipment_jobs', CAST(NEW.command_id AS CHAR));
END$$
CREATE TRIGGER trg_fulfillment_shadow_3_update AFTER UPDATE ON fulfillment_shipment_jobs FOR EACH ROW
BEGIN
  IF NOT (OLD.command_id <=> NEW.command_id) THEN
    CALL record_fulfillment_shadow_change('fulfillment_shipment_jobs', CAST(OLD.command_id AS CHAR));
  END IF;
  CALL record_fulfillment_shadow_change('fulfillment_shipment_jobs', CAST(NEW.command_id AS CHAR));
END$$
CREATE TRIGGER trg_fulfillment_shadow_3_delete AFTER DELETE ON fulfillment_shipment_jobs FOR EACH ROW
BEGIN
  CALL record_fulfillment_shadow_change('fulfillment_shipment_jobs', CAST(OLD.command_id AS CHAR));
END$$

CREATE TRIGGER trg_fulfillment_shadow_4_insert AFTER INSERT ON fulfillment_merchant_jobs FOR EACH ROW
BEGIN
  CALL record_fulfillment_shadow_change('fulfillment_merchant_jobs', CAST(NEW.command_id AS CHAR));
END$$
CREATE TRIGGER trg_fulfillment_shadow_4_update AFTER UPDATE ON fulfillment_merchant_jobs FOR EACH ROW
BEGIN
  IF NOT (OLD.command_id <=> NEW.command_id) THEN
    CALL record_fulfillment_shadow_change('fulfillment_merchant_jobs', CAST(OLD.command_id AS CHAR));
  END IF;
  CALL record_fulfillment_shadow_change('fulfillment_merchant_jobs', CAST(NEW.command_id AS CHAR));
END$$
CREATE TRIGGER trg_fulfillment_shadow_4_delete AFTER DELETE ON fulfillment_merchant_jobs FOR EACH ROW
BEGIN
  CALL record_fulfillment_shadow_change('fulfillment_merchant_jobs', CAST(OLD.command_id AS CHAR));
END$$

CREATE TRIGGER trg_fulfillment_shadow_5_insert AFTER INSERT ON fulfillment_shipment_recoveries FOR EACH ROW
BEGIN
  CALL record_fulfillment_shadow_change('fulfillment_shipment_recoveries', CAST(NEW.id AS CHAR));
END$$
CREATE TRIGGER trg_fulfillment_shadow_5_update AFTER UPDATE ON fulfillment_shipment_recoveries FOR EACH ROW
BEGIN
  IF NOT (OLD.id <=> NEW.id) THEN
    CALL record_fulfillment_shadow_change('fulfillment_shipment_recoveries', CAST(OLD.id AS CHAR));
  END IF;
  CALL record_fulfillment_shadow_change('fulfillment_shipment_recoveries', CAST(NEW.id AS CHAR));
END$$
CREATE TRIGGER trg_fulfillment_shadow_5_delete AFTER DELETE ON fulfillment_shipment_recoveries FOR EACH ROW
BEGIN
  CALL record_fulfillment_shadow_change('fulfillment_shipment_recoveries', CAST(OLD.id AS CHAR));
END$$

CREATE TRIGGER trg_fulfillment_shadow_6_insert AFTER INSERT ON fulfillment_merchant_recoveries FOR EACH ROW
BEGIN
  CALL record_fulfillment_shadow_change('fulfillment_merchant_recoveries', CAST(NEW.id AS CHAR));
END$$
CREATE TRIGGER trg_fulfillment_shadow_6_update AFTER UPDATE ON fulfillment_merchant_recoveries FOR EACH ROW
BEGIN
  IF NOT (OLD.id <=> NEW.id) THEN
    CALL record_fulfillment_shadow_change('fulfillment_merchant_recoveries', CAST(OLD.id AS CHAR));
  END IF;
  CALL record_fulfillment_shadow_change('fulfillment_merchant_recoveries', CAST(NEW.id AS CHAR));
END$$
CREATE TRIGGER trg_fulfillment_shadow_6_delete AFTER DELETE ON fulfillment_merchant_recoveries FOR EACH ROW
BEGIN
  CALL record_fulfillment_shadow_change('fulfillment_merchant_recoveries', CAST(OLD.id AS CHAR));
END$$
DELIMITER ;

CREATE VIEW fulfillment_shadow_rows AS
SELECT 'fulfillment_order_locks' AS table_name, CAST(order_id AS CHAR) AS row_key,
  JSON_OBJECT(
    'order_id', order_id
  ) AS payload
FROM fulfillment_order_locks
UNION ALL
SELECT 'fulfillment_command_ids' AS table_name, CAST(command_id AS CHAR) AS row_key,
  JSON_OBJECT(
    'command_id', command_id,
    'order_id', order_id
  ) AS payload
FROM fulfillment_command_ids
UNION ALL
SELECT 'fulfillment_shipment_jobs' AS table_name, CAST(command_id AS CHAR) AS row_key,
  JSON_OBJECT(
    'order_id', order_id,
    'command_id', command_id,
    'merchant_id', merchant_id,
    'order_number', order_number,
    'carrier', carrier,
    'tracking_number', tracking_number,
    'step', step,
    'status', status,
    'attempts', attempts,
    'owner_token', owner_token,
    'lease_until', lease_until,
    'next_attempt_at', next_attempt_at,
    'last_error', last_error,
    'created_at', created_at,
    'updated_at', updated_at
  ) AS payload
FROM fulfillment_shipment_jobs
UNION ALL
SELECT 'fulfillment_merchant_jobs' AS table_name, CAST(command_id AS CHAR) AS row_key,
  JSON_OBJECT(
    'command_id', command_id,
    'order_id', order_id,
    'merchant_id', merchant_id,
    'operation', operation,
    'expected_version', expected_version,
    'carrier', carrier,
    'tracking_number', tracking_number,
    'detail', detail,
    'step', step,
    'status', status,
    'attempts', attempts,
    'owner_token', owner_token,
    'lease_until', lease_until,
    'next_attempt_at', next_attempt_at,
    'last_error', last_error,
    'updated_at', updated_at
  ) AS payload
FROM fulfillment_merchant_jobs
UNION ALL
SELECT 'fulfillment_shipment_recoveries' AS table_name, CAST(id AS CHAR) AS row_key,
  JSON_OBJECT(
    'id', id,
    'order_id', order_id,
    'recovery_id', recovery_id,
    'command_id', command_id,
    'operator_id', operator_id,
    'step', step,
    'previous_error', previous_error,
    'reason', reason,
    'evidence', evidence,
    'created_at', created_at
  ) AS payload
FROM fulfillment_shipment_recoveries
UNION ALL
SELECT 'fulfillment_merchant_recoveries' AS table_name, CAST(id AS CHAR) AS row_key,
  JSON_OBJECT(
    'id', id,
    'command_id', command_id,
    'recovery_id', recovery_id,
    'operator_id', operator_id,
    'step', step,
    'previous_error', previous_error,
    'reason', reason,
    'evidence', evidence,
    'created_at', created_at
  ) AS payload
FROM fulfillment_merchant_recoveries;

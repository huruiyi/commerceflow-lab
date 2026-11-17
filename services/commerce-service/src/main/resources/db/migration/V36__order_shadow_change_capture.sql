CREATE TABLE order_shadow_change_clock (
  singleton_id TINYINT NOT NULL,
  last_sequence BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (singleton_id),
  CONSTRAINT chk_order_shadow_clock_singleton CHECK (singleton_id = 1)
);

INSERT INTO order_shadow_change_clock(singleton_id,last_sequence) VALUES (1,0);

CREATE TABLE order_shadow_change_log (
  change_sequence BIGINT NOT NULL,
  table_name VARCHAR(50) NOT NULL,
  row_key VARCHAR(100) NOT NULL,
  operation VARCHAR(10) NOT NULL,
  changed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (change_sequence),
  KEY idx_order_shadow_change_table (table_name,row_key,change_sequence),
  CONSTRAINT chk_order_shadow_change_table CHECK (table_name IN (
    'shop_orders','order_items','order_shipping_addresses','order_events',
    'order_process_managers','order_command_outbox','order_result_inbox'
  )),
  CONSTRAINT chk_order_shadow_change_operation CHECK (operation IN ('UPSERT','DELETE'))
);

DELIMITER $$
CREATE PROCEDURE record_order_shadow_change(
  IN changed_table VARCHAR(50), IN changed_key VARCHAR(100), IN change_operation VARCHAR(10))
BEGIN
  UPDATE order_shadow_change_clock
  SET last_sequence = LAST_INSERT_ID(last_sequence + 1)
  WHERE singleton_id = 1;
  INSERT INTO order_shadow_change_log(change_sequence,table_name,row_key,operation)
  VALUES (LAST_INSERT_ID(),changed_table,changed_key,change_operation);
END$$

CREATE TRIGGER trg_shop_orders_shadow_insert AFTER INSERT ON shop_orders FOR EACH ROW
BEGIN CALL record_order_shadow_change('shop_orders',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_shop_orders_shadow_update AFTER UPDATE ON shop_orders FOR EACH ROW
BEGIN CALL record_order_shadow_change('shop_orders',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_shop_orders_shadow_delete AFTER DELETE ON shop_orders FOR EACH ROW
BEGIN CALL record_order_shadow_change('shop_orders',CAST(OLD.id AS CHAR),'DELETE'); END$$

CREATE TRIGGER trg_order_items_shadow_insert AFTER INSERT ON order_items FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_items',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_order_items_shadow_update AFTER UPDATE ON order_items FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_items',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_order_items_shadow_delete AFTER DELETE ON order_items FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_items',CAST(OLD.id AS CHAR),'DELETE'); END$$

CREATE TRIGGER trg_order_addresses_shadow_insert AFTER INSERT ON order_shipping_addresses FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_shipping_addresses',CAST(NEW.order_id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_order_addresses_shadow_update AFTER UPDATE ON order_shipping_addresses FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_shipping_addresses',CAST(NEW.order_id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_order_addresses_shadow_delete AFTER DELETE ON order_shipping_addresses FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_shipping_addresses',CAST(OLD.order_id AS CHAR),'DELETE'); END$$

CREATE TRIGGER trg_order_events_shadow_insert AFTER INSERT ON order_events FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_events',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_order_events_shadow_update AFTER UPDATE ON order_events FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_events',CAST(NEW.id AS CHAR),'UPSERT'); END$$
CREATE TRIGGER trg_order_events_shadow_delete AFTER DELETE ON order_events FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_events',CAST(OLD.id AS CHAR),'DELETE'); END$$

CREATE TRIGGER trg_order_process_shadow_insert AFTER INSERT ON order_process_managers FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_process_managers',NEW.process_id,'UPSERT'); END$$
CREATE TRIGGER trg_order_process_shadow_update AFTER UPDATE ON order_process_managers FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_process_managers',NEW.process_id,'UPSERT'); END$$
CREATE TRIGGER trg_order_process_shadow_delete AFTER DELETE ON order_process_managers FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_process_managers',OLD.process_id,'DELETE'); END$$

CREATE TRIGGER trg_order_outbox_shadow_insert AFTER INSERT ON order_command_outbox FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_command_outbox',NEW.command_id,'UPSERT'); END$$
CREATE TRIGGER trg_order_outbox_shadow_update AFTER UPDATE ON order_command_outbox FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_command_outbox',NEW.command_id,'UPSERT'); END$$
CREATE TRIGGER trg_order_outbox_shadow_delete AFTER DELETE ON order_command_outbox FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_command_outbox',OLD.command_id,'DELETE'); END$$

CREATE TRIGGER trg_order_inbox_shadow_insert AFTER INSERT ON order_result_inbox FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_result_inbox',NEW.result_id,'UPSERT'); END$$
CREATE TRIGGER trg_order_inbox_shadow_update AFTER UPDATE ON order_result_inbox FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_result_inbox',NEW.result_id,'UPSERT'); END$$
CREATE TRIGGER trg_order_inbox_shadow_delete AFTER DELETE ON order_result_inbox FOR EACH ROW
BEGIN CALL record_order_shadow_change('order_result_inbox',OLD.result_id,'DELETE'); END$$
DELIMITER ;

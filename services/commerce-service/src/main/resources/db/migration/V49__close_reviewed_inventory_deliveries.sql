ALTER TABLE inventory_command_delivery_outbox
  DROP CHECK chk_inventory_delivery_status,
  ADD CONSTRAINT chk_inventory_delivery_status
    CHECK (status IN (
      'NEW','IN_FLIGHT','RETRY','ACKNOWLEDGED','RECONCILIATION_REQUIRED','CLOSED'
    ));
